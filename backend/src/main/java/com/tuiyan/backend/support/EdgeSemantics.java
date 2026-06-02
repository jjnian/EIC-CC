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
 *   <li>{@code triggers}：from=event → to=被触发 ⇒ 流向 = from→to（正向）；</li>
 *   <li>{@code governs}：from=rule → to=被治理 ⇒ 流向 = from→to（正向）；</li>
 *   <li>其它 / 未知：默认 from→to。</li>
 * </ul>
 */
public final class EdgeSemantics {

    private EdgeSemantics() {}

    /** 数据流方向与存储方向相反的 rel_type（上游源在 to 端）。 */
    private static final Set<String> REVERSED = Set.of("derived_from", "composed_of");

    /** 该 rel_type 的血缘数据流方向是否与存储的 from→to 相反。 */
    public static boolean reversedForLineage(String relType) {
        return relType != null && REVERSED.contains(relType.trim().toLowerCase());
    }

    /**
     * 该边在血缘意义上的 {@code [上游源, 下游]} 节点对。
     * <p>上游 = 数据来源 / 起点；下游 = 派生 / 依赖方。
     */
    public static String[] sourceTarget(String from, String to, String relType) {
        return reversedForLineage(relType) ? new String[]{to, from} : new String[]{from, to};
    }
}
