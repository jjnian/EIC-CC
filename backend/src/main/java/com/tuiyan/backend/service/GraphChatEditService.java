package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.tuiyan.backend.config.ResourceNotFoundException;
import com.tuiyan.backend.repository.OntologyModelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 对话驱动的精准改图：用户用自然语言描述要怎么改血缘图（连边/删节点/改方向/改命名…），
 * 系统<b>只检索相关子图</b>作上下文喂 LLM（不发整图，故大图也能改），LLM 产出一份精确 patch，
 * 经 {@link GraphPatchService} 外科手术式应用。含归属校验。
 * <p>面向「文件多时快速修复大图」：定位相关部分 → 局部改 → 局部落库，全程不重建整图。
 */
@Service
public class GraphChatEditService {

    private static final Logger log = LoggerFactory.getLogger(GraphChatEditService.class);

    /** 喂给 LLM 的相关子图节点上限（控 token）。 */
    private static final int CONTEXT_NODES = 60;
    /** 相关子图边上限。 */
    private static final int CONTEXT_EDGES = 200;

    private static final String EDIT_SYSTEM = """
        你是业务血缘图的编辑助手。根据【当前相关子图】和【用户请求】，输出对图的最小精确修改（JSON）。
        只输出 JSON，形如：{"reply":"一句话说明你做了什么","ops":[ ... ]}
        op 类型（增删改 单个节点/边）：
        - {"op":"add_node","node":{"id":"u_1","label":"发票","type":"entity"}}   新节点 id 用 u_ 前缀
        - {"op":"update_node","node":{"id":"<子图里的已有id>","label":"新名","type":"..."}}
        - {"op":"delete_node","id":"<子图里的已有id>"}
        - {"op":"add_edge","edge":{"id":"u_e1","from":"<id>","to":"<id>","rel_type":"produces","label":"生成"}}
        - {"op":"update_edge","edge":{"id":"<子图里的已有边id>","from":"<id>","to":"<id>","rel_type":"..."}}
        - {"op":"delete_edge","id":"<子图里的已有边id>"}
        规则：
        - from/to 必须引用真实节点 id：子图里给出的已有 id，或本次 add_node 新建的 u_ id；不要编造 id。
        - rel_type 用受控类型：produces/consumes/derived_from/depends_on/flows_to/transforms/triggers/governs/composed_of/associated_with。
          血缘方向：produces/flows_to/transforms/triggers/governs 是 from=来源→to=结果；
          derived_from/depends_on/consumes/composed_of 是 from=结果→to=来源（反向）。
        - 只做用户要求的最小改动，不要重建整图；拿不准就少改，并在 reply 里说明。
        - 若用户请求与子图无关或无法定位，返回空 ops 并在 reply 里说明。
        """;

    private final OntologyModelRepository modelRepo;
    private final GraphPatchService patchService;
    /** 向量检索的候选嵌入上限：按度数取前 N 个节点嵌入(枢纽优先)，控嵌入开销。 */
    private static final int VECTOR_CANDIDATES = 1000;

    private final ExtractionLlmService extractionLlmService;
    private final com.tuiyan.backend.service.indexing.EmbeddingClient embeddingClient;
    private final com.tuiyan.backend.service.indexing.GraphNodeIndexService nodeIndex;

    public GraphChatEditService(OntologyModelRepository modelRepo, GraphPatchService patchService,
                                ExtractionLlmService extractionLlmService,
                                com.tuiyan.backend.service.indexing.EmbeddingClient embeddingClient,
                                com.tuiyan.backend.service.indexing.GraphNodeIndexService nodeIndex) {
        this.modelRepo = modelRepo;
        this.extractionLlmService = extractionLlmService;
        this.patchService = patchService;
        this.embeddingClient = embeddingClient;
        this.nodeIndex = nodeIndex;
    }

    /** 改图结果：LLM 说明 + 应用统计 + 实际应用的 ops（供前端局部反映，不必重载整图）+ 最新计数。 */
    public record EditResult(String reply, int applied, int skipped,
                             List<Map<String, Object>> ops, long nodeCount, long edgeCount) {}

