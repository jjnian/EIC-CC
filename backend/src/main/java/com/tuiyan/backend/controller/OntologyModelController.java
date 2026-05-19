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
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ontology-models")
public class OntologyModelController {

    private final OntologyModelService svc;
    private final LlmService llmService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int PDF_TEXT_CHAR_BUDGET = 60_000;          // 单文档文本上限，超过截断
    private static final long IMAGE_BYTE_LIMIT     = 8L * 1024 * 1024; // 单张图最大 8 MB
    private static final int  TOTAL_FILE_LIMIT     = 8;               // 一次最多 8 个文件

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
        return ResponseEntity.ok(Map.of("deleted", svc.delete(id)));
    }

    // ========== v1.0 文档导入：PDF / image → 本体抽取草稿 ==========

    /**
     * 接收 multipart 文件（PDF / image），抽取文本 + 编码图片，调用 LLM 抽取本体草稿。
     * 不落盘——返回带 salted id 的 {nodes, edges, sources} 给前端预览，由用户决定合并 / 另存。
     */
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
                String name = f.getOriginalFilename();
                String contentType = f.getContentType();
                long size = f.getSize();
                if (size <= 0) continue;

                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("name", name);
                meta.put("contentType", contentType);
                meta.put("size", size);

                if (isPdf(name, contentType)) {
                    String text;
                    try (PDDocument doc = Loader.loadPDF(f.getBytes())) {
                        meta.put("pages", doc.getNumberOfPages());
                        PDFTextStripper stripper = new PDFTextStripper();
                        text = stripper.getText(doc);
                    }
                    int rawChars = text == null ? 0 : text.length();
                    if (text != null && text.length() > PDF_TEXT_CHAR_BUDGET) {
                        text = text.substring(0, PDF_TEXT_CHAR_BUDGET) + "\n[…truncated…]";
                        meta.put("truncated", true);
                    }
                    meta.put("type", "pdf");
                    meta.put("chars", rawChars);
                    combinedText.append("# 文件 ").append(name).append("\n\n")
                                .append(text == null ? "" : text).append("\n\n");
                } else if (isImage(contentType)) {
                    if (size > IMAGE_BYTE_LIMIT) {
                        return ResponseEntity.badRequest().body(Map.of(
                            "error", "图片 " + name + " 超过 " + (IMAGE_BYTE_LIMIT / (1024*1024)) + " MB 限制"));
                    }
                    String b64 = Base64.getEncoder().encodeToString(f.getBytes());
                    String dataUrl = "data:" + (contentType != null ? contentType : "image/png") + ";base64," + b64;
                    Map<String, Object> att = new LinkedHashMap<>();
                    att.put("type", "image");
                    att.put("dataUrl", dataUrl);
                    imageAttachments.add(att);
                    meta.put("type", "image");
                } else {
                    meta.put("type", "skipped");
                    meta.put("reason", "不支持的 content-type：" + contentType);
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
            return ResponseEntity.internalServerError().body(Map.of(
                "error", e.getMessage() == null ? "抽取失败" : e.getMessage(),
                "sources", sourcesMeta));
        }
    }

    private static boolean isPdf(String name, String ct) {
        if (ct != null && ct.toLowerCase().contains("pdf")) return true;
        return name != null && name.toLowerCase().endsWith(".pdf");
    }

    private static boolean isImage(String ct) {
        return ct != null && ct.toLowerCase().startsWith("image/");
    }

    /**
     * 把 LLM 返回的 add_nodes / add_edges 整体加 salt 前缀（节点 id 和边 from/to 同步重写），
     * 保证导入草稿绝不会与已有图谱 / 其他导入批次撞 id。
     */
    private JsonNode applyImportSalt(JsonNode draft, String salt) {
        ArrayNode srcNodes = draft.has("add_nodes") && draft.get("add_nodes").isArray()
                ? (ArrayNode) draft.get("add_nodes") : objectMapper.createArrayNode();
        ArrayNode srcEdges = draft.has("add_edges") && draft.get("add_edges").isArray()
                ? (ArrayNode) draft.get("add_edges") : objectMapper.createArrayNode();

        Map<String, String> idMap = new HashMap<>();
        ArrayNode outNodes = objectMapper.createArrayNode();
        for (JsonNode n : srcNodes) {
            if (!n.has("id")) continue;
            String oldId = n.get("id").asText();
            String newId = "imp" + salt + "_" + oldId;
            idMap.put(oldId, newId);
            ObjectNode copy = n.deepCopy();
            copy.put("id", newId);
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
            outEdges.add(copy);
        }

        ObjectNode root = objectMapper.createObjectNode();
        root.set("nodes", outNodes);
        root.set("edges", outEdges);
        return root;
    }
}
