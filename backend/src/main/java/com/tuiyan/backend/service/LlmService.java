package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tuiyan.backend.config.AppPaths;
import com.tuiyan.backend.model.ChatRequest;
import com.tuiyan.backend.model.ConfigResponse;
import com.tuiyan.backend.model.LlmProvider;
import com.tuiyan.backend.model.ModelConfig;
import com.tuiyan.backend.model.ModelConfigPersist;
import com.tuiyan.backend.util.JsonAtomic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 单独的 mapper：写盘时通过 mixin 重新暴露 apiKey，避免 WRITE_ONLY 把字段丢掉。 */
    private final ObjectMapper persistMapper = new ObjectMapper()
            .addMixIn(ModelConfig.class, ModelConfigPersist.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(20))
            .build();

    private final AppPaths appPaths;

    public LlmService(AppPaths appPaths) {
        this.appPaths = appPaths;
    }

    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int ANTHROPIC_MAX_TOKENS = 8192;

    // ========== 缓存：避免每次接口都读盘 ==========
    private static final long CONFIG_CACHE_TTL_MS = 5_000;
    private volatile List<ModelConfig> cachedConfigs;
    private volatile long cachedAt;

    private static final String SCHEMA_STRING = """
        {
          "reply": "A short, helpful assistant reply acknowledging the user's request and explaining the graph updates.",
          "add_nodes": [
            {
              "id": "A unique id for the node, e.g., 'n_123'",
              "label": "The name of the entity, event, or rule",
              "type": "Must be one of: 'entity', 'event', 'rule', 'process', 'data', 'external'",
              "source": "Must be one of: 'derived' (from text) or 'inferred'",
              "props": [
                { "key": "string", "value": "string", "source": "Must be one of: 'derived' or 'inferred'" }
              ]
            }
          ],
          "add_edges": [
            {
              "id": "Unique edge id, e.g., 'e_456'",
              "from": "Source node id",
              "to": "Target node id",
              "label": "Description or verb of the relationship or rule",
              "source": "Must be one of: 'derived' or 'inferred'",
              "rule_driven": true_or_false
            }
          ]
        }
        """;

    private static final String SYSTEM_INSTRUCTION = """
        You are an AI Ontology Developer.
        Analyze the user's text to extract real-world relationships, driving events, entities, and rules.
        Formulate this as a directed graph.
        CRITICAL INSTRUCTION:
        1. Explicitly represent rules (type: 'rule') if they drive events.
        2. Label ALL properties, nodes, and edges with their 'source' - if it was explicitly mentioned in the user's text, mark it 'derived'. If you imagined it or inferred it with your world knowledge to fill in blanks, mark it 'inferred'.
        3. You MUST return ONLY valid JSON strictly matching this schema. NO markdown wrapping, just the raw JSON object.

        SCHEMA:
        %s""".formatted(SCHEMA_STRING);

    private static final String EXTRACT_SYSTEM = """
        You are an AI Ontology Developer extracting a knowledge graph from documents.
        The user has uploaded one or more sources: PDF text excerpts and/or images of
        diagrams, flowcharts, tables, or screenshots.

        Your job: identify every distinct entity, event, process, data, external system,
        and explicit RULE / regulation / SOP step, plus the directed relationships among them.
        Treat the document as authoritative — do not invent content that isn't grounded in it.

        Strict rules:
        1. If a passage describes a conditional / business rule / regulation / SOP step,
           emit a node with type='rule' and add edges from that rule to the events/processes it governs.
        2. Mark nodes/edges 'derived' when they are explicitly stated in the source;
           mark 'inferred' only when filling in obvious gaps with world knowledge.
        3. Use stable ids like 'n_1', 'n_2', 'e_1' — the server rewrites them to avoid collisions.
        4. Be exhaustive but de-duplicated: if two phrasings clearly refer to the same concept,
           emit ONE node.
        5. Return ONLY a JSON object exactly matching SCHEMA. No markdown wrapping.

        SCHEMA:
        %s""".formatted(SCHEMA_STRING);

    private static final String PREDICT_SCHEMA = """
        {
          "chain": [
            {
              "step": 1,
              "id": "p_1",
              "label": "短文本（实体/事件名称）",
              "type": "Must be one of: 'event', 'process', 'outcome', 'entity'",
              "triggered_by": ["id of upstream existing node OR earlier predicted id"],
              "rule_id": "id of rule node that fires (or null)",
              "explanation": "≤40 中文字符，解释为什么这一步会发生",
              "confidence": 0.0
            }
          ]
        }
        """;

    private static final String PREDICT_SYSTEM = """
        你是一个基于本体图谱的前向推演 (Forward Simulation) 引擎。
        给定现有图谱（节点、边、规则）和一个或多个起点节点 (seeds)，请沿因果链向前预测后续可能发生的 N 步事件。

        严格要求：
        1. 所有预测节点的 id 形如 'p_1' / 'p_2'，不要复用现有节点 id。
        2. 每个节点都要给出 triggered_by（上游已有节点 id 或更早的预测节点 id 数组，至少一个）。
        3. 若有规则节点 (type: 'rule') 适用，请在 rule_id 字段引用，否则置 null。
        4. 优先沿 rule_driven 边推演；当现有图谱缺乏路径时，再编造合理新边。
        5. explanation 用简体中文，≤40 字。
        6. confidence ∈ [0, 1]，越高越笃定。
        7. 只输出严格符合 schema 的 JSON，禁止 markdown 包裹。
        8. 步骤数严格等于用户指定的 N；不足时尽量补足，超出请截断。

        SCHEMA:
        %s""".formatted(PREDICT_SCHEMA);

    private static final String PREDICT_BACKWARD_SCHEMA = """
        {
          "chain": [
            {
              "step": 1,
              "id": "p_1",
              "label": "短文本（候选原因/前置事件名称）",
              "type": "Must be one of: 'event', 'process', 'outcome', 'entity'",
              "leads_to": ["id of downstream existing node OR earlier predicted id（即此原因导致的下游节点）"],
              "rule_id": "id of rule node that fires (or null)",
              "explanation": "≤40 中文字符，解释为什么这是合理的上游原因",
              "confidence": 0.0
            }
          ]
        }
        """;

    private static final String PREDICT_BACKWARD_SYSTEM = """
        你是一个基于本体图谱的溯因推演 (Backward Simulation / Abduction) 引擎。
        给定现有图谱和一个或多个目标节点 (seeds，即结果)，请逆向推断可能导致该结果的 N 层上游原因。

        严格要求：
        1. 所有预测节点的 id 形如 'p_1' / 'p_2'，不要复用现有节点 id。
        2. 每个节点都要给出 leads_to（此原因直接导致的下游节点 id 数组，至少一个；通常是目标 seeds 之一，或更晚预测的中间原因）。
        3. step=1 的预测节点应直接 leads_to 到某个 seed；step=k (k>1) 可 leads_to 到更早 step 的预测节点（更靠近 seed 的中间原因）。
        4. 若有规则节点 (type: 'rule') 解释该因果，请在 rule_id 字段引用，否则置 null。
        5. 多个独立原因并行存在很正常，可属于同一 step。
        6. explanation 用简体中文，≤40 字。
        7. confidence ∈ [0, 1]，越高越笃定。
        8. 只输出严格符合 schema 的 JSON，禁止 markdown 包裹。

        SCHEMA:
        %s""".formatted(PREDICT_BACKWARD_SCHEMA);

    /** 解析配置的字段集合，替代旧的 String[]。 */
    public record ResolvedConfig(String baseURL, String modelName, String apiKey, String protocol) {}

    // ========== 模型配置 CRUD ==========

    public synchronized List<ModelConfig> getAllModelConfigs() throws IOException {
        long now = System.currentTimeMillis();
        if (cachedConfigs != null && (now - cachedAt) < CONFIG_CACHE_TTL_MS) {
            return cachedConfigs;
        }
        File file = appPaths.modelsConfigFile();
        if (!file.exists()) {
            migrateLegacyConfig();
            file = appPaths.modelsConfigFile();
        }
        List<ModelConfig> configs = new ArrayList<>();
        if (file.exists()) {
            JsonNode node = persistMapper.readTree(file);
            if (node.isArray()) {
                for (JsonNode item : node) {
                    configs.add(persistMapper.treeToValue(item, ModelConfig.class));
                }
            }
        }
        cachedConfigs = configs;
        cachedAt = now;
        return configs;
    }

    private void invalidateCache() {
        cachedConfigs = null;
        cachedAt = 0L;
    }

    public ModelConfig createModelConfig(com.tuiyan.backend.controller.ModelController.ModelConfigRequest req) throws IOException {
        List<ModelConfig> configs = new ArrayList<>(getAllModelConfigs());
        ModelConfig nc = new ModelConfig(
                req.getName(), req.getBaseUrl(), req.getModelName(),
                req.getApiKey() != null ? req.getApiKey() : "");
        nc.setProvider(req.getProvider());
        nc.setDescription(req.getDescription());
        nc.setContextWindow(req.getContextWindow());
        nc.setMaxOutputTokens(req.getMaxOutputTokens());
        nc.setCapabilities(req.getCapabilities());
        nc.setProtocol(req.getProtocol());
        configs.add(nc);
        saveModelConfigs(configs);
        return nc;
    }

    public ModelConfig updateModelConfig(String id, com.tuiyan.backend.controller.ModelController.ModelConfigRequest req) throws IOException {
        List<ModelConfig> configs = new ArrayList<>(getAllModelConfigs());
        for (int i = 0; i < configs.size(); i++) {
            if (configs.get(i).getId().equals(id)) {
                ModelConfig c = configs.get(i);
                c.setName(req.getName());
                c.setBaseUrl(req.getBaseUrl());
                c.setModelName(req.getModelName());
                if (req.getApiKey() != null && !req.getApiKey().isBlank()) {
                    c.setApiKey(req.getApiKey());
                }
                if (req.getProvider() != null) c.setProvider(req.getProvider());
                if (req.getDescription() != null) c.setDescription(req.getDescription());
                if (req.getContextWindow() != null) c.setContextWindow(req.getContextWindow());
                if (req.getMaxOutputTokens() != null) c.setMaxOutputTokens(req.getMaxOutputTokens());
                if (req.getCapabilities() != null) c.setCapabilities(req.getCapabilities());
                if (req.getProtocol() != null) c.setProtocol(req.getProtocol());
                c.setUpdatedAt(System.currentTimeMillis());
                configs.set(i, c);
                saveModelConfigs(configs);
                return c;
            }
        }
        throw new IllegalArgumentException("Model config not found: " + id);
    }

    public int deleteModelConfig(String id) throws IOException {
        List<ModelConfig> configs = new ArrayList<>(getAllModelConfigs());
        int before = configs.size();
        configs.removeIf(c -> c.getId().equals(id));
        int removed = before - configs.size();
        saveModelConfigs(configs);
        return removed;
    }

    public void toggleModelConfig(String id) throws IOException {
        List<ModelConfig> configs = new ArrayList<>(getAllModelConfigs());
        for (int i = 0; i < configs.size(); i++) {
            if (configs.get(i).getId().equals(id)) {
                ModelConfig config = configs.get(i);
                config.setEnabled(!config.isEnabled());
                config.setUpdatedAt(System.currentTimeMillis());
                configs.set(i, config);
                saveModelConfigs(configs);
                return;
            }
        }
        throw new IllegalArgumentException("Model config not found: " + id);
    }

    private void saveModelConfigs(List<ModelConfig> configs) throws IOException {
        JsonAtomic.write(persistMapper, appPaths.modelsConfigFile(), configs);
        invalidateCache();
    }

    private void migrateLegacyConfig() throws IOException {
        File legacyFile = appPaths.legacyConfigTargetFile();
        if (legacyFile.exists()) {
            JsonNode legacy = persistMapper.readTree(legacyFile);
            String provider = legacy.has("provider") ? legacy.get("provider").asText() : "qwen";
            String baseUrl = legacy.has("baseUrl") ? legacy.get("baseUrl").asText() : "";
            String modelName = legacy.has("modelName") ? legacy.get("modelName").asText() : "";

            List<ModelConfig> configs = new ArrayList<>();
            ModelConfig migrated = new ModelConfig("迁移配置", baseUrl, modelName, "");
            migrated.setId("legacy-" + provider);
            configs.add(migrated);
            saveModelConfigs(configs);
        } else {
            saveModelConfigs(new ArrayList<>());
        }
    }

    // ========== 兼容旧接口 ==========

    public JsonNode getConfig() throws IOException {
        List<ModelConfig> configs = getAllModelConfigs();
        ObjectNode node = objectMapper.createObjectNode();
        if (!configs.isEmpty()) {
            ModelConfig first = configs.get(0);
            node.put("provider", "qwen");
            node.put("baseUrl", first.getBaseUrl());
            node.put("modelName", first.getModelName());
        } else {
            node.put("provider", "qwen");
            node.put("baseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1");
            node.put("modelName", "qwen-max");
        }
        return node;
    }

    public ConfigResponse getConfigResponse() throws IOException {
        JsonNode config = getConfig();
        String providerCode = config.has("provider") ? config.get("provider").asText() : LlmProvider.QWEN.getCode();
        LlmProvider provider = LlmProvider.fromCode(providerCode);

        String baseUrl = config.has("baseUrl") ? config.get("baseUrl").asText() : provider.getBaseUrl();
        String modelName = config.has("modelName") ? config.get("modelName").asText() : provider.getDefaultModel();

        List<ConfigResponse.ProviderInfo> providers = new ArrayList<>();
        for (LlmProvider p : LlmProvider.values()) {
            providers.add(new ConfigResponse.ProviderInfo(
                p.getCode(),
                p.getDisplayName(),
                p.getBaseUrl(),
                p.getDefaultModel(),
                p.getModels(),
                p.getApiKeyEnvName()
            ));
        }

        List<ConfigResponse.ModelConfigInfo> customModels = new ArrayList<>();
        for (ModelConfig mc : getAllModelConfigs()) {
            ConfigResponse.ModelConfigInfo info = new ConfigResponse.ModelConfigInfo(
                mc.getId(),
                mc.getName(),
                mc.getBaseUrl(),
                mc.getModelName(),
                mc.isEnabled()
            );
            info.setProvider(mc.getProvider());
            info.setDescription(mc.getDescription());
            info.setContextWindow(mc.getContextWindow());
            info.setMaxOutputTokens(mc.getMaxOutputTokens());
            info.setCapabilities(mc.getCapabilities());
            info.setProtocol(mc.getProtocol());
            customModels.add(info);
        }

        return new ConfigResponse(providerCode, baseUrl, modelName, providers, customModels);
    }

    public void saveConfig(String provider, String baseUrl, String modelName, String apiKey) throws IOException {
        ObjectNode config = objectMapper.createObjectNode();
        config.put("provider", provider);
        config.put("baseUrl", baseUrl);
        config.put("modelName", modelName);
        if (apiKey != null && !apiKey.isBlank()) {
            config.put("apiKey", apiKey);
        }
        JsonAtomic.write(objectMapper, appPaths.legacyConfigTargetFile(), config);
        invalidateCache();
    }

    // ========== 聊天接口 ==========

    /**
     * 解析模型配置，返回 baseURL / modelName / apiKey / protocol。
     */
    private ResolvedConfig resolveConfig(String modelOverride, String configId) throws Exception {
        String baseURL;
        String modelName;
        String apiKey;
        String protocol = null;

        if (configId != null && !configId.isBlank()) {
            List<ModelConfig> configs = getAllModelConfigs();
            ModelConfig selected = null;
            for (ModelConfig mc : configs) {
                if (mc.getId().equals(configId)) {
                    selected = mc;
                    break;
                }
            }
            if (selected == null) {
                throw new IllegalArgumentException("Model config not found: " + configId);
            }
            if (!selected.isEnabled()) {
                throw new IllegalStateException("Model config is disabled: " + configId);
            }
            baseURL = selected.getBaseUrl();
            modelName = selected.getModelName();
            apiKey = selected.getApiKey();
            protocol = selected.getProtocol();
        } else {
            JsonNode fileConfig = getConfig();
            String providerCode = fileConfig.has("provider") ? fileConfig.get("provider").asText() : LlmProvider.QWEN.getCode();
            LlmProvider provider = LlmProvider.fromCode(providerCode);

            apiKey = getApiKey(provider, fileConfig);
            baseURL = fileConfig.has("baseUrl") ? fileConfig.get("baseUrl").asText() : provider.getBaseUrl();
            modelName = fileConfig.has("modelName") ? fileConfig.get("modelName").asText() : provider.getDefaultModel();
        }

        if (modelOverride != null && !modelOverride.isBlank()) {
            modelName = modelOverride;
        }

        if (System.getenv("LLM_BASE_URL") != null && !System.getenv("LLM_BASE_URL").isBlank()) {
            baseURL = System.getenv("LLM_BASE_URL");
        }
        if (System.getenv("LLM_MODEL_NAME") != null && !System.getenv("LLM_MODEL_NAME").isBlank()) {
            modelName = System.getenv("LLM_MODEL_NAME");
        }
        if (apiKey == null && System.getenv("LLM_API_KEY") != null && !System.getenv("LLM_API_KEY").isBlank()) {
            apiKey = System.getenv("LLM_API_KEY");
        }

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Missing API key. Please set environment variable: LLM_API_KEY");
        }

        return new ResolvedConfig(baseURL, modelName, apiKey, protocol);
    }

    /**
     * 决定是否走 Anthropic 协议：显式 protocol 字段最高优先，其次按 baseURL/modelName 探测。
     */
    private boolean isAnthropic(String baseURL, String modelName, String protocol) {
        if (protocol != null && !protocol.isBlank()) {
            return "anthropic".equalsIgnoreCase(protocol);
        }
        return LlmProvider.isAnthropicEndpoint(baseURL, modelName);
    }

    /**
     * 统一构建 OpenAI 兼容协议的请求体。三个调用点（chat / predict / extract）共用，
     * 自动处理 history、image_url 多模态附件、response_format=json_object。
     */
    private String buildOpenAiBody(String modelName, String systemPrompt, String userText,
                                   List<Map<String, Object>> history,
                                   List<Map<String, Object>> attachments,
                                   boolean stream, boolean jsonMode) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", modelName);
        root.put("stream", stream);

        ArrayNode messages = objectMapper.createArrayNode();

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode sys = objectMapper.createObjectNode();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
            messages.add(sys);
        }

        if (history != null) {
            for (Map<String, Object> msg : history) {
                String role = String.valueOf(msg.get("role"));
                if (!"user".equals(role) && !"assistant".equals(role)) continue;
                ObjectNode h = objectMapper.createObjectNode();
                h.put("role", role);
                h.put("content", String.valueOf(msg.get("content")));
                messages.add(h);
            }
        }

        List<Map<String, Object>> imageAtts = new ArrayList<>();
        if (attachments != null) {
            for (Map<String, Object> a : attachments) {
                Object kind = a.get("type");
                Object url = a.get("dataUrl");
                if ("image".equals(String.valueOf(kind)) && url != null && !String.valueOf(url).isBlank()) {
                    imageAtts.add(a);
                }
            }
        }

        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        if (imageAtts.isEmpty()) {
            userMsg.put("content", userText == null ? "" : userText);
        } else {
            ArrayNode contentArr = objectMapper.createArrayNode();
            ObjectNode textPart = objectMapper.createObjectNode();
            textPart.put("type", "text");
            textPart.put("text", userText == null ? "" : userText);
            contentArr.add(textPart);
            for (Map<String, Object> img : imageAtts) {
                ObjectNode imgPart = objectMapper.createObjectNode();
                imgPart.put("type", "image_url");
                ObjectNode imgUrl = objectMapper.createObjectNode();
                imgUrl.put("url", String.valueOf(img.get("dataUrl")));
                imgPart.set("image_url", imgUrl);
                contentArr.add(imgPart);
            }
            userMsg.set("content", contentArr);
        }
        messages.add(userMsg);

        root.set("messages", messages);

        if (jsonMode) {
            ObjectNode rf = objectMapper.createObjectNode();
            rf.put("type", "json_object");
            root.set("response_format", rf);
        }
        return objectMapper.writeValueAsString(root);
    }

    /**
     * 同步聊天（非流式）
     */
    public JsonNode chat(List<Map<String, Object>> nodes, List<Map<String, Object>> edges, String message, String modelOverride, String configId, List<Map<String, Object>> history) throws Exception {
        return chat(nodes, edges, message, modelOverride, configId, history, null);
    }

    public JsonNode chat(List<Map<String, Object>> nodes, List<Map<String, Object>> edges, String message,
                         String modelOverride, String configId, List<Map<String, Object>> history,
                         List<Map<String, Object>> attachments) throws Exception {
        ResolvedConfig cfg = resolveConfig(modelOverride, configId);
        boolean anthropic = isAnthropic(cfg.baseURL(), cfg.modelName(), cfg.protocol());

        String prompt = "Here is the user's latest message:\n" + message +
                "\n\nPlease generate the corresponding entities and relationships strictly in JSON format matching the given schema.";

        String requestBody = anthropic
                ? buildAnthropicBody(cfg.modelName(), SYSTEM_INSTRUCTION, prompt, history, attachments, false, ANTHROPIC_MAX_TOKENS)
                : buildOpenAiBody(cfg.modelName(), SYSTEM_INSTRUCTION, prompt, history, attachments, false, true);

        HttpRequest request = buildHttpRequest(cfg.baseURL(), cfg.apiKey(), anthropic, requestBody);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            logUpstreamError("chat", response.statusCode(), response.body());
            throw new RuntimeException("LLM 调用失败 HTTP " + response.statusCode() + "（详情见服务器日志）");
        }

        JsonNode responseJson = objectMapper.readTree(response.body());
        String content = extractContent(responseJson, anthropic);
        content = content.replaceAll("(?i)^```json", "").replaceAll("```$", "").trim();
        if (content.isEmpty()) content = "{}";
        return objectMapper.readTree(content);
    }

    /**
     * 流式聊天：通过 SSE 逐 token 推送文本，完成后发送结构化数据
     */
    public void chatStreaming(ChatRequest request, SseEmitter emitter) {
        try {
            ResolvedConfig cfg = resolveConfig(request.getModelOverride(), request.getConfigId());
            boolean anthropic = isAnthropic(cfg.baseURL(), cfg.modelName(), cfg.protocol());

            String prompt = "Here is the user's latest message:\n" + request.getMessage() +
                    "\n\nPlease generate the corresponding entities and relationships strictly in JSON format matching the given schema.";

            String requestBody = anthropic
                    ? buildAnthropicBody(cfg.modelName(), SYSTEM_INSTRUCTION, prompt, request.getHistory(), request.getAttachments(), true, ANTHROPIC_MAX_TOKENS)
                    : buildOpenAiBody(cfg.modelName(), SYSTEM_INSTRUCTION, prompt, request.getHistory(), request.getAttachments(), true, true);

            HttpRequest httpRequest = buildHttpRequest(cfg.baseURL(), cfg.apiKey(), anthropic, requestBody);

            httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
                    .thenAccept(resp -> {
                        if (resp.statusCode() != 200) {
                            String errorBody;
                            try {
                                errorBody = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
                            } catch (IOException e) {
                                errorBody = "Unknown error";
                            }
                            logUpstreamError("chatStreaming", resp.statusCode(), errorBody);
                            try {
                                emitter.send(SseEmitter.event().name("error").data(
                                        "LLM 调用失败 HTTP " + resp.statusCode() + "（详情见服务器日志）"));
                            } catch (IOException e) {
                                log.warn("emit error event failed", e);
                            }
                            emitter.complete();
                            return;
                        }

                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                            StringBuilder fullContent = anthropic
                                    ? streamAnthropic(reader, emitter)
                                    : streamOpenAI(reader, emitter);

                            String content = fullContent.toString()
                                    .replaceAll("(?i)^```json", "").replaceAll("```$", "").trim();
                            if (content.isEmpty()) content = "{}";
                            JsonNode result = objectMapper.readTree(content);

                            ObjectNode finalEvent = objectMapper.createObjectNode();
                            finalEvent.put("reply", result.path("reply").asText(""));
                            finalEvent.set("add_nodes", result.path("add_nodes"));
                            finalEvent.set("add_edges", result.path("add_edges"));
                            try {
                                emitter.send(SseEmitter.event().name("complete")
                                        .data(objectMapper.writeValueAsString(finalEvent)));
                            } catch (IOException sendErr) {
                                log.warn("emit complete failed (client likely disconnected): {}", sendErr.toString());
                            }
                            emitter.complete();
                        } catch (IOException e) {
                            try {
                                emitter.send(SseEmitter.event().name("error").data("Failed to parse response: " + e.getMessage()));
                            } catch (IOException ex) {
                                log.warn("emit parse-error failed", ex);
                            }
                            emitter.complete();
                        }
                    })
                    .exceptionally(ex -> {
                        try {
                            emitter.send(SseEmitter.event().name("error").data("Network error: " + ex.getMessage()));
                        } catch (IOException e) {
                            log.warn("emit network-error failed", e);
                        }
                        emitter.completeWithError(ex);
                        return null;
                    });
        } catch (Exception e) {
            try {
                emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
            } catch (IOException ioEx) {
                log.warn("emit init-error failed", ioEx);
            }
            emitter.completeWithError(e);
        }
    }

    private StringBuilder streamOpenAI(BufferedReader reader, SseEmitter emitter) throws IOException {
        StringBuilder fullContent = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.startsWith("data: ")) continue;
            String data = line.substring(6);
            if ("[DONE]".equals(data)) break;
            try {
                JsonNode chunk = objectMapper.readTree(data);
                String delta = chunk.path("choices").path(0).path("delta").path("content").asText("");
                if (delta.isEmpty()) continue;
                fullContent.append(delta);
                try {
                    emitter.send(SseEmitter.event().name("text").data(delta));
                } catch (IOException sendErr) {
                    log.warn("emit chunk failed (client disconnected?): {}", sendErr.toString());
                    break;
                }
            } catch (IOException parseErr) {
                log.warn("openai stream chunk parse failed: {}", parseErr.toString());
            }
        }
        return fullContent;
    }

    /**
     * Anthropic SSE 解析。
     */
    private StringBuilder streamAnthropic(BufferedReader reader, SseEmitter emitter) throws IOException {
        StringBuilder fullContent = new StringBuilder();
        String line;
        String currentEvent = "";
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) continue;
            if (line.startsWith("event: ")) {
                currentEvent = line.substring(7).trim();
                continue;
            }
            if (!line.startsWith("data: ")) continue;
            String data = line.substring(6);
            if ("[DONE]".equals(data)) break;

            try {
                JsonNode chunk = objectMapper.readTree(data);
                String type = chunk.path("type").asText("");
                if ("content_block_delta".equals(type) || "content_block_delta".equals(currentEvent)) {
                    JsonNode delta = chunk.path("delta");
                    String dtype = delta.path("type").asText("");
                    if ("text_delta".equals(dtype) || "input_json_delta".equals(dtype)) {
                        String text = delta.path("text").asText("");
                        if (text.isEmpty()) text = delta.path("partial_json").asText("");
                        if (!text.isEmpty()) {
                            fullContent.append(text);
                            try {
                                emitter.send(SseEmitter.event().name("text").data(text));
                            } catch (IOException sendErr) {
                                log.warn("emit anthropic chunk failed: {}", sendErr.toString());
                                return fullContent;
                            }
                        }
                    }
                } else if ("message_stop".equals(type) || "message_stop".equals(currentEvent)) {
                    break;
                } else if ("error".equals(type)) {
                    String msg = chunk.path("error").path("message").asText("Anthropic error");
                    try {
                        emitter.send(SseEmitter.event().name("error").data(msg));
                    } catch (IOException sendErr) {
                        log.warn("emit anthropic error failed: {}", sendErr.toString());
                    }
                    break;
                }
            } catch (IOException parseErr) {
                log.warn("anthropic chunk parse failed: {}", parseErr.toString());
            }
        }
        return fullContent;
    }

    // ========== 场景推演 ==========

    public JsonNode predictChain(com.tuiyan.backend.model.PredictRequest req) throws Exception {
        ResolvedConfig cfg = resolveConfig(req.getModelOverride(), req.getConfigId());
        boolean anthropic = isAnthropic(cfg.baseURL(), cfg.modelName(), cfg.protocol());

        int steps = req.getSteps() == null ? 4 : Math.max(1, Math.min(10, req.getSteps()));
        boolean backward = "backward".equalsIgnoreCase(req.getIntent());
        String systemPrompt = backward ? PREDICT_BACKWARD_SYSTEM : PREDICT_SYSTEM;
        String seedRole = backward ? "目标节点 (seeds，需要溯因的结果)" : "起点节点 (seeds)";
        String taskWord = backward ? "请向上回溯 " : "请向前推演 ";
        String taskUnit = backward ? " 层上游原因" : " 步";

        String graphSummary = summarizeGraph(req.getNodes(), req.getEdges());
        String seedSummary = summarizeSeeds(req.getSeeds(), req.getNodes());
        String rulesSummary = summarizeRules(req.getNodes());
        String constraintsSummary = summarizeConstraints(req.getConstraints(), req.getNodes());

        // v0.9：context 截断 —— 大图谱时只保留 seeds/规则/约束目标 + k-hop 邻域
        TruncatedGraph truncated = truncateGraphForContext(
                req.getNodes(), req.getEdges(), req.getSeeds(), req.getConstraints());
        boolean wasTruncated = truncated.droppedNodes > 0 || truncated.droppedEdges > 0;
        if (wasTruncated) {
            graphSummary = summarizeGraph(truncated.nodes, truncated.edges);
        }

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("当前本体图谱：\n").append(graphSummary).append("\n");
        if (wasTruncated) {
            userPrompt.append("(为控制 LLM context 已截断 ")
                      .append(truncated.droppedNodes).append(" 个节点 / ")
                      .append(truncated.droppedEdges).append(" 条边，仅保留 seeds、规则、约束目标及其 ")
                      .append(CONTEXT_HOPS).append("-hop 邻域。)\n");
        }
        userPrompt.append("\n");
        if (!rulesSummary.isBlank()) {
            userPrompt.append("可用规则 (优先沿规则推演)：\n").append(rulesSummary).append("\n");
        }
        userPrompt.append(seedRole).append("：\n").append(seedSummary).append("\n");
        if (!constraintsSummary.isBlank()) {
            userPrompt.append("\nWhat-if 约束（必须严格遵守）：\n").append(constraintsSummary).append("\n");
        }
        if (req.getPrompt() != null && !req.getPrompt().isBlank()) {
            userPrompt.append("\n额外场景说明：").append(req.getPrompt()).append("\n");
        }
        userPrompt.append("\n").append(taskWord).append(steps).append(taskUnit).append("，严格按 schema 输出 JSON。");

        String requestBody = anthropic
                ? buildAnthropicBody(cfg.modelName(), systemPrompt, userPrompt.toString(),
                        null, null, false, ANTHROPIC_MAX_TOKENS)
                : buildOpenAiBody(cfg.modelName(), systemPrompt, userPrompt.toString(),
                        null, null, false, true);

        HttpRequest httpReq = buildHttpRequest(cfg.baseURL(), cfg.apiKey(), anthropic, requestBody);
        HttpResponse<String> resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            logUpstreamError("predict", resp.statusCode(), resp.body());
            throw new RuntimeException("LLM 调用失败 HTTP " + resp.statusCode() + "（详情见服务器日志）");
        }

        JsonNode root = objectMapper.readTree(resp.body());
        String content = extractContent(root, anthropic);
        content = content.replaceAll("(?i)^```json", "").replaceAll("```$", "").trim();
        if (content.isEmpty()) content = "{}";
        return objectMapper.readTree(content);
    }

    private static final int EXTRACT_CHUNK_CHARS = 30_000;

    public JsonNode extractOntologyFromSources(String combinedText,
                                               List<Map<String, Object>> imageAttachments,
                                               String modelOverride,
                                               String configId) throws Exception {
        ResolvedConfig cfg = resolveConfig(modelOverride, configId);
        boolean anthropic = isAnthropic(cfg.baseURL(), cfg.modelName(), cfg.protocol());

        List<String> chunks = chunkText(combinedText, EXTRACT_CHUNK_CHARS);
        boolean hasImages = imageAttachments != null && !imageAttachments.isEmpty();
        if (chunks.isEmpty() && !hasImages) {
            throw new IllegalArgumentException("No usable text or images for extraction");
        }
        if (chunks.isEmpty()) chunks = new ArrayList<>(java.util.List.of(""));

        JsonNode merged = null;
        for (int i = 0; i < chunks.size(); i++) {
            List<Map<String, Object>> imgs = (i == 0) ? imageAttachments : null;
            String preface = chunks.size() > 1
                    ? "（这是分 " + chunks.size() + " 段输入的第 " + (i + 1)
                      + " 段；语义相同的概念请保持 label 一致，便于跨段合并。）\n\n"
                    : "";
            JsonNode part = callExtractOnce(preface + chunks.get(i), imgs,
                    cfg.modelName(), cfg.baseURL(), cfg.apiKey(), anthropic);
            if (chunks.size() > 1) part = prefixChunkIds(part, "c" + i + "_");
            merged = (merged == null) ? part : mergeExtractionByLabel(merged, part);
        }
        return merged == null ? objectMapper.createObjectNode() : merged;
    }

    private JsonNode callExtractOnce(String userText,
                                     List<Map<String, Object>> imageAttachments,
                                     String modelName, String baseURL, String apiKey, boolean anthropic) throws Exception {
        boolean hasImages = imageAttachments != null && !imageAttachments.isEmpty();
        StringBuilder userPrompt = new StringBuilder();
        if (userText != null && !userText.isBlank()) {
            userPrompt.append("以下是从上传文档中提取的文本内容：\n\n----- BEGIN TEXT -----\n")
                      .append(userText)
                      .append("\n----- END TEXT -----\n\n");
        }
        if (hasImages) {
            userPrompt.append("用户还附上了 ").append(imageAttachments.size())
                      .append(" 张图片（流程图 / 截图 / 表格 / 示意图），请同时分析图中文字、")
                      .append("箭头指向、表格关系，把图中可见的实体和因果链也抽取出来。\n\n");
        }
        userPrompt.append("请抽取所有可识别的本体节点（含规则）与关系，按 SCHEMA 输出 JSON。");

        String requestBody = anthropic
                ? buildAnthropicBody(modelName, EXTRACT_SYSTEM, userPrompt.toString(),
                        null, imageAttachments, false, ANTHROPIC_MAX_TOKENS)
                : buildOpenAiBody(modelName, EXTRACT_SYSTEM, userPrompt.toString(),
                        null, imageAttachments, false, true);

        HttpRequest httpReq = buildHttpRequest(baseURL, apiKey, anthropic, requestBody);
        HttpResponse<String> resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            logUpstreamError("extract", resp.statusCode(), resp.body());
            throw new RuntimeException("LLM 调用失败 HTTP " + resp.statusCode() + "（详情见服务器日志）");
        }
        JsonNode root = objectMapper.readTree(resp.body());
        String content = extractContent(root, anthropic);
        content = content.replaceAll("(?i)^```json", "").replaceAll("```$", "").trim();
        if (content.isEmpty()) content = "{}";
        return objectMapper.readTree(content);
    }

    /** Log upstream LLM HTTP errors, truncated to first 1000 chars for safety. */
    private static void logUpstreamError(String where, int status, String body) {
        String snippet = body == null ? "" : body.substring(0, Math.min(body.length(), 1000));
        log.warn("LLM upstream error in {}: HTTP {} body[:1000]={}", where, status, snippet);
    }

    private List<String> chunkText(String text, int maxChars) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        if (text.length() <= maxChars) { out.add(text); return out; }
        int n = text.length();
        int idx = 0;
        while (idx < n) {
            int end = Math.min(n, idx + maxChars);
            if (end < n) {
                int bp = text.lastIndexOf('\n', end);
                if (bp <= idx + maxChars / 2) bp = text.lastIndexOf('。', end);
                if (bp <= idx + maxChars / 2) bp = text.lastIndexOf('.', end);
                if (bp > idx + maxChars / 2) end = bp + 1;
            }
            out.add(text.substring(idx, end));
            idx = end;
        }
        return out;
    }

    private JsonNode prefixChunkIds(JsonNode part, String prefix) {
        ObjectNode out = objectMapper.createObjectNode();
        if (part.has("reply")) out.set("reply", part.get("reply"));

        Map<String, String> idMap = new HashMap<>();
        ArrayNode srcNodes = part.has("add_nodes") && part.get("add_nodes").isArray()
                ? (ArrayNode) part.get("add_nodes") : objectMapper.createArrayNode();
        ArrayNode outNodes = objectMapper.createArrayNode();
        for (JsonNode n : srcNodes) {
            ObjectNode copy = n.deepCopy();
            String oldId = copy.path("id").asText("");
            if (oldId.isEmpty()) continue;
            String newId = prefix + oldId;
            idMap.put(oldId, newId);
            copy.put("id", newId);
            outNodes.add(copy);
        }
        ArrayNode srcEdges = part.has("add_edges") && part.get("add_edges").isArray()
                ? (ArrayNode) part.get("add_edges") : objectMapper.createArrayNode();
        ArrayNode outEdges = objectMapper.createArrayNode();
        for (JsonNode e : srcEdges) {
            ObjectNode copy = e.deepCopy();
            String f = copy.path("from").asText("");
            String t = copy.path("to").asText("");
            if (idMap.containsKey(f)) copy.put("from", idMap.get(f));
            if (idMap.containsKey(t)) copy.put("to", idMap.get(t));
            String eid = copy.path("id").asText("");
            if (!eid.isEmpty()) copy.put("id", prefix + eid);
            outEdges.add(copy);
        }
        out.set("add_nodes", outNodes);
        out.set("add_edges", outEdges);
        return out;
    }

    /**
     * 按 label 标准化跨段合并：相同标签视为同一节点；
     * 第二段重复 label 的节点不再直接丢弃，而是把它的 props 合并入第一段对应节点，
     * 按 props.key 去重，a 中已有的 key 保留 a 的值。
     */
    private JsonNode mergeExtractionByLabel(JsonNode a, JsonNode b) {
        ObjectNode out = objectMapper.createObjectNode();
        if (a.has("reply")) out.set("reply", a.get("reply"));

        ArrayNode outNodes = objectMapper.createArrayNode();
        ArrayNode outEdges = objectMapper.createArrayNode();
        Map<String, String> labelToId = new HashMap<>();
        Map<String, ObjectNode> idToNode = new HashMap<>();
        Map<String, String> idRemap = new HashMap<>();

        for (JsonNode n : a.path("add_nodes")) {
            ObjectNode copy = n.deepCopy();
            outNodes.add(copy);
            String norm = normalizeLabel(copy.path("label").asText(""));
            String id = copy.path("id").asText("");
            if (!norm.isEmpty()) labelToId.put(norm, id);
            if (!id.isEmpty()) idToNode.put(id, copy);
        }
        for (JsonNode e : a.path("add_edges")) outEdges.add(e);

        for (JsonNode n : b.path("add_nodes")) {
            String norm = normalizeLabel(n.path("label").asText(""));
            String id = n.path("id").asText("");
            if (!norm.isEmpty() && labelToId.containsKey(norm)) {
                String aId = labelToId.get(norm);
                idRemap.put(id, aId);
                ObjectNode existing = idToNode.get(aId);
                if (existing != null) {
                    mergeNodeProps(existing, n);
                }
            } else {
                ObjectNode copy = n.deepCopy();
                outNodes.add(copy);
                if (!norm.isEmpty() && !id.isEmpty()) {
                    labelToId.put(norm, id);
                    idToNode.put(id, copy);
                }
            }
        }
        for (JsonNode e : b.path("add_edges")) {
            ObjectNode copy = e.deepCopy();
            String f = copy.path("from").asText("");
            String t = copy.path("to").asText("");
            copy.put("from", idRemap.getOrDefault(f, f));
            copy.put("to", idRemap.getOrDefault(t, t));
            outEdges.add(copy);
        }
        out.set("add_nodes", outNodes);
        out.set("add_edges", outEdges);
        return out;
    }

    /**
     * 合并 b 节点的 props 到 a 节点的 props，按 props.key 去重，a 的 key 优先保留。
     */
    private void mergeNodeProps(ObjectNode aNode, JsonNode bNode) {
        JsonNode aProps = aNode.path("props");
        JsonNode bProps = bNode.path("props");
        if (!bProps.isArray() || bProps.isEmpty()) return;
        ArrayNode merged;
        Set<String> seenKeys = new HashSet<>();
        if (aProps.isArray()) {
            merged = (ArrayNode) aProps;
            for (JsonNode p : aProps) {
                String k = p.path("key").asText("");
                if (!k.isEmpty()) seenKeys.add(k);
            }
        } else {
            merged = objectMapper.createArrayNode();
        }
        for (JsonNode p : bProps) {
            String k = p.path("key").asText("");
            if (k.isEmpty()) continue;
            if (seenKeys.contains(k)) continue;
            merged.add(p.deepCopy());
            seenKeys.add(k);
        }
        aNode.set("props", merged);
    }

    private static String normalizeLabel(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    // ========== 协议适配辅助 ==========

    private HttpRequest buildHttpRequest(String baseURL, String apiKey, boolean anthropic, String requestBody) {
        String url = baseURL.replaceFirst("/+$", "") + (anthropic ? "/messages" : "/chat/completions");
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));
        if (anthropic) {
            b.header("x-api-key", apiKey);
            b.header("anthropic-version", ANTHROPIC_VERSION);
        } else {
            b.header("Authorization", "Bearer " + apiKey);
        }
        return b.build();
    }

    private String extractContent(JsonNode responseJson, boolean anthropic) {
        if (anthropic) {
            StringBuilder sb = new StringBuilder();
            JsonNode arr = responseJson.path("content");
            if (arr.isArray()) {
                for (JsonNode block : arr) {
                    if ("text".equals(block.path("type").asText())) {
                        sb.append(block.path("text").asText());
                    }
                }
            }
            return sb.toString();
        }
        return responseJson.path("choices").path(0).path("message").path("content").asText("");
    }

    private String buildAnthropicBody(String modelName, String systemPrompt, String userMessage,
                                      List<Map<String, Object>> history,
                                      List<Map<String, Object>> attachments,
                                      boolean stream, int maxTokens) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", modelName);
        root.put("max_tokens", maxTokens);
        root.put("stream", stream);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            root.put("system", systemPrompt);
        }

        ArrayNode messages = objectMapper.createArrayNode();
        if (history != null) {
            for (Map<String, Object> h : history) {
                String role = String.valueOf(h.get("role"));
                if (!"user".equals(role) && !"assistant".equals(role)) continue;
                ObjectNode m = objectMapper.createObjectNode();
                m.put("role", role);
                m.put("content", String.valueOf(h.get("content")));
                messages.add(m);
            }
        }

        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");

        List<Map<String, Object>> imageAtts = new ArrayList<>();
        if (attachments != null) {
            for (Map<String, Object> a : attachments) {
                Object kind = a.get("type");
                Object url = a.get("dataUrl");
                if ("image".equals(String.valueOf(kind)) && url != null && !String.valueOf(url).isBlank()) {
                    imageAtts.add(a);
                }
            }
        }

        if (imageAtts.isEmpty()) {
            userMsg.put("content", userMessage);
        } else {
            ArrayNode content = objectMapper.createArrayNode();
            for (Map<String, Object> img : imageAtts) {
                String dataUrl = String.valueOf(img.get("dataUrl"));
                String mediaType = "image/jpeg";
                String base64 = dataUrl;
                int comma = dataUrl.indexOf(',');
                if (comma > 0) {
                    String header = dataUrl.substring(0, comma);
                    base64 = dataUrl.substring(comma + 1);
                    int colon = header.indexOf(':');
                    int semi = header.indexOf(';');
                    if (colon >= 0 && semi > colon) {
                        mediaType = header.substring(colon + 1, semi);
                    }
                }
                ObjectNode imgPart = objectMapper.createObjectNode();
                imgPart.put("type", "image");
                ObjectNode source = objectMapper.createObjectNode();
                source.put("type", "base64");
                source.put("media_type", mediaType);
                source.put("data", base64);
                imgPart.set("source", source);
                content.add(imgPart);
            }
            ObjectNode textPart = objectMapper.createObjectNode();
            textPart.put("type", "text");
            textPart.put("text", userMessage);
            content.add(textPart);
            userMsg.set("content", content);
        }
        messages.add(userMsg);
        root.set("messages", messages);

        return objectMapper.writeValueAsString(root);
    }

    private String summarizeGraph(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        StringBuilder sb = new StringBuilder();
        if (nodes != null) {
            sb.append("节点 (id | label | type):\n");
            for (Map<String, Object> n : nodes) {
                sb.append("  ").append(n.get("id"))
                  .append(" | ").append(n.get("label"))
                  .append(" | ").append(n.get("type"))
                  .append("\n");
            }
        }
        if (edges != null && !edges.isEmpty()) {
            sb.append("边 (from -> to : label, rule_driven):\n");
            for (Map<String, Object> e : edges) {
                Object rd = e.get("rule_driven");
                sb.append("  ").append(e.get("from"))
                  .append(" -> ").append(e.get("to"))
                  .append(" : ").append(e.getOrDefault("label", ""))
                  .append(Boolean.TRUE.equals(rd) ? " [rule]" : "")
                  .append("\n");
            }
        }
        return sb.toString();
    }

    // ========== v0.9：context 截断 ==========

    private static final int CONTEXT_NODE_BUDGET = 120;
    private static final int CONTEXT_EDGE_BUDGET = 240;
    private static final int CONTEXT_HOPS = 3;

    static final class TruncatedGraph {
        final List<Map<String, Object>> nodes;
        final List<Map<String, Object>> edges;
        final int droppedNodes;
        final int droppedEdges;
        TruncatedGraph(List<Map<String, Object>> n, List<Map<String, Object>> e, int dn, int de) {
            this.nodes = n; this.edges = e; this.droppedNodes = dn; this.droppedEdges = de;
        }
    }

    /**
     * 超过预算时按优先级保留：mandatory（seeds + 规则节点 + 约束目标）无条件保留，
     * 再做 BFS 邻域扩展并受预算约束。即便 mandatory 集本身已经超过预算，
     * 也不会丢失必要节点 —— 业务正确性优先于 budget。
     */
    TruncatedGraph truncateGraphForContext(List<Map<String, Object>> nodes,
                                           List<Map<String, Object>> edges,
                                           List<String> seeds,
                                           List<com.tuiyan.backend.model.Constraint> constraints) {
        int totalNodes = nodes == null ? 0 : nodes.size();
        int totalEdges = edges == null ? 0 : edges.size();
        if (totalNodes <= CONTEXT_NODE_BUDGET && totalEdges <= CONTEXT_EDGE_BUDGET) {
            return new TruncatedGraph(
                    nodes == null ? new ArrayList<>() : nodes,
                    edges == null ? new ArrayList<>() : edges,
                    0, 0);
        }

        // 1) mandatory：无条件保留
        Set<String> mandatory = new LinkedHashSet<>();
        if (seeds != null) mandatory.addAll(seeds);
        if (nodes != null) {
            for (Map<String, Object> n : nodes) {
                if ("rule".equalsIgnoreCase(String.valueOf(n.get("type")))) {
                    mandatory.add(String.valueOf(n.get("id")));
                }
            }
        }
        if (constraints != null) {
            for (com.tuiyan.backend.model.Constraint c : constraints) {
                if (c != null && c.getNodeId() != null) mandatory.add(c.getNodeId());
            }
        }

        Set<String> keep = new LinkedHashSet<>(mandatory);

        // 2) 邻接表
        Map<String, List<String>> neighbors = new HashMap<>();
        if (edges != null) {
            for (Map<String, Object> e : edges) {
                String f = String.valueOf(e.get("from"));
                String t = String.valueOf(e.get("to"));
                neighbors.computeIfAbsent(f, k -> new ArrayList<>()).add(t);
                neighbors.computeIfAbsent(t, k -> new ArrayList<>()).add(f);
            }
        }

        // 3) BFS 扩展（mandatory 之上叠加邻域，受预算约束；mandatory 不会被踢掉）
        Set<String> frontier = new HashSet<>(mandatory);
        for (int hop = 0; hop < CONTEXT_HOPS && keep.size() < CONTEXT_NODE_BUDGET; hop++) {
            Set<String> next = new LinkedHashSet<>();
            for (String id : frontier) {
                List<String> nbs = neighbors.get(id);
                if (nbs != null) for (String nb : nbs) if (!keep.contains(nb)) next.add(nb);
            }
            for (String nb : next) {
                if (keep.size() >= CONTEXT_NODE_BUDGET) break;
                keep.add(nb);
            }
            if (next.isEmpty()) break;
            frontier = next;
        }

        List<Map<String, Object>> outNodes = new ArrayList<>();
        if (nodes != null) {
            for (Map<String, Object> n : nodes) {
                if (keep.contains(String.valueOf(n.get("id")))) outNodes.add(n);
            }
        }
        List<Map<String, Object>> outEdges = new ArrayList<>();
        if (edges != null) {
            for (Map<String, Object> e : edges) {
                if (keep.contains(String.valueOf(e.get("from")))
                        && keep.contains(String.valueOf(e.get("to")))) {
                    outEdges.add(e);
                    if (outEdges.size() >= CONTEXT_EDGE_BUDGET) break;
                }
            }
        }
        return new TruncatedGraph(outNodes, outEdges,
                totalNodes - outNodes.size(),
                totalEdges - outEdges.size());
    }

    private String summarizeRules(List<Map<String, Object>> nodes) {
        if (nodes == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> n : nodes) {
            if (!"rule".equalsIgnoreCase(String.valueOf(n.get("type")))) continue;
            sb.append("  - ").append(n.get("id"))
              .append(" | ").append(n.get("label"));
            Object props = n.get("properties");
            if (props instanceof Map<?, ?> p) {
                Object br = p.get("baseRate");
                Object w = p.get("weight");
                if (br != null) sb.append(" | baseRate=").append(br);
                if (w != null) sb.append(" | weight=").append(w);
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String summarizeConstraints(List<com.tuiyan.backend.model.Constraint> cs,
                                        List<Map<String, Object>> nodes) {
        if (cs == null || cs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (com.tuiyan.backend.model.Constraint c : cs) {
            if (c == null || c.getNodeId() == null) continue;
            String label = c.getNodeId();
            if (nodes != null) {
                for (Map<String, Object> n : nodes) {
                    if (c.getNodeId().equals(n.get("id"))) {
                        Object lb = n.get("label");
                        if (lb != null) label = c.getNodeId() + "(" + lb + ")";
                        break;
                    }
                }
            }
            if ("block".equalsIgnoreCase(c.getMode())) {
                sb.append("  - 禁止: ").append(label)
                  .append(" 不发生；预测中不得以其为 triggered_by / leads_to，也不得预测出等价节点。\n");
            } else {
                sb.append("  - 强制: ").append(label)
                  .append(" 必然发生，可作为 step=1 的合法上游/下游连接点。\n");
            }
            if (c.getNote() != null && !c.getNote().isBlank()) {
                sb.append("    说明: ").append(c.getNote()).append("\n");
            }
        }
        return sb.toString();
    }

    private String summarizeSeeds(List<String> seeds, List<Map<String, Object>> nodes) {
        if (seeds == null || seeds.isEmpty()) return "(未指定，请基于全图任选一个合理起点)";
        StringBuilder sb = new StringBuilder();
        for (String s : seeds) {
            sb.append("  - ").append(s);
            if (nodes != null) {
                for (Map<String, Object> n : nodes) {
                    if (s.equals(n.get("id"))) {
                        sb.append(" (").append(n.get("label")).append(")");
                        break;
                    }
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String getApiKey(LlmProvider provider, JsonNode fileConfig) {
        if (fileConfig.has("apiKey")) {
            String savedApiKey = fileConfig.get("apiKey").asText();
            if (savedApiKey != null && !savedApiKey.isBlank()) {
                return savedApiKey;
            }
        }

        String envName = provider.getApiKeyEnvName();
        String apiKey = System.getenv(envName);
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey;
        }

        if (provider == LlmProvider.QWEN) {
            apiKey = System.getenv("QWEN_API_KEY");
            if (apiKey != null && !apiKey.isBlank()) return apiKey;
        } else if (provider == LlmProvider.KIMI) {
            apiKey = System.getenv("KIMI_API_KEY");
            if (apiKey != null && !apiKey.isBlank()) return apiKey;
        } else if (provider == LlmProvider.DEEPSEEK) {
            apiKey = System.getenv("DEEPSEEK_KEY");
            if (apiKey != null && !apiKey.isBlank()) return apiKey;
        } else if (provider == LlmProvider.ANTHROPIC) {
            apiKey = System.getenv("CLAUDE_API_KEY");
            if (apiKey != null && !apiKey.isBlank()) return apiKey;
        } else if (provider == LlmProvider.OPENAI) {
            apiKey = System.getenv("OPENAI_KEY");
            if (apiKey != null && !apiKey.isBlank()) return apiKey;
        }

        return null;
    }
}