    public EditResult chatEdit(String modelId, String message, List<String> scopeNodeIds,
                               String modelOverride, String configId) throws IOException {
        if (!modelRepo.existsInWorkspace(modelId)) {
            throw new ResourceNotFoundException("模型不存在或不属于当前工作空间：" + modelId);
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("请描述你想怎么修改血缘图");
        }
        // 上下文来源(办法 B 优先)：前端传来「当前可见子图」的节点范围时，直接按 id 取(不读整图)；
        // 否则退回「读整图 + 关键词检索」。用户正看着的邻域多半就是要改的部分，既省读整图又更精准。
        String context;
        if (scopeNodeIds != null && !scopeNodeIds.isEmpty()) {
            List<Map<String, Object>> nodes = modelRepo.nodeHeadsByIds(modelId, scopeNodeIds);
            List<Map<String, Object>> edges = modelRepo.edgesAmongIds(modelId, scopeNodeIds);
            context = formatContext(nodes, edges);
        } else {
            // 无可见范围(全图检索)：优先用「持久化节点向量索引」——纯索引级检索，不加载整图。
            context = buildContextFromIndex(modelId, message);
            if (context == null) {
                // 索引未建/未命中：退回读整图 + 向量(配了 embedding)或关键词检索。
                OntologyModelRepository.NodesAndEdges g = modelRepo.loadGraphForVersion(modelId);
                context = embeddingClient.isConfigured()
                        ? buildContextVector(g, message)
                        : buildContext(g, message);
            }
        }

        String user = "【当前相关子图】\n" + context + "\n\n【用户请求】\n" + message.trim()
                + "\n\n请输出精确修改的 JSON。";
        JsonNode out;
        try {
            out = extractionLlmService.extractRaw(user, EDIT_SYSTEM, modelOverride, configId);
        } catch (Exception e) {
            log.warn("[graph-chat-edit] LLM 调用失败: {}", e.toString());
            throw new IllegalStateException("改图助手调用失败，请稍后重试");
        }

        String reply = out.path("reply").asText("");
        List<Map<String, Object>> ops = toOpList(out.path("ops"));
        GraphPatchService.PatchResult r = patchService.apply(modelId, ops);
        if (reply.isBlank()) {
            reply = r.applied() > 0 ? "已应用 " + r.applied() + " 处修改" : "未做修改";
        }
        return new EditResult(reply, r.applied(), r.skipped(), ops, r.nodeCount(), r.edgeCount());
    }

