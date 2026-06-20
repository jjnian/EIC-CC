package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tuiyan.backend.model.ChatRequest;
import com.tuiyan.backend.model.MentionRef;
import com.tuiyan.backend.service.chat.ChatContextCollector;
import com.tuiyan.backend.service.chat.ChatStepEmitter;
import com.tuiyan.backend.service.chat.DerivedSourceStamper;
import com.tuiyan.backend.service.llm.GraphPromptBuilder;
import com.tuiyan.backend.service.llm.LlmCallLogger;
import com.tuiyan.backend.service.llm.LlmHttpClient;
import com.tuiyan.backend.service.llm.prompt.ChatPrompts;
import com.tuiyan.backend.support.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天业务 facade：把"用户消息 + 图谱上下文 + 历史 + 附件"翻译成 LLM 调用，
 * 同步返回 JSON 或通过 SSE 推送结果。
 * <p>上下文取数（RAG / DB schema / @引用）委托给 {@link ChatContextCollector}，
 * SSE 进度推送委托给 {@link ChatStepEmitter}，本类只负责编排与响应处理。
 */
@Service
public class ChatLlmService {

    private static final Logger log = LoggerFactory.getLogger(ChatLlmService.class);

    // 单次对话最多展示的"逐个构建"步骤数，避免大量实体淹没时间线；超出由 merging_graph 汇总兜底
    private static final int MAX_BUILD_STEPS = 24;
    // 每条构建步骤之间的间隔，制造"逐步生长"的视觉节奏（与推演编排一致）
    private static final long BUILD_STEP_DELAY_MS = 70;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LlmHttpClient http;
    private final GraphPromptBuilder promptBuilder;
    private final LlmCallLogger callLogger;
    private final ChatContextCollector context;

