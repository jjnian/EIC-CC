package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tuiyan.backend.repository.DataSourceRepository;
import com.tuiyan.backend.service.extraction.ExtractionContext;
import com.tuiyan.backend.service.extraction.FileProbe;
import com.tuiyan.backend.service.extraction.SourceFileHandler;
import com.tuiyan.backend.service.extraction.StepSink;
import com.tuiyan.backend.service.extraction.UploadedFile;
import com.tuiyan.backend.support.FileSniffer;
import com.tuiyan.backend.support.IdSaltRewriter;
import com.tuiyan.backend.support.WebPageFetcher;
import com.tuiyan.backend.support.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 文档抽取编排：文件嗅探 → 按类型分派给 {@link SourceFileHandler} → LLM 抽取 → idMap salt 重写。
 * <p>本类只负责"编排"：拿到累积的文本 / 图片后交给 LLM，再做 salt 重写与数据源登记。
 * 各文件类型（PDF / DOCX / 图片）的识别与抽取细节都封装在各自的 {@link SourceFileHandler}
 * 实现里，由 Spring 注入并按 {@code @Order} 排序——新增文件类型无需改动本类。
 */
@Service
public class DocumentExtractionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentExtractionService.class);

    // 一次最多上传 8 个文件，防止 OOM
    private static final int TOTAL_FILE_LIMIT = 8;
    // 一次抽取最多接受 5 个 URL，避免对外网批量打洞
    private static final int URL_LIMIT        = 5;

    private final ExtractionLlmService extractionLlmService;
    private final Executor urlFetchExecutor;
    private final DataSourceRepository dataSourceRepository;
    private final List<SourceFileHandler> fileHandlers;
    private final com.tuiyan.backend.service.indexing.DataSourceIndexService dataSourceIndexService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DocumentExtractionService(ExtractionLlmService extractionLlmService,
                                     @Qualifier("appTaskExecutor") ThreadPoolTaskExecutor appTaskExecutor,
                                     DataSourceRepository dataSourceRepository,
                                     List<SourceFileHandler> fileHandlers,
                                     com.tuiyan.backend.service.indexing.DataSourceIndexService dataSourceIndexService) {
        this.extractionLlmService = extractionLlmService;
        this.urlFetchExecutor = appTaskExecutor;
        this.dataSourceRepository = dataSourceRepository;
        this.fileHandlers = fileHandlers;
        this.dataSourceIndexService = dataSourceIndexService;
    }

    /**
     * 入口：文件列表 + URL 列表 → LLM 抽取 → salt 重写后的 {nodes, edges, reply, sources, salt}。
     * <p>files 与 urls 至少要有一个非空；
     * 入参不合法（无文件 / 超数量 / 单文件超大 / 无可分析内容）时抛 {@link IllegalArgumentException}，
     * 由 GlobalExceptionHandler 映射为 400。
     */
    public Map<String, Object> extract(List<MultipartFile> files,
                                       List<String> urls,
                                       String modelOverride,
                                       String configId) throws Exception {
        return extractCore(toUploaded(files), urls, modelOverride, configId, StepSink.NOOP);
    }

    /**
     * 流式抽取：在后台线程上执行 {@link #extractCore}，每个阶段通过 SSE {@code step} 事件推送，
     * 完成时发送 {@code complete} 事件（携带与非流式版本一致的 {nodes,edges,reply,sources,salt}）。
     * <p>关键点：
     * <ul>
     *   <li>files 必须在 controller 线程内已读入内存（multipart 临时文件随请求结束即回收）；</li>
     *   <li>workspaceId 也要在请求线程捕获后透传——后台线程没有 WorkspaceInterceptor 设置的
     *       ThreadLocal，持久化数据源时需要手动 set/clear。</li>
     * </ul>
     */
    public void extractStreaming(List<UploadedFile> files,
                                 List<String> urls,
                                 String modelOverride,
                                 String configId,
                                 String workspaceId,
                                 SseEmitter emitter) {
        CompletableFuture.runAsync(() -> {
            boolean ctxSet = false;
            try {
                if (workspaceId != null && !workspaceId.isBlank()) {
                    WorkspaceContext.set(workspaceId);
                    ctxSet = true;
                }
                StepSink step = (k, l) -> emitStep(emitter, k, l);
                Map<String, Object> result = extractCore(files, urls, modelOverride, configId, step);
                emitter.send(SseEmitter.event().name("complete").data(objectMapper.writeValueAsString(result)));
                emitter.complete();
            } catch (IllegalArgumentException e) {
                emitError(emitter, e.getMessage());
            } catch (Exception e) {
                log.error("[extract-sse] 抽取失败: {}", e.getMessage(), e);
                emitError(emitter, "抽取失败: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            } finally {
                if (ctxSet) WorkspaceContext.clear();
            }
        }, urlFetchExecutor);
    }

    /** 把 MultipartFile 列表的字节提前读入内存，过滤掉空文件。 */
    private static List<UploadedFile> toUploaded(List<MultipartFile> files) throws IOException {
        List<UploadedFile> out = new ArrayList<>();
        if (files == null) return out;
        for (MultipartFile f : files) {
            if (f == null || f.getSize() <= 0) continue;
            out.add(new UploadedFile(f.getOriginalFilename(), f.getContentType(), f.getSize(), f.getBytes()));
        }
        return out;
    }

    /** 抽取主流程：文件 / URL 处理 → LLM 抽取 → salt 重写 → 持久化数据源，沿途上报 step。 */
    private Map<String, Object> extractCore(List<UploadedFile> files,
                                            List<String> urls,
                                            String modelOverride,
                                            String configId,
                                            StepSink step) throws Exception {
        boolean hasFiles = files != null && !files.isEmpty();
        boolean hasUrls = urls != null && !urls.isEmpty();
        if (!hasFiles && !hasUrls) {
            throw new IllegalArgumentException("请至少提供一个文件或网址");
        }
        if (hasFiles && files.size() > TOTAL_FILE_LIMIT) {
            throw new IllegalArgumentException("一次最多 " + TOTAL_FILE_LIMIT + " 个文件");
        }
        if (hasUrls && urls.size() > URL_LIMIT) {
            throw new IllegalArgumentException("一次最多 " + URL_LIMIT + " 个网址");
        }

        ExtractionContext ctx = new ExtractionContext(step);

        if (hasFiles) {
            processFiles(files, ctx);
        }
        if (hasUrls) {
            processUrls(urls, ctx);
        }

        if (ctx.textLength() == 0 && ctx.imageAttachments().isEmpty()) {
            throw new IllegalArgumentException("未能从上传文件或网址中抽出任何可分析的文本或图片");
        }

        step.emit("calling_llm", "正在调用大模型抽取实体与关系…（依据 "
                + ctx.textLength() + " 字符文本 / " + ctx.imageCount() + " 张图片）");
        JsonNode draft = extractionLlmService.extractOntologyFromSources(
                ctx.text(), ctx.imageAttachments(), modelOverride, configId);

        // 用毫秒时间戳的 36 进制作 salt，加在每个节点 id 前面避免与已有图谱冲突
        step.emit("normalizing", "正在整理抽取结果、消解 id 冲突…");
        String salt = Long.toString(System.currentTimeMillis(), 36);
        JsonNode rewritten = IdSaltRewriter.applyImportSalt(draft, salt);

        // 补血缘来源标记：对话建图有 DerivedSourceStamper、经验库建图有 stampSource(经验库)，
        // 文档/URL 导入同样要在节点/边上留 derived_source，否则「数据来源」卡片无从追溯
        String sourceLabel = sourceLabelOf(ctx.sourcesMeta());
        stampDerivedSource(rewritten.path("nodes"), sourceLabel);
        stampDerivedSource(rewritten.path("edges"), sourceLabel);

        int nodeCount = rewritten.path("nodes").isArray() ? rewritten.path("nodes").size() : 0;
        int edgeCount = rewritten.path("edges").isArray() ? rewritten.path("edges").size() : 0;
        step.emit("persisting", "正在登记数据源…（抽出 " + nodeCount + " 节点 / " + edgeCount + " 关系）");
        persistDataSources(ctx.sourcesMeta());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", rewritten.path("nodes"));
        out.put("edges", rewritten.path("edges"));
        out.put("reply", draft.path("reply").asText(""));
        out.put("sources", ctx.sourcesMeta());
        // 把 salt 也回传给前端：后续如果用户取消导入，前端能用 salt 反过滤出本次新加的节点
        out.put("salt", salt);
        return out;
    }

    private void emitStep(SseEmitter emitter, String key, String label) {
        try {
            emitter.send(SseEmitter.event().name("step")
                    .data(objectMapper.writeValueAsString(Map.of("key", key, "label", label))));
        } catch (IOException e) {
            log.warn("emit step '{}' failed: {}", key, e.toString());
        }
    }

    private void emitError(SseEmitter emitter, String msg) {
        try {
            emitter.send(SseEmitter.event().name("error").data(msg == null ? "未知错误" : msg));
        } catch (IOException e) {
            log.warn("emit extract error failed: {}", e.toString());
        }
        emitter.complete();
    }

    /** byte[] 头部安全切片。 */
    private static byte[] headOf(byte[] bytes, int n) {
        if (bytes == null) return new byte[0];
        return Arrays.copyOf(bytes, Math.min(n, bytes.length));
    }

    /** 跳过类型 source meta 构造器。 */
    private static Map<String, Object> skippedMeta(String name, String reason) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", name);
        meta.put("type", "skipped");
        meta.put("reason", reason);
        return meta;
    }

    /**
     * 逐个文件嗅探类型并分派给匹配的 {@link SourceFileHandler}；没有 handler 认领则标记 skipped。
     * 文件按上传顺序单线程处理，meta 累加到 ctx。
     */
    private void processFiles(List<UploadedFile> files, ExtractionContext ctx) throws IOException {
        int total = files.size();
        int idx = 0;
        for (UploadedFile f : files) {
            idx++;
            String safeName = FileSniffer.sanitizeFilename(f.name());
            // 空文件直接跳过（不算错误，可能是用户误拖）
            if (f.size() <= 0) continue;

            ctx.step().emit("reading_file", "正在解析文件（" + idx + "/" + total + "）" + safeName + "…");

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("name", safeName);
            meta.put("contentType", f.contentType());
            meta.put("size", f.size());

            // 嗅探文件头 + 扩展名 + content-type，交由各 handler 综合判断真实类型
            FileProbe probe = new FileProbe(headOf(f.bytes(), 12), safeName, f.contentType());
            SourceFileHandler handler = fileHandlers.stream()
                    .filter(h -> h.supports(probe))
                    .findFirst()
                    .orElse(null);
            if (handler != null) {
                handler.handle(f, safeName, ctx, meta);
            } else {
                meta.put("type", "skipped");
                meta.put("reason", "不支持的 content-type 或文件签名:" + f.contentType());
            }
            ctx.addSource(meta);
        }
    }

    /**
     * URL 列表抓取：并行（最多 5 个）跑 Jsoup 静态 → Playwright 兜底。
     * <p>用 appTaskExecutor 调度，避免串行最差 5 × (15s + 25s) ≈ 200s 的延迟。
     * 顺序与输入一致，失败的 URL 留 reason 在对应位置。
     */
    private void processUrls(List<String> urls, ExtractionContext ctx) {
        long n = urls.stream().filter(u -> u != null && !u.isBlank()).count();
        ctx.step().emit("fetching_urls", "正在抓取网页（" + n + " 个）…");
        List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>(urls.size());
        for (String raw : urls) {
            if (raw == null || raw.isBlank()) continue;
            String url = raw.trim();
            futures.add(CompletableFuture.supplyAsync(() -> fetchOneUrl(url, ctx), urlFetchExecutor));
        }
        for (CompletableFuture<Map<String, Object>> fu : futures) {
            try {
                ctx.addSource(fu.join());
            } catch (Exception e) {
                ctx.addSource(skippedMeta("(unknown)", "抓取异常: " + e.getMessage()));
            }
        }
        ctx.step().emit("fetched_urls", "网页抓取完成（" + n + " 个）");
    }

    /** 单 URL 抓取：返回该 URL 的 sourcesMeta；正文线程安全地追加到 ctx。 */
    private Map<String, Object> fetchOneUrl(String url, ExtractionContext ctx) {
        try {
            WebPageFetcher.Result r = WebPageFetcher.fetch(url);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("name", r.url);
            meta.put("type", "url");
            meta.put("chars", r.chars);
            meta.put("size", r.chars);
            if (r.title != null && !r.title.isBlank()) meta.put("title", r.title);
            if (r.truncated) meta.put("truncated", true);
            if (r.usedHeadless) meta.put("usedHeadless", true);
            if (r.errorMsg != null) meta.put("reason", r.errorMsg);

            if (r.text != null && !r.text.isBlank()) {
                String header = (r.title != null && !r.title.isBlank())
                        ? "# 网页 " + r.title + "（" + r.url + "）"
                        : "# 网页 " + r.url;
                ctx.appendSection(header, r.text);
            }
            return meta;
        } catch (IllegalArgumentException ie) {
            log.warn("[web] URL 校验失败 url={} err={}", url, ie.getMessage());
            return skippedMeta(url, ie.getMessage());
        } catch (Exception e) {
            log.warn("[web] URL 抓取异常 url={} err={}", url, e.toString());
            return skippedMeta(url, "抓取失败: " + e.getMessage());
        }
    }

    /**
     * 本次抽取的血缘来源名：单来源直接用其名称（与 persistDataSources 登记的 data_source 名一致），
     * 多来源用「首个名称 等 N 份资料」概括；全部 skipped 时返回 null（不打标）。
     */
    private static String sourceLabelOf(List<Map<String, Object>> sourcesMeta) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> meta : sourcesMeta) {
            if ("skipped".equals(String.valueOf(meta.getOrDefault("type", "")))) continue;
            String name = String.valueOf(meta.getOrDefault("name", ""));
            if (!name.isBlank()) names.add(name);
        }
        if (names.isEmpty()) return null;
        return names.size() == 1 ? names.get(0) : names.get(0) + " 等 " + names.size() + " 份资料";
    }

    /** 给 nodes/edges 数组里 derived_source 缺失的对象补来源标记，保留 LLM 已填的值。 */
    private static void stampDerivedSource(JsonNode arr, String sourceLabel) {
        if (sourceLabel == null || !(arr instanceof ArrayNode list)) return;
        for (JsonNode n : list) {
            if (n instanceof ObjectNode obj && obj.path("derived_source").asText("").isBlank()) {
                obj.put("derived_source", sourceLabel);
            }
        }
    }

    /** 抽取成功后把每个非 skipped 的 source 写入 data_source 表。 */
    private void persistDataSources(List<Map<String, Object>> sourcesMeta) {
        for (Map<String, Object> meta : sourcesMeta) {
            String type = String.valueOf(meta.getOrDefault("type", ""));
            if ("skipped".equals(type)) continue;
            String name = String.valueOf(meta.getOrDefault("name", ""));
            if (name.isBlank()) continue;
            String kind = "url".equals(type) ? "url" : "file";
            String mime = meta.containsKey("contentType") ? String.valueOf(meta.get("contentType")) : null;
            Long size = null;
            Object sizeObj = meta.get("size");
            if (sizeObj instanceof Number num) size = num.longValue();
            Map<String, Object> extra = new LinkedHashMap<>(meta);
            extra.remove("name");
            extra.remove("contentType");
            extra.remove("size");
            try {
                // 音频/图片来源带「识别正文」(transcript)：按 (工作空间,名称) upsert 去重，避免重复上传同一
                // 音频/图片堆出多条可检索数据源；其余来源沿用新增(作为每次抽取的溯源记录)。
                boolean recognizable = "audio".equals(type) || "image".equals(type);
                boolean hasText = meta.get("transcript") instanceof String ts && !ts.isBlank();
                var saved = recognizable
                        ? dataSourceRepository.upsertSource(kind, name, mime, size, extra.isEmpty() ? null : extra)
                        : dataSourceRepository.saveSource(kind, name, mime, size, extra.isEmpty() ? null : extra);
                // 识别正文已落 extra_json。把来源纳入当前工作空间（建引用）后自动建向量索引——
                // 检索按「工作空间引用」过滤，没有引用则索引了也召回不到，故二者一起做。
                // 这样图片/音频「数据」即可被对话召回、并能「抽取到经验库」参与血缘建图。
                if (saved != null && recognizable && hasText) {
                    try {
                        dataSourceRepository.reference(java.util.List.of(saved.getId()));
                    } catch (Exception refErr) {
                        log.warn("reference {} data source failed for {}: {}", type, name, refErr.toString());
                    }
                    if (dataSourceIndexService.isConfigured()) {
                        dataSourceIndexService.indexDataSource(saved.getId(), null);
                    }
                }
            } catch (Exception e) {
                log.warn("persist data source failed for {}: {}", name, e.toString());
            }
        }
    }
}
