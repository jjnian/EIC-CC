package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.tuiyan.backend.support.FileSniffer;
import com.tuiyan.backend.support.IdSaltRewriter;
import com.tuiyan.backend.support.PdfTextExtractor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档抽取编排:文件嗅探 + PDF/图像处理 + LLM 抽取 + idMap salt 重写。
 */
@Service
public class DocumentExtractionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentExtractionService.class);

    private static final int PDF_TEXT_CHAR_BUDGET = 60_000;
    private static final long IMAGE_BYTE_LIMIT    = 8L * 1024 * 1024;
    private static final int  TOTAL_FILE_LIMIT    = 8;
    private static final int  MIN_TEXT_PER_PAGE   = 200;
    private static final int  RENDER_MAX_PAGES    = 8;
    private static final int  RENDER_DPI          = 110;
    private static final int  TOTAL_IMAGE_BUDGET  = 12;
    private static final long PDF_FILE_BYTES_LIMIT = 12L * 1024 * 1024;

    private final LlmService llmService;

    public DocumentExtractionService(LlmService llmService) {
        this.llmService = llmService;
    }

    /**
     * 文件 → LLM 抽取 → salt 重写。返回 {nodes, edges, reply, sources, salt}。
     * 入参不合法时抛 IllegalArgumentException。
     */
    public Map<String, Object> extract(List<MultipartFile> files, String modelOverride, String configId) throws Exception {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("未提供文件");
        }
        if (files.size() > TOTAL_FILE_LIMIT) {
            throw new IllegalArgumentException("一次最多 " + TOTAL_FILE_LIMIT + " 个文件");
        }

        StringBuilder combinedText = new StringBuilder();
        List<Map<String, Object>> imageAttachments = new ArrayList<>();
        List<Map<String, Object>> sourcesMeta = new ArrayList<>();

        for (MultipartFile f : files) {
            String safeName = FileSniffer.sanitizeFilename(f.getOriginalFilename());
            String contentType = f.getContentType();
            long size = f.getSize();
            if (size <= 0) continue;

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("name", safeName);
            meta.put("contentType", contentType);
            meta.put("size", size);

            byte[] head = FileSniffer.readHead(f, 8);
            boolean pdfMagic = FileSniffer.isPdfMagic(head);
            boolean imageMagic = FileSniffer.isImageMagic(head);
            boolean isPdf = (contentType != null && contentType.toLowerCase().contains("pdf") && pdfMagic)
                    || (safeName.toLowerCase().endsWith(".pdf") && pdfMagic);
            boolean isImage = (contentType != null && contentType.toLowerCase().startsWith("image/") && imageMagic)
                    || imageMagic;

            if (isPdf) {
                handlePdf(f, safeName, size, meta, combinedText, imageAttachments);
            } else if (isImage) {
                handleImage(f, safeName, size, contentType, meta, imageAttachments);
            } else {
                meta.put("type", "skipped");
                meta.put("reason", "不支持的 content-type 或文件签名:" + contentType);
            }
            sourcesMeta.add(meta);
        }

        if (combinedText.length() == 0 && imageAttachments.isEmpty()) {
            throw new IllegalArgumentException("未能从上传文件中抽出任何可分析的文本或图片");
        }

        JsonNode draft = llmService.extractOntologyFromSources(
                combinedText.toString(), imageAttachments, modelOverride, configId);

        String salt = Long.toString(System.currentTimeMillis(), 36);
        JsonNode rewritten = IdSaltRewriter.applyImportSalt(draft, salt);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", rewritten.path("nodes"));
        out.put("edges", rewritten.path("edges"));
        out.put("reply", draft.path("reply").asText(""));
        out.put("sources", sourcesMeta);
        out.put("salt", salt);
        return out;
    }

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

            int rawLen = text == null ? 0 : text.trim().length();
            boolean textBare = pageCount > 0 && rawLen < pageCount * MIN_TEXT_PER_PAGE;
            if (textBare) {
                int rendered = PdfTextExtractor.renderPages(doc, imageAttachments,
                        RENDER_MAX_PAGES, RENDER_DPI, IMAGE_BYTE_LIMIT, TOTAL_IMAGE_BUDGET);
                meta.put("renderedPages", rendered);
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
            combinedText.append("# 文件 ").append(safeName).append("\n\n")
                        .append(text).append("\n\n");
        }
    }

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