    static List<Map<String, Object>> toOpList(JsonNode opsNode) {
        List<Map<String, Object>> ops = new ArrayList<>();
        if (!opsNode.isArray()) return ops;
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        for (JsonNode op : opsNode) {
            if (!op.isObject()) continue;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = om.convertValue(op, Map.class);
                ops.add(m);
            } catch (RuntimeException ignore) { /* 单条畸形跳过 */ }
        }
        return ops;
    }

    /**
     * 检索与用户请求相关的子图：按请求里的关键词匹配节点 label（+其一跳邻居），控在 {@link #CONTEXT_NODES} 内；
     * 匹配不到时退回度数最高的若干枢纽节点作锚点。只发相关部分，故整图再大也不撑 prompt。
     */
    static String buildContext(OntologyModelRepository.NodesAndEdges g, String message) {
        List<Map<String, Object>> nodes = g.nodes();
        List<Map<String, Object>> edges = g.edges();
        Map<String, Map<String, Object>> byId = new java.util.HashMap<>();
        for (Map<String, Object> n : nodes) byId.put(str(n.get("id")), n);

        // 关键词：请求里长度≥2 的词块（中英文皆按非分隔切）
        Set<String> kws = new LinkedHashSet<>();
        for (String tok : message.toLowerCase(Locale.ROOT).split("[\\s,，。、;；:：\"'（）()\\[\\]/\\\\]+")) {
            if (tok.length() >= 2) kws.add(tok);
        }

        Set<String> keep = new LinkedHashSet<>();
        for (Map<String, Object> n : nodes) {
            if (keep.size() >= CONTEXT_NODES) break;
            String label = str(n.get("label")).toLowerCase(Locale.ROOT);
            for (String kw : kws) {
                if (!label.isEmpty() && (label.contains(kw) || kw.contains(label))) { keep.add(str(n.get("id"))); break; }
            }
        }
        // 一跳邻居（让 LLM 能看到匹配节点的现有连接，改方向/连边更准）
        if (!keep.isEmpty()) {
            Set<String> seed = new LinkedHashSet<>(keep);
            for (Map<String, Object> e : edges) {
                if (keep.size() >= CONTEXT_NODES) break;
                String f = str(e.get("from")), t = str(e.get("to"));
                if (seed.contains(f) && byId.containsKey(t)) keep.add(t);
                if (seed.contains(t) && byId.containsKey(f)) keep.add(f);
            }
        }
        // 匹配不到：退回度数最高的枢纽节点作锚点
        if (keep.isEmpty()) {
            Map<String, Integer> deg = new java.util.HashMap<>();
            for (Map<String, Object> e : edges) {
                deg.merge(str(e.get("from")), 1, Integer::sum);
                deg.merge(str(e.get("to")), 1, Integer::sum);
            }
            nodes.stream()
                    .sorted((a, b) -> Integer.compare(deg.getOrDefault(str(b.get("id")), 0), deg.getOrDefault(str(a.get("id")), 0)))
                    .limit(CONTEXT_NODES)
                    .forEach(n -> keep.add(str(n.get("id"))));
        }

        List<Map<String, Object>> selNodes = new ArrayList<>();
        for (String id : keep) { Map<String, Object> n = byId.get(id); if (n != null) selNodes.add(n); }
        List<Map<String, Object>> selEdges = new ArrayList<>();
        for (Map<String, Object> e : edges) {
            if (keep.contains(str(e.get("from"))) && keep.contains(str(e.get("to")))) selEdges.add(e);
        }
        return formatContext(selNodes, selEdges);
    }

    /**
     * 用持久化节点向量索引取相关子图上下文：语义检索命中的节点 id（+一跳邻居）→ 按 id 取头/边，
     * <b>全程不加载整图</b>，任意规模都是索引级。索引未建/未命中/未配置 → 返回 null(调用方降级)。
     */
    private String buildContextFromIndex(String modelId, String message) {
        if (!nodeIndex.isConfigured()) return null;
        List<String> hits = nodeIndex.search(modelId, message, CONTEXT_NODES);
        if (hits == null || hits.isEmpty()) return null;
        LinkedHashSet<String> keep = new LinkedHashSet<>(hits);
        // 一跳邻居：让 LLM 看到命中节点的现有连接（改方向/连边更准）
        List<Map<String, Object>> incident = modelRepo.edgesIncident(modelId, hits);
        for (Map<String, Object> e : incident) {
            if (keep.size() >= CONTEXT_NODES) break;
            keep.add(str(e.get("from")));
            keep.add(str(e.get("to")));
        }
        List<Map<String, Object>> nodes = modelRepo.nodeHeadsByIds(modelId, keep);
        List<Map<String, Object>> edges = modelRepo.edgesAmongIds(modelId, keep);
        return formatContext(nodes, edges);
    }

    /**
     * 向量语义检索：把请求与节点 label 一起 embedding，按余弦相似度取最相关的若干节点(+一跳邻居)作上下文。
     * 认同义/模糊意图——能找到与请求语义相关但字面不匹配的节点(如请求「账单」命中图里的「发票」)。
     * 候选按度数取前 {@link #VECTOR_CANDIDATES} 个控嵌入开销；任何嵌入失败降级到关键词检索。
     */
    private String buildContextVector(OntologyModelRepository.NodesAndEdges g, String message) {
        List<Map<String, Object>> nodes = g.nodes();
        List<Map<String, Object>> edges = g.edges();
        try {
            // 候选：按度数降序取前 N 个有 label 的节点(枢纽优先)
            Map<String, Integer> deg = new java.util.HashMap<>();
            for (Map<String, Object> e : edges) {
                deg.merge(str(e.get("from")), 1, Integer::sum);
                deg.merge(str(e.get("to")), 1, Integer::sum);
            }
            List<Map<String, Object>> cand = new ArrayList<>();
            for (Map<String, Object> n : nodes) if (!str(n.get("label")).isBlank()) cand.add(n);
            cand.sort((a, b) -> Integer.compare(
                    deg.getOrDefault(str(b.get("id")), 0), deg.getOrDefault(str(a.get("id")), 0)));
            if (cand.size() > VECTOR_CANDIDATES) cand = cand.subList(0, VECTOR_CANDIDATES);
            if (cand.isEmpty()) return buildContext(g, message);

            float[] qv = embeddingClient.embed(message);
            List<String> labels = new ArrayList<>(cand.size());
            for (Map<String, Object> n : cand) labels.add(str(n.get("label")));
            List<float[]> vecs = embeddingClient.embedBatch(labels);
            if (vecs.size() != cand.size()) return buildContext(g, message);

            // 按余弦相似度降序取 top-K 节点
            Integer[] order = new Integer[cand.size()];
            for (int i = 0; i < order.length; i++) order[i] = i;
            double[] sim = new double[cand.size()];
            for (int i = 0; i < cand.size(); i++) sim[i] = cosine(qv, vecs.get(i));
            java.util.Arrays.sort(order, (a, b) -> Double.compare(sim[b], sim[a]));

            Set<String> keep = new LinkedHashSet<>();
            for (int k = 0; k < order.length && keep.size() < CONTEXT_NODES; k++) {
                keep.add(str(cand.get(order[k]).get("id")));
            }
            // 一跳邻居，让 LLM 看到相关节点的现有连接
            Map<String, Map<String, Object>> byId = new java.util.HashMap<>();
            for (Map<String, Object> n : nodes) byId.put(str(n.get("id")), n);
            Set<String> seed = new LinkedHashSet<>(keep);
            for (Map<String, Object> e : edges) {
                if (keep.size() >= CONTEXT_NODES) break;
                String f = str(e.get("from")), t = str(e.get("to"));
                if (seed.contains(f) && byId.containsKey(t)) keep.add(t);
                if (seed.contains(t) && byId.containsKey(f)) keep.add(f);
            }
            List<Map<String, Object>> selNodes = new ArrayList<>();
            for (String id : keep) { Map<String, Object> n = byId.get(id); if (n != null) selNodes.add(n); }
            List<Map<String, Object>> selEdges = new ArrayList<>();
            for (Map<String, Object> e : edges) {
                if (keep.contains(str(e.get("from"))) && keep.contains(str(e.get("to")))) selEdges.add(e);
            }
            return formatContext(selNodes, selEdges);
        } catch (Exception ex) {
            log.warn("[graph-chat-edit] 向量检索失败，降级关键词: {}", ex.toString());
            return buildContext(g, message);
        }
    }

    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        return (na == 0 || nb == 0) ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** 把选定的节点/边格式化成喂 LLM 的相关子图文本（两条检索路共用）。 */
    static String formatContext(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        StringBuilder sb = new StringBuilder("节点(id | label | type | domain):\n");
        int nc = 0;
        for (Map<String, Object> n : nodes) {
            if (nc++ >= CONTEXT_NODES) break;
            sb.append(str(n.get("id"))).append(" | ").append(str(n.get("label")))
              .append(" | ").append(str(n.get("type")))
              .append(" | ").append(str(n.get("domain"))).append('\n');
        }
        sb.append("\n边(id | from | to | rel_type | label):\n");
        int ec = 0;
        for (Map<String, Object> e : edges) {
            if (ec++ >= CONTEXT_EDGES) break;
            sb.append(str(e.get("id"))).append(" | ").append(str(e.get("from"))).append(" | ").append(str(e.get("to")))
              .append(" | ").append(str(e.get("rel_type"))).append(" | ").append(str(e.get("label"))).append('\n');
        }
        return sb.toString();
    }

    private static String str(Object v) { return v == null ? "" : String.valueOf(v); }
}