    public ChatLlmService(LlmHttpClient http,
                          GraphPromptBuilder promptBuilder,
                          LlmCallLogger callLogger,
                          ChatContextCollector context) {
        this.http = http;
        this.promptBuilder = promptBuilder;
        this.callLogger = callLogger;
        this.context = context;
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
        callLogger.logConversation("LLM-chat", cfg.modelName(), ChatPrompts.CHAT_SYSTEM, history, prompt, attachments);

        String requestBody = http.buildBody(cfg, ChatPrompts.CHAT_SYSTEM, prompt, history, attachments, false, true);
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
        ChatStepEmitter step = new ChatStepEmitter(emitter, objectMapper);
        long startMs = System.currentTimeMillis();
        String modelName = "unknown";
        try {
            // 空输入兜底:message 为空且无附件时直接返回友好提示,不浪费一次 LLM 调用
            boolean blankMessage = request.getMessage() == null || request.getMessage().isBlank();
            boolean noAttachments = request.getAttachments() == null || request.getAttachments().isEmpty();
            if (blankMessage && noAttachments) {
                step.send("error", "消息内容为空，请输入描述或上传文件后再试。");
                emitter.complete();
                return;
            }

            step.step("resolving_config", "正在解析模型配置…");

            LlmHttpClient.ResolvedConfig cfg = http.resolveConfig(request.getModelOverride(), request.getConfigId());
            modelName = cfg.modelName();
            boolean anthropic = http.isAnthropic(cfg.baseURL(), cfg.modelName(), cfg.protocol());

            log.info("[LLM-chat-sse] 开始非流式请求 model={} url={} protocol={}", cfg.modelName(), cfg.baseURL(),
                    anthropic ? "anthropic" : "openai");

            step.step("building_context", "正在构建图谱上下文…");

            // RAG：从已索引的数据源 + 经验库中检索相关内容
            String wsId = WorkspaceContext.get();
            List<GraphPromptBuilder.RagChunk> ragChunks = context.searchRag(wsId, request.getMessage(), step);

            // 数据库类数据源：默认枚举工作空间下的全部，但用户用 @ 显式引用了数据源时只拉那些
            List<MentionRef> mentions = request.getMentions();
            List<String> explicitDsIds = ChatContextCollector.pickMentionIds(mentions, "datasource");
            List<GraphPromptBuilder.DbSchema> dbSchemas = explicitDsIds.isEmpty()
                    ? context.collectDbSchemas(wsId, step)
                    : context.collectDbSchemasByIds(explicitDsIds, step);

            // 经验库文件：用户用 @ 显式引用了经验文件时，把全文作为定向上下文注入（优先于自动 RAG 命中）
            List<String> pinnedExpIds = ChatContextCollector.pickMentionIds(mentions, "experience");
            if (!pinnedExpIds.isEmpty() && wsId != null) {
                List<GraphPromptBuilder.RagChunk> pinned = context.collectPinnedExperiences(pinnedExpIds, step);
                if (!pinned.isEmpty()) {
                    // @ 指定的经验放在最前，确保它在上下文里优先于自动召回的片段
                    List<GraphPromptBuilder.RagChunk> merged = new ArrayList<>(pinned);
                    merged.addAll(ragChunks);
                    ragChunks = merged;
                }
            }

            String prompt = promptBuilder.buildChatPrompt(request.getNodes(), request.getEdges(),
                    request.getMessage(), ragChunks, dbSchemas, mentions);
            callLogger.logConversation("LLM-chat-sse", cfg.modelName(), ChatPrompts.CHAT_SYSTEM,
                    request.getHistory(), prompt, request.getAttachments());

            String requestBody = http.buildBody(cfg, ChatPrompts.CHAT_SYSTEM, prompt,
                    request.getHistory(), request.getAttachments(), false, true);
            log.debug("[LLM-chat-sse] 请求体大小: {} chars", requestBody.length());

            HttpRequest httpRequest = http.buildHttpRequest(cfg.baseURL(), cfg.apiKey(), anthropic, requestBody, cfg.rawUrl());

            final String modelForMetrics = cfg.modelName();
            // 把本次会话用到的数据库 schema 透传给 handleResponse，
            // 让 LLM 输出里只填了 derived_tables 的节点/边也能被补齐 derived_source / derived_database
            final List<GraphPromptBuilder.DbSchema> dbSchemasForStamp = dbSchemas;

            http.httpClient().sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(resp -> handleResponse(resp, step, emitter, anthropic, startMs, modelForMetrics, dbSchemasForStamp))
                    .exceptionally(ex -> {
                        long elapsed = System.currentTimeMillis() - startMs;
                        log.error("[LLM-chat-sse] 网络异常 耗时={}ms error={}", elapsed, ex.getMessage());
                        http.metrics().recordCall(modelForMetrics, elapsed, false);
                        step.send("error", "Network error: " + ex.getMessage());
                        emitter.completeWithError(ex);
                        return null;
                    });
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startMs;
            log.error("[LLM-chat-sse] 初始化失败: {}", e.getMessage());
            http.metrics().recordCall(modelName, elapsed, false);
            step.send("error", e.getMessage());
            emitter.completeWithError(e);
        }
    }

