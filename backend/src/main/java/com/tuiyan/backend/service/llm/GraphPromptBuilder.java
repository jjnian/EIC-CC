package com.tuiyan.backend.service.llm;

import com.tuiyan.backend.model.Constraint;
import com.tuiyan.backend.model.PredictRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 图谱 → LLM Prompt 的纯函数集合：图谱摘要、上下文截断、各业务 user prompt 拼接。
 * <p>不持有任何状态、不发起 IO，单纯把"业务输入"转成"LLM 看的人类可读文本"。
 */
@Component
public class GraphPromptBuilder {

    // 单次推演给 LLM 的图谱预算
    private static final int CONTEXT_NODE_BUDGET = 120;
    private static final int CONTEXT_EDGE_BUDGET = 240;
    // 从 mandatory 节点向外扩散的跳数；3 跳通常足够覆盖核心因果链
    private static final int CONTEXT_HOPS = 3;

    /** 截断结果：保留下来的节点 / 边 + 被丢弃的数量。 */
    public record TruncatedGraph(List<Map<String, Object>> nodes,
                                 List<Map<String, Object>> edges,
                                 int droppedNodes,
                                 int droppedEdges) {}

    /** 推演 prompt 的结构化产物，供 orchestrator 写入 Scenario.rawPrompt 与 LLM 调用复用。 */
    public record PredictPromptArtifact(String system, String user,
                                        boolean truncated, int droppedNodes, int droppedEdges) {}

    /** RAG 检索结果片段 */
    public record RagChunk(String content, String sourceName, double score) {}

    /** chat 用 user prompt：把现有图谱摘要放在前面，作为已知上下文。 */
    public String buildChatPrompt(List<Map<String, Object>> nodes,
                                  List<Map<String, Object>> edges,
                                  String message) {
        return buildChatPrompt(nodes, edges, message, null);
    }

    /** chat 用 user prompt（含 RAG 数据源上下文）。 */
    public String buildChatPrompt(List<Map<String, Object>> nodes,
                                  List<Map<String, Object>> edges,
                                  String message,
                                  List<RagChunk> ragChunks) {
        StringBuilder sb = new StringBuilder();

        // RAG 上下文
        if (ragChunks != null && !ragChunks.isEmpty()) {
            sb.append("相关数据源内容 (按相关度排序，请参考这些内容来提取实体和关系):\n");
            for (RagChunk chunk : ragChunks) {
                sb.append("--- 来源: ").append(chunk.sourceName())
                  .append(" (相关度: ").append(String.format("%.2f", chunk.score())).append(") ---\n");
                sb.append(chunk.content()).append("\n\n");
            }
        }

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

    /**
     * 构造推演用的完整 prompt（system + user）。
     * <p>纯函数，不发起网络调用；orchestrator 把 user 部分原样写入 Scenario.rawPrompt 供前端展示。
     */
    public PredictPromptArtifact buildPredictPrompt(PredictRequest req) {
        int steps = req.getSteps() == null ? 4 : Math.max(1, Math.min(10, req.getSteps()));
        boolean backward = "backward".equalsIgnoreCase(req.getIntent());

        String systemPrompt = backward ? LlmPrompts.PREDICT_BACKWARD_SYSTEM : LlmPrompts.PREDICT_SYSTEM;
        String seedRole = backward ? "目标节点 (seeds，需要溯因的结果)" : "起点节点 (seeds)";
        String taskWord = backward ? "请向上回溯 " : "请向前推演 ";
        String taskUnit = backward ? " 层上游原因" : " 步";

        String graphSummary = summarizeGraph(req.getNodes(), req.getEdges());
        String seedSummary = summarizeSeeds(req.getSeeds(), req.getNodes());
        String rulesSummary = summarizeRules(req.getNodes());
        String constraintsSummary = summarizeConstraints(req.getConstraints(), req.getNodes());

        TruncatedGraph truncated = truncateGraphForContext(
                req.getNodes(), req.getEdges(), req.getSeeds(), req.getConstraints());
        boolean wasTruncated = truncated.droppedNodes() > 0 || truncated.droppedEdges() > 0;
        if (wasTruncated) {
            graphSummary = summarizeGraph(truncated.nodes(), truncated.edges());
        }

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("当前本体图谱：\n").append(graphSummary).append("\n");
        if (wasTruncated) {
            userPrompt.append("(为控制 LLM context 已截断 ")
                      .append(truncated.droppedNodes()).append(" 个节点 / ")
                      .append(truncated.droppedEdges()).append(" 条边，仅保留 seeds、规则、约束目标及其 ")
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
                truncated.droppedNodes(),
                truncated.droppedEdges());
    }

    /** 把图谱压缩成 LLM 能读的可读文本（ASCII 表格风），节点 / 边各一段。 */
    public String summarizeGraph(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
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

    /** 提取规则节点（type=rule）的可读摘要，单独列出方便 LLM 在推演时优先用规则。 */
    public String summarizeRules(List<Map<String, Object>> nodes) {
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
     * <p>probability 模式提示 LLM 把先验作为初值做贝叶斯更新。
     */
    public String summarizeConstraints(List<Constraint> cs, List<Map<String, Object>> nodes) {
        if (cs == null || cs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Constraint c : cs) {
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
    public String summarizeSeeds(List<String> seeds, List<Map<String, Object>> nodes) {
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

    /**
     * 根据预算把大图谱裁剪成"以 seeds / rules / constraints 为中心的 N-hop 邻域"。
     * <p>策略：
     * <ol>
     *   <li>未超预算直接返回原图；</li>
     *   <li>把 seeds、所有 rule 节点、constraints 目标作为 mandatory；</li>
     *   <li>从 mandatory 出发 BFS 向外扩 {@link #CONTEXT_HOPS} 层，按发现顺序加入直到达到节点 budget；</li>
     *   <li>边只保留两端都在 keep 集合里的，不超过边 budget。</li>
     * </ol>
     */
    public TruncatedGraph truncateGraphForContext(List<Map<String, Object>> nodes,
                                                  List<Map<String, Object>> edges,
                                                  List<String> seeds,
                                                  List<Constraint> constraints) {
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
            for (Constraint c : constraints) {
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
}
