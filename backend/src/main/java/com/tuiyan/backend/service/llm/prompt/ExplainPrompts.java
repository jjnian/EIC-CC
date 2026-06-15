package com.tuiyan.backend.service.llm.prompt;

/**
 * 推演步骤解释（依据/假设/反例三段式）的 system prompt 与 schema。
 */
public final class ExplainPrompts {

    private ExplainPrompts() {}

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
        """ + EXPLAIN_SCHEMA;
}
