package com.tuiyan.backend.service.chat;

import com.tuiyan.backend.entity.DataSourcePO;
import com.tuiyan.backend.model.MentionRef;
import com.tuiyan.backend.repository.DataSourceRepository;
import com.tuiyan.backend.repository.ExperienceRepository;
import com.tuiyan.backend.service.connector.JdbcConnectorService;
import com.tuiyan.backend.service.indexing.DataSourceIndexService;
import com.tuiyan.backend.service.indexing.ExperienceIndexService;
import com.tuiyan.backend.service.llm.GraphPromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 聊天上下文采集器：把 "为一次对话准备 LLM 上下文" 的取数逻辑从 {@code ChatLlmService} 中分离出来。
 * <p>负责三类来源：①RAG 向量召回（数据源 + 经验库）②数据库 schema 内省 ③@ 显式引用的数据源/经验。
 * 进度通过 {@link ChatStepEmitter} 回传，本类不直接接触 SSE 协议细节。
 */
@Service
public class ChatContextCollector {

    private static final Logger log = LoggerFactory.getLogger(ChatContextCollector.class);

    /** 单条经验注入的正文上限，避免一份大文档把上下文撑爆。 */
    private static final int PINNED_EXP_CONTENT_MAX = 8000;

    private final DataSourceIndexService indexService;
    private final ExperienceIndexService experienceIndexService;
    private final DataSourceRepository dsRepo;
    private final ExperienceRepository experienceRepo;
    private final JdbcConnectorService jdbcConnector;

    public ChatContextCollector(DataSourceIndexService indexService,
                                ExperienceIndexService experienceIndexService,
                                DataSourceRepository dsRepo,
                                ExperienceRepository experienceRepo,
                                JdbcConnectorService jdbcConnector) {
        this.indexService = indexService;
        this.experienceIndexService = experienceIndexService;
        this.dsRepo = dsRepo;
        this.experienceRepo = experienceRepo;
        this.jdbcConnector = jdbcConnector;
    }

    /**
     * RAG 向量召回：从已索引的数据源与经验库中检索与 message 相关的文本块并合并。
     * <p>经验库命中来源名加「经验：」前缀以示区分；任一侧失败不影响另一侧。
     */
    public List<GraphPromptBuilder.RagChunk> searchRag(String wsId, String message, ChatStepEmitter step) {
        List<GraphPromptBuilder.RagChunk> ragChunks = new ArrayList<>();
        if (wsId == null) return ragChunks;

        if (indexService.isConfigured()) {
            try {
                step.step("searching_datasources", "正在检索工作空间数据源…");
                var results = indexService.searchRelevant(wsId, message, 5);
                if (!results.isEmpty()) {
                    for (var r : results) {
                        ragChunks.add(new GraphPromptBuilder.RagChunk(r.content(), r.dataSourceName(), r.score()));
                    }
                    log.info("[LLM-chat-sse] RAG 检索到 {} 条相关文本块", results.size());

                    // 把命中的数据源列出来,让用户看到"根据什么"在构建
                    String sources = results.stream()
                            .map(r -> r.dataSourceName())
                            .filter(Objects::nonNull)
                            .distinct().limit(4)
                            .collect(Collectors.joining("、"));
                    long distinctCount = results.stream()
                            .map(r -> r.dataSourceName())
                            .filter(Objects::nonNull)
                            .distinct().count();
                    String extra = distinctCount > 4 ? " 等 " + distinctCount + " 个" : "";
                    step.step("matched_datasources",
                            "已根据数据源「" + sources + extra + "」匹配 " + results.size() + " 段相关内容");
                } else {
                    step.step("no_match_datasources", "工作空间内暂无相关数据源,按用户描述构建");
                }
            } catch (Exception e) {
                log.warn("[LLM-chat-sse] RAG 检索失败（继续不带 RAG）: {}", e.getMessage());
            }
        }

        if (experienceIndexService.isConfigured()) {
            try {
                step.step("searching_experiences", "正在检索工作空间经验库…");
                var expResults = experienceIndexService.searchRelevant(wsId, message, 3);
                if (!expResults.isEmpty()) {
                    for (var r : expResults) {
                        ragChunks.add(new GraphPromptBuilder.RagChunk(
                                r.content(), "经验：" + r.experienceTitle(), r.score()));
                    }
                    String titles = expResults.stream()
                            .map(ExperienceIndexService.ChunkResult::experienceTitle)
                            .filter(Objects::nonNull)
                            .distinct().limit(4)
                            .collect(Collectors.joining("、"));
                    step.step("matched_experiences",
                            "已根据经验「" + titles + "」匹配 " + expResults.size() + " 段相关内容");
                } else {
                    step.step("no_match_experiences", "工作空间内暂无相关经验");
                }
            } catch (Exception e) {
                log.warn("[LLM-chat-sse] 经验库 RAG 检索失败（继续）: {}", e.getMessage());
            }
        }
        return ragChunks;
    }

