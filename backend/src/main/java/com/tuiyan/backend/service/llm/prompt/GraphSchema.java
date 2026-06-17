package com.tuiyan.backend.service.llm.prompt;

/**
 * 本体图谱构建的通用 JSON schema 常量：被 chat / 文档抽取 / schema→本体 三类 prompt 共享，
 * 描述 {reply, add_nodes, add_edges, questions} 的输出形状。
 */
public final class GraphSchema {

    private GraphSchema() {}

    public static final String SCHEMA_STRING = """
        {
          "reply": "A short, helpful assistant reply acknowledging the user's request and explaining the graph updates.",
          "add_nodes": [
            {
              "id": "A unique id for the node, e.g., 'n_123'",
              "label": "The canonical business concept name (pick ONE preferred form; raw table names may appear only as traceability hints)",
              "aliases": ["Other surface forms / abbreviations referring to the SAME concept, e.g., ['采购单','PO'] for '采购订单'. Used to de-duplicate across chunks. Omit or [] if none."],
              "type": "Must be one of: 'entity', 'event', 'rule', 'process', 'data', 'external'",
              "source": "Must be one of: 'derived' (from text) or 'inferred'",
              "evidence": "≤30-char quote or location from the source that grounds this node. Empty string for purely inferred nodes.",
              "confidence": "Number 0.0-1.0 — how certain this node really exists in the domain (derived≈0.9-1.0, inferred≈0.4-0.7).",
              "derived_tables": ["Required when schema-backed. The real table name(s) that back this business concept, e.g., ['orders'] or ['orders','order_items'] when multiple physical tables collapse into one semantic node. Omit only outside the schema→ontology flow."],
              "props": [
                { "key": "string", "value": "string", "source": "Must be one of: 'derived' or 'inferred'" }
              ],
              "attributes": [
                {
                  "name": "The attribute name — a BUSINESS-SEMANTIC name, e.g., '订单状态', '下单时间' (not necessarily the raw column name).",
                  "column": "Required when the attribute is backed by a physical column. The real column name backing this attribute, e.g., 'status'. Omit only outside the schema→ontology flow.",
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
              "from": "Source node id (MUST be an id present in add_nodes or in the KNOWN ENTITIES list provided)",
              "to": "Target node id (MUST be an id present in add_nodes or in the KNOWN ENTITIES list provided)",
              "rel_type": "The controlled relationship type — MUST be one of: 'produces','consumes','derived_from','depends_on','triggers','governs','composed_of','transforms','flows_to','associated_with'. Use 'associated_with' ONLY when none of the others fit.",
              "label": "The concrete phrasing from the source for this relationship (free text, for display), e.g., '审批通过后生成'",
              "source": "Must be one of: 'derived' or 'inferred'",
              "evidence": "≤30-char quote or location from the source that grounds this edge. Empty string for purely inferred edges.",
              "confidence": "Number 0.0-1.0 — how certain this relationship holds (derived≈0.9-1.0, inferred≈0.4-0.7).",
              "derived_tables": ["Required when schema-backed. The real table(s) backing this relationship, e.g., the two FK-linked tables or the single junction table for an N:M relationship. Omit only outside the schema→ontology flow."],
              "rule_driven": true_or_false,
              "constraints": [
                {
                  "kind": "Must be one of: 'cardinality', 'exclusive', 'symmetric', 'transitive', 'custom'",
                  "note": "Human-readable constraint on this relationship, e.g., '1:N — one supplier supplies many parts', 'symmetric — A partners with B implies B partners with A'",
                  "source": "Must be one of: 'derived' or 'inferred'"
                }
              ]
            }
          ],
          "questions": [
            {
              "header": "OPTIONAL — a very short label/topic for this question (≤6 Chinese chars), e.g. '客户类型' / '建模视角'.",
              "text": "A short clarifying question (one sentence, Chinese).",
              "multiSelect": "OPTIONAL boolean — set true ONLY when the user could reasonably pick several options at once (e.g. which perspectives/scopes to include). Default false (single choice).",
              "options": [
                { "label": "A concrete choice the user can click (short Chinese phrase, ≤12 chars)" },
                { "label": "Another choice" }
              ]
            }
          ]
        }
        """;
}