    private void handleResponse(HttpResponse<String> resp, ChatStepEmitter step, SseEmitter emitter,
                                boolean anthropic, long startMs, String modelName,
                                List<GraphPromptBuilder.DbSchema> dbSchemas) {
        long elapsed = System.currentTimeMillis() - startMs;

        if (resp.statusCode() != 200) {
            log.error("[LLM-chat-sse] 请求失败 status={} 耗时={}ms body={}", resp.statusCode(), elapsed, resp.body());
            callLogger.logUpstreamError("chatStreaming", resp.statusCode(), resp.body());
            http.metrics().recordCall(modelName, elapsed, false);
            // 抽出上游 message 给用户看(常见原因:模型名拼错/api key 错/欠费)
            String upstreamMsg = extractUpstreamErrorMessage(resp.body());
            String userMsg = "LLM 调用失败 HTTP " + resp.statusCode()
                    + (upstreamMsg == null ? "（详情见服务器日志）" : ":" + upstreamMsg);
            step.send("error", userMsg);
            emitter.complete();
            return;
        }

        String contentType = resp.headers().firstValue("Content-Type").orElse("");
        if (contentType.contains("text/html")) {
            log.error("[LLM-chat-sse] 收到 HTML 响应而非 JSON，base-url 可能缺少 /v1 路径前缀");
            http.metrics().recordCall(modelName, elapsed, false);
            step.send("error", "LLM 代理返回了 HTML 页面而非 API 响应，请检查 base-url 配置是否包含 /v1 路径");
            emitter.complete();
            return;
        }

        try {
            step.step("parsing_response", "正在解析模型响应…");

            log.info("[LLM-chat-sse] 请求成功 耗时={}ms 响应大小={} chars", elapsed, resp.body().length());
            http.metrics().recordCall(modelName, elapsed, true);

            JsonNode responseJson = objectMapper.readTree(resp.body());
            String content = http.stripJsonFence(http.extractContent(responseJson, anthropic));
            callLogger.logLlmResponse("LLM-chat-sse", modelName, elapsed, content);

            step.step("extracting_entities", "正在提取实体和关系…");

            JsonNode result = objectMapper.readTree(content);

            String reply = result.path("reply").asText("");
            if (!reply.isEmpty()) {
                step.send("text", reply);
            }

            JsonNode addNodes = result.path("add_nodes");
            JsonNode addEdges = result.path("add_edges");
            int nodeCount = addNodes.size();
            int edgeCount = addEdges.size();

            // 根据 derived_tables → schema 反查,把缺失的 derived_source / derived_database 补齐,
            // 保证前端在节点/关系上始终能看到"数据源 + 数据库 + 来源表"三段血缘
            DerivedSourceStamper.stamp(addNodes, dbSchemas);
            DerivedSourceStamper.stamp(addEdges, dbSchemas);

            // 删除:LLM 可返回待删除的现有节点/边 id(仅 chat 场景),透传给前端从画布移除
            ArrayNode removeNodes = collectIdArray(result.path("remove_nodes"));
            ArrayNode removeEdges = collectIdArray(result.path("remove_edges"));
            int removeNodeCount = removeNodes.size();
            int removeEdgeCount = removeEdges.size();
            // 修改:LLM 可返回对现有节点/边的局部 patch(须含 id),透传给前端就地更新
            ArrayNode updateNodes = collectPatchArray(result.path("update_nodes"));
            ArrayNode updateEdges = collectPatchArray(result.path("update_edges"));
            int updateNodeCount = updateNodes.size();
            int updateEdgeCount = updateEdges.size();

            // 逐个实体 / 关系上报，让用户看到本体被一步步"构建"出来，而不是只看到一个总数
            emitBuildSteps(step, addNodes, addEdges);

            StringBuilder mergeLabel = new StringBuilder("正在合并到图谱… (+")
                    .append(nodeCount).append(" 节点 / +").append(edgeCount).append(" 关系");
            if (updateNodeCount > 0 || updateEdgeCount > 0) {
                mergeLabel.append(" · ~").append(updateNodeCount).append(" 节点 / ~")
                        .append(updateEdgeCount).append(" 关系");
            }
            if (removeNodeCount > 0 || removeEdgeCount > 0) {
                mergeLabel.append(" · -").append(removeNodeCount).append(" 节点 / -")
                        .append(removeEdgeCount).append(" 关系");
            }
            mergeLabel.append(")");
            step.step("merging_graph", mergeLabel.toString());

            ObjectNode finalEvent = objectMapper.createObjectNode();
            finalEvent.put("reply", reply);
            finalEvent.set("add_nodes", addNodes);
            finalEvent.set("add_edges", addEdges);
            if (removeNodeCount > 0) finalEvent.set("remove_nodes", removeNodes);
            if (removeEdgeCount > 0) finalEvent.set("remove_edges", removeEdges);
            if (updateNodeCount > 0) finalEvent.set("update_nodes", updateNodes);
            if (updateEdgeCount > 0) finalEvent.set("update_edges", updateEdges);
            // 透传 LLM 返回的 clarifying questions(支持一次多个问题、单题多选);
            // 兼容旧的单 question 字段。前端渲染为可点击选项卡片。最多 4 个问题。
            ArrayNode questions = objectMapper.createArrayNode();
            JsonNode qsNode = result.path("questions");
            if (qsNode.isArray()) {
                for (JsonNode q : qsNode) {
                    if (questions.size() >= 4) break;
                    appendValidQuestion(questions, q);
                }
            } else {
                appendValidQuestion(questions, result.path("question"));
            }
            if (!questions.isEmpty()) finalEvent.set("questions", questions);
            step.send("complete", objectMapper.writeValueAsString(finalEvent));
            emitter.complete();
        } catch (Exception e) {
            log.error("[LLM-chat-sse] 响应解析失败: {}", e.getMessage());
            http.metrics().recordCall(modelName, elapsed, false);
            step.send("error", "响应解析失败: " + e.getMessage());
            emitter.complete();
        }
    }

