package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tuiyan.backend.config.LlmProperties;
import com.tuiyan.backend.model.ChatRequest;
import com.tuiyan.backend.model.ConfigResponse;
import com.tuiyan.backend.model.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
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

/**
 * LLM 适配层：把"应用语义"（chat / predict / extract / explain）翻译成"模型协议"调用。
 * <p>核心职责：
 * <ul>
 *   <li>多模型多 provider 适配：OpenAI 兼容 vs Anthropic Messages，统一通过 {@link #resolveConfig} 选 + {@link #isAnthropic} 路由；</li>
 *   <li>四类业务 prompt 的 system 模板与 schema 在本类常量集中维护，确保各处行为一致；</li>
 *   <li>SSE 流式与同步两套 HTTP 调用；流式分别有 {@link #streamOpenAI} / {@link #streamAnthropic} 解析；</li>
 *   <li>抽取（extract）支持文本分片 + 跨片标签去重合并（{@link #mergeExtractionByLabel}）；</li>
 *   <li>推演（predict）前对图谱做 budget 截断（{@link #truncateGraphForContext}），保留 seeds / rules / constraints 的 N-hop 邻域；</li>
 *   <li>每次 LLM 调用记录耗时 + 成功状态到 {@link LlmMetricsService}。</li>
 * </ul>
 * 本类不持有任何会话状态，所有方法都是无状态的；HttpClient 单例复用连接。
 */
