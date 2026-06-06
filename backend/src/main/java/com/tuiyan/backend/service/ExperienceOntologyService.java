package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tuiyan.backend.repository.ExperienceRepository;
import com.tuiyan.backend.support.IdSaltRewriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 经验库 → 本体血缘图：以「整个工作空间的经验库文件」为输入构建本体血缘图的专用 facade。
 * <p>定位与 {@link SchemaOntologyService} 互补——后者从数据库 schema 出图，本服务从经验库文档出图。
 * 在新的数据流里：
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

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExperienceRepository repo;
    private final ExtractionLlmService extractionLlmService;

    public ExperienceOntologyService(ExperienceRepository repo,
                                     ExtractionLlmService extractionLlmService) {
        this.repo = repo;
        this.extractionLlmService = extractionLlmService;
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
        long chars = 0;
        if (userHint != null && !userHint.isBlank()) {
            combined.append("【用户额外要求】").append(userHint.trim()).append("\n\n");
        }
        for (Map<String, Object> exp : all) {
            if (used >= MAX_EXPERIENCES) break;
            String title = String.valueOf(exp.getOrDefault("title", "未命名经验"));
            Object contentObj = exp.get("content");
            String content = contentObj == null ? "" : String.valueOf(contentObj);
            if (content.isBlank()) continue;
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
        step.emit("load_done", "已聚合 " + used + " 篇经验（约 " + chars + " 字符）");

        step.emit("llm_call", "正在调用大模型从经验库构建本体血缘图…");
        JsonNode draft = extractionLlmService.extractOntologyFromSources(
                combined.toString(), null, modelOverride, configId);

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