    /**
     * 把 LLM 返回的 remove_nodes / remove_edges 归一成「字符串 id 数组」。
     * 容错两种形态:["id1","id2"] 或 [{"id":"..","label":".."}],只取 id(回退 label);
     * 去重、去空白,非数组时返回空数组。
     */
    private ArrayNode collectIdArray(JsonNode node) {
        ArrayNode out = objectMapper.createArrayNode();
        if (node == null || !node.isArray()) return out;
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (JsonNode item : node) {
            String id;
            if (item.isTextual()) {
                id = item.asText("");
            } else if (item.isObject()) {
                id = item.path("id").asText("");
                if (id.isBlank()) id = item.path("label").asText("");
            } else {
                continue;
            }
            id = id.trim();
            if (!id.isBlank() && seen.add(id)) out.add(id);
        }
        return out;
    }

    /**
     * 把 LLM 返回的 update_nodes / update_edges 归一成「带 id 的 patch 对象数组」。
     * 仅保留是对象且含非空 id 的项,按 id 去重(后者覆盖前者)。非数组时返回空数组。
     */
    private ArrayNode collectPatchArray(JsonNode node) {
        ArrayNode out = objectMapper.createArrayNode();
        if (node == null || !node.isArray()) return out;
        java.util.LinkedHashMap<String, JsonNode> byId = new java.util.LinkedHashMap<>();
        for (JsonNode item : node) {
            if (item == null || !item.isObject()) continue;
            String id = item.path("id").asText("").trim();
            if (id.isBlank()) continue;
            byId.put(id, item);
        }
        for (JsonNode v : byId.values()) out.add(v);
        return out;
    }

    /** 校验并收录一个 clarifying question:需有非空 text 与非空 options 数组,否则跳过。 */
    private static void appendValidQuestion(ArrayNode arr, JsonNode q) {
        if (q == null || q.isMissingNode() || q.isNull()) return;
        if (!q.has("text") || q.path("text").asText("").isBlank()) return;
        JsonNode opts = q.path("options");
        if (!opts.isArray() || opts.isEmpty()) return;
        arr.add(q);
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
            // 兜底: 直接看顶层 message
            if (root.has("message")) return root.path("message").asText(null);
        } catch (Exception ignored) {
            // 非 JSON,截断前 200 字直接返回
            String trimmed = body.length() > 200 ? body.substring(0, 200) + "…" : body;
            return trimmed.replaceAll("\\s+", " ");
        }
        return null;
    }

    /**
     * 把 LLM 一次性返回的 add_nodes / add_edges 拆成逐条 step 事件推给前端：
     * 先逐个"构建实体「X」(type)"，再逐个"建立关系「A —关系→ B」"，
     * 让用户直观看到本体的实体与关系是如何被构建出来的。总条数受 {@link #MAX_BUILD_STEPS} 限制。
     */
    private void emitBuildSteps(ChatStepEmitter step, JsonNode addNodes, JsonNode addEdges) {
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
                if (!step.step("build_node_" + (i++), text)) return; // emitter 已关闭，停止逐步推送
                budget--;
                ChatStepEmitter.sleepQuiet(BUILD_STEP_DELAY_MS);
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
                if (!step.step("build_edge_" + (j++), text)) return; // emitter 已关闭，停止逐步推送
                budget--;
                ChatStepEmitter.sleepQuiet(BUILD_STEP_DELAY_MS);
            }
        }
    }
}
