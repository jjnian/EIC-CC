package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tuiyan.backend.repository.ExperienceRepository;
import com.tuiyan.backend.service.agent.ExplorationAgentService;
import com.tuiyan.backend.service.llm.prompt.ExtractPrompts;
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
    private final OntologyVocabService vocabService;
    private final EntityAlignmentService alignmentService;
    private final ExistingGraphContextService graphContextService;
    private final com.tuiyan.backend.repository.ExperienceFolderRepository folderRepo;
    private final com.tuiyan.backend.repository.ModelBuildSourceRepository buildSourceRepo;
    private final com.tuiyan.backend.repository.OntologyModelRepository modelRepo;
    /** 并行建图专用有界线程池（守护线程）：各批抽取在此并发跑，避免占用 appTaskExecutor 造成自饿死。 */
    private final ExecutorService batchExecutor = Executors.newFixedThreadPool(MAX_PARALLEL_BATCHES, r -> {
        Thread t = new Thread(r, "exp-ontology-batch");
        t.setDaemon(true);
        return t;
    });

    public ExperienceOntologyService(ExperienceRepository repo,
                                     ExtractionLlmService extractionLlmService,
                                     ExtractionGraphMerger merger,
                                     OntologyVocabService vocabService,
                                     EntityAlignmentService alignmentService,
                                     ExistingGraphContextService graphContextService,
                                     com.tuiyan.backend.repository.ExperienceFolderRepository folderRepo,
                                     com.tuiyan.backend.repository.ModelBuildSourceRepository buildSourceRepo,
                                     com.tuiyan.backend.repository.OntologyModelRepository modelRepo) {
        this.repo = repo;
        this.extractionLlmService = extractionLlmService;
        this.merger = merger;
        this.vocabService = vocabService;
        this.alignmentService = alignmentService;
        this.graphContextService = graphContextService;
        this.folderRepo = folderRepo;
        this.buildSourceRepo = buildSourceRepo;
        this.modelRepo = modelRepo;
    }

    /** 进度回调，用于 SSE 上报「读经验库 / 调 LLM / 后处理」等阶段。 */
    public interface StepSink {
        void emit(String key, String label);
    }

    /** 提取结果：与 chat / extract / schema 端兼容的 {nodes, edges, reply, salt} 形状。 */
    public record ExtractResult(JsonNode payload, String salt,
                                int sourceCount, int nodeCount, int edgeCount) {}

    /** 单篇待抽取经验：id + 标题 + 剥离图片段后的正文 + 是否 DDL 导出（走 schema 专用抽取规则）。 */
    /** 无文件夹归类的经验归入的默认域名。 */
    private static final String DEFAULT_DOMAIN = "未分域";

    private record ExpDoc(String id, String title, String content, boolean ddl, String domain) {}

    /** 一次 LLM 抽取批：拼好的输入文本 + 批内经验标题（供来源标记）/ id（供失败回滚清单） + 是否 DDL 批。 */
    private record Batch(String text, List<String> titles, List<String> expIds, boolean ddl, String domain) {}

    /**
     * 从当前工作空间的经验库构建本体血缘图。
     * <p>调用前需保证 {@code WorkspaceContext} 已设置（仓储按工作空间隔离）。
     * 返回的 JSON 满足 {@code {nodes:[], edges:[], reply:""}}，可被前端 import 流程直接合并。
     * <p>建图规则按经验来源细分：
     * <ul>
     *   <li>DDL 导出经验（origin=ddl）单独分批，用 schema 专用抽取规则（映射确定、反幻觉更严）；</li>
     *   <li>探索文档内嵌的结构化图片段直连合并，不经 LLM 重抽；</li>
     *   <li>其余散文经验按字符预算分批并行抽取。</li>
     * </ul>
     *
     * @param modelOverride      可选模型覆盖
     * @param configId           可选 LLM 配置 id
     * @param userHint           用户额外提示（如「重点关注审批链路」）
     * @param experienceIds      可选经验范围：非空时只用这些经验建图，空/null = 全部
     * @param incrementalModelId 非空时启用增量建图：按该模型的构建记录跳过内容未变化的经验，
     *                           只抽新增/变更部分（海量经验的常规更新方式）。结果由前端合并进该模型
     *                           并回写构建记录（{@code POST /api/ontology-models/{id}/build-sources}）。
     * @param step               进度回调
     */
    public ExtractResult extractFromWorkspace(String modelOverride,
                                              String configId,
                                              String userHint,
                                              List<String> experienceIds,
                                              String incrementalModelId,
                                              StepSink step) throws IOException {
        step.emit("load_start", "正在读取当前工作空间经验库…");
        final String workspaceId = WorkspaceContext.get();
        List<Map<String, Object>> all = repo.list();
        // 文件夹 id → 名称：把经验按其所属文件夹划为「领域(domain)」，供域感知分批与节点打标。
        // 分域让抽取域内聚焦(域内深抽)、且每个节点能精确归属到一个业务领域,便于前端分组/折叠。
        Map<String, String> folderNames = new java.util.HashMap<>();
        try {
            for (Map<String, Object> f : folderRepo.listMaps(workspaceId)) {
                Object fid = f.get("id");
                Object fname = f.get("name");
                if (fid != null && fname != null) folderNames.put(String.valueOf(fid), String.valueOf(fname));
            }
        } catch (Exception e) {
            log.warn("[exp-ontology] 载入文件夹名失败(域标签降级为未分域): {}", e.toString());
        }
        // 全空间现存经验 id 快照（须在范围过滤前取）：供增量模式检测
        // “构建记录里有、经验库里已删除”的孤儿来源
        java.util.Set<String> liveExpIds = new java.util.HashSet<>();
        for (Map<String, Object> e : all) liveExpIds.add(String.valueOf(e.get("id")));
        // 用户指定范围时只保留命中的经验（按 id 过滤，顺序沿用库序）
        if (experienceIds != null && !experienceIds.isEmpty()) {
            java.util.Set<String> wanted = new java.util.HashSet<>(experienceIds);
            all = all.stream()
                    .filter(e -> wanted.contains(String.valueOf(e.get("id"))))
                    .toList();
            step.emit("load_scope", "已按所选范围过滤：命中 " + all.size() + "/" + experienceIds.size() + " 篇经验");
        }

        String hintPrefix = (userHint != null && !userHint.isBlank())
                ? "【用户额外要求】" + userHint.trim() + "\n\n" : "";

        // 增量建图：读该模型的构建记录，内容哈希未变化的经验直接跳过（只抽新增/变更）。
        // 目标模型必须存在且属于当前工作空间（get 已按 ws 过滤），防跨空间读构建记录。
        boolean incremental = incrementalModelId != null && !incrementalModelId.isBlank();
        if (incremental && modelRepo.get(incrementalModelId) == null) {
            throw new IllegalStateException("增量建图的目标模型不存在或不属于当前工作空间：" + incrementalModelId);
        }
        Map<String, String> prevHashes = incremental
                ? buildSourceRepo.hashesOf(incrementalModelId) : Map.of();

        // 孤儿来源检测：上次建图引用、如今已从经验库删除的经验。其贡献的节点/边仍留在模型里
        // 且血缘指向一个不存在的来源——这里只报告不代删（清理与否是用户对图的决策）。
        // 构建记录特意不清理：孤儿未处理前，每次增量建图都持续提醒。
        List<String> removedSources = prevHashes.keySet().stream()
                .filter(expId -> !liveExpIds.contains(expId))
                .sorted()
                .toList();
        if (!removedSources.isEmpty()) {
            String preview = String.join("、",
                    removedSources.subList(0, Math.min(5, removedSources.size())))
                    + (removedSources.size() > 5 ? " …" : "");
            step.emit("orphan_sources", "⚠ 上次建图引用的 " + removedSources.size()
                    + " 篇经验已被删除（" + preview + "），其贡献的节点/边仍留在图中，血缘已失效，请确认是否清理");
        }

        List<ExpDoc> docs = new ArrayList<>();          // 待喂 LLM 的经验，逐篇携带标题/是否 DDL
        Map<String, String> manifest = new java.util.LinkedHashMap<>(); // 本轮扫过的经验 id → 内容哈希
        int used = 0;
        int skippedByCap = 0;
        int skippedUnchanged = 0;
        int preGraphCount = 0;
        int ddlCount = 0;
        long chars = 0;
        // 词表规约采样：覆盖「本轮所有非空经验」的标题+首段（含增量跳过的，规约要看整个库而不只是变化的部分）
        List<OntologyVocabService.DocSample> vocabSamples = new ArrayList<>();
        JsonNode preExtracted = null; // 探索文档直采的结构化图片段(免 LLM 重抽),累积后与 LLM 草稿合并
        for (Map<String, Object> exp : all) {
            String expId = String.valueOf(exp.get("id"));
            String title = String.valueOf(exp.getOrDefault("title", "未命名经验"));
            boolean ddl = "ddl".equalsIgnoreCase(String.valueOf(exp.getOrDefault("origin", "")));
            Object folderId = exp.get("folderId");
            String domain = folderId == null ? DEFAULT_DOMAIN
                    : folderNames.getOrDefault(String.valueOf(folderId), DEFAULT_DOMAIN);
            Object contentObj = exp.get("content");
            String content = contentObj == null ? "" : String.valueOf(contentObj);
            if (content.isBlank()) continue;
            vocabSamples.add(OntologyVocabService.sampleOf(title, content));
            String hash = sha256Hex(title + "\u0000" + content);
            manifest.put(expId, hash);
            if (incremental && hash.equals(prevHashes.get(expId))) { skippedUnchanged++; continue; }
            // #1 结构化直连:抽出探索文档内嵌的图片段并从正文剥离,改走结构化合并而非散文重抽（不占建图上限）
            JsonNode frag = extractGraphFragment(content);
            if (frag != null) {
                JsonNode prefixed = merger.prefixChunkIds(frag, "ex" + preGraphCount + "_");
                stampWhenBlank(prefixed.path("add_nodes"), "经验：" + title);
                stampWhenBlank(prefixed.path("add_edges"), "经验：" + title);
                stampDomainWhenBlank(prefixed.path("add_nodes"), domain);
                stampDomainWhenBlank(prefixed.path("add_edges"), domain);
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
            docs.add(new ExpDoc(expId, title, content, ddl, domain));
            if (ddl) ddlCount++;
            used++;
            chars += content.length();
        }

        if (used == 0 && preExtracted == null) {
            if (incremental && skippedUnchanged > 0) {
                throw new IllegalStateException("增量建图：所选 " + skippedUnchanged
                        + " 篇经验自上次建图以来均无变化，无需重建。如需全部重抽请关闭增量选项。");
            }
            throw new IllegalStateException(
                    "当前工作空间经验库为空（或经验均无正文），请先在经验库中创建/上传经验文件，或把数据源结构导出到经验库供血后再建图。");
        }

        // 域感知分批：先按领域(文件夹)分组、组内再按 char 预算打包，批次不跨域 →
        // 抽取域内聚焦(域内深抽)，且每个节点能精确归属到它来源的领域。散文与 DDL 仍分开
        // （DDL 批走 schema 专用抽取规则）。每批约对应一次 LLM 调用，批间并行、按 label 增量合并。
        List<Batch> batches = new ArrayList<>();
        Map<String, List<ExpDoc>> proseByDomain = new java.util.LinkedHashMap<>();
        Map<String, List<ExpDoc>> ddlByDomain = new java.util.LinkedHashMap<>();
        for (ExpDoc d : docs) {
            (d.ddl() ? ddlByDomain : proseByDomain)
                    .computeIfAbsent(d.domain(), k -> new ArrayList<>()).add(d);
        }
        for (Map.Entry<String, List<ExpDoc>> en : proseByDomain.entrySet()) {
            batches.addAll(groupIntoBatches(en.getValue(), hintPrefix, false, en.getKey()));
        }
        for (Map.Entry<String, List<ExpDoc>> en : ddlByDomain.entrySet()) {
            batches.addAll(groupIntoBatches(en.getValue(), hintPrefix, true, en.getKey()));
        }
        step.emit("load_done", "已聚合 " + used + " 篇经验（约 " + chars + " 字符）"
                + (skippedUnchanged > 0 ? "；增量模式：跳过 " + skippedUnchanged + " 篇内容未变化的经验" : "")
                + (preGraphCount > 0 ? "；其中 " + preGraphCount + " 篇含探索直采的结构化图谱(直接合并)" : "")
                + (ddlCount > 0 ? "；" + ddlCount + " 篇为数据源 DDL 导出(按 schema 专用规则抽取)" : "")
                + (batches.size() > 1 ? "；将分 " + batches.size() + " 批并行建图" : "")
                + (skippedByCap > 0 ? "；超出单次建图上限 " + MAX_EXPERIENCES + " 篇，已跳过 " + skippedByCap + " 篇" : ""));

        // 词表规约（Schema-First）：分批抽取前先建/复用一份受控词表，注入各批 prompt 统一命名，
        // 消除「客户/顾客/Customer 在不同批各造一个节点」的跨批命名漂移。增量建图复用旧词表，
        // 全量建图重建覆盖；构建失败降级为无骨架（vocab.isEmpty()），不阻塞主流程。
        OntologyVocabService.Vocab vocab = OntologyVocabService.Vocab.empty();
        if (!batches.isEmpty()) {
            step.emit("vocab", incremental ? "正在复用/更新本体词表骨架…" : "正在从经验库规约本体词表骨架（统一命名口径）…");
            vocab = vocabService.loadOrBuild(workspaceId, vocabSamples, incremental, modelOverride, configId);
            if (!vocab.isEmpty()) {
                step.emit("vocab_done", "已就绪词表骨架：" + vocab.entries().size() + " 个受控概念，将据此统一各批命名");
            } else {
                step.emit("vocab_skip", "未生成词表骨架，本次按无规约建图（不影响结果，仅跨批同义词去重稍弱）");
            }
        }

        // 既有图检索回灌（第三阶段，仅增量建图）：从要合并进的既有模型里检索相关概念注入各批抽取，
        // 让新内容连回既有图谱，不在每次增量时断链。构建失败返回空 context（不回灌），不阻塞建图。
        ExistingGraphContextService.Context graphCtx = null;
        if (incremental && !batches.isEmpty()) {
            step.emit("graph_ctx", "正在载入既有图谱作为增量回灌上下文…");
            graphCtx = graphContextService.build(incrementalModelId);
            step.emit("graph_ctx_done", graphCtx.isEmpty()
                    ? "既有图谱为空或不可用，本次增量不做回灌"
                    : "已就绪既有图谱回灌上下文，将为各批注入相关既有概念以连回图谱");
        }

        JsonNode draft;
        int failedBatches = 0;
        if (batches.isEmpty()) {
            // 全部为探索直采的纯结构化文档,无散文可喂 LLM:直接用结构化图谱
            step.emit("llm_call", "经验均为探索直采的结构化图谱,跳过大模型抽取,直接合并…");
            draft = emptyGraph();
        } else {
            step.emit("llm_call", "正在并行调用大模型分 " + batches.size() + " 批从经验库构建本体血缘图…");
            BatchOutcome outcome = extractBatchesParallel(batches, vocab, graphCtx, modelOverride, configId, workspaceId, step);
            draft = outcome.graph();
            failedBatches = outcome.failed();
            if (failedBatches > 0) {
                // 失败批的经验从构建清单剔除：不回写哈希，下次（增量）建图仍会重抽，避免漏抽
                outcome.failedExpIds().forEach(manifest::remove);
                step.emit("partial", failedBatches + "/" + outcome.total()
                        + " 批建图失败，已用成功批次合并，结果可能不完整，可重试");
            }
        }

        // 把探索直采的结构化图谱并入 LLM 草稿(按 label 去重合并),再统一校验
        if (preExtracted != null) {
            step.emit("normalizing", "正在合并探索直采的结构化图谱…");
            // 结构化片段也按词表归一,使其与 LLM 草稿共享同一命名口径后再合并(否则同义节点合不到一起)
            vocabService.normalize(preExtracted, vocab);
            // 片段内部先自去重（如多张表映射到同一业务名、FK 片段两表同注释），避免带着重复节点并进草稿
            preExtracted = merger.dedupeWithin(preExtracted);
            draft = merger.mergeExtractionByLabel(draft, preExtracted);
        }
        // 向量兜底同义消解(第二阶段):词表没收录的同义词(收款/回款)在这里靠向量召回 + LLM 仲裁折叠。
        // 放在跨批连边之前:先把同义节点并成一个,连边才连到规范节点而非散落的重复节点上。
        step.emit("aligning", "正在做向量兜底的同义实体对齐…");
        EntityAlignmentService.AlignResult align = alignmentService.align(draft, modelOverride, configId);
        draft = align.graph();
        if (align.any()) {
            step.emit("align_done", "向量对齐:合并 " + align.foldedGroups() + " 组同义实体(共折叠 "
                    + align.foldedNodes() + " 个重复节点)");
            // 发现的同义反哺词表:下次建图走确定性快路径,无需再花向量 + LLM
            vocabService.recordDiscoveredAliases(workspaceId, align.discoveredAliases());
        } else {
            step.emit("align_skip", "向量对齐:未发现需要合并的同义实体");
        }
        // 跨批连边:各批并行抽取时互相看不见对方的实体,批与批之间的关系天然缺失。
        // 用实体清单追加一轮轻量 LLM 调用,只补跨批组关系(source=inferred,失败不影响主结果)。
        draft = crossBatchLinkPass(draft, modelOverride, configId, step);
        draft = merger.sanitizeGraph(draft);

        step.emit("normalizing", "正在整理抽取结果、消解 id 冲突…");
        String salt = Long.toString(System.currentTimeMillis(), 36);
        JsonNode rewritten = IdSaltRewriter.applyImportSalt(draft, salt);
        ObjectNode out = (ObjectNode) rewritten;

        // 兜底来源标记：批级/片段级已精确标注具体经验，剩余空白统一落「经验库」，
        // 便于与其它来源的图区分、并为后续「数据源供血绑定」留追溯入口
        stampWhenBlank(out.path("nodes"), "经验库");
        stampWhenBlank(out.path("edges"), "经验库");

        int nodes = out.path("nodes").isArray() ? out.path("nodes").size() : 0;
        int edges = out.path("edges").isArray() ? out.path("edges").size() : 0;
        String reply = buildReplyText(used, nodes, edges, failedBatches);
        if (skippedUnchanged > 0) {
            reply += "\n\n（增量建图：跳过 " + skippedUnchanged
                    + " 篇内容未变化的经验；本次结果为增量，合并到当前模型即可，旧节点不受影响）";
        }
        if (!removedSources.isEmpty()) {
            reply += "\n\n⚠ 检测到 " + removedSources.size()
                    + " 篇上次建图引用的经验已从经验库删除，其贡献的节点/边仍留在图中且血缘来源已失效。"
                    + "建议在图上核对「来源=经验库」且已无对应经验的节点，确认后手动清理。";
            ArrayNode removedArr = objectMapper.createArrayNode();
            removedSources.forEach(removedArr::add);
            out.set("removedSources", removedArr);
        }
        out.put("reply", reply);
        out.put("incremental", incremental);
        out.put("skippedUnchanged", skippedUnchanged);
        // 构建清单：本轮扫过的全部经验（含跳过的）的内容哈希。前端确认合并后回写为构建记录，
        // 下次增量据此跳过；用户丢弃结果则不回写，变更不会被漏掉。
        ArrayNode manifestArr = objectMapper.createArrayNode();
        for (Map.Entry<String, String> en : manifest.entrySet()) {
            ObjectNode m = objectMapper.createObjectNode();
            m.put("experienceId", en.getKey());
            m.put("contentHash", en.getValue());
            manifestArr.add(m);
        }
        out.set("manifest", manifestArr);

        step.emit("done", "完成：从 " + used + " 篇经验生成 " + nodes + " 个节点 / " + edges + " 条边"
                + (failedBatches > 0 ? "（" + failedBatches + " 批失败，结果可能不完整）" : ""));
        return new ExtractResult(out, salt, used, nodes, edges);
    }

    /**
     * 把某个领域内的逐篇经验按 {@link #BATCH_CHAR_BUDGET} 贪心分批（同域不跨批混入其它域）；
     * 每批前缀用户额外要求 + 领域聚焦提示，保证每批都遵循。单篇超预算时自成一批（其超长部分由
     * 下游抽取管线再切片）。批内记录经验标题（供来源标记回溯）与所属领域（供节点打标）。
     */
    private List<Batch> groupIntoBatches(List<ExpDoc> docs, String hintPrefix, boolean ddl, String domain) {
        List<Batch> batches = new ArrayList<>();
        if (docs.isEmpty()) return batches;
        // 领域聚焦提示：非「未分域」时告诉 LLM 本批内容的业务领域，聚焦域内实体、少漂到无关领域
        String domainNote = (domain == null || domain.isBlank() || DEFAULT_DOMAIN.equals(domain))
                ? "" : "【本批内容属于业务领域「" + domain + "」，请聚焦该领域的实体与关系】\n\n";
        String prefix = hintPrefix + domainNote;
        StringBuilder cur = new StringBuilder();
        List<String> titles = new ArrayList<>();
        List<String> expIds = new ArrayList<>();
        for (ExpDoc doc : docs) {
            String text = "# 经验：" + doc.title() + "\n\n" + doc.content();
            if (cur.length() > 0 && cur.length() + text.length() > BATCH_CHAR_BUDGET) {
                batches.add(new Batch(prefix + cur, List.copyOf(titles), List.copyOf(expIds), ddl, domain));
                cur = new StringBuilder();
                titles.clear();
                expIds.clear();
            }
            cur.append(text).append("\n\n");
            titles.add(doc.title());
            expIds.add(doc.id());
        }
        if (cur.length() > 0) batches.add(new Batch(prefix + cur, List.copyOf(titles), List.copyOf(expIds), ddl, domain));
        return batches;
    }

    /**
     * 并行跑各批抽取（{@link #batchExecutor}），各批独立加前缀避免 id 冲突，按 label 增量合并；
     * 单批失败不影响整体（记日志后跳过）。全部失败才抛错。进度在主线程按完成数上报，避免并发写 SSE。
     */
    /** 并行建图结果：合并后的图 + 总批数 + 失败批数 + 失败批覆盖的经验 id（须从构建清单剔除，避免下次增量漏抽）。 */
    private record BatchOutcome(JsonNode graph, int total, int failed, java.util.Set<String> failedExpIds) {}

    private BatchOutcome extractBatchesParallel(List<Batch> batches, OntologyVocabService.Vocab vocab,
                                                ExistingGraphContextService.Context graphCtx,
                                                String modelOverride, String configId,
                                                String workspaceId, StepSink step) {
        int n = batches.size();
        // 词表 preface：注入每批抽取的 user prompt 顶部，统一命名口径（空词表返回空串，行为回退到无骨架）
        final String vocabPreface = vocabService.preface(vocab);
        final OntologyVocabService.Vocab vocabRef = vocab;
        final ExistingGraphContextService.Context ctxRef = graphCtx;
        List<CompletableFuture<JsonNode>> futures = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            final Batch batch = batches.get(i);
            final int idx = i;
            futures.add(CompletableFuture.supplyAsync(() -> {
                boolean ctxSet = false;
                try {
                    // 后台线程没有 WorkspaceInterceptor 的 ThreadLocal，手动透传工作空间
                    if (workspaceId != null && !workspaceId.isBlank()) {
                        WorkspaceContext.set(workspaceId);
                        ctxSet = true;
                    }
                    // 词表规约 + 既有图检索回灌，一并前置到本批（增量时 ctxRef 非空才检索既有概念）
                    String preface = vocabPreface;
                    if (ctxRef != null && !ctxRef.isEmpty()) {
                        preface = preface + ctxRef.prefaceFor(batch.text());
                    }
                    JsonNode part = extractBatchWithRetry(batch, preface, modelOverride, configId, idx, n);
                    if (part == null) return null;
                    // 服务端确定性归一：LLM 对词表 preface 的遵循不可靠，这里把命中别名的 label
                    // 强制改写为规范名 + 补 type，归一后的同名节点由随后的 label+type 合并自然折叠
                    vocabService.normalize(part, vocabRef);
                    // 批内自去重：词表归一可能把同批的「顾客/客户」都改成「客户」，跨批合并只折叠 b 对 a、
                    // 兜不住单批内部（尤其单批建图时该批直接作基底），这里先把批内同名重复折掉
                    part = merger.dedupeWithin(part);
                    // 各批内部独立命名 id，加批前缀避免跨批冲突，再交由 label 合并去重
                    part = merger.prefixChunkIds(part, "b" + idx + "_");
                    // 来源标记精确到本批经验：单篇批直接落该经验标题，多篇批概括
                    String label = batchSourceLabel(batch.titles());
                    if (label != null) {
                        stampWhenBlank(part.path("add_nodes"), label);
                        stampWhenBlank(part.path("add_edges"), label);
                    }
                    // 领域标记：本批不跨域，故本批产出的每个节点/边都归属该域(供前端分组/着色/折叠)
                    stampDomainWhenBlank(part.path("add_nodes"), batch.domain());
                    stampDomainWhenBlank(part.path("add_edges"), batch.domain());
                    return part;
                } finally {
                    if (ctxSet) WorkspaceContext.clear();
                }
            }, batchExecutor));
        }

        JsonNode merged = null;
        int done = 0;
        int failed = 0;
        java.util.Set<String> failedExpIds = new java.util.HashSet<>();
        for (int i = 0; i < futures.size(); i++) {
            JsonNode part;
            try { part = futures.get(i).join(); } catch (Exception e) { part = null; }
            done++;
            if (part == null) {
                failed++;
                failedExpIds.addAll(batches.get(i).expIds());
            }
            step.emit("llm_batch", "本体抽取进度 " + done + "/" + n + " 批"
                    + (failed > 0 ? "（" + failed + " 批失败）" : "") + "…");
            if (part == null) continue;
            merged = (merged == null) ? part : merger.mergeExtractionByLabel(merged, part);
        }
        if (merged == null) {
            throw new IllegalStateException("大模型建图全部批次失败，请检查模型配置后重试");
        }
        return new BatchOutcome(merged, n, failed, failedExpIds);
    }

    /**
     * 单批抽取（失败自动重试一次）：DDL 批用 schema 专用 system prompt，散文批用通用抽取 prompt。
     * {@code vocabPreface} 非空时前置到正文，作为受控词表规约（统一命名）。
     * 两次都失败返回 null，由上层按"部分失败"处理。
     */
    private JsonNode extractBatchWithRetry(Batch batch, String vocabPreface,
                                           String modelOverride, String configId, int idx, int total) {
        String systemPrompt = batch.ddl() ? ExtractPrompts.SCHEMA_TO_ONTOLOGY_SYSTEM : null;
        String text = (vocabPreface == null || vocabPreface.isBlank())
                ? batch.text() : vocabPreface + batch.text();
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return extractionLlmService.extractOntologyFromSources(
                        text, null, modelOverride, configId, systemPrompt);
            } catch (Exception e) {
                log.warn("[exp-ontology] 第 {}/{} 批建图第 {} 次尝试失败：{}", idx + 1, total, attempt, e.toString());
            }
        }
        return null;
    }

    // 跨批连边:实体清单上限(防撑爆 context)与已有关系对上限
    private static final int CROSS_LINK_MAX_ENTITIES = 200;
    private static final int CROSS_LINK_MAX_PAIRS = 400;

    /**
     * 跨批连边 pass:并行分批抽取的各批互相看不见对方实体,跨批关系天然缺失(血缘链在批边界断裂)。
     * 把合并后草稿的「实体清单(id/label/type/批组) + 已有关系对」喂给 LLM,只补跨批组的缺失关系。
     * <p>批组取节点 id 的批前缀(b0_/b1_/ex0_,由 {@code prefixChunkIds} 保证);组数 &lt;2 直接跳过。
     * 服务端二次过滤:只收两端都存在于清单、且分属不同批组的边;来源强制 source=inferred、
     * confidence 封顶 0.55、打 cross_batch 标记,交用户在图上复核。任何失败只记日志,不影响主结果。
     */
    private JsonNode crossBatchLinkPass(JsonNode draft, String modelOverride, String configId, StepSink step) {
        if (draft == null || !draft.isObject()) return draft;
        JsonNode nodes = draft.path("add_nodes");
        JsonNode edges = draft.path("add_edges");
        if (!nodes.isArray() || nodes.size() < 2) return draft;

        // 节点 id → 批组(id 首个 '_' 前的前缀)
        Map<String, String> groupOf = new java.util.HashMap<>();
        java.util.Set<String> groups = new java.util.HashSet<>();
        for (JsonNode n : nodes) {
            String id = n.path("id").asText("");
            if (id.isEmpty()) continue;
            int us = id.indexOf('_');
            String g = us > 0 ? id.substring(0, us) : id;
            groupOf.put(id, g);
            groups.add(g);
        }
        if (groups.size() < 2) return draft; // 单批建图没有跨批断裂

        // 实体清单超上限时按度数降序取前 N：枢纽节点承载的跨批关系最多，比"取前 N 个"更值得保留
        Map<String, Integer> degree = new java.util.HashMap<>();
        if (edges.isArray()) {
            for (JsonNode e : edges) {
                degree.merge(e.path("from").asText(""), 1, Integer::sum);
                degree.merge(e.path("to").asText(""), 1, Integer::sum);
            }
        }
        List<JsonNode> ordered = new ArrayList<>();
        for (JsonNode nd : nodes) if (!nd.path("id").asText("").isEmpty()) ordered.add(nd);
        if (ordered.size() > CROSS_LINK_MAX_ENTITIES) {
            ordered.sort((a, b) -> Integer.compare(
                    degree.getOrDefault(b.path("id").asText(""), 0),
                    degree.getOrDefault(a.path("id").asText(""), 0)));
        }

        StringBuilder roster = new StringBuilder("【实体清单】(id | label | type | 批组)\n");
        int listed = 0;
        for (JsonNode n : ordered) {
            if (listed >= CROSS_LINK_MAX_ENTITIES) break;
            String id = n.path("id").asText("");
            roster.append(id).append(" | ").append(n.path("label").asText(""))
                  .append(" | ").append(n.path("type").asText(""))
                  .append(" | ").append(groupOf.get(id)).append('\n');
            listed++;
        }
        boolean truncated = ordered.size() > listed;
        roster.append("\n【已存在的关系对】(请勿重复提出)\n");
        int pairs = 0;
        if (edges.isArray()) {
            for (JsonNode e : edges) {
                if (pairs >= CROSS_LINK_MAX_PAIRS) { roster.append("…(更多已省略)\n"); break; }
                roster.append(e.path("from").asText("")).append(" -> ")
                      .append(e.path("to").asText("")).append('\n');
                pairs++;
            }
        }
        step.emit("cross_link", "检测到 " + groups.size() + " 个独立抽取批组,正在补齐跨批关系…"
                + (truncated ? "(实体较多,按度数取前 " + CROSS_LINK_MAX_ENTITIES + " 个枢纽节点参与连边)" : ""));

        try {
            JsonNode result = extractionLlmService.extractRaw(
                    roster.toString(), ExtractPrompts.CROSS_LINK_SYSTEM, modelOverride, configId);
            ArrayNode accepted = objectMapper.createArrayNode();
            int seq = 0;
            for (JsonNode e : result.path("add_edges")) {
                if (!(e instanceof ObjectNode src)) continue;
                String from = src.path("from").asText("");
                String to = src.path("to").asText("");
                if (!groupOf.containsKey(from) || !groupOf.containsKey(to) || from.equals(to)) continue;
                if (groupOf.get(from).equals(groupOf.get(to))) continue; // 同批关系批内已抽,不收
                ObjectNode copy = src.deepCopy();
                copy.put("id", "xl_" + (seq++)); // 统一重编 id,避免与批内边撞号
                copy.put("source", "inferred");   // 连边 pass 看不到原文,一律降为推断
                copy.put("cross_batch", true);
                double conf = copy.path("confidence").isNumber() ? copy.path("confidence").asDouble() : 0.5;
                copy.put("confidence", Math.min(Math.max(conf, 0.0), 0.55));
                accepted.add(copy);
            }
            if (!accepted.isEmpty() && edges instanceof ArrayNode edgeArr) {
                edgeArr.addAll(accepted); // 重复/悬空由随后的 sanitizeGraph 统一兜底
            }
            step.emit("cross_link_done", accepted.isEmpty()
                    ? "跨批连边:未发现可靠的跨批关系"
                    : "跨批连边:新增 " + accepted.size() + " 条跨批关系(source=inferred,建议人工复核)");
        } catch (Exception e) {
            log.warn("[exp-ontology] 跨批连边失败(忽略,不影响主结果): {}", e.toString());
            step.emit("cross_link_err", "跨批连边失败,已跳过(不影响已抽取的节点与关系)");
        }
        return draft;
    }

    /** SHA-256 十六进制（增量建图的内容指纹）。 */
    private static String sha256Hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // JVM 必带 SHA-256
        }
    }

    /** 本批的来源标记：单篇 → 「经验：标题」；多篇 → 「经验：首篇 等 N 篇」；空批 → null。 */
    private static String batchSourceLabel(List<String> titles) {
        if (titles == null || titles.isEmpty()) return null;
        return titles.size() == 1
                ? "经验：" + titles.get(0)
                : "经验：" + titles.get(0) + " 等 " + titles.size() + " 篇";
    }

    /** 给数组里 derived_source 缺失的节点/边补 label；已有值（如批级/片段级精确标记）不覆盖。 */
    private static void stampWhenBlank(JsonNode arr, String label) {
        if (label == null || !(arr instanceof ArrayNode list)) return;
        for (JsonNode n : list) {
            if (n instanceof ObjectNode obj && obj.path("derived_source").asText("").isBlank()) {
                obj.put("derived_source", label);
            }
        }
    }

    /** 给数组里 domain 缺失的节点/边补业务领域；已有值不覆盖（跨域合并时保留最先归属的域）。 */
    private static void stampDomainWhenBlank(JsonNode arr, String domain) {
        if (domain == null || domain.isBlank() || !(arr instanceof ArrayNode list)) return;
        for (JsonNode n : list) {
            if (n instanceof ObjectNode obj && obj.path("domain").asText("").isBlank()) {
                obj.put("domain", domain);
            }
        }
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

    private static String buildReplyText(int sourceCount, int nodeCount, int edgeCount, int failedBatches) {
        String base = String.format(
                "已基于当前工作空间经验库的 %d 篇经验文件构建本体血缘图：%d 个节点 / %d 条关系。\n\n"
                        + "本图由经验库文件构建；数据源不再直接出图，而是先把结构（DDL/schema）沉淀到经验库参与建图，"
                        + "再为建好的图节点绑定真实数据来源供血。",
                sourceCount, nodeCount, edgeCount);
        if (failedBatches > 0) {
            base += String.format("\n\n⚠️ 有 %d 批经验抽取失败（已跳过），本图可能不完整，可稍后重试以补全。", failedBatches);
        }
        return base;
    }
}
