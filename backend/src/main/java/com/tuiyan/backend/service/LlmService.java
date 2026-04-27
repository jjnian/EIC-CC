package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

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
        node.put("baseUrl", DEFAULT_BASE_URL);
        node.put("modelName", DEFAULT_MODEL_NAME);
        return node;
    }

    public void saveConfig(String baseUrl, String modelName) throws IOException {
        ObjectNode config = objectMapper.createObjectNode();
        config.put("baseUrl", baseUrl);
        config.put("modelName", modelName);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(CONFIG_FILE), config);
    }

    @SuppressWarnings("unchecked")
    public JsonNode chat(List<Map<String, Object>> nodes, List<Map<String, Object>> edges, String message) throws Exception {
        String apiKey = System.getenv("LLM_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("GEMINI_API_KEY");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Missing LLM_API_KEY or GEMINI_API_KEY in environment variables.");
        }

        JsonNode fileConfig = getConfig();
        String baseURL = System.getenv("LLM_BASE_URL");
        if (baseURL == null || baseURL.isBlank()) {
            baseURL = fileConfig.has("baseUrl") ? fileConfig.get("baseUrl").asText() : DEFAULT_BASE_URL;
        }
        String modelName = System.getenv("LLM_MODEL_NAME");
        if (modelName == null || modelName.isBlank()) {
            modelName = fileConfig.has("modelName") ? fileConfig.get("modelName").asText() : DEFAULT_MODEL_NAME;
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
}
