package com.tuiyan.backend.support;

import java.util.Set;

/**
 * 边语义 → 血缘数据流方向的集中映射。
 * <p>本体边的存储方向 {@code from→to} 由抽取映射规则决定，不同 {@code rel_type} 约定不同
 * （见 {@code SCHEMA_TO_ONTOLOGY_SYSTEM} Rule 3）：部分 rel_type 的「数据来源→派生」方向与
 * {@code from→to} 相反。因此上下游遍历必须按 rel_type 判定真实数据流方向，而不能一律按 from→to。
 * <ul>
 *   <li>{@code derived_from}：存为 from=child → to=parent，但数据来源是 parent ⇒ 血缘流向 = to→from（反向）；</li>
 *   <li>{@code composed_of}：存为 from=whole → to=part，部件构成整体 ⇒ 血缘流向 = to→from（反向）；</li>
 *   <li>{@code depends_on}：from=依赖方 → to=被依赖方，被依赖方是前提/来源 ⇒ 血缘流向 = to→from（反向）；</li>
 *   <li>{@code consumes}：from=消费方 → to=被消耗的输入，输入是来源 ⇒ 血缘流向 = to→from（反向）；</li>
 *   <li>{@code triggers}：from=event → to=被触发 ⇒ 流向 = from→to（正向）；</li>
 *   <li>{@code governs}：from=rule → to=被治理 ⇒ 流向 = from→to（正向）；</li>
 *   <li>{@code produces} / {@code transforms} / {@code flows_to} / 其它未知：默认 from→to（正向）；</li>
 *   <li>{@code associated_with}：纯关联、无数据流方向，默认不参与血缘遍历（见 {@link #nonLineage}）。</li>
 * </ul>
 * <p>必须与前端 {@code useLineageTrace.ts} 的 {@code REVERSE_RELS} / {@code NON_LINEAGE_RELS} 保持一致，
 * 否则后端 {@code /lineage} 遍历与画布上的血缘高亮会给出相互矛盾的上下游。
 */
public final class EdgeSemantics {

    private EdgeSemantics() {}

    /** 数据流方向与存储方向相反的 rel_type（上游源在 to 端）。与前端 REVERSE_RELS 对齐。 */
    private static final Set<String> REVERSED = Set.of("derived_from", "composed_of", "depends_on", "consumes");

    /**
     * 不承载数据流方向的纯关联 rel_type。「A 与 B 相关」不构成上下游派生关系，
     * 默认从血缘遍历中排除，避免关联噪声把不相干的节点卷进上下游影响分析。
     * 与前端 NON_LINEAGE_RELS 对齐。
     */
    private static final Set<String> NON_LINEAGE = Set.of("associated_with");

    /** 该 rel_type 的血缘数据流方向是否与存储的 from→to 相反。 */
    public static boolean reversedForLineage(String relType) {
        return relType != null && REVERSED.contains(relType.trim().toLowerCase());
    }

    /** 该 rel_type 是否为无方向的纯关联（默认不参与血缘遍历）。 */
    public static boolean nonLineage(String relType) {
        return relType != null && NON_LINEAGE.contains(relType.trim().toLowerCase());
    }

    /**
     * 该边在血缘意义上的 {@code [上游源, 下游]} 节点对。
     * <p>上游 = 数据来源 / 起点；下游 = 派生 / 依赖方。
     */
    public static String[] sourceTarget(String from, String to, String relType) {
        return reversedForLineage(relType) ? new String[]{to, from} : new String[]{from, to};
    }
}
