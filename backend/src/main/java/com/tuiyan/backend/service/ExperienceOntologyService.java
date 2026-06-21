package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tuiyan.backend.repository.ExperienceRepository;
import com.tuiyan.backend.service.agent.ExplorationAgentService;
import com.tuiyan.backend.support.IdSaltRewriter;
import com.tuiyan.backend.support.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    /** 单次建图最多聚合的经验数量：从 60 提高到 500，作为防止极端工作空间拉爆的安全上限（超出会上报并跳过）。 */
    private static final int MAX_EXPERIENCES = 500;
    /** 单篇经验正文截断上限（字符），过长正文按头部截断，整体切片仍由下游抽取管线负责。 */
    private static final int MAX_CHARS_PER_EXPERIENCE = 40_000;
    /** 每批聚合的正文字符上限，约对应一次 LLM 抽取调用；批与批之间并行跑、按 label 增量合并。 */
    private static final int BATCH_CHAR_BUDGET = 30_000;
    /** 并行批数上限，避免一次性打爆 LLM 限流。 */
    private static final int MAX_PARALLEL_BATCHES = 4;

    /** 探索文档里内嵌结构化图片段的注释块:{@code <!-- EXPLORE_GRAPH {json} EXPLORE_GRAPH -->}。 */
    private static final Pattern GRAPH_BLOCK = Pattern.compile(
            "(?s)<!--\\s*" + ExplorationAgentService.GRAPH_MARKER + "\\s*(\\{.*?\\})\\s*"
            + ExplorationAgentService.GRAPH_MARKER + "\\s*-->");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExperienceRepository repo;
    private final ExtractionLlmService extractionLlmService;
    private final ExtractionGraphMerger merger;
    /** 并行建图专用有界线程池（守护线程）：各批抽取在此并发跑，避免占用 predictionExecutor 造成自饿死。 */
    private final ExecutorService batchExecutor = Executors.newFixedThreadPool(MAX_PARALLEL_BATCHES, r -> {
        Thread t = new Thread(r, "exp-ontology-batch");
        t.setDaemon(true);
        return t;
    });

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
        final String workspaceId = WorkspaceContext.get();
        List<Map<String, Object>> all = repo.list();

        String hintPrefix = (userHint != null && !userHint.isBlank())
                ? "【用户额外要求】" + userHint.trim() + "\n\n" : "";

        List<String> proseDocs = new ArrayList<>();     // 待喂 LLM 的散文经验，逐篇成段
        int used = 0;
        int skippedByCap = 0;
        int preGraphCount = 0;
        long chars = 0;
        JsonNode preExtracted = null; // 探索文档直采的结构化图片段(免 LLM 重抽),累积后与 LLM 草稿合并
        for (Map<String, Object> exp : all) {
            String title = String.valueOf(exp.getOrDefault("title", "未命名经验"));
            Object contentObj = exp.get("content");
            String content = contentObj == null ? "" : String.valueOf(contentObj);
            if (content.isBlank()) continue;
            // #1 结构化直连:抽出探索文档内嵌的图片段并从正文剥离,改走结构化合并而非散文重抽（不占建图上限）
            JsonNode frag = extractGraphFragment(content);
            if (frag != null) {
                JsonNode prefixed = merger.prefixChunkIds(frag, "ex" + preGraphCount + "_");
                preExtracted = (preExtracted == null) ? prefixed
                        : merger.mergeExtractionByLabel(preExtracted, prefixed);
                preGraphCount++;
                content = stripGraphFragment(content);
                if (content.isBlank()) { used++; continue; } // 纯结构化文档,无散文可喂 LLM
            }
            if (used >= MAX_EXPERIENCES) { skippedByCap++; continue; }
            if (content.length() > MAX_CHARS_PER_EXPERIENCE) {
                content = content.substring(0, MAX_CHARS_PER_EXPERIENCE) + "\n…（正文过长已截断）";
            }
            proseDocs.add("# 经验：" + title + "\n\n" + content);
            used++;
            chars += content.length();
        }

        if (used == 0 && preExtracted == null) {
            throw new IllegalStateException(
                    "当前工作空间经验库为空（或经验均无正文），请先在经验库中创建/上传经验文件，或把数据源结构导出到经验库供血后再建图。");
        }

        // 把散文按字符预算分批，每批约对应一次 LLM 抽取调用，批间并行、按 label 增量合并
        List<String> batches = groupIntoBatches(proseDocs, hintPrefix);
        step.emit("load_done", "已聚合 " + used + " 篇经验（约 " + chars + " 字符）"
                + (preGraphCount > 0 ? "；其中 " + preGraphCount + " 篇含探索直采的结构化图谱(直接合并)" : "")
                + (batches.size() > 1 ? "；将分 " + batches.size() + " 批并行建图" : "")
                + (skippedByCap > 0 ? "；超出单次建图上限 " + MAX_EXPERIENCES + " 篇，已跳过 " + skippedByCap + " 篇" : ""));

        JsonNode draft;
        if (batches.isEmpty()) {
            // 全部为探索直采的纯结构化文档,无散文可喂 LLM:直接用结构化图谱
            step.emit("llm_call", "经验均为探索直采的结构化图谱,跳过大模型抽取,直接合并…");
            draft = emptyGraph();
        } else {
            step.emit("llm_call", "正在并行调用大模型分 " + batches.size() + " 批从经验库构建本体血缘图…");
            draft = extractBatchesParallel(batches, modelOverride, configId, workspaceId, step);
        }

        // 把探索直采的结构化图谱并入 LLM 草稿(按 label 去重合并),再统一校验
        if (preExtracted != null) {
            step.emit("normalizing", "正在合并探索直采的结构化图谱…");
            draft = merger.mergeExtractionByLabel(draft, preExtracted);
        }
        draft = merger.sanitizeGraph(draft);

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
     * 把逐篇散文经验按 {@link #BATCH_CHAR_BUDGET} 贪心分批；每批前缀用户额外要求，保证每批都遵循。
     * 单篇超预算时自成一批（其超长部分由下游抽取管线再切片）。
     */
    private List<String> groupIntoBatches(List<String> docs, String hintPrefix) {
        List<String> batches = new ArrayList<>();
        if (docs.isEmpty()) return batches;
        StringBuilder cur = new StringBuilder();
        for (String doc : docs) {
            if (cur.length() > 0 && cur.length() + doc.length() > BATCH_CHAR_BUDGET) {
                batches.add(hintPrefix + cur);
                cur = new StringBuilder();
            }
            cur.append(doc).append("\n\n");
        }
        if (cur.length() > 0) batches.add(hintPrefix + cur);
        return batches;
    }

    /**
     * 并行跑各批抽取（{@link #batchExecutor}），各批独立加前缀避免 id 冲突，按 label 增量合并；
     * 单批失败不影响整体（记日志后跳过）。全部失败才抛错。进度在主线程按完成数上报，避免并发写 SSE。
     */
    private JsonNode extractBatchesParallel(List<String> batches, String modelOverride, String configId,
                                            String workspaceId, StepSink step) {
        int n = batches.size();
        List<CompletableFuture<JsonNode>> futures = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            final String batchText = batches.get(i);
            final int idx = i;
            futures.add(CompletableFuture.supplyAsync(() -> {
                boolean ctxSet = false;
                try {
                    // 后台线程没有 WorkspaceInterceptor 的 ThreadLocal，手动透传工作空间
                    if (workspaceId != null && !workspaceId.isBlank()) {
                        WorkspaceContext.set(workspaceId);
                        ctxSet = true;
                    }
                    JsonNode part = extractionLlmService.extractOntologyFromSources(
                            batchText, null, modelOverride, configId);
                    // 各批内部独立命名 id，加批前缀避免跨批冲突，再交由 label 合并去重
                    return merger.prefixChunkIds(part, "b" + idx + "_");
                } catch (Exception e) {
                    log.warn("[exp-ontology] 第 {}/{} 批建图失败，跳过：{}", idx + 1, n, e.toString());
                    return null;
                } finally {
                    if (ctxSet) WorkspaceContext.clear();
                }
            }, batchExecutor));
        }

        JsonNode merged = null;
        int done = 0;
        for (CompletableFuture<JsonNode> f : futures) {
            JsonNode part;
            try { part = f.join(); } catch (Exception e) { part = null; }
            done++;
            step.emit("llm_batch", "本体抽取进度 " + done + "/" + n + " 批…");
            if (part == null) continue;
            merged = (merged == null) ? part : merger.mergeExtractionByLabel(merged, part);
        }
        if (merged == null) {
            throw new IllegalStateException("大模型建图全部批次失败，请检查模型配置后重试");
        }
        return merged;
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
