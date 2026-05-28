package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tuiyan.backend.entity.DataSourcePO;
import com.tuiyan.backend.model.ChatRequest;
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

            // 数据库类数据源：枚举工作空间下已接入的 MySQL / PostgreSQL，拉表清单交给 LLM 作为结构化上下文
            List<GraphPromptBuilder.DbSchema> dbSchemas = collectDbSchemas(wsId, emitter);

            String prompt = promptBuilder.buildChatPrompt(request.getNodes(), request.getEdges(),
                    request.getMessage(), ragChunks, dbSchemas);
            callLogger.logConversation("LLM-chat-sse", cfg.modelName(), LlmPrompts.CHAT_SYSTEM,
                    request.getHistory(), prompt, request.getAttachments());

            String requestBody = http.buildBody(cfg, LlmPrompts.CHAT_SYSTEM, prompt,
                    request.getHistory(), request.getAttachments(), false, true);
            log.debug("[LLM-chat-sse] 请求体大小: {} chars", requestBody.length());

            emitStep(emitter, "calling_llm", "正在调用 " + cfg.modelName() + " 推理构建本体…");

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
            log.error("[LLM-chat-sse] 请求失败 status={} 耗时={}ms", resp.statusCode(), elapsed);
            callLogger.logUpstreamError("chatStreaming", resp.statusCode(), resp.body());
            http.metrics().recordCall(modelName, elapsed, false);
            try {
                emitter.send(SseEmitter.event().name("error").data(
                        "LLM 调用失败 HTTP " + resp.statusCode() + "（详情见服务器日志）"));
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

            int nodeCount = result.path("add_nodes").size();
            int edgeCount = result.path("add_edges").size();
            emitStep(emitter, "merging_graph",
                    "正在合并到图谱… (+" + nodeCount + " 节点 / +" + edgeCount + " 关系)");

            ObjectNode finalEvent = objectMapper.createObjectNode();
            finalEvent.put("reply", reply);
            finalEvent.set("add_nodes", result.path("add_nodes"));
            finalEvent.set("add_edges", result.path("add_edges"));
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

    private void emitStep(SseEmitter emitter, String key, String label) {
        try {
            String json = objectMapper.writeValueAsString(Map.of("key", key, "label", label));
            emitter.send(SseEmitter.event().name("step").data(json));
        } catch (IOException e) {
            log.warn("emit step '{}' failed: {}", key, e.toString());
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
                List<String> tables = jdbcConnector.listTables(kind, cfg);
                if (tables == null) tables = List.of();
                String preview = tables.stream().limit(6)
                        .collect(java.util.stream.Collectors.joining("、"));
                String tail = tables.size() > 6 ? " … 共 " + tables.size() + " 张" : "";
                emitStep(emitter, "reading_db_" + id,
                        "正在读取数据库「" + name + "」(" + kind + ":" + database
                                + ") 共 " + tables.size() + " 张表"
                                + (tables.isEmpty() ? "" : ":" + preview + tail));
                out.add(new GraphPromptBuilder.DbSchema(name, kind, database, tables));
            } catch (Exception e) {
                log.warn("[LLM-chat-sse] 读取数据库 {} 失败: {}", name, e.getMessage());
                emitStep(emitter, "reading_db_" + id + "_err",
                        "数据库「" + name + "」读取失败,跳过 (" + e.getMessage() + ")");
            }
        }
        return out;
    }
}
