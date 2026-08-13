package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tuiyan.backend.service.indexing.EmbeddingClient;
import com.tuiyan.backend.service.llm.prompt.ExtractPrompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 实体对齐服务（Schema-First 建图第二阶段：向量兜底同义消解）。
 * <p>词表规约（第一阶段）只能统一「已知别名」——词表没收录的同义词
 * （收款/回款、供货商/供应商）在批 A、批 B 里仍各造一个节点、血缘链断裂。
 * 本服务在合并阶段用向量把这一层兜住：
 * <ol>
 *   <li><b>召回</b>：对本次建图的全部节点 label 做嵌入，两两余弦找出高相似的「异名对」
 *       （相似度 ≥ 阈值；受候选对数上限约束）；</li>
 *   <li><b>仲裁</b>：向量只负责召回，是否真同义交给便宜模型一次批量判定
 *       （{@link ExtractPrompts#ENTITY_ALIGN_SYSTEM}，保守判定）；</li>
 *   <li><b>折叠</b>：并查集把确认同义的节点归并到一个规范节点（高度数优先），
 *       重映射边端点、并别名与 props；产出交给下游 sanitize 去重。</li>
 * </ol>
 * <p>发现的同义映射一并返回，供上层反哺词表，下次走确定性快路径而不必再花向量+LLM。
 * embedding 未配置时整体 no-op（返回原图 + 空映射）。任何失败只记日志、不影响主结果。
 */
@Service
public class EntityAlignmentService {

    private static final Logger log = LoggerFactory.getLogger(EntityAlignmentService.class);

    /**
     * 参与对齐的节点上限：超出只嵌入前 N 个（按度数降序，枢纽优先）。
     * 面向海量经验建图（跨众多领域、成千上万节点），从 400 放宽到 1200——两两余弦 O(N²)≈144 万次，
     * CPU 秒级可接受；覆盖更多节点，海量同义消解更全。仍按度数优先，小图不受影响（全参与）。
     */
    private static final int MAX_ALIGN_NODES = 1200;
    /** 送交 LLM 仲裁的候选对上限（按相似度降序取），控制单次仲裁 token；随节点池放宽同步略增。 */
    private static final int MAX_CANDIDATE_PAIRS = 150;
    /** 余弦相似度召回阈值：偏召回，精度由 LLM 仲裁把关。 */
    private static final double SIM_THRESHOLD = 0.88;

    private final EmbeddingClient embeddingClient;
    private final ExtractionLlmService extractionLlmService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EntityAlignmentService(EmbeddingClient embeddingClient, ExtractionLlmService extractionLlmService) {
        this.embeddingClient = embeddingClient;
        this.extractionLlmService = extractionLlmService;
    }

    /** 对齐结果：折叠后的图 + 归并组数 + 被折叠节点数 + 发现的同义映射（规范名 → 别名集）。 */
    public record AlignResult(JsonNode graph, int foldedGroups, int foldedNodes,
                              Map<String, Set<String>> discoveredAliases) {
        public boolean any() { return foldedGroups > 0; }
    }

    /**
     * 对 {@code {add_nodes, add_edges}} 形状的草稿做向量兜底对齐。
     * embedding 未配置 / 节点太少 / 无候选 / 任何异常 → 原样返回（no-op）。
     */
    public AlignResult align(JsonNode draft, String modelOverride, String configId) {
        AlignResult noop = new AlignResult(draft, 0, 0, Map.of());
        if (draft == null || !draft.isObject()) return noop;
        if (!embeddingClient.isConfigured()) return noop;
        JsonNode nodesNode = draft.path("add_nodes");
        if (!nodesNode.isArray() || nodesNode.size() < 2) return noop;

        try {
            List<ObjectNode> nodes = new ArrayList<>();
            for (JsonNode n : nodesNode) if (n instanceof ObjectNode o) nodes.add(o);
            Map<String, Integer> degree = degrees(draft.path("add_edges"));

            // 节点太多时按度数降序取前 N 个参与嵌入（枢纽节点最值得对齐）
            List<ObjectNode> pool = nodes;
            if (nodes.size() > MAX_ALIGN_NODES) {
                pool = new ArrayList<>(nodes);
                pool.sort((a, b) -> Integer.compare(
                        degree.getOrDefault(idOf(b), 0), degree.getOrDefault(idOf(a), 0)));
                pool = pool.subList(0, MAX_ALIGN_NODES);
            }

            List<String> labels = new ArrayList<>(pool.size());
            for (ObjectNode n : pool) labels.add(n.path("label").asText(""));
            List<float[]> vecs = embeddingClient.embedBatch(labels);
            if (vecs.size() != pool.size()) return noop;

            // 两两余弦找候选异名对（label 已不同——完全同名在 label 合并阶段已折叠）
            List<int[]> candidates = new ArrayList<>();
            List<Double> sims = new ArrayList<>();
            for (int i = 0; i < pool.size(); i++) {
                for (int j = i + 1; j < pool.size(); j++) {
                    double sim = cosine(vecs.get(i), vecs.get(j));
                    if (sim >= SIM_THRESHOLD) { candidates.add(new int[]{i, j}); sims.add(sim); }
                }
            }
            if (candidates.isEmpty()) return noop;
            // 相似度降序，截断到上限
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) order.add(i);
            order.sort((a, b) -> Double.compare(sims.get(b), sims.get(a)));
            int keep = Math.min(MAX_CANDIDATE_PAIRS, order.size());

            StringBuilder prompt = new StringBuilder("【候选同义实体对】判断每一对是否指同一业务概念:\n");
            List<int[]> shortlist = new ArrayList<>(keep);
            for (int k = 0; k < keep; k++) {
                int[] pr = candidates.get(order.get(k));
                shortlist.add(pr);
                ObjectNode a = pool.get(pr[0]);
                ObjectNode b = pool.get(pr[1]);
                prompt.append(k + 1).append(". A=\"").append(a.path("label").asText(""))
                      .append("\"(").append(a.path("type").asText("")).append(") <=> B=\"")
                      .append(b.path("label").asText("")).append("\"(")
                      .append(b.path("type").asText("")).append(")\n");
            }

            JsonNode verdict = extractionLlmService.extractRaw(
                    prompt.toString(), ExtractPrompts.ENTITY_ALIGN_SYSTEM, modelOverride, configId);
            Set<Integer> samePairs = new LinkedHashSet<>();
            JsonNode arr = verdict.path("same_pairs");
            if (arr.isArray()) for (JsonNode x : arr) {
                int idx = x.asInt(0);
                if (idx >= 1 && idx <= shortlist.size()) samePairs.add(idx);
            }
            if (samePairs.isEmpty()) return noop;

            // 确认同义对 → (winnerId, loserId) 折叠关系
            List<String[]> confirmed = new ArrayList<>();
            for (int idx : samePairs) {
                int[] pr = shortlist.get(idx - 1);
                confirmed.add(new String[]{idOf(pool.get(pr[0])), idOf(pool.get(pr[1]))});
            }
            return foldConfirmed(draft, confirmed, degree);
        } catch (Exception e) {
            log.warn("[entity-align] 实体对齐失败(忽略,不影响主结果): {}", e.toString());
            return noop;
        }
    }

    /**
     * 纯函数折叠：把确认同义的节点用并查集归组，每组选规范节点（度数高→label 短→id 小，确定性），
     * 其余折叠进它——并别名/props、重映射边端点、移除被折叠节点。就地改 draft 并返回。
     * <p>抽成静态方法便于离线单测（不碰 embedding/LLM）。
     */
    static AlignResult foldConfirmed(JsonNode draft, List<String[]> confirmedPairs, Map<String, Integer> degree) {
        JsonNode nodesNode = draft.path("add_nodes");
        if (!nodesNode.isArray()) return new AlignResult(draft, 0, 0, Map.of());
        Map<String, ObjectNode> byId = new LinkedHashMap<>();
        for (JsonNode n : nodesNode) if (n instanceof ObjectNode o) byId.put(idOf(o), o);

        // 并查集
        Map<String, String> parent = new HashMap<>();
        for (String id : byId.keySet()) parent.put(id, id);
        for (String[] pr : confirmedPairs) {
            if (byId.containsKey(pr[0]) && byId.containsKey(pr[1])) union(parent, pr[0], pr[1]);
        }
        // 分组
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String id : byId.keySet()) {
            groups.computeIfAbsent(find(parent, id), k -> new ArrayList<>()).add(id);
        }

        Map<String, String> remap = new HashMap<>();          // loserId → winnerId
        Map<String, Set<String>> discovered = new LinkedHashMap<>(); // 规范名 → 别名集
        int foldedGroups = 0;
        int foldedNodes = 0;
        for (List<String> group : groups.values()) {
            if (group.size() < 2) continue;
            String winnerId = pickCanonical(group, byId, degree);
            ObjectNode winner = byId.get(winnerId);
            Set<String> aliasAcc = collectAliases(winner);
            String canon = winner.path("label").asText("");
            foldedGroups++;
            for (String loserId : group) {
                if (loserId.equals(winnerId)) continue;
                ObjectNode loser = byId.get(loserId);
                remap.put(loserId, winnerId);
                foldedNodes++;
                // 被折叠节点的 label + 别名 并入 winner 别名（去重，排除规范名自身）
                String loserLabel = loser.path("label").asText("");
                if (!norm(loserLabel).isEmpty() && !norm(loserLabel).equals(norm(canon))) {
                    aliasAcc.add(loserLabel);
                    discovered.computeIfAbsent(canon, k -> new LinkedHashSet<>()).add(loserLabel);
                }
                for (String a : collectAliases(loser)) {
                    if (!norm(a).equals(norm(canon))) aliasAcc.add(a);
                }
                mergeProps(winner, loser);
            }
            if (!aliasAcc.isEmpty()) {
                ArrayNode as = winner.putArray("aliases");
                aliasAcc.forEach(as::add);
            }
        }
        if (remap.isEmpty()) return new AlignResult(draft, 0, 0, Map.of());

        // 移除被折叠节点
        ArrayNode outNodes = ((ObjectNode) draft).arrayNode();
        for (JsonNode n : nodesNode) {
            if (n instanceof ObjectNode o && remap.containsKey(idOf(o))) continue;
            outNodes.add(n);
        }
        ((ObjectNode) draft).set("add_nodes", outNodes);
        // 重映射边端点（重复/自环由下游 sanitizeGraph 清理）
        JsonNode edgesNode = draft.path("add_edges");
        if (edgesNode.isArray()) {
            for (JsonNode e : edgesNode) {
                if (!(e instanceof ObjectNode eo)) continue;
                String f = eo.path("from").asText("");
                String t = eo.path("to").asText("");
                if (remap.containsKey(f)) eo.put("from", remap.get(f));
                if (remap.containsKey(t)) eo.put("to", remap.get(t));
            }
        }
        return new AlignResult(draft, foldedGroups, foldedNodes, discovered);
    }

    /** 组内规范节点：度数高 → label 短 → id 字典序小（全确定性，同一输入两次结果一致）。 */
    private static String pickCanonical(List<String> group, Map<String, ObjectNode> byId, Map<String, Integer> degree) {
        String best = null;
        for (String id : group) {
            if (best == null) { best = id; continue; }
            int dc = Integer.compare(degree.getOrDefault(id, 0), degree.getOrDefault(best, 0));
            if (dc > 0) { best = id; continue; }
            if (dc < 0) continue;
            int lc = Integer.compare(byId.get(id).path("label").asText("").length(),
                                     byId.get(best).path("label").asText("").length());
            if (lc < 0) { best = id; continue; }
            if (lc > 0) continue;
            if (id.compareTo(best) < 0) best = id;
        }
        return best;
    }

    private static Set<String> collectAliases(ObjectNode node) {
        Set<String> out = new LinkedHashSet<>();
        JsonNode a = node.path("aliases");
        if (a.isArray()) for (JsonNode x : a) {
            String s = x.asText("");
            if (!s.isBlank()) out.add(s);
        }
        return out;
    }

    /** 把 loser 的 props 并入 winner（winner 已有的 key 优先保留）。 */
    private static void mergeProps(ObjectNode winner, ObjectNode loser) {
        JsonNode lp = loser.path("props");
        if (!lp.isArray() || lp.isEmpty()) return;
        ArrayNode wp = winner.path("props").isArray()
                ? (ArrayNode) winner.path("props") : winner.putArray("props");
        Set<String> seen = new HashSet<>();
        for (JsonNode p : wp) {
            String k = p.path("key").asText("");
            if (!k.isEmpty()) seen.add(norm(k));
        }
        for (JsonNode p : lp) {
            String k = p.path("key").asText("");
            if (k.isEmpty() || seen.contains(norm(k))) continue;
            wp.add(p.deepCopy());
            seen.add(norm(k));
        }
    }

    private static Map<String, Integer> degrees(JsonNode edges) {
        Map<String, Integer> deg = new HashMap<>();
        if (edges != null && edges.isArray()) {
            for (JsonNode e : edges) {
                deg.merge(e.path("from").asText(""), 1, Integer::sum);
                deg.merge(e.path("to").asText(""), 1, Integer::sum);
            }
        }
        return deg;
    }

    private static String idOf(ObjectNode n) { return n.path("id").asText(""); }

    private static String find(Map<String, String> parent, String x) {
        String root = x;
        while (!root.equals(parent.get(root))) root = parent.get(root);
        while (!x.equals(root)) { String nx = parent.get(x); parent.put(x, root); x = nx; }
        return root;
    }

    private static void union(Map<String, String> parent, String a, String b) {
        String ra = find(parent, a);
        String rb = find(parent, b);
        if (!ra.equals(rb)) parent.put(ra, rb);
    }

    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
