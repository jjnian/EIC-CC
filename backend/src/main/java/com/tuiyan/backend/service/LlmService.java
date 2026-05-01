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

    public ModelConfig createModelConfig(String name, String baseUrl, String modelName, String apiKey) throws IOException {
        List<ModelConfig> configs = getAllModelConfigs();
        ModelConfig newConfig = new ModelConfig(name, baseUrl, modelName, apiKey);
        configs.add(newConfig);
        saveModelConfigs(configs);
        return newConfig;
    }

    public ModelConfig updateModelConfig(String id, String name, String baseUrl, String modelName, String apiKey) throws IOException {
        List<ModelConfig> configs = getAllModelConfigs();
        for (int i = 0; i < configs.size(); i++) {
            if (configs.get(i).getId().equals(id)) {
                ModelConfig config = configs.get(i);
                config.setName(name);
                config.setBaseUrl(baseUrl);
                config.setModelName(modelName);
                if (apiKey != null && !apiKey.isBlank()) {
                    config.setApiKey(apiKey);
                }
                config.setUpdatedAt(System.currentTimeMillis());
                configs.set(i, config);
                saveModelConfigs(configs);
                return config;
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
            customModels.add(new ConfigResponse.ModelConfigInfo(
                mc.getId(),
                mc.getName(),
                mc.getBaseUrl(),
                mc.getModelName(),
                mc.isEnabled()
            ));
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

        return new String[]{baseURL, modelName, apiKey};
    }

    /**
     * 构建 LLM 请求体 JSON
     */
    private String buildRequestBody(String modelName, String message, boolean stream) throws Exception {
        String prompt = "Here is the user's latest message:\n" + message +
                "\n\nPlease generate the corresponding entities and relationships strictly in JSON format matching the given schema.";

        ObjectNode requestNode = objectMapper.createObjectNode();
        requestNode.put("model", modelName);
        requestNode.put("stream", stream);

        ObjectNode systemMsg = objectMapper.createObjectNode();
        systemMsg.put("role", "system");
        systemMsg.put("content", SYSTEM_INSTRUCTION);

        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", prompt);

        requestNode.set("messages", objectMapper.createArrayNode()
                .add(systemMsg)
                .add(userMsg));

        ObjectNode responseFormat = objectMapper.createObjectNode();
        responseFormat.put("type", "json_object");
        requestNode.set("response_format", responseFormat);

        return objectMapper.writeValueAsString(requestNode);
    }

    /**
     * 同步聊天（非流式）
     */
    public JsonNode chat(List<Map<String, Object>> nodes, List<Map<String, Object>> edges, String message, String modelOverride, String configId) throws Exception {
        String[] cfg = resolveConfig(modelOverride, configId);
        String baseURL = cfg[0], modelName = cfg[1], apiKey = cfg[2];

        String requestBody = buildRequestBody(modelName, message, false);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseURL.replaceFirst("/+$", "") + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("LLM Error: " + response.statusCode() + " - " + response.body());
        }

        JsonNode responseJson = objectMapper.readTree(response.body());
        String content = responseJson.path("choices").path(0).path("message").path("content").asText("{}");
        content = content.replaceAll("(?i)^```json", "").replaceAll("```$", "").trim();

        return objectMapper.readTree(content);
    }

    /**
     * 流式聊天：通过 SSE 逐 token 推送文本，完成后发送结构化数据
     */
    public void chatStreaming(ChatRequest request, SseEmitter emitter) {
        try {
            String[] cfg = resolveConfig(request.getModelOverride(), request.getConfigId());
            String baseURL = cfg[0], modelName = cfg[1], apiKey = cfg[2];

            String requestBody = buildRequestBody(modelName, request.getMessage(), true);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseURL.replaceFirst("/+$", "") + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

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
                                } catch (IOException e) {
                                    // 解析单个 chunk 失败，跳过
                                }
                            }

                            // 流完成后解析完整 JSON
                            String content = fullContent.toString()
                                    .replaceAll("(?i)^```json", "").replaceAll("```$", "").trim();
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
        }

        return null;
    }
}