@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 单例 HttpClient，复用底层连接池；20s 连接超时只覆盖 TCP 建联阶段，业务超时在每个 request 上单独设
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(20))
            .build();

    private final LlmProperties llmProperties;
    private final LlmMetricsService metricsService;

    public LlmService(LlmProperties llmProperties, LlmMetricsService metricsService) {
        this.llmProperties = llmProperties;
        this.metricsService = metricsService;
    }

    // Anthropic Messages API 强制要求 anthropic-version 头；本项目固定使用 2023-06-01（稳定版）
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    // Anthropic 必须显式给 max_tokens；8192 在常见 claude 模型中安全且足够覆盖业务输出
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
              ],
              "attributes": [
                {
                  "name": "The attribute name, e.g., 'weight', 'duration', 'status'",
                  "valueSpace": "The value type or range, e.g., 'number', 'string', '0..1', 'enum(high,medium,low)'",
                  "description": "Optional brief description of this attribute"
                }
              ],
              "constraints": [
                {
                  "kind": "Must be one of: 'cardinality', 'exclusive', 'symmetric', 'transitive', 'custom'",
                  "note": "Human-readable description of the constraint, e.g., 'Each order must have exactly one customer'"
                }
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
              "rule_driven": true_or_false,
              "constraints": [
                {
                  "kind": "Must be one of: 'cardinality', 'exclusive', 'symmetric', 'transitive', 'custom'",
                  "note": "Human-readable constraint on this relationship, e.g., '1:N — one supplier supplies many parts', 'symmetric — A partners with B implies B partners with A'"
                }
              ]
            }
          ]
        }
        """;

    private static final String SYSTEM_INSTRUCTION = """
        You are an AI Ontology Developer. Your task is to build a static ontology graph (TBox) from the user's description.

        **First-Principles Methodology — you MUST follow this rigorous approach:**

        You must decompose the domain through four fundamental ontological dimensions. Analyze each dimension with depth and precision:

        **A. 本体对象 (Ontology Objects / Nodes):**
        1. Identify the most fundamental, irreducible concepts — the "atoms" that cannot be broken down further.
        2. For each concept, ask: "Is this truly primitive, or can it be decomposed into more fundamental parts?"
        3. Classify each object precisely: entity (thing), event (happens), process (ongoing), rule (governs), data (information), external (outside boundary).
        4. Every object must have a clear reason to exist — if removing it would not lose information, it should not be a separate node.

        **B. 关系 (Relationships / Edges):**
        1. Every edge must represent a real causal mechanism, governance, dependency, or compositional relationship — never a vague association.
        2. Ask for each relationship: "What is the precise nature of this connection? Is it causation, composition, governance, enablement, or something else?"
        3. Name relationships with precise verbs that capture directionality and semantics (e.g., 'triggers', 'governs', 'composed_of', 'requires', not vague terms like 'related_to').
        4. Identify rule-driven edges: if a business rule, regulation, or SOP step governs the relationship, set rule_driven=true and create a rule node.

        **C. 约束 (Constraints):**
        1. Identify structural constraints on BOTH nodes and edges:
           - Cardinality: How many instances can participate? (e.g., "1:N", "exactly one", "at most 3")
           - Exclusive: Are there mutually exclusive alternatives? (e.g., "an order is either domestic OR international, never both")
           - Symmetric: Does the relationship hold in both directions? (e.g., "A partners with B implies B partners with A")
           - Transitive: Does the relationship chain? (e.g., "A contains B, B contains C → A contains C")
           - Custom: Any domain-specific invariant or business rule constraint
        2. Constraints are the "laws of physics" of the domain — they define what is possible and what is forbidden.
        3. Do NOT omit constraints. If a concept has inherent limitations, cardinality rules, or mutual exclusions, these MUST be captured.

        **D. 属性 (Attributes):**
        1. For each node, identify its essential attributes — the measurable or describable properties that characterize it.
        2. Each attribute must have a name and a valueSpace (the type or range of valid values, e.g., 'number', 'string', '0..1', 'enum(high,medium,low)', 'date', 'boolean').
        3. Ask: "What properties would you need to fully describe an instance of this concept?" Include quantitative metrics, states, classifications, and temporal properties.
        4. Distinguish between definitional attributes (always present) and optional attributes.

        **Source Tracking:**
        - Mark everything 'derived' if explicitly stated in the user's text; 'inferred' if you are filling gaps with world knowledge.
        - This applies to nodes, edges, properties, attributes, and constraints alike.

        CRITICAL INSTRUCTION:
        1. Explicitly represent rules (type: 'rule') if they drive events.
        2. Label ALL properties, nodes, and edges with their 'source'.
        3. You MUST output attributes and constraints for nodes and edges — an ontology without constraints and attributes is incomplete.
        4. You MUST return ONLY valid JSON strictly matching this schema. NO markdown wrapping, just the raw JSON object.

        SCHEMA:
        %s""".formatted(SCHEMA_STRING);

    private static final String EXTRACT_SYSTEM = """
        You are an AI Ontology Developer extracting a static ontology graph (TBox) from documents.
        The user has uploaded one or more sources: PDF text excerpts and/or images of
        diagrams, flowcharts, tables, or screenshots.

        **First-Principles Methodology — you MUST rigorously analyze four dimensions:**

        **A. 本体对象 (Ontology Objects / Nodes):**
        1. Identify the most fundamental, irreducible concepts — the "atoms" of the domain the document describes.
        2. For each concept, ask: "Is this truly primitive, or is it composed of deeper parts?"
        3. Classify precisely: entity, event, process, rule, data, external.
        4. Every node must have a clear ontological justification — no redundant or vague nodes.

        **B. 关系 (Relationships / Edges):**
        1. Extract every directed relationship with a precise verb label (triggers, governs, composed_of, requires, produces, etc.).
        2. For each relationship, ask: "Is this causal, compositional, governance, or enablement? What is the exact mechanism?"
        3. Never use vague labels like 'related_to' — capture the specific nature of each connection.
        4. If a rule governs the relationship, set rule_driven=true and emit the rule node.

        **C. 约束 (Constraints):**
        1. Extract ALL structural constraints mentioned or implied in the document:
           - Cardinality: "each X has exactly one Y", "at most N", "1:N"
           - Exclusive: "either A or B but not both"
           - Symmetric: bidirectional relationships
           - Transitive: chainable relationships
           - Custom: domain-specific invariants, business rules, regulatory limits
        2. Apply constraints to BOTH nodes and edges. Constraints are the "laws of physics" of this domain.
        3. Even if the document doesn't explicitly state a constraint, if the domain logically requires it (e.g., a purchase order must have at least one line item), include it as 'inferred'.

        **D. 属性 (Attributes):**
        1. For each entity/concept node, extract its essential attributes with name + valueSpace.
        2. Look for: quantitative metrics, states, classifications, temporal properties, identifiers, capacities.
        3. valueSpace should specify the type or range: 'number', 'string', '0..1', 'enum(X,Y,Z)', 'date', 'boolean', 'percentage', etc.
        4. If a table or diagram shows properties/columns/fields, these become attributes on the relevant node.

        Your job: identify every distinct entity, event, process, data, external system,
        and explicit RULE / regulation / SOP step, plus the directed relationships, constraints, and attributes.
        Treat the document as authoritative — do not invent content that isn't grounded in it.

        Strict rules:
        1. If a passage describes a conditional / business rule / regulation / SOP step,
           emit a node with type='rule' and add edges from that rule to the events/processes it governs.
        2. Mark nodes/edges 'derived' when they are explicitly stated in the source;
           mark 'inferred' only when filling in obvious gaps with world knowledge.
        3. Use stable ids like 'n_1', 'n_2', 'e_1' — the server rewrites them to avoid collisions.
        4. Be exhaustive but de-duplicated: if two phrasings clearly refer to the same concept,
           emit ONE node.
        5. You MUST output attributes and constraints — an extraction without them is incomplete.
        6. Return ONLY a JSON object exactly matching SCHEMA. No markdown wrapping.

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

    private static final String EXPLAIN_SCHEMA = """
        {
          "evidence": "依据：因果链上有哪些证据/规则/节点支持这一步发生",
          "assumptions": "假设：得出该结论的隐含前提",
          "counterexamples": "反例：可能让该步骤不成立的反向证据或场景"
        }
        """;

    private static final String EXPLAIN_SYSTEM = """
        你是一个推演分析助手。在给定的因果链上下文中，请对指定预测步骤给出三段式解释。

        要求：
        1. evidence (依据)：列出图谱里支持该步骤的具体节点、规则、上下游路径（2-4 句中文）。
        2. assumptions (假设)：列出得出该结论的隐含前提条件（2-4 句中文）。
        3. counterexamples (反例)：列出可能推翻该步骤的反向证据或边界场景（2-3 句中文）。
        4. 用简体中文。每段保持简洁，避免重复信息。
        5. 只输出严格符合 schema 的 JSON，禁止 markdown 包裹。

        SCHEMA:
        %s""".formatted(EXPLAIN_SCHEMA);

    /** 解析后的模型连接配置：包含 endpoint、模型名、API key、协议类型。 */
    public record ResolvedConfig(String baseURL, String modelName, String apiKey, String protocol) {}

    /** P1-8：推演 prompt 的结构化产物，供 orchestrator 写入 Scenario.rawPrompt 与 LLM 调用复用。 */
    public record PredictPromptArtifact(String system, String user, boolean truncated, int droppedNodes, int droppedEdges) {}

    /** 单条文本日志最大字符数，超出后截断；避免日志被大段 base64 / JSON 撑爆。 */
    private static final int LOG_TEXT_MAX = 2000;

    private static String truncateForLog(String s) {
        if (s == null) return "";
        if (s.length() <= LOG_TEXT_MAX) return s;
        return s.substring(0, LOG_TEXT_MAX) + "…(已截断,原长 " + s.length() + ")";
    }

    /**
     * 把发给 LLM 的对话内容（system + history + 当前 user prompt）按可读格式打到 INFO 日志，
     * 方便排查"模型为什么这么回"。图片 / 超长文本会被脱敏 + 截断。
     */
    private void logConversation(String tag, String modelName, String systemPrompt,
                                 List<Map<String, Object>> history, String userText,
                                 List<Map<String, Object>> attachments) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n========== [").append(tag).append("] → LLM 请求 model=").append(modelName).append(" ==========\n");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            sb.append("[system]\n").append(truncateForLog(systemPrompt)).append("\n");
        }
        int hi = 0;
        if (history != null) {
            for (Map<String, Object> msg : history) {
                String role = String.valueOf(msg.get("role"));
                if (!"user".equals(role) && !"assistant".equals(role)) continue;
                sb.append("[history#").append(hi++).append(" ").append(role).append("]\n")
                  .append(truncateForLog(String.valueOf(msg.get("content")))).append("\n");
            }
        }
        sb.append("[user]\n").append(truncateForLog(userText == null ? "" : userText)).append("\n");
        if (attachments != null && !attachments.isEmpty()) {
            int imgCount = 0;
            for (Map<String, Object> a : attachments) {
                if ("image".equals(String.valueOf(a.get("type")))) imgCount++;
            }
            sb.append("[attachments] images=").append(imgCount).append(" total=").append(attachments.size()).append("\n");
        }
        sb.append("==========================================================");
        log.info(sb.toString());
    }

    /** 打印从 LLM 收到的最终文本内容（已去掉 ```json 包装），便于和前端展示对照。 */
    private void logLlmResponse(String tag, String modelName, long elapsedMs, String content) {
        log.info("\n========== [{}] ← LLM 响应 model={} 耗时={}ms 长度={} ==========\n{}\n==========================================================",
                tag, modelName, elapsedMs, content == null ? 0 : content.length(), truncateForLog(content));
    }

    // ========== 模型配置（只读，来自 yaml） ==========

    /** 返回 application.yml 中配置的全部模型条目（含 apiKey；仅服务端内部使用）。 */
    public List<LlmProperties.ModelEntry> getAllModelConfigs() {
        return llmProperties.getModels();
    }

    // ========== 模型连接测试 ==========

    /**
     * 向指定模型发送最小化请求以验证连接可用性。
     * <p>用极短的 "hi" + max_tokens=1 探测，几乎不消耗 token；
     * 同时把延迟记录到 metrics，让前端"测试连接"按钮的耗时也参与统计。
     * @return 调用延迟（毫秒）；失败时抛出异常，由 controller 决定如何展示
     */
    public long testModelConnection(String modelId) throws Exception {
        LlmProperties.ModelEntry entry = llmProperties.getModels().stream()
            .filter(m -> m.getId().equals(modelId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Model not found: " + modelId));

        if (!entry.isEnabled()) {
            throw new IllegalStateException("Model is disabled: " + entry.getName());
        }

        String baseUrl = entry.getBaseUrl();
        String apiKey = entry.getApiKey();
        // 如果配置中没有 api-key，尝试从环境变量获取
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("LLM_API_KEY");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Missing API key for model: " + entry.getName());
        }
        String modelName = entry.getModelName();
        String protocol = entry.getProtocol();

        long start = System.currentTimeMillis();

        try {
            if (isAnthropic(baseUrl, modelName, protocol)) {
                testAnthropicConnection(baseUrl, apiKey, modelName);
            } else {
                testOpenAIConnection(baseUrl, apiKey, modelName);
            }
            long latency = System.currentTimeMillis() - start;
            metricsService.recordCall(modelName, latency, true);
            return latency;
        } catch (Exception e) {
            metricsService.recordCall(modelName, System.currentTimeMillis() - start, false);
            throw e;
        }
    }

    /** 通过 OpenAI 兼容接口发送最小化请求测试连通性 */
    private void testOpenAIConnection(String baseUrl, String apiKey, String modelName) throws Exception {
        String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";
        String body = "{\"model\":\"" + modelName + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":1}";

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(java.time.Duration.ofSeconds(15))
            .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() >= 400) {
            String respBody = resp.body();
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + respBody.substring(0, Math.min(200, respBody.length())));
        }
    }

    /** 通过 Anthropic Messages API 发送最小化请求测试连通性 */
    private void testAnthropicConnection(String baseUrl, String apiKey, String modelName) throws Exception {
        String url = baseUrl.replaceAll("/+$", "") + "/messages";
        String body = "{\"model\":\"" + modelName + "\",\"max_tokens\":1,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(java.time.Duration.ofSeconds(15))
            .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() >= 400) {
            String respBody = resp.body();
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + respBody.substring(0, Math.min(200, respBody.length())));
        }
    }

    // ========== 兼容旧接口 ==========

    /**
     * 构造前端设置页用的完整配置响应：含 provider 元信息列表 + 用户自定义模型列表。
     * <p>顶层 provider/baseUrl/modelName 是 v0.5 之前的兼容字段，新前端会优先用 customModels[0]。
     */
    public ConfigResponse getConfigResponse() {
        List<LlmProperties.ModelEntry> models = llmProperties.getModels();

        String providerCode = "qwen";
        String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        String modelName = "qwen-max";
        if (!models.isEmpty()) {
            LlmProperties.ModelEntry first = models.get(0);
            providerCode = first.getProvider() != null ? first.getProvider() : providerCode;
            baseUrl = first.getBaseUrl() != null ? first.getBaseUrl() : baseUrl;
            modelName = first.getModelName() != null ? first.getModelName() : modelName;
        }

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
        for (LlmProperties.ModelEntry mc : models) {
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

    // ========== 聊天接口 ==========

    /**
     * 解析最终连接配置：把 configId / modelOverride / 环境变量 / 默认值按优先级合并。
     * <p>优先级（从高到低）：
     * <ol>
     *   <li>显式 configId 指向的 ModelEntry；</li>
     *   <li>没有 configId 时取 yaml 中的第一项；</li>
     *   <li>yaml 也为空时回退到内置默认（QWEN）；</li>
     *   <li>modelOverride 覆盖 modelName；</li>
     *   <li>环境变量 LLM_BASE_URL / LLM_MODEL_NAME / LLM_API_KEY 进一步覆盖（开发期 / 容器场景方便）。</li>
     * </ol>
     * 最终 apiKey 仍为空时抛 IllegalStateException → 由 GlobalExceptionHandler 映射为 400。
     */
    private ResolvedConfig resolveConfig(String modelOverride, String configId) {
        String baseURL;
        String modelName;
        String apiKey;
        String protocol = null;

        if (configId != null && !configId.isBlank()) {
            LlmProperties.ModelEntry selected = null;
            for (LlmProperties.ModelEntry mc : llmProperties.getModels()) {
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
            List<LlmProperties.ModelEntry> models = llmProperties.getModels();
            if (!models.isEmpty()) {
                LlmProperties.ModelEntry first = models.get(0);
                baseURL = first.getBaseUrl();
                modelName = first.getModelName();
                apiKey = first.getApiKey();
                protocol = first.getProtocol();
            } else {
                LlmProvider provider = LlmProvider.QWEN;
                baseURL = provider.getBaseUrl();
                modelName = provider.getDefaultModel();
                apiKey = System.getenv(provider.getApiKeyEnvName());
            }
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
        if ((apiKey == null || apiKey.isBlank()) && System.getenv("LLM_API_KEY") != null && !System.getenv("LLM_API_KEY").isBlank()) {
            apiKey = System.getenv("LLM_API_KEY");
        }

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Missing API key. Please configure api-key in application.yml or set environment variable: LLM_API_KEY");
        }

        return new ResolvedConfig(baseURL, modelName, apiKey, protocol);
    }

    /**
     * 判断当前调用走 Anthropic Messages 还是 OpenAI Chat Completions。
     * <p>protocol 显式指定时一锤定音；否则根据 baseURL / modelName 做嗅探（见 {@link LlmProvider#isAnthropicEndpoint}）。
     */
    private boolean isAnthropic(String baseURL, String modelName, String protocol) {
        if (protocol != null && !protocol.isBlank()) {
            return "anthropic".equalsIgnoreCase(protocol);
        }
        return LlmProvider.isAnthropicEndpoint(baseURL, modelName);
    }

    /**
     * 构造 OpenAI 兼容 ChatCompletions 请求体。
     * <p>支持：流式 / 非流式、json_mode、history、图片附件（image_url 形式）。
     * <p>有图片时 user.content 用数组形式（text 块 + 多个 image_url 块），无图片时退化为字符串。
     */
    private String buildOpenAiBody(String modelName, String systemPrompt, String userText,
                                   List<Map<String, Object>> history,
                                   List<Map<String, Object>> attachments,
                                   boolean stream, boolean jsonMode) throws IOException {
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

    /** chat 兼容入口：未传 attachments 时调主重载，保持旧调用方代码不变。 */
    public JsonNode chat(List<Map<String, Object>> nodes, List<Map<String, Object>> edges, String message, String modelOverride, String configId, List<Map<String, Object>> history) throws IOException {
        return chat(nodes, edges, message, modelOverride, configId, history, null);
    }

    /**
     * 同步 chat：把图谱上下文 + 用户消息发给 LLM，返回 {reply, add_nodes, add_edges} 形状的 JSON。
     * <p>非流式版本，主要供测试 / 不需要流式渲染的场景；正式聊天走 {@link #chatStreaming}。
     */
    public JsonNode chat(List<Map<String, Object>> nodes, List<Map<String, Object>> edges, String message,
                         String modelOverride, String configId, List<Map<String, Object>> history,
                         List<Map<String, Object>> attachments) throws IOException {
        ResolvedConfig cfg = resolveConfig(modelOverride, configId);
        boolean anthropic = isAnthropic(cfg.baseURL(), cfg.modelName(), cfg.protocol());

        log.info("[LLM-chat] 开始请求 model={} url={} protocol={}", cfg.modelName(), cfg.baseURL(), anthropic ? "anthropic" : "openai");

        String prompt = buildChatPrompt(nodes, edges, message);

        logConversation("LLM-chat", cfg.modelName(), SYSTEM_INSTRUCTION, history, prompt, attachments);

        String requestBody = anthropic
                ? buildAnthropicBody(cfg.modelName(), SYSTEM_INSTRUCTION, prompt, history, attachments, false, ANTHROPIC_MAX_TOKENS)
                : buildOpenAiBody(cfg.modelName(), SYSTEM_INSTRUCTION, prompt, history, attachments, false, true);

        log.debug("[LLM-chat] 请求体大小: {} chars", requestBody.length());
        long startTime = System.currentTimeMillis();

        try {
            HttpRequest request = buildHttpRequest(cfg.baseURL(), cfg.apiKey(), anthropic, requestBody);
            HttpResponse<String> response = sendHttp(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - startTime;

            if (response.statusCode() != 200) {
                log.error("[LLM-chat] 请求失败 status={} 耗时={}ms", response.statusCode(), elapsed);
                logUpstreamError("chat", response.statusCode(), response.body());
                metricsService.recordCall(cfg.modelName(), elapsed, false);
                throw new RuntimeException("LLM 调用失败 HTTP " + response.statusCode() + "（详情见服务器日志）");
            }

            log.info("[LLM-chat] 请求成功 status=200 耗时={}ms 响应大小={} chars", elapsed, response.body().length());
            metricsService.recordCall(cfg.modelName(), elapsed, true);

            JsonNode responseJson = objectMapper.readTree(response.body());
            String content = extractContent(responseJson, anthropic);
            content = content.replaceAll("(?i)^```json", "").replaceAll("```$", "").trim();
            if (content.isEmpty()) content = "{}";
            logLlmResponse("LLM-chat", cfg.modelName(), elapsed, content);
            return objectMapper.readTree(content);
        } catch (IOException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            metricsService.recordCall(cfg.modelName(), elapsed, false);
            throw e;
        }
    }

    /**
     * 流式 chat：HTTP 异步 + SSE 转发，把 LLM 的增量 text 通过 emitter 实时推回前端。
     * <p>整体流程：异步发起 HTTP → 收到响应后用 {@link #streamOpenAI} / {@link #streamAnthropic} 解析 SSE →
     * 完整 content 累积完毕后再解 JSON → 通过 complete 事件把 reply/add_nodes/add_edges 发回前端。
     * <p>之所以累积完整 content 后再解 JSON：LLM 的 JSON 可能在最后才闭合 {}，半流式解析意义不大反而出错率高。
     */
    public void chatStreaming(ChatRequest request, SseEmitter emitter) {
        long streamStartMs = System.currentTimeMillis();
        String streamModelName = "unknown";
        try {
            ResolvedConfig cfg = resolveConfig(request.getModelOverride(), request.getConfigId());
            streamModelName = cfg.modelName();
            boolean anthropic = isAnthropic(cfg.baseURL(), cfg.modelName(), cfg.protocol());

            log.info("[LLM-stream] 开始流式请求 model={} url={} protocol={}", cfg.modelName(), cfg.baseURL(), anthropic ? "anthropic" : "openai");

            String prompt = buildChatPrompt(request.getNodes(), request.getEdges(), request.getMessage());

            logConversation("LLM-stream", cfg.modelName(), SYSTEM_INSTRUCTION,
                    request.getHistory(), prompt, request.getAttachments());

            String requestBody = anthropic
                    ? buildAnthropicBody(cfg.modelName(), SYSTEM_INSTRUCTION, prompt, request.getHistory(), request.getAttachments(), true, ANTHROPIC_MAX_TOKENS)
                    : buildOpenAiBody(cfg.modelName(), SYSTEM_INSTRUCTION, prompt, request.getHistory(), request.getAttachments(), true, true);

            log.debug("[LLM-stream] 请求体大小: {} chars", requestBody.length());

            HttpRequest httpRequest = buildHttpRequest(cfg.baseURL(), cfg.apiKey(), anthropic, requestBody);

            // 捕获模型名用于异步回调中记录统计
            final String modelNameForMetrics = cfg.modelName();

            httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
                    .thenAccept(resp -> {
                        long firstByteTime = System.currentTimeMillis() - streamStartMs;
                        if (resp.statusCode() != 200) {
                            String errorBody;
                            try {
                                errorBody = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
                            } catch (IOException e) {
                                errorBody = "Unknown error";
                            }
                            log.error("[LLM-stream] 请求失败 status={} 首字节耗时={}ms", resp.statusCode(), firstByteTime);
                            logUpstreamError("chatStreaming", resp.statusCode(), errorBody);
                            metricsService.recordCall(modelNameForMetrics, firstByteTime, false);
                            try {
                                emitter.send(SseEmitter.event().name("error").data(
                                        "LLM 调用失败 HTTP " + resp.statusCode() + "（详情见服务器日志）"));
                            } catch (IOException e) {
                                log.warn("emit error event failed", e);
                            }
                            emitter.complete();
                            return;
                        }

                        log.info("[LLM-stream] 连接成功 首字节耗时={}ms", firstByteTime);

                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                            StringBuilder fullContent = anthropic
                                    ? streamAnthropic(reader, emitter)
                                    : streamOpenAI(reader, emitter);

                            long totalTime = System.currentTimeMillis() - streamStartMs;
                            log.info("[LLM-stream] 流式完成 总耗时={}ms 响应长度={} chars", totalTime, fullContent.length());
                            metricsService.recordCall(modelNameForMetrics, totalTime, true);

                            String content = fullContent.toString()
                                    .replaceAll("(?i)^```json", "").replaceAll("```$", "").trim();
                            if (content.isEmpty()) content = "{}";
                            logLlmResponse("LLM-stream", modelNameForMetrics, totalTime, content);
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
                            long totalTime = System.currentTimeMillis() - streamStartMs;
                            log.error("[LLM-stream] 响应解析失败: {}", e.getMessage());
                            metricsService.recordCall(modelNameForMetrics, totalTime, false);
                            try {
                                emitter.send(SseEmitter.event().name("error").data("Failed to parse response: " + e.getMessage()));
                            } catch (IOException ex) {
                                log.warn("emit parse-error failed", ex);
                            }
                            emitter.complete();
                        }
                    })
                    .exceptionally(ex -> {
                        long totalTime = System.currentTimeMillis() - streamStartMs;
                        log.error("[LLM-stream] 网络异常 耗时={}ms error={}", totalTime, ex.getMessage());
                        metricsService.recordCall(modelNameForMetrics, totalTime, false);
                        try {
                            emitter.send(SseEmitter.event().name("error").data("Network error: " + ex.getMessage()));
                        } catch (IOException e) {
                            log.warn("emit network-error failed", e);
                        }
                        emitter.completeWithError(ex);
                        return null;
                    });
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - streamStartMs;
            log.error("[LLM-stream] 初始化失败: {}", e.getMessage());
            metricsService.recordCall(streamModelName, elapsed, false);
            try {
                emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
            } catch (IOException ioEx) {
                log.warn("emit init-error failed", ioEx);
            }
            emitter.completeWithError(e);
        }
    }

    /** 解析 OpenAI 兼容流：每行 "data: <json>"，累积 delta.content；遇 [DONE] 结束。 */
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
     * 解析 Anthropic SSE 流：事件类型由 event: 行决定，content_block_delta 携带文本增量。
     * <p>同时处理 text_delta（普通文本）和 input_json_delta（工具调用 JSON 流），让 JSON 模式响应也能流式收齐。
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

    /**
     * P1-8：构造推演用的完整 prompt（system + user），抽出来便于 orchestrator 写入 rawPrompt。
     * 该方法是纯函数，不发起网络调用。
     */
    public PredictPromptArtifact buildPredictPrompt(com.tuiyan.backend.model.PredictRequest req) {
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

        return new PredictPromptArtifact(
                systemPrompt,
                userPrompt.toString(),
                wasTruncated,
                truncated.droppedNodes,
                truncated.droppedEdges);
    }

    /**
     * 同步推演调用：
     * <ol>
     *   <li>{@link #buildPredictPrompt} 构造完整 system / user prompt（与 Scenario.rawPrompt 一致）；</li>
     *   <li>按 provider 协议序列化为请求体；</li>
     *   <li>同步发起 HTTP，超时 90s（推演任务长，给足 LLM 思考时间）；</li>
     *   <li>剥掉可能的 ```json``` 包装，解析为 JsonNode 返回。</li>
     * </ol>
     * 由 {@link PredictionOrchestrator} 调用；流式推送是 orchestrator 自己做的，本方法只负责一次性拿全部 chain。
     */
    public JsonNode predictChain(com.tuiyan.backend.model.PredictRequest req) throws IOException {
        ResolvedConfig cfg = resolveConfig(req.getModelOverride(), req.getConfigId());
        boolean anthropic = isAnthropic(cfg.baseURL(), cfg.modelName(), cfg.protocol());

        int steps = req.getSteps() == null ? 4 : Math.max(1, Math.min(10, req.getSteps()));
        boolean backward = "backward".equalsIgnoreCase(req.getIntent());

        log.info("[LLM-predict] 开始推演 model={} url={} direction={} steps={}", cfg.modelName(), cfg.baseURL(), backward ? "backward" : "forward", steps);

        // 复用统一的 prompt 构造逻辑，确保与 Scenario.rawPrompt 一致
        PredictPromptArtifact artifact = buildPredictPrompt(req);
        String systemPrompt = artifact.system();
        String userPromptStr = artifact.user();

        logConversation("LLM-predict", cfg.modelName(), systemPrompt, null, userPromptStr, null);

        String requestBody = anthropic
                ? buildAnthropicBody(cfg.modelName(), systemPrompt, userPromptStr,
                        null, null, false, ANTHROPIC_MAX_TOKENS)
                : buildOpenAiBody(cfg.modelName(), systemPrompt, userPromptStr,
                        null, null, false, true);

        log.debug("[LLM-predict] 请求体大小: {} chars", requestBody.length());
        long startTime = System.currentTimeMillis();

        try {
            HttpRequest httpReq = buildHttpRequest(cfg.baseURL(), cfg.apiKey(), anthropic, requestBody);
            HttpResponse<String> resp = sendHttp(httpReq, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - startTime;

            if (resp.statusCode() != 200) {
                log.error("[LLM-predict] 请求失败 status={} 耗时={}ms", resp.statusCode(), elapsed);
                logUpstreamError("predict", resp.statusCode(), resp.body());
                metricsService.recordCall(cfg.modelName(), elapsed, false);
                throw new RuntimeException("LLM 调用失败 HTTP " + resp.statusCode() + "（详情见服务器日志）");
            }

            log.info("[LLM-predict] 请求成功 status=200 耗时={}ms 响应大小={} chars", elapsed, resp.body().length());
            metricsService.recordCall(cfg.modelName(), elapsed, true);

            JsonNode root = objectMapper.readTree(resp.body());
            String content = extractContent(root, anthropic);
            content = content.replaceAll("(?i)^```json", "").replaceAll("```$", "").trim();
            if (content.isEmpty()) content = "{}";
            logLlmResponse("LLM-predict", cfg.modelName(), elapsed, content);
            return objectMapper.readTree(content);
        } catch (IOException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            metricsService.recordCall(cfg.modelName(), elapsed, false);
            throw e;
        }
    }

    // 抽取阶段单次输入文本上限：超过则切分多次调用，最后用 mergeExtractionByLabel 合并
    private static final int EXTRACT_CHUNK_CHARS = 30_000;

    /**
     * P1-7：调 LLM 对预测节点给出三段式解释，返回原始 JsonNode（含 evidence/assumptions/counterexamples）。
     * <p>调用方负责构造 userPrompt（含因果链上下文）；该方法只做协议适配 + JSON 解析，
     * 解析后的内容由 {@link ScenarioExplanationService} 拆为 chunk 推送给前端。
     */
    public record ExplainResult(JsonNode json, String modelName) {}

    public ExplainResult explainNode(String userPrompt, String modelOverride, String configId) throws IOException {
        ResolvedConfig cfg = resolveConfig(modelOverride, configId);
        boolean anthropic = isAnthropic(cfg.baseURL(), cfg.modelName(), cfg.protocol());

        log.info("[LLM-explain] 开始 explain model={} url={}", cfg.modelName(), cfg.baseURL());
        logConversation("LLM-explain", cfg.modelName(), EXPLAIN_SYSTEM, null, userPrompt, null);

        String requestBody = anthropic
                ? buildAnthropicBody(cfg.modelName(), EXPLAIN_SYSTEM, userPrompt, null, null, false, ANTHROPIC_MAX_TOKENS)
                : buildOpenAiBody(cfg.modelName(), EXPLAIN_SYSTEM, userPrompt, null, null, false, true);

        long startTime = System.currentTimeMillis();
        try {
            HttpRequest httpReq = buildHttpRequest(cfg.baseURL(), cfg.apiKey(), anthropic, requestBody);
            HttpResponse<String> resp = sendHttp(httpReq, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - startTime;
            if (resp.statusCode() != 200) {
                log.error("[LLM-explain] 请求失败 status={} 耗时={}ms", resp.statusCode(), elapsed);
                logUpstreamError("explain", resp.statusCode(), resp.body());
                metricsService.recordCall(cfg.modelName(), elapsed, false);
                throw new RuntimeException("LLM 调用失败 HTTP " + resp.statusCode() + "（详情见服务器日志）");
            }
            metricsService.recordCall(cfg.modelName(), elapsed, true);
            JsonNode root = objectMapper.readTree(resp.body());
            String content = extractContent(root, anthropic);
            content = content.replaceAll("(?i)^```json", "").replaceAll("```$", "").trim();
            if (content.isEmpty()) content = "{}";
            logLlmResponse("LLM-explain", cfg.modelName(), elapsed, content);
            return new ExplainResult(objectMapper.readTree(content), cfg.modelName());
        } catch (IOException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            metricsService.recordCall(cfg.modelName(), elapsed, false);
            throw e;
        }
    }

    /**
     * 文档抽取入口：把长文本切成多个 chunk 分别调用 LLM，再按 label 合并去重。
     * <p>设计要点：
     * <ul>
     *   <li>大文档一次喂给 LLM 容易超 context，所以切分；</li>
     *   <li>切分后跨 chunk 可能重复识别同一实体，{@link #mergeExtractionByLabel} 按 label 归并；</li>
     *   <li>图片只在第一个 chunk 调用时挂上（避免重复发送 base64）。</li>
     * </ul>
     */
    public JsonNode extractOntologyFromSources(String combinedText,
                                               List<Map<String, Object>> imageAttachments,
                                               String modelOverride,
                                               String configId) throws IOException {
        ResolvedConfig cfg = resolveConfig(modelOverride, configId);
        boolean anthropic = isAnthropic(cfg.baseURL(), cfg.modelName(), cfg.protocol());

        List<String> chunks = chunkText(combinedText, EXTRACT_CHUNK_CHARS);
        boolean hasImages = imageAttachments != null && !imageAttachments.isEmpty();

        log.info("[LLM-extract] 开始抽取 model={} url={} textChunks={} hasImages={}", cfg.modelName(), cfg.baseURL(), chunks.size(), hasImages);

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

    /** 单次 chunk 调用 LLM 抽取节点 / 边。文本与图片同时挂上，让 LLM 能跨模态理解文档。 */
    private JsonNode callExtractOnce(String userText,
                                     List<Map<String, Object>> imageAttachments,
                                     String modelName, String baseURL, String apiKey, boolean anthropic) throws IOException {
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

        logConversation("LLM-extract", modelName, EXTRACT_SYSTEM, null, userPrompt.toString(), imageAttachments);

        String requestBody = anthropic
                ? buildAnthropicBody(modelName, EXTRACT_SYSTEM, userPrompt.toString(),
                        null, imageAttachments, false, ANTHROPIC_MAX_TOKENS)
                : buildOpenAiBody(modelName, EXTRACT_SYSTEM, userPrompt.toString(),
                        null, imageAttachments, false, true);

        HttpRequest httpReq = buildHttpRequest(baseURL, apiKey, anthropic, requestBody);
        long startTime = System.currentTimeMillis();
        HttpResponse<String> resp = sendHttp(httpReq, HttpResponse.BodyHandlers.ofString());
        long elapsed = System.currentTimeMillis() - startTime;

        if (resp.statusCode() != 200) {
            log.error("[LLM-extract] chunk请求失败 status={} 耗时={}ms", resp.statusCode(), elapsed);
            logUpstreamError("extract", resp.statusCode(), resp.body());
            throw new RuntimeException("LLM 调用失败 HTTP " + resp.statusCode() + "（详情见服务器日志）");
        }

        log.info("[LLM-extract] chunk请求成功 耗时={}ms 响应大小={} chars", elapsed, resp.body().length());
        JsonNode root = objectMapper.readTree(resp.body());
        String content = extractContent(root, anthropic);
        content = content.replaceAll("(?i)^```json", "").replaceAll("```$", "").trim();
        if (content.isEmpty()) content = "{}";
        logLlmResponse("LLM-extract", modelName, elapsed, content);
        return objectMapper.readTree(content);
    }

    /** 把上游错误体截断到前 1000 字符记录到 warn 日志，避免冗长 HTML / JSON 充斥日志。 */
    private static void logUpstreamError(String where, int status, String body) {
        String snippet = body == null ? "" : body.substring(0, Math.min(body.length(), 1000));
        log.warn("LLM upstream error in {}: HTTP {} body[:1000]={}", where, status, snippet);
    }

    /**
     * 长文本按字符上限切片，但优先在自然边界（换行 / 句号）切，避免把句子从中间切断。
     * <p>边界搜索范围限制在 [maxChars/2, maxChars]：太靠前切会让分片严重偏小，太靠后又找不到边界。
     */
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

    /**
     * 给某 chunk 的所有节点 / 边 id 加 chunk 前缀（如 "c0_n1"），并把边的 from/to 同步重写。
     * <p>用途：多 chunk 抽取时，不同 chunk 内 LLM 都用 n1/n2 命名，加前缀避免合并时冲突。
     */
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
     * 跨 chunk 合并：按 normalized label 去重节点，把重复节点的 props 合并，并对重复 id 做 from/to 重映射。
     * <p>这样多 chunk 抽取的同一实体（如"客户" / "客户 "）会归为一个节点，边的端点也指向合并后的 id。
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

    /** 合并节点 props：以 a 为基准，从 b 加入 a 没有的 key（同 key 取 a 的优先，避免覆盖已确认信息）。 */
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

    /** label 标准化：trim + 小写 + 多空白合一；用于 chunk 间去重的等价判断。 */
    private static String normalizeLabel(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    // ========== 协议适配辅助 ==========

    /**
     * 根据协议类型构造 HttpRequest。
     * <p>OpenAI 兼容用 {@code Authorization: Bearer ...}，路径 {@code /chat/completions}；
     * Anthropic 用 {@code x-api-key} + {@code anthropic-version} 头，路径 {@code /messages}。
     * 总体超时 90s，覆盖 LLM 推理的最坏情况。
     */
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

    /**
     * 从 LLM 响应中抽出文本内容。
     * <p>Anthropic 响应是 {@code content[]} 数组，需要拼接所有 type=text 的 block；
     * OpenAI 直接取 {@code choices[0].message.content}。
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
     * 构造 Anthropic Messages 请求体。
     * <p>关键差异（vs OpenAI）：
     * <ul>
     *   <li>system 是顶层字段而非 messages[0]；</li>
     *   <li>必须提供 max_tokens；</li>
     *   <li>图片用 base64 source 形式（type=image, source.type=base64），需要从 dataUrl 中拆出 media_type 和 base64 体。</li>
     * </ul>
     */
    private String buildAnthropicBody(String modelName, String systemPrompt, String userMessage,
                                      List<Map<String, Object>> history,
                                      List<Map<String, Object>> attachments,
                                      boolean stream, int maxTokens) throws IOException {
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

    /** {@link HttpClient#send} 的 checked-InterruptedException 包装：转成 IOException 让上层不必处理两种异常。 */
    private <T> HttpResponse<T> sendHttp(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler) throws IOException {
        try {
            return httpClient.send(request, bodyHandler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP 调用被中断", e);
        }
    }

    /**
     * 构造 chat 用 user prompt：把现有图谱摘要放在前面，作为"已知上下文"，
     * 让 LLM 在增量扩展时避免重复实体 / 关系，而是引用已有 id。
     */
    private String buildChatPrompt(List<Map<String, Object>> nodes,
                                   List<Map<String, Object>> edges,
                                   String message) {
        StringBuilder sb = new StringBuilder();
        boolean hasGraph = (nodes != null && !nodes.isEmpty()) || (edges != null && !edges.isEmpty());
        if (hasGraph) {
            sb.append("Existing ontology graph (the user is incrementally extending this — do NOT recreate any of these; reuse the ids exactly when you need to reference them):\n");
            sb.append(summarizeGraph(nodes, edges));
            sb.append("\nIncremental update rules:\n");
            sb.append("  - In add_nodes, include ONLY genuinely new entities/events/rules not already present above.\n");
            sb.append("  - If a concept already exists above, reuse its existing id in add_edges instead of creating a duplicate node.\n");
            sb.append("  - In add_edges, 'from'/'to' may reference existing node ids OR ids of nodes in your own add_nodes list.\n");
            sb.append("  - Do not emit an edge that already exists above with the same (from, to, label).\n\n");
        }
        sb.append("Here is the user's latest message:\n").append(message)
          .append("\n\nPlease generate the corresponding entities and relationships strictly in JSON format matching the given schema.");
        return sb.toString();
    }

    /** 把图谱压缩成 LLM 能读的可读文本（ASCII 表格风），节点 / 边各一段。 */
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

    // ========== context 截断 ==========

    // 单次推演给 LLM 的图谱预算：节点 / 边数量上限。超过则裁剪到 seeds + rules + constraints 的 N-hop 邻域
    private static final int CONTEXT_NODE_BUDGET = 120;
    private static final int CONTEXT_EDGE_BUDGET = 240;
    // 从 mandatory 节点向外扩散的跳数；3 跳通常足够覆盖核心因果链
    private static final int CONTEXT_HOPS = 3;

    /** 截断结果：保留下来的节点 / 边 + 被丢弃的数量（用于在 prompt 中告知 LLM "已截断"）。 */
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
     * 根据预算把大图谱裁剪成"以 seeds / rules / constraints 为中心的 N-hop 邻域"。
     * <p>策略：
     * <ol>
     *   <li>未超预算直接返回原图（不截断）；</li>
     *   <li>把 seeds、所有 rule 节点、constraints 目标作为 mandatory（强制保留）；</li>
     *   <li>从 mandatory 出发 BFS 向外扩 {@link #CONTEXT_HOPS} 层，每层按发现顺序加入直到达到节点 budget；</li>
     *   <li>边只保留两端都在 keep 集合里的，且不超过边 budget。</li>
     * </ol>
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

        Map<String, List<String>> neighbors = new HashMap<>();
        if (edges != null) {
            for (Map<String, Object> e : edges) {
                String f = String.valueOf(e.get("from"));
                String t = String.valueOf(e.get("to"));
                neighbors.computeIfAbsent(f, k -> new ArrayList<>()).add(t);
                neighbors.computeIfAbsent(t, k -> new ArrayList<>()).add(f);
            }
        }

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

    /** 提取规则节点（type=rule）的可读摘要，单独列出方便 LLM 在推演时优先用规则。 */
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
     * 把 what-if 约束转成中文 prompt 文本：block / probability / force 三种模式各对应一段说明。
     * <p>probability 模式特别提示 LLM 把先验作为初值做贝叶斯更新（P1-10 设计点）。
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
            } else if ("probability".equalsIgnoreCase(c.getMode())) {
                // P1-10：先验概率作为推演入口的初值，提示 LLM 做贝叶斯更新
                double p = c.getProbability() == null ? 0.5 : Math.max(0.0, Math.min(1.0, c.getProbability()));
                sb.append("  - 概率: ").append(label)
                  .append(" 先验概率 = ").append(String.format("%.2f", p))
                  .append("。请把先验作为初值，结合上下游证据用贝叶斯式更新；返回的 confidence 应反映综合后验。\n");
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

    /** 把 seeds 列成 "id (label)" 格式，让 LLM 在 prompt 中直接看到种子的人类可读名称。 */
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
}
