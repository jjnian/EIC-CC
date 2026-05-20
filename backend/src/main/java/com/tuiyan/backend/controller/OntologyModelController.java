package com.tuiyan.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tuiyan.backend.model.OntologyModel;
import com.tuiyan.backend.service.LlmService;
import com.tuiyan.backend.service.OntologyModelService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ontology-models")
public class OntologyModelController {

    private static final Logger log = LoggerFactory.getLogger(OntologyModelController.class);

    private final OntologyModelService svc;
    private final LlmService llmService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int PDF_TEXT_CHAR_BUDGET = 60_000;
    private static final long IMAGE_BYTE_LIMIT     = 8L * 1024 * 1024;
    private static final int  TOTAL_FILE_LIMIT     = 8;
    private static final int  MIN_TEXT_PER_PAGE    = 200;
    private static final int  RENDER_MAX_PAGES     = 8;
    private static final int  RENDER_DPI           = 110;
    // 跨文件累计：最多渲染 12 张图，且总附件字节 <= TOTAL_IMAGE_BUDGET * IMAGE_BYTE_LIMIT
    private static final int  TOTAL_IMAGE_BUDGET   = 12;
    // 单个 PDF 文件最大 12 MB
    private static final long PDF_FILE_BYTES_LIMIT = 12L * 1024 * 1024;

    public OntologyModelController(OntologyModelService svc, LlmService llmService) {
        this.svc = svc;
        this.llmService = llmService;
    }

