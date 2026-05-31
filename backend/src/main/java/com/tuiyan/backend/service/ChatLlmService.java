package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tuiyan.backend.entity.DataSourcePO;
import com.tuiyan.backend.model.ChatRequest;
import com.tuiyan.backend.model.MentionRef;
import com.tuiyan.backend.repository.DataSourceRepository;
import com.tuiyan.backend.service.connector.JdbcConnectorService;
import com.tuiyan.backend.service.indexing.DataSourceIndexService;
import com.tuiyan.backend.service.llm.GraphPromptBuilder;
import com.tuiyan.backend.service.llm.LlmCallLogger;
import com.tuiyan.backend.service.llm.LlmHttpClient;
import com.tuiyan.backend.service.llm.LlmPrompts;
import com.tuiyan.backend.support.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天业务 facade：把"用户消息 + 图谱上下文 + 历史 + 附件"翻译成 LLM 调用，
 * 同步返回 JSON 或通过 SSE 推送结果。
 */
@Service
public class ChatLlmService {

    private static final Logger log = LoggerFactory.getLogger(ChatLlmService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LlmHttpClient http;
    private final GraphPromptBuilder promptBuilder;
    private final LlmCallLogger callLogger;
    private final DataSourceIndexService indexService;
    private final DataSourceRepository dsRepo;
    private final JdbcConnectorService jdbcConnector;

    public ChatLlmService(LlmHttpClient http,
                          GraphPromptBuilder promptBuilder,
                          LlmCallLogger callLogger,
                          DataSourceIndexService indexService,
                          DataSourceRepository dsRepo,
                          JdbcConnectorService jdbcConnector) {
        this.http = http;
        this.promptBuilder = promptBuilder;
        this.callLogger = callLogger;
        this.indexService = indexService;
        this.dsRepo = dsRepo;
        this.jdbcConnector = jdbcConnector;
    }

    /**
     * 同步 chat：把图谱上下文 + 用户消息发给 LLM，返回 {reply, add_nodes, add_edges} 形状的 JSON。
     */
    public JsonNode chat(List<Map<String, Object>> nodes, List<Map<String, Object>> edges, String message,
                         String modelOverride, String configId, List<Map<String, Object>> history,
                         List<Map<String, Object>> attachments) throws IOException {
        LlmHttpClient.ResolvedConfig cfg = http.resolveConfig(modelOverride, configId);
        boolean anthropic = http.isAnthropic(cfg.baseURL(), cfg.modelName(), cfg.protocol());

        log.info("[LLM-chat] 开始请求 model={} url={} protocol={}", cfg.modelName(), cfg.baseURL(),
                anthropic ? "anthropic" : "openai");

        String prompt = promptBuilder.buildChatPrompt(nodes, edges, message);
        callLogger.logConversation("LLM-chat", cfg.modelName(), LlmPrompts.CHAT_SYSTEM, history, prompt, attachments);

        String requestBody = http.buildBody(cfg, LlmPrompts.CHAT_SYSTEM, prompt, history, attachments, false, true);
        log.debug("[LLM-chat] 请求体大小: {} chars", requestBody.length());

        long startTime = System.currentTimeMillis();
        try {
            HttpRequest request = http.buildHttpRequest(cfg.baseURL(), cfg.apiKey(), anthropic, requestBody, cfg.rawUrl());
            HttpResponse<String> response = http.sendHttp(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - startTime;

            if (response.statusCode() != 200) {
                log.error("[LLM-chat] 请求失败 status={} 耗时={}ms", response.statusCode(), elapsed);
                callLogger.logUpstreamError("chat", response.statusCode(), response.body());
                http.metrics().recordCall(cfg.modelName(), elapsed, false);
                throw new RuntimeException("LLM 调用失败 HTTP " + response.statusCode() + "（详情见服务器日志）");
            }

            log.info("[LLM-chat] 请求成功 status=200 耗时={}ms 响应大小={} chars", elapsed, response.body().length());

            String responseContentType = response.headers().firstValue("Content-Type").orElse("");
            if (responseContentType.contains("text/html")) {
                log.error("[LLM-chat] 收到 HTML 响应而非 JSON，base-url 可能缺少 /v1 路径前缀");
                http.metrics().recordCall(cfg.modelName(), elapsed, false);
                throw new RuntimeException("LLM 代理返回了 HTML 页面而非 API 响应，请检查 base-url 配置是否包含 /v1 路径");
            }

            http.metrics().recordCall(cfg.modelName(), elapsed, true);

            JsonNode responseJson = objectMapper.readTree(response.body());
            String content = http.stripJsonFence(http.extractContent(responseJson, anthropic));
            callLogger.logLlmResponse("LLM-chat", cfg.modelName(), elapsed, content);
            return objectMapper.readTree(content);
        } catch (IOException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            http.metrics().recordCall(cfg.modelName(), elapsed, false);
            throw e;
        }
    }

    /**
     * SSE chat：以非流式方式调用 LLM，拿到完整响应后通过 SSE 事件推送给前端。
     * <p>对 LLM 侧是普通 HTTP 请求（stream=false），对前端仍保持 SSE 接口兼容。
     * 使用异步 HTTP 避免阻塞 servlet 线程。
     */
    public void chatStreaming(ChatRequest request, SseEmitter emitter) {
        long startMs = System.currentTimeMillis();
        String modelName = "unknown";
        try {
            // 空输入兜底:message 为空且无附件时直接返回友好提示,不浪费一次 LLM 调用
            boolean blankMessage = request.getMessage() == null || request.getMessage().isBlank();
            boolean noAttachments = request.getAttachments() == null || request.getAttachments().isEmpty();
            if (blankMessage && noAttachments) {
                try {
                    emitter.send(SseEmitter.event().name("error").data("消息内容为空，请输入描述或上传文件后再试。"));
                } catch (IOException e) {
                    log.warn("emit empty-message error failed", e);
                }
                emitter.complete();
                return;
            }

            emitStep(emitter, "resolving_config", "正在解析模型配置…");

            LlmHttpClient.ResolvedConfig cfg = http.resolveConfig(request.getModelOverride(), request.getConfigId());
            modelName = cfg.modelName();
            boolean anthropic = http.isAnthropic(cfg.baseURL(), cfg.modelName(), cfg.protocol());

            log.info("[LLM-chat-sse] 开始非流式请求 model={} url={} protocol={}", cfg.modelName(), cfg.baseURL(),
                    anthropic ? "anthropic" : "openai");

            emitStep(emitter, "building_context", "正在构建图谱上下文…");

            // RAG：从已索引的数据源中检索相关内容
            List<GraphPromptBuilder.RagChunk> ragChunks = List.of();
            String wsId = WorkspaceContext.get();
            if (wsId != null && indexService.isConfigured()) {
                try {
                    emitStep(emitter, "searching_datasources", "正在检索工作空间数据源…");
                    var results = indexService.searchRelevant(wsId, request.getMessage(), 5);
                    if (!results.isEmpty()) {
                        ragChunks = results.stream()
                                .map(r -> new GraphPromptBuilder.RagChunk(r.content(), r.dataSourceName(), r.score()))
                                .toList();
                        log.info("[LLM-chat-sse] RAG 检索到 {} 条相关文本块", ragChunks.size());

                        // 把命中的数据源列出来,让用户看到"根据什么"在构建
                        String sources = results.stream()
                                .map(r -> r.dataSourceName())
                                .filter(java.util.Objects::nonNull)
                                .distinct()
                                .limit(4)
                                .collect(java.util.stream.Collectors.joining("、"));
                        long distinctCount = results.stream()
                                .map(r -> r.dataSourceName())
                                .filter(java.util.Objects::nonNull)
                                .distinct()
                                .count();
                        String extra = distinctCount > 4 ? " 等 " + distinctCount + " 个" : "";
                        emitStep(emitter, "matched_datasources",
                                "已根据数据源「" + sources + extra + "」匹配 " + results.size() + " 段相关内容");
                    } else {
                        emitStep(emitter, "no_match_datasources", "工作空间内暂无相关数据源,按用户描述构建");
                    }
                } catch (Exception e) {
                    log.warn("[LLM-chat-sse] RAG 检索失败（继续不带 RAG）: {}", e.getMessage());
                }
            }

            // 数据库类数据源：默认枚举工作空间下的全部，但用户用 @ 显式引用了数据源时只拉那些
            List<MentionRef> mentions = request.getMentions();
            List<String> explicitDsIds = pickMentionIds(mentions, "datasource");
            List<GraphPromptBuilder.DbSchema> dbSchemas = explicitDsIds.isEmpty()
                    ? collectDbSchemas(wsId, emitter)
                    : collectDbSchemasByIds(explicitDsIds, emitter);

            String prompt = promptBuilder.buildChatPrompt(request.getNodes(), request.getEdges(),
                    request.getMessage(), ragChunks, dbSchemas, mentions);
            callLogger.logConversation("LLM-chat-sse", cfg.modelName(), LlmPrompts.CHAT_SYSTEM,
                    request.getHistory(), prompt, request.getAttachments());

            String requestBody = http.buildBody(cfg, LlmPrompts.CHAT_SYSTEM, prompt,
                    request.getHistory(), request.getAttachments(), false, true);
            log.debug("[LLM-chat-sse] 请求体大小: {} chars", requestBody.length());

            HttpRequest httpRequest = http.buildHttpRequest(cfg.baseURL(), cfg.apiKey(), anthropic, requestBody, cfg.rawUrl());

            final String modelForMetrics = cfg.modelName();

            http.httpClient().sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(resp -> handleResponse(resp, emitter, anthropic, startMs, modelForMetrics))
                    .exceptionally(ex -> {
                        long elapsed = System.currentTimeMillis() - startMs;
                        log.error("[LLM-chat-sse] 网络异常 耗时={}ms error={}", elapsed, ex.getMessage());
                        http.metrics().recordCall(modelForMetrics, elapsed, false);
                        try {
                            emitter.send(SseEmitter.event().name("error").data("Network error: " + ex.getMessage()));
                        } catch (IOException e) {
                            log.warn("emit network-error failed", e);
                        }
                        emitter.completeWithError(ex);
                        return null;
                    });
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startMs;
            log.error("[LLM-chat-sse] 初始化失败: {}", e.getMessage());
            http.metrics().recordCall(modelName, elapsed, false);
            try {
                emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
            } catch (IOException ioEx) {
                log.warn("emit init-error failed", ioEx);
            }
            emitter.completeWithError(e);
        }
    }

    private void handleResponse(HttpResponse<String> resp, SseEmitter emitter,
                                boolean anthropic, long startMs, String modelName) {
        long elapsed = System.currentTimeMillis() - startMs;

        if (resp.statusCode() != 200) {
            log.error("[LLM-chat-sse] 请求失败 status={} 耗时={}ms body={}", resp.statusCode(), elapsed, resp.body());
            callLogger.logUpstreamError("chatStreaming", resp.statusCode(), resp.body());
            http.metrics().recordCall(modelName, elapsed, false);
            // 抽出上游 message 给用户看(常见原因:模型名拼错/api key 错/欠费)
            String upstreamMsg = extractUpstreamErrorMessage(resp.body());
            String userMsg = "LLM 调用失败 HTTP " + resp.statusCode()
                    + (upstreamMsg == null ? "（详情见服务器日志）" : ":" + upstreamMsg);
            try {
                emitter.send(SseEmitter.event().name("error").data(userMsg));
            } catch (IOException e) {
                log.warn("emit error event failed", e);
            }
            emitter.complete();
            return;
        }

        String contentType = resp.headers().firstValue("Content-Type").orElse("");
        if (contentType.contains("text/html")) {
            log.error("[LLM-chat-sse] 收到 HTML 响应而非 JSON，base-url 可能缺少 /v1 路径前缀");
            http.metrics().recordCall(modelName, elapsed, false);
            try {
                emitter.send(SseEmitter.event().name("error").data(
                        "LLM 代理返回了 HTML 页面而非 API 响应，请检查 base-url 配置是否包含 /v1 路径"));
            } catch (IOException e) {
                log.warn("emit html-error event failed", e);
            }
            emitter.complete();
            return;
        }

        try {
            emitStep(emitter, "parsing_response", "正在解析模型响应…");

            log.info("[LLM-chat-sse] 请求成功 耗时={}ms 响应大小={} chars", elapsed, resp.body().length());
            http.metrics().recordCall(modelName, elapsed, true);

            JsonNode responseJson = objectMapper.readTree(resp.body());
            String content = http.stripJsonFence(http.extractContent(responseJson, anthropic));
            callLogger.logLlmResponse("LLM-chat-sse", modelName, elapsed, content);

            emitStep(emitter, "extracting_entities", "正在提取实体和关系…");

            JsonNode result = objectMapper.readTree(content);

            String reply = result.path("reply").asText("");
            if (!reply.isEmpty()) {
                emitter.send(SseEmitter.event().name("text").data(reply));
            }

            JsonNode addNodes = result.path("add_nodes");
            JsonNode addEdges = result.path("add_edges");
            int nodeCount = addNodes.size();
            int edgeCount = addEdges.size();

            // 逐个实体 / 关系上报，让用户看到本体被一步步"构建"出来，而不是只看到一个总数
            emitBuildSteps(emitter, addNodes, addEdges);

            emitStep(emitter, "merging_graph",
                    "正在合并到图谱… (+" + nodeCount + " 节点 / +" + edgeCount + " 关系)");

            ObjectNode finalEvent = objectMapper.createObjectNode();
            finalEvent.put("reply", reply);
            finalEvent.set("add_nodes", result.path("add_nodes"));
            finalEvent.set("add_edges", result.path("add_edges"));
            // 透传 LLM 返回的 clarifying question(如果有),前端会渲染为可点击选项
            JsonNode question = result.path("question");
            if (question != null && !question.isMissingNode() && !question.isNull()
                    && question.has("text") && !question.path("text").asText("").isBlank()) {
                finalEvent.set("question", question);
            }
            emitter.send(SseEmitter.event().name("complete")
                    .data(objectMapper.writeValueAsString(finalEvent)));
            emitter.complete();
        } catch (Exception e) {
            log.error("[LLM-chat-sse] 响应解析失败: {}", e.getMessage());
            http.metrics().recordCall(modelName, elapsed, false);
            try {
                emitter.send(SseEmitter.event().name("error").data("响应解析失败: " + e.getMessage()));
            } catch (IOException ex) {
                log.warn("emit parse-error failed", ex);
            }
            emitter.complete();
        }
    }

    /**
     * 从 OpenAI / DeepSeek / Anthropic 错误体里抽出 human-readable message,
     * 让前端能直接显示"Model deepseek-v4-flash does not exist"这种具体原因。
     */
    private String extractUpstreamErrorMessage(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(body);
            // OpenAI / DeepSeek 风格: { "error": { "message": "..." } } 或 { "error": "..." }
            JsonNode err = root.path("error");
            if (err.isObject() && err.has("message")) return err.path("message").asText(null);
            if (err.isTextual()) return err.asText(null);
            // Anthropic 风格: { "error": { "message": "..." } } - 已被上面覆盖
            // 兜底: 直接看顶层 message
            if (root.has("message")) return root.path("message").asText(null);
        } catch (Exception ignored) {
            // 非 JSON,截断前 200 字直接返回
            String trimmed = body.length() > 200 ? body.substring(0, 200) + "…" : body;
            return trimmed.replaceAll("\\s+", " ");
        }
        return null;
    }

    // 单次对话最多展示的"逐个构建"步骤数，避免大量实体淹没时间线；超出由 merging_graph 汇总兜底
    private static final int MAX_BUILD_STEPS = 24;
    // 每条构建步骤之间的间隔，制造"逐步生长"的视觉节奏（与推演编排一致）
    private static final long BUILD_STEP_DELAY_MS = 70;

    /**
     * 把 LLM 一次性返回的 add_nodes / add_edges 拆成逐条 step 事件推给前端：
     * 先逐个"构建实体「X」(type)"，再逐个"建立关系「A —关系→ B」"，
     * 让用户直观看到本体的实体与关系是如何被构建出来的。总条数受 {@link #MAX_BUILD_STEPS} 限制。
     */
    private void emitBuildSteps(SseEmitter emitter, JsonNode addNodes, JsonNode addEdges) {
        int budget = MAX_BUILD_STEPS;

        if (addNodes.isArray()) {
            int i = 0;
            for (JsonNode n : addNodes) {
                if (budget <= 0) break;
                String label = n.path("label").asText("");
                if (label.isBlank()) label = n.path("id").asText("实体");
                String type = n.path("type").asText("");
                String text = type.isBlank()
                        ? "构建实体「" + label + "」"
                        : "构建实体「" + label + "」(" + type + ")";
                if (!emitStep(emitter, "build_node_" + (i++), text)) return; // emitter 已关闭，停止逐步推送
                budget--;
                sleepQuiet(BUILD_STEP_DELAY_MS);
            }
        }

        if (addEdges.isArray()) {
            // 用 add_nodes 的 id→label 映射美化关系端点；引用已有图谱节点时回退为 id
            Map<String, String> idToLabel = new HashMap<>();
            if (addNodes.isArray()) {
                for (JsonNode n : addNodes) {
                    String id = n.path("id").asText("");
                    if (!id.isBlank()) idToLabel.put(id, n.path("label").asText(id));
                }
            }
            int j = 0;
            for (JsonNode e : addEdges) {
                if (budget <= 0) break;
                String from = e.path("from").asText("");
                String to = e.path("to").asText("");
                String fromLabel = idToLabel.getOrDefault(from, from);
                String toLabel = idToLabel.getOrDefault(to, to);
                String rel = e.path("label").asText("");
                String text = rel.isBlank()
                        ? "建立关系「" + fromLabel + " → " + toLabel + "」"
                        : "建立关系「" + fromLabel + " —" + rel + "→ " + toLabel + "」";
                if (!emitStep(emitter, "build_edge_" + (j++), text)) return; // emitter 已关闭，停止逐步推送
                budget--;
                sleepQuiet(BUILD_STEP_DELAY_MS);
            }
        }
    }

    /** 安静地 sleep，保留中断标志；用于构建步骤间制造节奏。 */
    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 推送一条 step 事件。
     * @return true=发送成功；false=emitter 已关闭(超时/客户端断开)或发送失败，调用方应停止后续推送。
     */
    private boolean emitStep(SseEmitter emitter, String key, String label) {
        try {
            String json = objectMapper.writeValueAsString(Map.of("key", key, "label", label));
            emitter.send(SseEmitter.event().name("step").data(json));
            return true;
        } catch (IllegalStateException closed) {
            // emitter 已 complete(常见于 180s 超时或客户端断开)：再 send 会抛此异常。
            // 不再当作错误刷屏，仅 debug 记录，并让调用方据返回值提前收尾。
            log.debug("emit step '{}' skipped, emitter closed: {}", key, closed.toString());
            return false;
        } catch (IOException e) {
            log.warn("emit step '{}' failed (client disconnected?): {}", key, e.toString());
            return false;
        }
    }

    /**
     * 枚举当前工作空间下已接入的 MySQL / PostgreSQL 数据源,拉表清单作为 LLM 结构化输入。
     * <p>对每个 DB 单独 try-catch,坏的跳过；最多读 5 个数据源以控制总耗时；
     * 每个数据源在 SSE 里 emit 一条 step,让用户看到"读取了哪个库的哪些表"。
     */
    private List<GraphPromptBuilder.DbSchema> collectDbSchemas(String wsId, SseEmitter emitter) {
        if (wsId == null) return List.of();
        List<Map<String, Object>> dsList;
        try {
            dsList = dsRepo.list(wsId);
        } catch (Exception e) {
            log.warn("[LLM-chat-sse] 列举工作空间数据源失败: {}", e.getMessage());
            return List.of();
        }

        List<GraphPromptBuilder.DbSchema> out = new java.util.ArrayList<>();
        int probed = 0;
        for (Map<String, Object> ds : dsList) {
            String kind = String.valueOf(ds.get("kind"));
            if (!"mysql".equals(kind) && !"pgsql".equals(kind)) continue;
            // status=error 的连不上,直接跳过避免拖慢聊天
            Object statusObj = ds.get("status");
            if ("error".equals(String.valueOf(statusObj))) continue;
            if (probed >= 5) break;
            probed++;

            String id = String.valueOf(ds.get("id"));
            String name = String.valueOf(ds.getOrDefault("name", id));
            DataSourcePO po = dsRepo.findById(id);
            if (po == null) continue;
            Map<String, Object> cfg = dsRepo.readConfig(po);
            String database = String.valueOf(cfg.getOrDefault("database", "?"));

            try {
                // 升级：拉完整 schema (表+列+FK+唯一键)，让 LLM 看到结构而不只看到表名
                var schemaInfo = jdbcConnector.introspectSchema(kind, cfg, 80);
                List<String> tables = schemaInfo.tables().stream()
                        .map(t -> t.name()).toList();
                int fkCount = schemaInfo.tables().stream()
                        .mapToInt(t -> t.foreignKeys().size()).sum();
                String preview = tables.stream().limit(6)
                        .collect(java.util.stream.Collectors.joining("、"));
                String tail = tables.size() > 6 ? " … 共 " + tables.size() + " 张" : "";
                emitStep(emitter, "reading_db_" + id,
                        "正在读取数据库「" + name + "」(" + kind + ":" + database
                                + ") 共 " + tables.size() + " 张表 / " + fkCount + " 条外键"
                                + (tables.isEmpty() ? "" : ":" + preview + tail));
                out.add(new GraphPromptBuilder.DbSchema(name, kind, database, tables, schemaInfo));
            } catch (Exception e) {
                log.warn("[LLM-chat-sse] 读取数据库 {} 失败: {}", name, e.getMessage());
                emitStep(emitter, "reading_db_" + id + "_err",
                        "数据库「" + name + "」读取失败,跳过 (" + e.getMessage() + ")");
            }
        }
        return out;
    }

    /**
     * 按 @ 引用指定的 id 集合精确拉数据源,跳过全部"5 个上限"等启发式策略。
     * <p>用户明确 @ 了哪个库,就只把哪个库的完整 schema 注入上下文 —
     * 这才是"@真正影响上下文范围"的体现。
     */
    private List<GraphPromptBuilder.DbSchema> collectDbSchemasByIds(List<String> dsIds, SseEmitter emitter) {
        List<GraphPromptBuilder.DbSchema> out = new java.util.ArrayList<>();
        if (dsIds == null || dsIds.isEmpty()) return out;
        for (String id : dsIds) {
            DataSourcePO po;
            try { po = dsRepo.findById(id); }
            catch (Exception e) { log.warn("[LLM-chat-sse] @ 引用的数据源 {} 查询失败: {}", id, e.getMessage()); continue; }
            if (po == null) {
                emitStep(emitter, "missing_ref_ds_" + id, "@ 引用的数据源 " + id + " 不存在,已忽略");
                continue;
            }
            String kind = po.getKind();
            if (!"mysql".equals(kind) && !"pgsql".equals(kind)) {
                emitStep(emitter, "skip_ref_ds_" + id,
                        "@ 引用的数据源「" + po.getName() + "」非数据库类型,跳过 schema 注入");
                continue;
            }
            Map<String, Object> cfg = dsRepo.readConfig(po);
            String database = String.valueOf(cfg.getOrDefault("database", "?"));
            String name = po.getName() == null ? id : po.getName();
            try {
                // 用户明确引用 → 限额可以适当放宽到 200 张
                var schemaInfo = jdbcConnector.introspectSchema(kind, cfg, 200);
                List<String> tables = schemaInfo.tables().stream()
                        .map(t -> t.name()).toList();
                int fkCount = schemaInfo.tables().stream()
                        .mapToInt(t -> t.foreignKeys().size()).sum();
                emitStep(emitter, "reading_ref_db_" + id,
                        "🎯 按 @ 引用读取数据库「" + name + "」(" + kind + ":" + database
                                + ") 共 " + tables.size() + " 张表 / " + fkCount + " 条外键");
                out.add(new GraphPromptBuilder.DbSchema(name, kind, database, tables, schemaInfo));
            } catch (Exception e) {
                log.warn("[LLM-chat-sse] 读取 @ 引用的数据库 {} 失败: {}", name, e.getMessage());
                emitStep(emitter, "reading_ref_db_" + id + "_err",
                        "数据库「" + name + "」读取失败,跳过 (" + e.getMessage() + ")");
            }
        }
        return out;
    }

    /** 从 mentions 列表中按 kind 过滤出 id 列表。 */
    private static List<String> pickMentionIds(List<MentionRef> mentions, String kind) {
        if (mentions == null || mentions.isEmpty()) return List.of();
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        for (MentionRef m : mentions) {
            if (m == null || m.getKind() == null || m.getId() == null) continue;
            if (kind.equalsIgnoreCase(m.getKind())) set.add(m.getId());
        }
        return new java.util.ArrayList<>(set);
    }
}
