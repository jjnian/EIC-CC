package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tuiyan.backend.model.ConfigResponse;
import com.tuiyan.backend.model.LlmProvider;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class LlmService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .build();
    private static final String CONFIG_FILE = "src/main/resources/llm-config.json";
    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String DEFAULT_MODEL_NAME = "qwen-max";

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

    public JsonNode getConfig() throws IOException {
        File file = new File(CONFIG_FILE);
        if (file.exists()) {
            try {
                return objectMapper.readTree(file);
            } catch (IOException e) {
                // fallback to defaults
            }
        }
        ObjectNode node = objectMapper.createObjectNode();
        node.put("provider", LlmProvider.QWEN.getCode());
        node.put("baseUrl", DEFAULT_BASE_URL);
        node.put("modelName", DEFAULT_MODEL_NAME);
        return node;
    }

    /**
     * 获取配置响应，包含所有提供商信息供前端选择
     */
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

        return new ConfigResponse(providerCode, baseUrl, modelName, providers);
    }

    public void saveConfig(String provider, String baseUrl, String modelName, String apiKey) throws IOException {
        ObjectNode config = objectMapper.createObjectNode();
        config.put("provider", provider);
        config.put("baseUrl", baseUrl);
        config.put("modelName", modelName);
        if (apiKey != null && !apiKey.isBlank()) {
            config.put("apiKey", apiKey);
        }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(CONFIG_FILE), config);
    }

    @SuppressWarnings("unchecked")
    public JsonNode chat(List<Map<String, Object>> nodes, List<Map<String, Object>> edges, String message, String modelOverride) throws Exception {
        JsonNode fileConfig = getConfig();
        String providerCode = fileConfig.has("provider") ? fileConfig.get("provider").asText() : LlmProvider.QWEN.getCode();
        LlmProvider provider = LlmProvider.fromCode(providerCode);

        // 根据提供商获取对应的 API Key
        String apiKey = getApiKey(provider, fileConfig);

        String baseURL = fileConfig.has("baseUrl") ? fileConfig.get("baseUrl").asText() : provider.getBaseUrl();
        String modelName = fileConfig.has("modelName") ? fileConfig.get("modelName").asText() : provider.getDefaultModel();

        // 允许请求级别的模型覆盖
        if (modelOverride != null && !modelOverride.isBlank()) {
            modelName = modelOverride;
        }

        // 允许环境变量覆盖
        if (System.getenv("LLM_BASE_URL") != null && !System.getenv("LLM_BASE_URL").isBlank()) {
            baseURL = System.getenv("LLM_BASE_URL");
        }
        if (System.getenv("LLM_MODEL_NAME") != null && !System.getenv("LLM_MODEL_NAME").isBlank()) {
            modelName = System.getenv("LLM_MODEL_NAME");
        }
        // 通用 API Key 环境变量作为后备
        if (apiKey == null && System.getenv("LLM_API_KEY") != null && !System.getenv("LLM_API_KEY").isBlank()) {
            apiKey = System.getenv("LLM_API_KEY");
        }
        if (apiKey == null && System.getenv("GEMINI_API_KEY") != null && !System.getenv("GEMINI_API_KEY").isBlank()) {
            apiKey = System.getenv("GEMINI_API_KEY");
        }

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Missing API key for provider '" + provider.getDisplayName() + "'. Please set environment variable: " + provider.getApiKeyEnvName());
        }

        String prompt = "Here is the user's latest message:\n" + message +
                "\n\nPlease generate the corresponding entities and relationships strictly in JSON format matching the given schema.";

        // Build request JSON
        ObjectNode requestNode = objectMapper.createObjectNode();
        requestNode.put("model", modelName);

        // system message
        ObjectNode systemMsg = objectMapper.createObjectNode();
        systemMsg.put("role", "system");
        systemMsg.put("content", SYSTEM_INSTRUCTION);

        // user message
        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", prompt);

        requestNode.set("messages", objectMapper.createArrayNode()
                .add(systemMsg)
                .add(userMsg));

        ObjectNode responseFormat = objectMapper.createObjectNode();
        responseFormat.put("type", "json_object");
        requestNode.set("response_format", responseFormat);

        String requestBody = objectMapper.writeValueAsString(requestNode);

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

        // Clean markdown wrapping
        content = content.replaceAll("(?i)^```json", "").replaceAll("```$", "").trim();

        return objectMapper.readTree(content);
    }

    /**
     * 根据提供商获取对应的 API Key
     * 优先级：配置文件中保存的 Key > 环境变量
     */
    private String getApiKey(LlmProvider provider, JsonNode fileConfig) {
        // 优先尝试从配置文件中读取保存的 API Key
        if (fileConfig.has("apiKey")) {
            String savedApiKey = fileConfig.get("apiKey").asText();
            if (savedApiKey != null && !savedApiKey.isBlank()) {
                return savedApiKey;
            }
        }

        // 尝试提供商专属的环境变量
        String envName = provider.getApiKeyEnvName();
        String apiKey = System.getenv(envName);
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey;
        }

        // 对于某些提供商，尝试多个可能的环境变量名称
        if (provider == LlmProvider.QWEN) {
            apiKey = System.getenv("QWEN_API_KEY");
            if (apiKey != null && !apiKey.isBlank()) {
                return apiKey;
            }
        } else if (provider == LlmProvider.KIMI) {
            apiKey = System.getenv("KIMI_API_KEY");
            if (apiKey != null && !apiKey.isBlank()) {
                return apiKey;
            }
        } else if (provider == LlmProvider.DEEPSEEK) {
            apiKey = System.getenv("DEEPSEEK_KEY");
            if (apiKey != null && !apiKey.isBlank()) {
                return apiKey;
            }
        }

        return null;
    }
}
