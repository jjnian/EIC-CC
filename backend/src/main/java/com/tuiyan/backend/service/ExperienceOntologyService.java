package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tuiyan.backend.repository.ExperienceRepository;
import com.tuiyan.backend.service.agent.ExplorationAgentService;
import com.tuiyan.backend.support.IdSaltRewriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 经验库 → 本体血缘图：以「整个工作空间的经验库文件」为输入构建本体血缘图的专用 facade。
 * <p>这是本体血缘图的唯一构建入口（数据库 schema 直出图的旧链路已移除）。在新的数据流里：
 * <ul>
 *   <li><b>本体血缘图由经验库文件构建</b>：把当前工作空间下所有经验文档聚合成长文本，
 *       交给文档抽取管线 {@link ExtractionLlmService} 抽出节点 / 边；</li>
 *   <li><b>数据源只负责供血</b>：数据源结构（DDL/schema）先沉淀成经验库文件参与建图，
 *       建好的图节点再由数据源绑定真实数据来源，本服务本身不直接读数据库。</li>
 * </ul>
 */
@Service
public class ExperienceOntologyService {

    private static final Logger log = LoggerFactory.getLogger(ExperienceOntologyService.class);

    /** 单次建图最多聚合的经验数量，避免超大工作空间一次性塞爆 prompt。 */
    private static final int MAX_EXPERIENCES = 60;
    /** 单篇经验正文截断上限（字符），过长正文按头部截断，整体切片仍由下游抽取管线负责。 */
    private static final int MAX_CHARS_PER_EXPERIENCE = 40_000;

    /** 探索文档里内嵌结构化图片段的注释块:{@code <!-- EXPLORE_GRAPH {json} EXPLORE_GRAPH -->}。 */
    private static final Pattern GRAPH_BLOCK = Pattern.compile(
            "(?s)<!--\\s*" + ExplorationAgentService.GRAPH_MARKER + "\\s*(\\{.*?\\})\\s*"
            + ExplorationAgentService.GRAPH_MARKER + "\\s*-->");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExperienceRepository repo;
    private final ExtractionLlmService extractionLlmService;
    private final ExtractionGraphMerger merger;

    public ExperienceOntologyService(ExperienceRepository repo,
                                     ExtractionLlmService extractionLlmService,
                                     ExtractionGraphMerger merger) {
        this.repo = repo;
        this.extractionLlmService = extractionLlmService;
        this.merger = merger;
    }

    /** 进度回调，用于 SSE 上报「读经验库 / 调 LLM / 后处理」等阶段。 */
    public interface StepSink {
        void emit(String key, String label);
    }

    /** 提取结果：与 chat / extract / schema 端兼容的 {nodes, edges, reply, salt} 形状。 */
    public record ExtractResult(JsonNode payload, String salt,
                                int sourceCount, int nodeCount, int edgeCount) {}

