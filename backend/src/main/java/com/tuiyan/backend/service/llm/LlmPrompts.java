package com.tuiyan.backend.service.llm;

/**
 * 所有 LLM 业务场景的 system prompt 与 JSON schema 常量。
 * <p>集中存放便于一处维护多处复用：chat / extract / predict (forward+backward) / explain。
 * 修改 prompt 不需要进入大段业务代码，降低误改其它逻辑的风险。
 */
public final class LlmPrompts {

    private LlmPrompts() {}

    public static final String SCHEMA_STRING = """
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
                  "description": "Optional brief description of this attribute",
                  "source": "Must be one of: 'derived' or 'inferred'"
                }
              ],
              "constraints": [
                {
                  "kind": "Must be one of: 'cardinality', 'exclusive', 'symmetric', 'transitive', 'custom'",
                  "note": "Human-readable description of the constraint, e.g., 'Each order must have exactly one customer'",
                  "source": "Must be one of: 'derived' or 'inferred'"
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
                  "note": "Human-readable constraint on this relationship, e.g., '1:N — one supplier supplies many parts', 'symmetric — A partners with B implies B partners with A'",
                  "source": "Must be one of: 'derived' or 'inferred'"
                }
              ]
            }
          ]
        }
        """;

    public static final String CHAT_SYSTEM = """
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

    public static final String EXTRACT_SYSTEM = """
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

    public static final String PREDICT_SCHEMA = """
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

    public static final String PREDICT_SYSTEM = """
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

    public static final String PREDICT_BACKWARD_SCHEMA = """
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

    public static final String PREDICT_BACKWARD_SYSTEM = """
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

    public static final String EXPLAIN_SCHEMA = """
        {
          "evidence": "依据：因果链上有哪些证据/规则/节点支持这一步发生",
          "assumptions": "假设：得出该结论的隐含前提",
          "counterexamples": "反例：可能让该步骤不成立的反向证据或场景"
        }
        """;

    public static final String EXPLAIN_SYSTEM = """
        你是一个推演分析助手。在给定的因果链上下文中，请对指定预测步骤给出三段式解释。

        要求：
        1. evidence (依据)：列出图谱里支持该步骤的具体节点、规则、上下游路径（2-4 句中文）。
        2. assumptions (假设)：列出得出该结论的隐含前提条件（2-4 句中文）。
        3. counterexamples (反例)：列出可能推翻该步骤的反向证据或边界场景（2-3 句中文）。
        4. 用简体中文。每段保持简洁，避免重复信息。
        5. 只输出严格符合 schema 的 JSON，禁止 markdown 包裹。

        SCHEMA:
        %s""".formatted(EXPLAIN_SCHEMA);
}