    /**
     * 枚举当前工作空间下已接入的 MySQL / PostgreSQL 数据源,拉表清单作为 LLM 结构化输入。
     * <p>对每个 DB 单独 try-catch,坏的跳过；最多读 5 个数据源以控制总耗时；
     * 每个数据源在 SSE 里 emit 一条 step,让用户看到"读取了哪个库的哪些表"。
     */
    public List<GraphPromptBuilder.DbSchema> collectDbSchemas(String wsId, ChatStepEmitter step) {
        if (wsId == null) return List.of();
        List<Map<String, Object>> dsList;
        try {
            dsList = dsRepo.list(wsId);
        } catch (Exception e) {
            log.warn("[LLM-chat-sse] 列举工作空间数据源失败: {}", e.getMessage());
            return List.of();
        }

        List<GraphPromptBuilder.DbSchema> out = new ArrayList<>();
        int probed = 0;
        for (Map<String, Object> ds : dsList) {
            String kind = String.valueOf(ds.get("kind"));
            if (!"mysql".equals(kind) && !"pgsql".equals(kind) && !"oracle".equals(kind)) continue;
            // status=error 的连不上,直接跳过避免拖慢聊天
            Object statusObj = ds.get("status");
            if ("error".equals(String.valueOf(statusObj))) continue;
            if (probed >= 5) break;
            probed++;

            String id = String.valueOf(ds.get("id"));
            String name = String.valueOf(ds.getOrDefault("name", id));
            DataSourcePO po = dsRepo.findById(id);
            if (po == null) continue;
            Map<String, Object> cfg = dsRepo.readConfig(po);
            String database = String.valueOf(cfg.getOrDefault("database", "?"));

            try {
                // 升级：拉完整 schema (表+列+FK+唯一键)，让 LLM 看到结构而不只看到表名
                var schemaInfo = jdbcConnector.introspectSchema(kind, cfg, 80);
                List<String> tables = schemaInfo.tables().stream().map(t -> t.name()).toList();
                int fkCount = schemaInfo.tables().stream().mapToInt(t -> t.foreignKeys().size()).sum();
                String preview = tables.stream().limit(6).collect(Collectors.joining("、"));
                String tail = tables.size() > 6 ? " … 共 " + tables.size() + " 张" : "";
                step.step("reading_db_" + id,
                        "正在读取数据库「" + name + "」(" + kind + ":" + database
                                + ") 共 " + tables.size() + " 张表 / " + fkCount + " 条外键"
                                + (tables.isEmpty() ? "" : ":" + preview + tail));
                out.add(new GraphPromptBuilder.DbSchema(name, kind, database, tables, schemaInfo));
            } catch (Exception e) {
                log.warn("[LLM-chat-sse] 读取数据库 {} 失败: {}", name, e.getMessage());
                step.step("reading_db_" + id + "_err",
                        "数据库「" + name + "」读取失败,跳过 (" + e.getMessage() + ")");
            }
        }
        return out;
    }

