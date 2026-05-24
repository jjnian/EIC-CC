package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.tuiyan.backend.support.DocxTextExtractor;
import com.tuiyan.backend.support.FileSniffer;
import com.tuiyan.backend.support.IdSaltRewriter;
import com.tuiyan.backend.support.PdfTextExtractor;
import com.tuiyan.backend.support.WebPageFetcher;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 文档抽取编排：文件嗅探 + PDF / DOCX / 图片处理 + LLM 抽取 + idMap salt 重写。
 * <p>把"用户上传的混合文件" → "可合并到图谱的节点 / 边草稿"的全流程串起来：
 * <ol>
 *   <li>按文件签名 + 扩展名识别真实类型，避免被伪造扩展名绕过；</li>
 *   <li>PDF 优先抽文本，文本稀疏时再渲染为图片（兼顾扫描件 / 截图为主的文档）；</li>
 *   <li>DOCX 用 POI 抽段落和表格；图片直接 base64 编码挂为 attachment；</li>
 *   <li>把累积文本 + 附件交给 LLM 做实体 / 关系抽取；</li>
 *   <li>用时间戳 salt 给草稿节点 id 加前缀，防止同一文档反复导入产生冲突。</li>
 * </ol>
 */
@Service
public class DocumentExtractionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentExtractionService.class);

    // PDF / DOCX 单文件抽取文本上限（按字符），超过会截断并打 truncated 标记
    private static final int PDF_TEXT_CHAR_BUDGET = 60_000;
    private static final int DOCX_TEXT_CHAR_BUDGET = 60_000;
    // 单张图片字节上限：8 MB；超过则直接拒绝
    private static final long IMAGE_BYTE_LIMIT    = 8L * 1024 * 1024;
    // 一次最多上传 8 个文件，防止 OOM
    private static final int  TOTAL_FILE_LIMIT    = 8;
    // PDF 每页平均文本字符数下限：低于此阈值视为"扫描件 / 文本稀疏"，触发图片渲染兜底
    private static final int  MIN_TEXT_PER_PAGE   = 200;
    // 渲染兜底时最多渲染前 8 页，分辨率 110 dpi（视觉清晰 + 体积可控）
    private static final int  RENDER_MAX_PAGES    = 8;
    private static final int  RENDER_DPI          = 110;
    // 单次抽取允许的总图片数（PDF 渲染 + 用户直传图片合计）
    private static final int  TOTAL_IMAGE_BUDGET  = 12;
    // 单个 PDF / DOCX 文件体积上限（12 MB）
    private static final long PDF_FILE_BYTES_LIMIT = 12L * 1024 * 1024;
    private static final long DOCX_FILE_BYTES_LIMIT = 12L * 1024 * 1024;
    // 一次抽取最多接受 5 个 URL，避免对外网批量打洞
    private static final int  URL_LIMIT             = 5;

    private final ExtractionLlmService extractionLlmService;
    private final Executor urlFetchExecutor;

    public DocumentExtractionService(ExtractionLlmService extractionLlmService,
                                     @Qualifier("predictionExecutor") ThreadPoolTaskExecutor predictionExecutor) {
        this.extractionLlmService = extractionLlmService;
        this.urlFetchExecutor = predictionExecutor;
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

        StringBuilder combinedText = new StringBuilder();
        List<Map<String, Object>> imageAttachments = new ArrayList<>();
        List<Map<String, Object>> sourcesMeta = new ArrayList<>();

        if (hasFiles) {
            processFiles(files, combinedText, imageAttachments, sourcesMeta);
        }
        if (hasUrls) {
            processUrls(urls, combinedText, sourcesMeta);
        }

        if (combinedText.length() == 0 && imageAttachments.isEmpty()) {
            throw new IllegalArgumentException("未能从上传文件或网址中抽出任何可分析的文本或图片");
        }

        JsonNode draft = extractionLlmService.extractOntologyFromSources(
                combinedText.toString(), imageAttachments, modelOverride, configId);

        // 用毫秒时间戳的 36 进制作 salt，加在每个节点 id 前面避免与已有图谱冲突
        String salt = Long.toString(System.currentTimeMillis(), 36);
        JsonNode rewritten = IdSaltRewriter.applyImportSalt(draft, salt);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", rewritten.path("nodes"));
        out.put("edges", rewritten.path("edges"));
        out.put("reply", draft.path("reply").asText(""));
        out.put("sources", sourcesMeta);
        // 把 salt 也回传给前端：后续如果用户取消导入，前端能用 salt 反过滤出本次新加的节点
        out.put("salt", salt);
        return out;
    }

    /** 跳过类型 source meta 构造器。 */
    private static Map<String, Object> skippedMeta(String name, String reason) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", name);
        meta.put("type", "skipped");
        meta.put("reason", reason);
        return meta;
    }

    private void processFiles(List<MultipartFile> files,
                              StringBuilder combinedText,
                              List<Map<String, Object>> imageAttachments,
                              List<Map<String, Object>> sourcesMeta) throws IOException {
        for (MultipartFile f : files) {
            String safeName = FileSniffer.sanitizeFilename(f.getOriginalFilename());
            String contentType = f.getContentType();
            long size = f.getSize();
            // 空文件直接跳过（不算错误，可能是用户误拖）
            if (size <= 0) continue;

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("name", safeName);
            meta.put("contentType", contentType);
            meta.put("size", size);

            // 嗅探文件头 + 扩展名 + content-type，三者综合判断真实类型
            byte[] head = FileSniffer.readHead(f, 12);
            boolean pdfMagic = FileSniffer.isPdfMagic(head);
            boolean imageMagic = FileSniffer.isImageMagic(head);
            boolean zipMagic = FileSniffer.isZipMagic(head);
            String lname = safeName.toLowerCase();
            String lct = contentType == null ? "" : contentType.toLowerCase();
            boolean isPdf = (lct.contains("pdf") && pdfMagic)
                    || (lname.endsWith(".pdf") && pdfMagic);
            // DOCX 是 ZIP 容器，必须 ZIP 签名 + 扩展名 / content-type 同时命中才认
            boolean isDocx = zipMagic && (
                    lname.endsWith(".docx")
                            || lct.contains("wordprocessingml")
                            || lct.contains("officedocument"));
            boolean isImage = (lct.startsWith("image/") && imageMagic) || imageMagic;

            if (isPdf) {
                handlePdf(f, safeName, size, meta, combinedText, imageAttachments);
            } else if (isDocx) {
                handleDocx(f, safeName, size, meta, combinedText);
            } else if (isImage) {
                handleImage(f, safeName, size, contentType, meta, imageAttachments);
            } else {
                meta.put("type", "skipped");
                meta.put("reason", "不支持的 content-type 或文件签名:" + contentType);
            }
            sourcesMeta.add(meta);
        }
    }

    /**
     * URL 列表抓取：并行（最多 5 个）跑 Jsoup 静态 → Playwright 兜底。
     * <p>用 predictionExecutor 调度，避免串行最差 5 × (15s + 25s) ≈ 200s 的延迟。
     * 顺序与输入一致，失败的 URL 留 reason 在对应位置。
     */
    private void processUrls(List<String> urls,
                             StringBuilder combinedText,
                             List<Map<String, Object>> sourcesMeta) {
        List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>(urls.size());
        for (String raw : urls) {
            if (raw == null || raw.isBlank()) continue;
            String url = raw.trim();
            futures.add(CompletableFuture.supplyAsync(() -> fetchOneUrl(url, combinedText), urlFetchExecutor));
        }
        for (CompletableFuture<Map<String, Object>> fu : futures) {
            try {
                sourcesMeta.add(fu.join());
            } catch (Exception e) {
                sourcesMeta.add(skippedMeta("(unknown)", "抓取异常: " + e.getMessage()));
            }
        }
    }

    /** 单 URL 抓取：返回该 URL 的 sourcesMeta；正文同步追加到 combinedText（StringBuilder 加锁）。 */
    private Map<String, Object> fetchOneUrl(String url, StringBuilder combinedText) {
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
                synchronized (combinedText) {
                    combinedText.append(header).append("\n\n").append(r.text).append("\n\n");
                }
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
     * PDF 处理：
     * <ol>
     *   <li>抽取全部文本；</li>
     *   <li>若文本量 / 页数低于 {@link #MIN_TEXT_PER_PAGE}（很可能是扫描件），追加把每页渲染成图片；</li>
     *   <li>超过 budget 时截断并打 truncated 标记。</li>
     * </ol>
     */
    private void handlePdf(MultipartFile f, String safeName, long size,
                           Map<String, Object> meta, StringBuilder combinedText,
                           List<Map<String, Object>> imageAttachments) throws IOException {
        if (size > PDF_FILE_BYTES_LIMIT) {
            throw new IllegalArgumentException(
                    "PDF " + safeName + " 超过 " + (PDF_FILE_BYTES_LIMIT / (1024 * 1024)) + " MB 限制");
        }
        String text;
        int pageCount;
        try (PDDocument doc = Loader.loadPDF(f.getBytes())) {
            pageCount = doc.getNumberOfPages();
            meta.put("pages", pageCount);
            text = PdfTextExtractor.extractText(doc);

            // 文本稀疏判定：每页字符数 < MIN_TEXT_PER_PAGE 视为扫描件 / 文字稀少，启用图片兜底
            int rawLen = text == null ? 0 : text.trim().length();
            boolean textBare = pageCount > 0 && rawLen < pageCount * MIN_TEXT_PER_PAGE;
            if (textBare) {
                int rendered = PdfTextExtractor.renderPages(doc, imageAttachments,
                        RENDER_MAX_PAGES, RENDER_DPI, IMAGE_BYTE_LIMIT, TOTAL_IMAGE_BUDGET);
                meta.put("renderedPages", rendered);
                // 稀疏文本还是有点价值（如页眉页脚），但保留太多会污染 prompt，截短到 4000 字符
                if (text != null && text.length() > 4_000) {
                    text = text.substring(0, 4_000);
                }
            }
        }
        int rawChars = text == null ? 0 : text.length();
        if (text != null && text.length() > PDF_TEXT_CHAR_BUDGET) {
            text = text.substring(0, PDF_TEXT_CHAR_BUDGET) + "\n[…truncated…]";
            meta.put("truncated", true);
        }
        meta.put("type", "pdf");
        meta.put("chars", rawChars);
        if (text != null && !text.isBlank()) {
            // 文本以 "# 文件 名称" 分段拼接，让 LLM 知道哪些段落来自哪个文件
            combinedText.append("# 文件 ").append(safeName).append("\n\n")
                        .append(text).append("\n\n");
        }
    }

    /** DOCX 处理：用 {@link DocxTextExtractor} 抽段落 + 表格，超过 budget 截断。 */
    private void handleDocx(MultipartFile f, String safeName, long size,
                            Map<String, Object> meta, StringBuilder combinedText) throws IOException {
        if (size > DOCX_FILE_BYTES_LIMIT) {
            throw new IllegalArgumentException(
                    "DOCX " + safeName + " 超过 " + (DOCX_FILE_BYTES_LIMIT / (1024 * 1024)) + " MB 限制");
        }
        DocxTextExtractor.Result r;
        try (var in = f.getInputStream()) {
            r = DocxTextExtractor.extract(in);
        }
        String text = r.text;
        int rawChars = text == null ? 0 : text.length();
        if (text != null && text.length() > DOCX_TEXT_CHAR_BUDGET) {
            text = text.substring(0, DOCX_TEXT_CHAR_BUDGET) + "\n[…truncated…]";
            meta.put("truncated", true);
        }
        meta.put("type", "docx");
        meta.put("chars", rawChars);
        meta.put("paragraphs", r.paragraphs);
        meta.put("tables", r.tables);
        if (text != null && !text.isBlank()) {
            combinedText.append("# 文件 ").append(safeName).append("\n\n")
                        .append(text).append("\n\n");
        }
    }

    /** 图片处理：直接 base64 编码挂到 attachments 列表；超过单张大小或总额度则记原因后跳过。 */
    private void handleImage(MultipartFile f, String safeName, long size, String contentType,
                             Map<String, Object> meta,
                             List<Map<String, Object>> imageAttachments) throws IOException {
        if (size > IMAGE_BYTE_LIMIT) {
            throw new IllegalArgumentException(
                    "图片 " + safeName + " 超过 " + (IMAGE_BYTE_LIMIT / (1024 * 1024)) + " MB 限制");
        }
        if (imageAttachments.size() >= TOTAL_IMAGE_BUDGET) {
            meta.put("type", "skipped");
            meta.put("reason", "已达全局图片预算 " + TOTAL_IMAGE_BUDGET + " 张");
            return;
        }
        String b64 = Base64.getEncoder().encodeToString(f.getBytes());
        // contentType 缺失或非 image/* 时回退为 png，避免 LLM 拿到不合法的 media type
        String mediaType = (contentType != null && contentType.toLowerCase().startsWith("image/"))
                ? contentType : "image/png";
        String dataUrl = "data:" + mediaType + ";base64," + b64;
        Map<String, Object> att = new LinkedHashMap<>();
        att.put("type", "image");
        att.put("dataUrl", dataUrl);
        imageAttachments.add(att);
        meta.put("type", "image");
    }
}
