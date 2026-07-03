package com.tuiyan.backend.service.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Locale;

/**
 * 抽取结果的事实性校验：prompt 里的 FACT-FIRST 硬规则（derived 必须有据可查、
 * confidence 分档）此前只靠 LLM 自觉，这里在服务端落地为可执行的校验。
 * <ul>
 *   <li><b>evidence 落地校验</b>（仅在给定源文本时）：source="derived" 的节点/边，
 *       其 evidence 必须能在源文本中命中（空白折叠 + 忽略大小写；"…"截断的引文按分段逐一匹配）。
 *       命不中或缺失 → 降级为 source="inferred"、confidence 压到 ≤0.55，并打上
 *       {@code evidence_unverified:true} 供前端/审计识别，evidence 原文保留以便人工核对；</li>
 *   <li><b>confidence 边界收敛</b>（任何路径都做）：越界值收敛到 [0,1]；
 *       source="inferred" 的 confidence 按 prompt 约定压到 ≤0.55。</li>
 * </ul>
 * 全部为无副作用可预期的 JSON 就地修正；不删除任何元素——校验的职责是"降级存疑项"，
 * 不是"替 LLM 做裁决"，删不删由用户在图上决定。
 */
public final class ProvenanceValidator {

    /** inferred（含被降级项）允许的置信度上限，与 ChatPrompts / ExtractPrompts 的分档约定一致。 */
    private static final double INFERRED_CONFIDENCE_CAP = 0.55;

    private ProvenanceValidator() {}

    /** 校验统计：downgraded = 降级为 inferred 的元素数；clamped = confidence 被收敛的元素数。 */
    public record Stats(int downgraded, int clamped) {
        public boolean any() { return downgraded > 0 || clamped > 0; }
        public Stats plus(Stats o) { return new Stats(downgraded + o.downgraded, clamped + o.clamped); }
        public static Stats empty() { return new Stats(0, 0); }
    }

    /**
     * 就地校验一份抽取结果（{@code add_nodes} / {@code add_edges}）。
     *
     * @param graph          LLM 输出的图 JSON（会被就地修正）
     * @param sourceText     本段抽取所依据的原文；null/空 = 不做 evidence 落地校验
     * @param checkGrounding false 时跳过 evidence 校验（如多模态图片输入、DDL 结构化输入），只做 confidence 收敛
     */
    public static Stats validate(JsonNode graph, String sourceText, boolean checkGrounding) {
        if (graph == null || !graph.isObject()) return Stats.empty();
        boolean grounding = checkGrounding && sourceText != null && !sourceText.isBlank();
        String normSource = grounding ? normalize(sourceText) : null;
        Stats a = validateArray(graph.path("add_nodes"), normSource);
        Stats b = validateArray(graph.path("add_edges"), normSource);
        return a.plus(b);
    }

    private static Stats validateArray(JsonNode arr, String normSource) {
        if (arr == null || !arr.isArray()) return Stats.empty();
        int downgraded = 0;
        int clamped = 0;
        for (JsonNode el : arr) {
            if (!(el instanceof ObjectNode obj)) continue;

            // 1. confidence 越界收敛到 [0,1]
            if (obj.hasNonNull("confidence") && obj.path("confidence").isNumber()) {
                double v = obj.path("confidence").asDouble();
                double c = Math.min(1.0, Math.max(0.0, v));
                if (c != v) { obj.put("confidence", c); clamped++; }
            }

            String source = obj.path("source").asText("");

            // 2. derived 的 evidence 必须能在源文本命中，否则降级为 inferred
            if (normSource != null && "derived".equalsIgnoreCase(source)) {
                String evidence = obj.path("evidence").asText("");
                if (evidence.isBlank() || !grounded(normSource, evidence)) {
                    obj.put("source", "inferred");
                    obj.put("evidence_unverified", true);
                    source = "inferred";
                    downgraded++;
                }
            }

            // 3. inferred（含刚降级的）confidence 压到 ≤ 上限
            if ("inferred".equalsIgnoreCase(source)
                    && obj.hasNonNull("confidence") && obj.path("confidence").isNumber()) {
                double v = obj.path("confidence").asDouble();
                if (v > INFERRED_CONFIDENCE_CAP) {
                    obj.put("confidence", INFERRED_CONFIDENCE_CAP);
                    clamped++;
                }
            }
        }
        return new Stats(downgraded, clamped);
    }

    /**
     * 引文是否能在源文本命中：空白折叠 + 小写后做包含判断；
     * LLM 用 "…"/"..." 截断的引文拆成多段，要求每段都命中。
     */
    private static boolean grounded(String normSource, String evidence) {
        for (String part : evidence.split("…|\\.{3}")) {
            String p = normalize(part);
            if (p.isEmpty()) continue;
            if (!normSource.contains(p)) return false;
        }
        return true;
    }

    /** 归一化：小写 + 去除全部空白（含全角空格），适配中文原文的换行/排版差异。 */
    private static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\u3000]+", "");
    }
}
