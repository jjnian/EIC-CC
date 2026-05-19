package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tuiyan.backend.model.ChatRequest;
import com.tuiyan.backend.model.ConfigResponse;
import com.tuiyan.backend.model.LlmProvider;
import com.tuiyan.backend.model.ModelConfig;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class LlmService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().build();
    private static final String MODELS_CONFIG_FILE = "src/main/resources/llm-models.json";
    private static final String LEGACY_CONFIG_FILE = "src/main/resources/llm-config.json";

    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int ANTHROPIC_MAX_TOKENS = 8192;

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

    // ========== 模型配置 CRUD ==========

    public List<ModelConfig> getAllModelConfigs() throws IOException {
        File file = new File(MODELS_CONFIG_FILE);
        if (!file.exists()) {
            migrateLegacyConfig();
            file = new File(MODELS_CONFIG_FILE);
        }
        if (!file.exists()) {
            return new ArrayList<>();
        }
        JsonNode node = objectMapper.readTree(file);
        List<ModelConfig> configs = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                configs.add(objectMapper.treeToValue(item, ModelConfig.class));
            }
        }
        return configs;
    }

    public ModelConfig createModelConfig(com.tuiyan.backend.controller.ModelController.ModelConfigRequest req) throws IOException {
        List<ModelConfig> configs = getAllModelConfigs();
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
        List<ModelConfig> configs = getAllModelConfigs();
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

    public void deleteModelConfig(String id) throws IOException {
        List<ModelConfig> configs = getAllModelConfigs();
        configs.removeIf(c -> c.getId().equals(id));
        saveModelConfigs(configs);
    }

    public void toggleModelConfig(String id) throws IOException {
        List<ModelConfig> configs = getAllModelConfigs();
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
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(MODELS_CONFIG_FILE), configs);
    }

    private void migrateLegacyConfig() throws IOException {
        File legacyFile = new File(LEGACY_CONFIG_FILE);
        if (legacyFile.exists()) {
            JsonNode legacy = objectMapper.readTree(legacyFile);
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
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(LEGACY_CONFIG_FILE), config);
    }

    // ========== 聊天接口 ==========

    /**
     * 解析模型配置，返回 baseURL / modelName / apiKey
     */
    private String[] resolveConfig(String modelOverride, String configId) throws Exception {
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

        return new String[]{baseURL, modelName, apiKey, protocol};
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
     * 构建 LLM 请求体 JSON
     */
    private String buildRequestBody(String modelName, String message, List<Map<String, Object>> history,
                                    List<Map<String, Object>> attachments, boolean stream) throws Exception {
        String prompt = "Here is the user's latest message:\n" + message +
                "\n\nPlease generate the corresponding entities and relationships strictly in JSON format matching the given schema.";

        ObjectNode requestNode = objectMapper.createObjectNode();
        requestNode.put("model", modelName);
        requestNode.put("stream", stream);

        ArrayNode messages = objectMapper.createArrayNode();

        // System instruction
        ObjectNode systemMsg = objectMapper.createObjectNode();
        systemMsg.put("role", "system");
        systemMsg.put("content", SYSTEM_INSTRUCTION);
        messages.add(systemMsg);

        // Conversation history
        if (history != null && !history.isEmpty()) {
            for (Map<String, Object> msg : history) {
                ObjectNode h = objectMapper.createObjectNode();
                String role = String.valueOf(msg.get("role"));
                h.put("role", "user".equals(role) ? "user" : "assistant");
                h.put("content", String.valueOf(msg.get("content")));
                messages.add(h);
            }
        }

        // Current user message — 如果有图片附件，使用多模态 content 数组
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
            userMsg.put("content", prompt);
        } else {
            ArrayNode contentArr = objectMapper.createArrayNode();
            ObjectNode textPart = objectMapper.createObjectNode();
            textPart.put("type", "text");
            textPart.put("text", prompt);
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

        requestNode.set("messages", messages);

        ObjectNode responseFormat = objectMapper.createObjectNode();
        responseFormat.put("type", "json_object");
        requestNode.set("response_format", responseFormat);

        return objectMapper.writeValueAsString(requestNode);
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
        String[] cfg = resolveConfig(modelOverride, configId);
        String baseURL = cfg[0], modelName = cfg[1], apiKey = cfg[2];
        boolean anthropic = isAnthropic(baseURL, modelName, cfg.length > 3 ? cfg[3] : null);

        String prompt = "Here is the user's latest message:\n" + message +
                "\n\nPlease generate the corresponding entities and relationships strictly in JSON format matching the given schema.";

        String requestBody = anthropic
                ? buildAnthropicBody(modelName, SYSTEM_INSTRUCTION, prompt, history, attachments, false, ANTHROPIC_MAX_TOKENS)
                : buildRequestBody(modelName, message, history, attachments, false);

        HttpRequest request = buildHttpRequest(baseURL, apiKey, anthropic, requestBody);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("LLM Error: " + response.statusCode() + " - " + response.body());
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
            String[] cfg = resolveConfig(request.getModelOverride(), request.getConfigId());
            String baseURL = cfg[0], modelName = cfg[1], apiKey = cfg[2];
            boolean anthropic = isAnthropic(baseURL, modelName, cfg.length > 3 ? cfg[3] : null);

            String prompt = "Here is the user's latest message:\n" + request.getMessage() +
                    "\n\nPlease generate the corresponding entities and relationships strictly in JSON format matching the given schema.";

            String requestBody = anthropic
                    ? buildAnthropicBody(modelName, SYSTEM_INSTRUCTION, prompt, request.getHistory(), request.getAttachments(), true, ANTHROPIC_MAX_TOKENS)
                    : buildRequestBody(modelName, request.getMessage(), request.getHistory(), request.getAttachments(), true);

            HttpRequest httpRequest = buildHttpRequest(baseURL, apiKey, anthropic, requestBody);

            httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
                    .thenAccept(resp -> {
                        if (resp.statusCode() != 200) {
                            String errorBody;
                            try {
                                errorBody = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
                            } catch (IOException e) {
                                errorBody = "Unknown error";
                            }
                            try {
                                emitter.send(SseEmitter.event().name("error").data("LLM Error: " + resp.statusCode() + " - " + errorBody));
                            } catch (IOException e) { /* ignore */ }
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
                            emitter.send(SseEmitter.event().name("complete")
                                    .data(objectMapper.writeValueAsString(finalEvent)));
                            emitter.complete();
                        } catch (IOException e) {
                            try {
                                emitter.send(SseEmitter.event().name("error").data("Failed to parse response: " + e.getMessage()));
                            } catch (IOException ex) { /* ignore */ }
                            emitter.complete();
                        }
                    })
                    .exceptionally(ex -> {
                        try {
                            emitter.send(SseEmitter.event().name("error").data("Network error: " + ex.getMessage()));
                        } catch (IOException e) { /* ignore */ }
                        emitter.completeWithError(ex);
                        return null;
                    });
        } catch (Exception e) {
            try {
                emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
            } catch (IOException ioEx) { /* ignore */ }
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
                emitter.send(SseEmitter.event().name("text").data(delta));
            } catch (IOException ignored) {}
        }
        return fullContent;
    }

    /**
     * Anthropic SSE 解析：
     *   event: content_block_delta
     *   data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"..."}}
     * 终止：event: message_stop
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
                            emitter.send(SseEmitter.event().name("text").data(text));
                        }
                    }
                } else if ("message_stop".equals(type) || "message_stop".equals(currentEvent)) {
                    break;
                } else if ("error".equals(type)) {
                    String msg = chunk.path("error").path("message").asText("Anthropic error");
                    emitter.send(SseEmitter.event().name("error").data(msg));
                    break;
                }
            } catch (IOException ignored) {}
        }
        return fullContent;
    }

    // ========== 场景推演 ==========

    /**
     * 对图谱进行前向推演，返回原始 chain JSON（不含落盘）。
     * 调用方负责构建 Scenario、保存、SSE 分步推送。
     */
    public JsonNode predictChain(com.tuiyan.backend.model.PredictRequest req) throws Exception {
        String[] cfg = resolveConfig(req.getModelOverride(), req.getConfigId());
        String baseURL = cfg[0], modelName = cfg[1], apiKey = cfg[2];
        boolean anthropic = isAnthropic(baseURL, modelName, cfg.length > 3 ? cfg[3] : null);

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

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("当前本体图谱：\n").append(graphSummary).append("\n\n");
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

        String requestBody;
        if (anthropic) {
            requestBody = buildAnthropicBody(modelName, systemPrompt, userPrompt.toString(),
                    null, null, false, ANTHROPIC_MAX_TOKENS);
        } else {
            ObjectNode requestNode = objectMapper.createObjectNode();
            requestNode.put("model", modelName);
            requestNode.put("stream", false);
            ArrayNode messages = objectMapper.createArrayNode();
            ObjectNode sys = objectMapper.createObjectNode();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
            messages.add(sys);
            ObjectNode user = objectMapper.createObjectNode();
            user.put("role", "user");
            user.put("content", userPrompt.toString());
            messages.add(user);
            requestNode.set("messages", messages);
            ObjectNode rf = objectMapper.createObjectNode();
            rf.put("type", "json_object");
            requestNode.set("response_format", rf);
            requestBody = objectMapper.writeValueAsString(requestNode);
        }

        HttpRequest httpReq = buildHttpRequest(baseURL, apiKey, anthropic, requestBody);
        HttpResponse<String> resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("LLM Error: " + resp.statusCode() + " - " + resp.body());
        }

        JsonNode root = objectMapper.readTree(resp.body());
        String content = extractContent(root, anthropic);
        content = content.replaceAll("(?i)^```json", "").replaceAll("```$", "").trim();
        if (content.isEmpty()) content = "{}";
        return objectMapper.readTree(content);
    }

    // ========== 协议适配辅助 ==========

    /**
     * 构造 HTTP 请求：Anthropic 用 /messages + x-api-key + anthropic-version；
     * OpenAI 兼容用 /chat/completions + Authorization: Bearer。
     */
    private HttpRequest buildHttpRequest(String baseURL, String apiKey, boolean anthropic, String requestBody) {
        String url = baseURL.replaceFirst("/+$", "") + (anthropic ? "/messages" : "/chat/completions");
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
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

    /**
     * 从非流式响应中提取文本内容（兼容两种协议）。
     */
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

    /**
     * 构造 Anthropic Messages API 请求体：system 顶层、max_tokens 必填、
     * messages 数组（role: user / assistant）、可选 image content blocks。
     */
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

    /**
     * 规则节点 (type=rule) 提取为单独章节，附 baseRate / weight（若存在于 properties）。
     * 让 LLM 优先沿规则推演，并在 rule_id 字段中显式引用。
     */
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

    /**
     * What-if 约束 → 提示词文本。force 提示 LLM 视为既成事实；block 禁止依赖。
     */
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
