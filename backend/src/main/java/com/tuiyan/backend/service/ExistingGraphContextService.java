package com.tuiyan.backend.service;

import com.tuiyan.backend.model.OntologyModel;
import com.tuiyan.backend.repository.OntologyModelRepository;
import com.tuiyan.backend.service.indexing.EmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 既有图检索回灌（Schema-First 建图第三阶段：让增量建图连回既有图谱）。
 * <p>增量建图时，新经验的抽取此前对既有图一无所知——新内容里没重新提到的既有概念就连不上去
 * （新经验讲「发货」但没提「订单」，发货节点就孤立）。本服务在增量抽取时，从既有模型图里检索出
 * 与本批 topically 相关的概念，作为「已有图谱概念，请复用这些名字连接过去」注入 prompt。
 * <p>配合第一阶段的持久词表（保证命名一致 → 前端按 label 归并把新旧同名节点重连），
 * 跨构建的血缘链就不会在每次增量时断裂。
 * <ul>
 *   <li>embedding 已配置：embed 既有节点 label（按度数上限），per-batch 用 batch 文本向量
 *       检索 top-K 最相关的既有概念；</li>
 *   <li>embedding 未配置 / 检索失败：回退注入「度数最高的枢纽概念」（图的骨干，静态但仍有助连接）。</li>
 * </ul>
 * 全程失败容忍：构建 context 失败返回空 context（preface 恒为空串），不阻塞建图。
 */
@Service
public class ExistingGraphContextService {

    private static final Logger log = LoggerFactory.getLogger(ExistingGraphContextService.class);

    /** 参与嵌入的既有节点上限（按度数降序取），控制增量建图的嵌入开销。 */
    private static final int MAX_INDEXED = 600;
    /** 每批注入的相关既有概念数。 */
    private static final int PER_BATCH_TOPK = 30;
    /** batch 文本用于检索的截断长度（embedding 有 token 上限，且首部最能代表主题）。 */
    private static final int QUERY_TEXT_CHARS = 2000;
    /** 余弦相似度下限：低于此的既有概念与本批无关，不注入（避免噪声干扰抽取）。 */
    private static final double SIM_FLOOR = 0.72;

    private final EmbeddingClient embeddingClient;
    private final OntologyModelRepository modelRepo;

    public ExistingGraphContextService(EmbeddingClient embeddingClient, OntologyModelRepository modelRepo) {
        this.embeddingClient = embeddingClient;
        this.modelRepo = modelRepo;
    }

    /** 既有图里的一个概念：规范名 + 类型 + 度数（连接的边数，度数高者为图的枢纽）。 */
    public record Concept(String label, String type, int degree) {}

    /**
     * 一次增量建图的既有图上下文：预载概念 + （可选）其 label 嵌入。
     * {@link #prefaceFor} 按批返回要注入的既有概念 preface。empty() 表示无上下文（preface 恒空）。
     */
    public final class Context {
        private final List<Concept> concepts;      // 按度数降序
        private final List<float[]> embeddings;    // 与 concepts 平行；null = 未嵌入（走枢纽回退）

        private Context(List<Concept> concepts, List<float[]> embeddings) {
            this.concepts = concepts;
            this.embeddings = embeddings;
        }

        public boolean isEmpty() { return concepts.isEmpty(); }

        /**
         * 为一批抽取生成「已有图谱相关概念」preface。
         * 有嵌入时按 batch 文本向量检索 top-K 最相关；否则回退度数最高的枢纽概念。
         * 任何嵌入异常静默回退枢纽，绝不抛出。
         */
        public String prefaceFor(String batchText) {
            if (concepts.isEmpty()) return "";
            List<Concept> picked;
            if (embeddings != null && batchText != null && !batchText.isBlank()) {
                try {
                    String q = batchText.length() > QUERY_TEXT_CHARS
                            ? batchText.substring(0, QUERY_TEXT_CHARS) : batchText;
                    float[] qv = embeddingClient.embed(q);
                    List<Integer> idx = rankTopK(qv, embeddings, PER_BATCH_TOPK, SIM_FLOOR);
                    picked = new ArrayList<>(idx.size());
                    for (int i : idx) picked.add(concepts.get(i));
                } catch (Exception e) {
                    log.warn("[graph-ctx] 批检索嵌入失败,回退枢纽概念: {}", e.toString());
                    picked = hubs();
                }
            } else {
                picked = hubs();
            }
            return render(picked);
        }