    /**
     * 从当前工作空间的整个经验库构建本体血缘图。
     * <p>调用前需保证 {@code WorkspaceContext} 已设置（仓储按工作空间隔离）。
     * 返回的 JSON 满足 {@code {nodes:[], edges:[], reply:""}}，可被前端 import 流程直接合并。
     *
     * @param modelOverride 可选模型覆盖
     * @param configId      可选 LLM 配置 id
     * @param userHint      用户额外提示（如「重点关注审批链路」）
     * @param step          进度回调
     */
    public ExtractResult extractFromWorkspace(String modelOverride,
                                              String configId,
                                              String userHint,
                                              StepSink step) throws IOException {
        step.emit("load_start", "正在读取当前工作空间经验库…");
        List<Map<String, Object>> all = repo.list();

        StringBuilder combined = new StringBuilder();
        int used = 0;
        int skippedByCap = 0;
        int preGraphCount = 0;
        long chars = 0;
        JsonNode preExtracted = null; // 探索文档直采的结构化图片段(免 LLM 重抽),累积后与 LLM 草稿合并
        if (userHint != null && !userHint.isBlank()) {
            combined.append("【用户额外要求】").append(userHint.trim()).append("\n\n");
        }
        for (Map<String, Object> exp : all) {
            String title = String.valueOf(exp.getOrDefault("title", "未命名经验"));
            Object contentObj = exp.get("content");
            String content = contentObj == null ? "" : String.valueOf(contentObj);
            if (content.isBlank()) continue;
            if (used >= MAX_EXPERIENCES) { skippedByCap++; continue; }
            // #1 结构化直连:抽出探索文档内嵌的图片段并从正文剥离,改走结构化合并而非散文重抽
            JsonNode frag = extractGraphFragment(content);
            if (frag != null) {
                JsonNode prefixed = merger.prefixChunkIds(frag, "ex" + preGraphCount + "_");
                preExtracted = (preExtracted == null) ? prefixed
                        : merger.mergeExtractionByLabel(preExtracted, prefixed);
                preGraphCount++;
                content = stripGraphFragment(content);
                if (content.isBlank()) { used++; continue; } // 纯结构化文档,无散文可喂 LLM
            }
            if (content.length() > MAX_CHARS_PER_EXPERIENCE) {
                content = content.substring(0, MAX_CHARS_PER_EXPERIENCE) + "\n…（正文过长已截断）";
            }
            combined.append("# 经验：").append(title).append("\n\n")
                    .append(content).append("\n\n");
            used++;
            chars += content.length();
        }

        if (used == 0) {
            throw new IllegalStateException(
                    "当前工作空间经验库为空（或经验均无正文），请先在经验库中创建/上传经验文件，或把数据源结构导出到经验库供血后再建图。");
        }
        step.emit("load_done", "已聚合 " + used + " 篇经验（约 " + chars + " 字符）"
                + (preGraphCount > 0 ? "；其中 " + preGraphCount + " 篇含探索直采的结构化图谱(直接合并)" : "")
                + (skippedByCap > 0 ? "；超出单次建图上限，已跳过较早的 " + skippedByCap + " 篇" : ""));

        JsonNode draft;
        String combinedText = combined.toString();
        if (combinedText.isBlank() && preExtracted != null) {
            // 全部为探索直采的纯结构化文档,无散文可喂 LLM:直接用结构化图谱
            step.emit("llm_call", "经验均为探索直采的结构化图谱,跳过大模型抽取,直接合并…");
            draft = emptyGraph();
        } else {
            step.emit("llm_call", "正在调用大模型从经验库构建本体血缘图…");
            draft = extractionLlmService.extractOntologyFromSources(
                    combinedText, null, modelOverride, configId);
        }

        // 把探索直采的结构化图谱并入 LLM 草稿(按 label 去重合并),再统一校验
        if (preExtracted != null) {
            step.emit("normalizing", "正在合并探索直采的结构化图谱…");
            draft = merger.sanitizeGraph(merger.mergeExtractionByLabel(draft, preExtracted));
        }

        step.emit("normalizing", "正在整理抽取结果、消解 id 冲突…");
        String salt = Long.toString(System.currentTimeMillis(), 36);
        JsonNode rewritten = IdSaltRewriter.applyImportSalt(draft, salt);
        ObjectNode out = (ObjectNode) rewritten;

        // 整张图同源于经验库，统一打来源标记，便于与数据源直出的图区分、并为后续「数据源供血绑定」留追溯入口
        stampSource(out.path("nodes"));
        stampSource(out.path("edges"));

        int nodes = out.path("nodes").isArray() ? out.path("nodes").size() : 0;
        int edges = out.path("edges").isArray() ? out.path("edges").size() : 0;
        out.put("reply", buildReplyText(used, nodes, edges));

        step.emit("done", "完成：从 " + used + " 篇经验生成 " + nodes + " 个节点 / " + edges + " 条边");
        return new ExtractResult(out, salt, used, nodes, edges);
    }

    /**
     * 抽出探索文档内嵌的结构化图片段({nodes,edges}),转成抽取管线的 {add_nodes,add_edges} 形状;
     * 没有或解析失败返回 null。
     */
    private JsonNode extractGraphFragment(String content) {
        if (content == null) return null;
        java.util.regex.Matcher m = GRAPH_BLOCK.matcher(content);
        if (!m.find()) return null;
        try {
            JsonNode parsed = objectMapper.readTree(m.group(1));
            ObjectNode frag = objectMapper.createObjectNode();
            frag.set("add_nodes", parsed.has("nodes") ? parsed.get("nodes") : objectMapper.createArrayNode());
            frag.set("add_edges", parsed.has("edges") ? parsed.get("edges") : objectMapper.createArrayNode());
            return frag;
        } catch (Exception e) {
            log.warn("[exp-ontology] 解析探索结构化图片段失败,忽略: {}", e.toString());
            return null;
        }
    }

    /** 从正文里剥掉结构化图片段注释块(已单独走结构化合并,避免再被 LLM 当散文重抽一遍)。 */
    private static String stripGraphFragment(String content) {
        return content == null ? null : GRAPH_BLOCK.matcher(content).replaceAll("").trim();
    }

    /** 空图骨架 {add_nodes:[], add_edges:[]},作为无 LLM 草稿时的合并基底。 */
    private JsonNode emptyGraph() {
        ObjectNode g = objectMapper.createObjectNode();
        g.set("add_nodes", objectMapper.createArrayNode());
        g.set("add_edges", objectMapper.createArrayNode());
        return g;
    }

    /** 给 nodes/edges 数组里每个对象补 derived_source=经验库，标明该图由经验库文件构建。 */
    private void stampSource(JsonNode arr) {
        if (!(arr instanceof ArrayNode list)) return;
        for (JsonNode n : list) {
            if (n instanceof ObjectNode obj) obj.put("derived_source", "经验库");
        }
    }

    private static String buildReplyText(int sourceCount, int nodeCount, int edgeCount) {
        return String.format(
                "已基于当前工作空间经验库的 %d 篇经验文件构建本体血缘图：%d 个节点 / %d 条关系。\n\n"
                        + "本图由经验库文件构建；数据源不再直接出图，而是先把结构（DDL/schema）沉淀到经验库参与建图，"
                        + "再为建好的图节点绑定真实数据来源供血。",
                sourceCount, nodeCount, edgeCount);
    }
}