    @GetMapping
    public ResponseEntity<?> list() {
        try { return ResponseEntity.ok(svc.list()); }
        catch (IOException e) { return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage())); }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        try {
            OntologyModel m = svc.get(id);
            return m == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(m);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody OntologyModel m) {
        try { return ResponseEntity.ok(svc.save(m)); }
        catch (IOException e) { return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage())); }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody OntologyModel m) {
        try {
            m.setId(id);
            return ResponseEntity.ok(svc.save(m));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        boolean ok = svc.delete(id);
        return ResponseEntity.ok(Map.of("success", ok, "count", ok ? 1 : 0));
    }

    // ========== v1.0 文档导入：PDF / image → 本体抽取草稿 ==========

    @PostMapping(value = "/extract", consumes = {"multipart/form-data"})
    public ResponseEntity<?> extract(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "modelOverride", required = false) String modelOverride,
            @RequestParam(value = "configId", required = false) String configId) {

        if (files == null || files.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "未提供文件"));
        }
        if (files.size() > TOTAL_FILE_LIMIT) {
            return ResponseEntity.badRequest().body(Map.of("error", "一次最多 " + TOTAL_FILE_LIMIT + " 个文件"));
        }

        StringBuilder combinedText = new StringBuilder();
        List<Map<String, Object>> imageAttachments = new ArrayList<>();
        List<Map<String, Object>> sourcesMeta = new ArrayList<>();

        try {
            for (MultipartFile f : files) {
                String safeName = sanitizeFilename(f.getOriginalFilename());
                String contentType = f.getContentType();
                long size = f.getSize();
                if (size <= 0) continue;

                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("name", safeName);
                meta.put("contentType", contentType);
                meta.put("size", size);

                // ---- 文件类型嗅探：content-type + magic-byte 双重验证 ----
                byte[] head = readHead(f, 8);
                boolean pdfMagic = sniffPdfMagic(head);
                boolean imageMagic = sniffImageMagic(head);
                boolean isPdf = (contentType != null && contentType.toLowerCase().contains("pdf") && pdfMagic)
                        || (safeName.toLowerCase().endsWith(".pdf") && pdfMagic);
                boolean isImage = (contentType != null && contentType.toLowerCase().startsWith("image/") && imageMagic)
                        || imageMagic;

                if (isPdf) {
                    if (size > PDF_FILE_BYTES_LIMIT) {
                        return ResponseEntity.badRequest().body(Map.of(
                            "error", "PDF " + safeName + " 超过 " + (PDF_FILE_BYTES_LIMIT / (1024 * 1024)) + " MB 限制"));
                    }
                    String text;
                    int pageCount;
                    try (PDDocument doc = Loader.loadPDF(f.getBytes())) {
                        pageCount = doc.getNumberOfPages();
                        meta.put("pages", pageCount);
                        PDFTextStripper stripper = new PDFTextStripper();
                        text = stripper.getText(doc);

                        int rawLen = text == null ? 0 : text.trim().length();
                        boolean textBare = pageCount > 0 && rawLen < pageCount * MIN_TEXT_PER_PAGE;
                        if (textBare) {
                            int rendered = renderPdfPages(doc, imageAttachments, RENDER_MAX_PAGES);
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
                } else if (isImage) {
                    if (size > IMAGE_BYTE_LIMIT) {
                        return ResponseEntity.badRequest().body(Map.of(
                            "error", "图片 " + safeName + " 超过 " + (IMAGE_BYTE_LIMIT / (1024 * 1024)) + " MB 限制"));
                    }
                    if (imageAttachments.size() >= TOTAL_IMAGE_BUDGET) {
                        meta.put("type", "skipped");
                        meta.put("reason", "已达全局图片预算 " + TOTAL_IMAGE_BUDGET + " 张");
                        sourcesMeta.add(meta);
                        continue;
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
                } else {
                    meta.put("type", "skipped");
                    meta.put("reason", "不支持的 content-type 或文件签名：" + contentType);
                }
                sourcesMeta.add(meta);
            }

            if (combinedText.length() == 0 && imageAttachments.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "未能从上传文件中抽出任何可分析的文本或图片",
                    "sources", sourcesMeta));
            }

            JsonNode draft = llmService.extractOntologyFromSources(
                combinedText.toString(), imageAttachments, modelOverride, configId);

            // id salt：避免与现有图谱节点撞车
            String salt = Long.toString(System.currentTimeMillis(), 36);
            JsonNode rewritten = applyImportSalt(draft, salt);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("nodes", rewritten.path("nodes"));
            out.put("edges", rewritten.path("edges"));
            out.put("reply", draft.path("reply").asText(""));
            out.put("sources", sourcesMeta);
            out.put("salt", salt);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            log.warn("/extract failed: {}", e.toString(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                "error", e.getMessage() == null ? "抽取失败" : e.getMessage(),
                "sources", sourcesMeta));
        }
    }

    /**
     * 清洗上传文件名：去除路径片段，仅保留字母/数字/点/下划线/横线，其他字符替换为 _。
     */
    private static String sanitizeFilename(String raw) {
        if (raw == null || raw.isBlank()) return "upload";
        String base = Paths.get(raw).getFileName().toString();
        String cleaned = base.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        if (cleaned.isBlank()) return "upload";
        return cleaned;
    }

    private static byte[] readHead(MultipartFile f, int n) {
        try (InputStream is = f.getInputStream()) {
            return is.readNBytes(n);
        } catch (IOException e) {
            log.warn("readHead failed for {}: {}", f.getOriginalFilename(), e.toString());
            return new byte[0];
        }
    }

    /** PDF magic bytes: 25 50 44 46 (%PDF) */
    private static boolean sniffPdfMagic(byte[] head) {
        if (head == null || head.length < 4) return false;
        return (head[0] & 0xff) == 0x25
                && (head[1] & 0xff) == 0x50
                && (head[2] & 0xff) == 0x44
                && (head[3] & 0xff) == 0x46;
    }

    /** PNG / JPEG / GIF / WebP magic bytes. */
    private static boolean sniffImageMagic(byte[] head) {
        if (head == null || head.length < 4) return false;
        int b0 = head[0] & 0xff, b1 = head[1] & 0xff, b2 = head[2] & 0xff, b3 = head[3] & 0xff;
        // PNG: 89 50 4E 47
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return true;
        // JPEG: FF D8 FF
        if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return true;
        // GIF: 47 49 46 38 ('GIF8')
        if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46 && b3 == 0x38) return true;
        // WebP: 'RIFF' then later 'WEBP'; 头 4 字节是 'RIFF'
        if (head.length >= 12 && b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46
                && (head[8] & 0xff) == 0x57 && (head[9] & 0xff) == 0x45
                && (head[10] & 0xff) == 0x42 && (head[11] & 0xff) == 0x50) {
            return true;
        }
        return false;
    }

    /**
     * 把 PDF 前 maxPages 页渲染成 PNG。每张图都做体积过滤；
     * 全局累计图片数达到 TOTAL_IMAGE_BUDGET 后停止；每页处理后 flush BufferedImage 释放内存。
     */
    private int renderPdfPages(PDDocument doc, List<Map<String, Object>> attachments, int maxPages) throws IOException {
        PDFRenderer renderer = new PDFRenderer(doc);
        int total = Math.min(doc.getNumberOfPages(), maxPages);
        int rendered = 0;
        for (int p = 0; p < total; p++) {
            if (attachments.size() >= TOTAL_IMAGE_BUDGET) break;
            BufferedImage img = renderer.renderImageWithDPI(p, RENDER_DPI);
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(img, "png", baos);
                byte[] bytes = baos.toByteArray();
                if (bytes.length > IMAGE_BYTE_LIMIT) continue;
                String b64 = Base64.getEncoder().encodeToString(bytes);
                Map<String, Object> att = new LinkedHashMap<>();
                att.put("type", "image");
                att.put("dataUrl", "data:image/png;base64," + b64);
                attachments.add(att);
                rendered++;
            } finally {
                img.flush();
            }
        }
        return rendered;
    }

    /**
     * 给 LLM 返回的 add_nodes / add_edges 整体加 salt 前缀。
     * 不仅重写顶层 id 和边的 from/to，也对节点内嵌的所有 string 字段做深度扫描，
     * 命中 idMap 时一并替换 —— 这样 triggered_by / leads_to / ruleId 等字段也能被联动重写。
     */
    private JsonNode applyImportSalt(JsonNode draft, String salt) {
        ArrayNode srcNodes = draft.has("add_nodes") && draft.get("add_nodes").isArray()
                ? (ArrayNode) draft.get("add_nodes") : objectMapper.createArrayNode();
        ArrayNode srcEdges = draft.has("add_edges") && draft.get("add_edges").isArray()
                ? (ArrayNode) draft.get("add_edges") : objectMapper.createArrayNode();

        Map<String, String> idMap = new HashMap<>();
        // 先做一次 pass 收集 id 映射
        for (JsonNode n : srcNodes) {
            if (!n.has("id")) continue;
            String oldId = n.get("id").asText();
            if (oldId.isEmpty()) continue;
            idMap.put(oldId, "imp" + salt + "_" + oldId);
        }

        ArrayNode outNodes = objectMapper.createArrayNode();
        for (JsonNode n : srcNodes) {
            if (!n.has("id")) continue;
            ObjectNode copy = n.deepCopy();
            String oldId = copy.get("id").asText();
            String newId = idMap.getOrDefault(oldId, "imp" + salt + "_" + oldId);
            copy.put("id", newId);
            // 深度扫描节点内其余字段，命中 idMap 的字符串替换
            remapStringsDeep(copy, idMap, "id");
            outNodes.add(copy);
        }

        ArrayNode outEdges = objectMapper.createArrayNode();
        int eIdx = 0;
        for (JsonNode e : srcEdges) {
            String from = e.path("from").asText("");
            String to = e.path("to").asText("");
            String newFrom = idMap.getOrDefault(from, from);
            String newTo = idMap.getOrDefault(to, to);
            ObjectNode copy = e.deepCopy();
            copy.put("from", newFrom);
            copy.put("to", newTo);
            String oldEid = e.path("id").asText("");
            copy.put("id", oldEid.isEmpty()
                    ? "impe" + salt + "_" + (++eIdx)
                    : "imp" + salt + "_" + oldEid);
            remapStringsDeep(copy, idMap, "id");
            outEdges.add(copy);
        }

        ObjectNode root = objectMapper.createObjectNode();
        root.set("nodes", outNodes);
        root.set("edges", outEdges);
        return root;
    }

    /**
     * 深度遍历 node 节点的所有字段：若某 string 值精确等于 idMap 中的 key，则替换为新 id。
     * 跳过 skipField（顶层 id），避免被二次覆盖。
     */
    private static void remapStringsDeep(JsonNode node, Map<String, String> idMap, String skipField) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                String fieldName = entry.getKey();
                JsonNode v = entry.getValue();
                if (v.isTextual()) {
                    if (skipField != null && skipField.equals(fieldName)) continue;
                    String mapped = idMap.get(v.asText());
                    if (mapped != null) obj.put(fieldName, mapped);
                } else if (v.isContainerNode()) {
                    remapStringsDeep(v, idMap, null);
                }
            }
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                JsonNode v = arr.get(i);
                if (v.isTextual()) {
                    String mapped = idMap.get(v.asText());
                    if (mapped != null) arr.set(i, arr.textNode(mapped));
                } else if (v.isContainerNode()) {
                    remapStringsDeep(v, idMap, null);
                }
            }
        }
    }
}
