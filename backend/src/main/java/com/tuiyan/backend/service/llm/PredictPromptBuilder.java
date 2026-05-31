package com.tuiyan.backend.service.llm;

import com.tuiyan.backend.model.Constraint;
import com.tuiyan.backend.model.PredictRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 推演场景的 prompt 拼接：组合图谱摘要 / seeds / rules / constraints，并按预算截断上下文。
 */
@Component
public class PredictPromptBuilder {

    private final GraphSummarizer graphSummarizer;

    public PredictPromptBuilder(GraphSummarizer graphSummarizer) {
        this.graphSummarizer = graphSummarizer;
    }

    /**
     * 构造推演用的完整 prompt（system + user）。
     * <p>纯函数，不发起网络调用；orchestrator 把 user 部分原样写入 Scenario.rawPrompt 供前端展示。
     */
    public GraphPromptBuilder.PredictPromptArtifact buildPredictPrompt(PredictRequest req) {
        int steps = req.getSteps() == null ? 4 : Math.max(1, Math.min(10, req.getSteps()));
        boolean backward = "backward".equalsIgnoreCase(req.getIntent());

        String systemPrompt = backward ? LlmPrompts.PREDICT_BACKWARD_SYSTEM : LlmPrompts.PREDICT_SYSTEM;
        String seedRole = backward ? "目标节点 (seeds，需要溯因的结果)" : "起点节点 (seeds)";
        String taskWord = backward ? "请向上回溯 " : "请向前推演 ";
        String taskUnit = backward ? " 层上游原因" : " 步";

        String graphSummary = graphSummarizer.summarizeGraph(req.getNodes(), req.getEdges());
        String seedSummary = summarizeSeeds(req.getSeeds(), req.getNodes());
        String rulesSummary = summarizeRules(req.getNodes());
        String constraintsSummary = summarizeConstraints(req.getConstraints(), req.getNodes());

        GraphPromptBuilder.TruncatedGraph truncated = graphSummarizer.truncateGraphForContext(
                req.getNodes(), req.getEdges(), req.getSeeds(), req.getConstraints());
        boolean wasTruncated = truncated.droppedNodes() > 0 || truncated.droppedEdges() > 0;
        if (wasTruncated) {
            graphSummary = graphSummarizer.summarizeGraph(truncated.nodes(), truncated.edges());
        }

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("当前本体图谱：\n").append(graphSummary).append("\n");
        if (wasTruncated) {
            userPrompt.append("(为控制 LLM context 已截断 ")
                      .append(truncated.droppedNodes()).append(" 个节点 / ")
                      .append(truncated.droppedEdges()).append(" 条边，仅保留 seeds、规则、约束目标及其 ")
                      .append(GraphSummarizer.CONTEXT_HOPS).append("-hop 邻域。)\n");
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

        return new GraphPromptBuilder.PredictPromptArtifact(
                systemPrompt,
                userPrompt.toString(),
                wasTruncated,
                truncated.droppedNodes(),
                truncated.droppedEdges());
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
}