        /** 度数最高的前 K 个枢纽概念（嵌入不可用时的回退）。concepts 已按度数降序，直接取头部。 */
        private List<Concept> hubs() {
            return concepts.subList(0, Math.min(PER_BATCH_TOPK, concepts.size()));
        }
    }

    /**
     * 从既有模型构建上下文。模型不存在 / 无节点 / 加载失败 → 空 context。
     * embedding 已配置时嵌入既有 label（按度数上限），否则只带概念（走枢纽回退）。
     */
    public Context build(String modelId) {
        List<Concept> empty = List.of();
        try {
            OntologyModel m = modelRepo.get(modelId);
            if (m == null || m.getGraphData() == null) return new Context(empty, null);
            List<Map<String, Object>> nodes = m.getGraphData().getNodes();
            List<Map<String, Object>> edges = m.getGraphData().getEdges();
            if (nodes == null || nodes.isEmpty()) return new Context(empty, null);

            Map<String, Integer> degree = new HashMap<>();
            if (edges != null) {
                for (Map<String, Object> e : edges) {
                    degree.merge(String.valueOf(e.get("from")), 1, Integer::sum);
                    degree.merge(String.valueOf(e.get("to")), 1, Integer::sum);
                }
            }
            List<Concept> concepts = new ArrayList<>();
            for (Map<String, Object> n : nodes) {
                String label = n.get("label") == null ? "" : String.valueOf(n.get("label"));
                if (label.isBlank()) continue;
                String type = n.get("type") == null ? "" : String.valueOf(n.get("type"));
                concepts.add(new Concept(label, type, degree.getOrDefault(String.valueOf(n.get("id")), 0)));
            }
            if (concepts.isEmpty()) return new Context(empty, null);
            // 按度数降序：既是枢纽回退的顺序，也是超上限时的取舍依据（枢纽最值得回灌）
            concepts.sort((a, b) -> Integer.compare(b.degree(), a.degree()));
            if (concepts.size() > MAX_INDEXED) concepts = new ArrayList<>(concepts.subList(0, MAX_INDEXED));

            List<float[]> embeddings = null;
            if (embeddingClient.isConfigured()) {
                try {
                    List<String> labels = new ArrayList<>(concepts.size());
                    for (Concept c : concepts) labels.add(c.label());
                    List<float[]> vecs = embeddingClient.embedBatch(labels);
                    if (vecs.size() == concepts.size()) embeddings = vecs;
                } catch (Exception e) {
                    log.warn("[graph-ctx] 既有图 label 嵌入失败,增量回灌走枢纽概念: {}", e.toString());
                }
            }
            log.info("[graph-ctx] 既有图上下文就绪: {} 个概念, 检索模式={}",
                    concepts.size(), embeddings != null ? "向量" : "枢纽度数");
            return new Context(concepts, embeddings);
        } catch (Exception e) {
            log.warn("[graph-ctx] 构建既有图上下文失败(增量不回灌): {}", e.toString());
            return new Context(empty, null);
        }
    }

    /** 渲染既有概念注入块。空列表返回空串。 */
    private static String render(List<Concept> picked) {
        if (picked == null || picked.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("【已有血缘图中的相关概念】以下概念已存在于当前图谱中。若本段内容涉及它们,")
          .append("请直接使用这些规范名称(而不是造新名),使新抽取的实体/关系连接到已有图谱:\n");
        for (Concept c : picked) {
            sb.append("- ").append(c.label());
            if (c.type() != null && !c.type().isBlank()) sb.append(" (").append(c.type()).append(")");
            sb.append('\n');
        }
        sb.append('\n');
        return sb.toString();
    }

    /**
     * 纯排序：返回 conceptVecs 中与 queryVec 余弦最高的前 k 个下标（降序），过滤低于 floor 的。
     * 抽成静态便于离线单测。
     */
    static List<Integer> rankTopK(float[] queryVec, List<float[]> conceptVecs, int k, double floor) {
        List<Integer> idx = new ArrayList<>();
        List<Double> sims = new ArrayList<>();
        for (int i = 0; i < conceptVecs.size(); i++) {
            double s = cosine(queryVec, conceptVecs.get(i));
            if (s >= floor) { idx.add(i); sims.add(s); }
        }
        List<Integer> order = new ArrayList<>(idx.size());
        for (int i = 0; i < idx.size(); i++) order.add(i);
        order.sort((a, b) -> Double.compare(sims.get(b), sims.get(a)));
        List<Integer> out = new ArrayList<>(Math.min(k, order.size()));
        for (int i = 0; i < Math.min(k, order.size()); i++) out.add(idx.get(order.get(i)));
        return out;
    }

    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