    /**
     * 按 @ 引用指定的 id 集合精确拉数据源,跳过全部"5 个上限"等启发式策略。
     * <p>用户明确 @ 了哪个库,就只把哪个库的完整 schema 注入上下文 —
     * 这才是"@真正影响上下文范围"的体现。
     */
    public List<GraphPromptBuilder.DbSchema> collectDbSchemasByIds(List<String> dsIds, ChatStepEmitter step) {
        List<GraphPromptBuilder.DbSchema> out = new ArrayList<>();
        if (dsIds == null || dsIds.isEmpty()) return out;
        for (String id : dsIds) {
            DataSourcePO po;
            try { po = dsRepo.findById(id); }
            catch (Exception e) { log.warn("[LLM-chat-sse] @ 引用的数据源 {} 查询失败: {}", id, e.getMessage()); continue; }
            if (po == null) {
                step.step("missing_ref_ds_" + id, "@ 引用的数据源 " + id + " 不存在,已忽略");
                continue;
            }
            String kind = po.getKind();
            if (!"mysql".equals(kind) && !"pgsql".equals(kind) && !"oracle".equals(kind)) {
                step.step("skip_ref_ds_" + id,
                        "@ 引用的数据源「" + po.getName() + "」非数据库类型,跳过 schema 注入");
                continue;
            }
            Map<String, Object> cfg = dsRepo.readConfig(po);
            String database = String.valueOf(cfg.getOrDefault("database", "?"));
            String name = po.getName() == null ? id : po.getName();
            try {
                // 用户明确引用 → 限额可以适当放宽到 200 张
                var schemaInfo = jdbcConnector.introspectSchema(kind, cfg, 200);
                List<String> tables = schemaInfo.tables().stream().map(t -> t.name()).toList();
                int fkCount = schemaInfo.tables().stream().mapToInt(t -> t.foreignKeys().size()).sum();
                step.step("reading_ref_db_" + id,
                        "🎯 按 @ 引用读取数据库「" + name + "」(" + kind + ":" + database
                                + ") 共 " + tables.size() + " 张表 / " + fkCount + " 条外键");
                out.add(new GraphPromptBuilder.DbSchema(name, kind, database, tables, schemaInfo));
            } catch (Exception e) {
                log.warn("[LLM-chat-sse] 读取 @ 引用的数据库 {} 失败: {}", name, e.getMessage());
                step.step("reading_ref_db_" + id + "_err",
                        "数据库「" + name + "」读取失败,跳过 (" + e.getMessage() + ")");
            }
        }
        return out;
    }

    /**
     * 把用户用 @ 显式引用的经验库文件读出全文，包装成 RagChunk（来源名带「经验(@指定)：」前缀以示区分）。
     * <p>与自动 RAG 召回不同：这里是用户主动点名的文件，整篇正文注入并给最高相关度，确保 LLM 优先采信。
     * 越权 / 不存在的 id 会发一条 step 提示并跳过。
     */
    public List<GraphPromptBuilder.RagChunk> collectPinnedExperiences(List<String> expIds, ChatStepEmitter step) {
        List<GraphPromptBuilder.RagChunk> out = new ArrayList<>();
        if (expIds == null || expIds.isEmpty()) return out;
        for (String id : expIds) {
            Map<String, Object> exp;
            try { exp = experienceRepo.findFull(id); }
            catch (Exception e) { log.warn("[LLM-chat-sse] @ 引用的经验 {} 查询失败: {}", id, e.getMessage()); continue; }
            if (exp == null) {
                step.step("missing_ref_exp_" + id, "@ 引用的经验文件 " + id + " 不存在或不属于当前空间,已忽略");
                continue;
            }
            String title = String.valueOf(exp.getOrDefault("title", "未命名经验"));
            Object contentObj = exp.get("content");
            String content = contentObj == null ? "" : String.valueOf(contentObj);
            if (content.isBlank()) {
                step.step("empty_ref_exp_" + id, "@ 引用的经验「" + title + "」无正文内容,已忽略");
                continue;
            }
            boolean truncated = content.length() > PINNED_EXP_CONTENT_MAX;
            if (truncated) content = content.substring(0, PINNED_EXP_CONTENT_MAX) + "\n…(正文过长已截断)";
            out.add(new GraphPromptBuilder.RagChunk(content, "经验(@指定)：" + title, 1.0));
            step.step("reading_ref_exp_" + id,
                    "🎯 按 @ 引用读取经验「" + title + "」" + (truncated ? "(已截断)" : "") + " 作为定向上下文");
        }
        return out;
    }

    /** 从 mentions 列表中按 kind 过滤出 id 列表。 */
    public static List<String> pickMentionIds(List<MentionRef> mentions, String kind) {
        if (mentions == null || mentions.isEmpty()) return List.of();
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (MentionRef m : mentions) {
            if (m == null || m.getKind() == null || m.getId() == null) continue;
            if (kind.equalsIgnoreCase(m.getKind())) set.add(m.getId());
        }
        return new ArrayList<>(set);
    }
}
