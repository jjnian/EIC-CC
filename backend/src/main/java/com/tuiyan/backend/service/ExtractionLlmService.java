package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tuiyan.backend.service.llm.LlmCallLogger;
import com.tuiyan.backend.service.llm.LlmHttpClient;
import com.tuiyan.backend.service.llm.LlmPrompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文档抽取业务 facade：把长文本切片喂给 LLM，再按 label 合并去重为统一的节点 / 边集合。
 * <p>设计要点：
 * <ul>
 *   <li>大文档一次喂给 LLM 容易超 context，所以按 {@link #EXTRACT_CHUNK_CHARS} 切分；</li>
 *   <li>切分后跨 chunk 可能重复识别同一实体，{@link #mergeExtractionByLabel} 按 label 归并；</li>
 *   <li>图片只在第一个 chunk 调用时挂上（避免重复发送 base64）。</li>
 * </ul>
 */
@Service
public class ExtractionLlmService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionLlmService.class);

    // 单次输入文本上限：超过则切分多次调用，最后用 mergeExtractionByLabel 合并
    private static final int EXTRACT_CHUNK_CHARS = 30_000;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LlmHttpClient http;
    private final LlmCallLogger callLogger;

    public ExtractionLlmService(LlmHttpClient http, LlmCallLogger callLogger) {
        this.http = http;
        this.callLogger = callLogger;
    }

    /** 抽取入口：合并文本 + 图片附件 → LLM 抽取 → 跨 chunk 合并去重 → 返回完整 JSON。 */
    public JsonNode extractOntologyFromSources(String combinedText,
                                               List<Map<String, Object>> imageAttachments,
                                               String modelOverride,
                                               String configId) throws IOException {
        LlmHttpClient.ResolvedConfig cfg = http.resolveConfig(modelOverride, configId);
        boolean anthropic = http.isAnthropic(cfg.baseURL(), cfg.modelName(), cfg.protocol());

        List<String> chunks = chunkText(combinedText, EXTRACT_CHUNK_CHARS);
        boolean hasImages = imageAttachments != null && !imageAttachments.isEmpty();

        log.info("[LLM-extract] 开始抽取 model={} url={} textChunks={} hasImages={}",
                cfg.modelName(), cfg.baseURL(), chunks.size(), hasImages);

        if (chunks.isEmpty() && !hasImages) {
            throw new IllegalArgumentException("No usable text or images for extraction");
        }
        if (chunks.isEmpty()) chunks = new ArrayList<>(List.of(""));

        JsonNode merged = null;
        for (int i = 0; i < chunks.size(); i++) {
            List<Map<String, Object>> imgs = (i == 0) ? imageAttachments : null;
            String preface = chunks.size() > 1
                    ? "（这是分 " + chunks.size() + " 段输入的第 " + (i + 1)
                      + " 段；语义相同的概念请保持 label 一致，便于跨段合并。）\n\n"
                    : "";
            JsonNode part = callExtractOnce(preface + chunks.get(i), imgs, cfg, anthropic);
            if (chunks.size() > 1) part = prefixChunkIds(part, "c" + i + "_");
            merged = (merged == null) ? part : mergeExtractionByLabel(merged, part);
        }
        return merged == null ? objectMapper.createObjectNode() : merged;
    }

    /** 单次 chunk 调用 LLM 抽取节点 / 边。文本与图片同时挂上让 LLM 跨模态理解文档。 */
    private JsonNode callExtractOnce(String userText,
                                     List<Map<String, Object>> imageAttachments,
                                     LlmHttpClient.ResolvedConfig cfg,
                                     boolean anthropic) throws IOException {
        boolean hasImages = imageAttachments != null && !imageAttachments.isEmpty();
        StringBuilder userPrompt = new StringBuilder();
        if (userText != null && !userText.isBlank()) {
            userPrompt.append("以下是从上传文档中提取的文本内容：\n\n----- BEGIN TEXT -----\n")
                      .append(userText)
                      .append("\n----- END TEXT -----\n\n");
        }
        if (hasImages) {
            userPrompt.append("用户还附上了 ").append(imageAttachments.size())
                      .append(" 张图片（流程图 / 截图 / 表格 / 示意图），请同时分析图中文字、")
                      .append("箭头指向、表格关系，把图中可见的实体和因果链也抽取出来。\n\n");
        }
        userPrompt.append("请抽取所有可识别的本体节点（含规则）与关系，按 SCHEMA 输出 JSON。");

        callLogger.logConversation("LLM-extract", cfg.modelName(), LlmPrompts.EXTRACT_SYSTEM,
                null, userPrompt.toString(), imageAttachments);

        String requestBody = http.buildBody(cfg, LlmPrompts.EXTRACT_SYSTEM, userPrompt.toString(),
                null, imageAttachments, false, true);

        HttpRequest httpReq = http.buildHttpRequest(cfg.baseURL(), cfg.apiKey(), anthropic, requestBody);
        long startTime = System.currentTimeMillis();
        HttpResponse<String> resp = http.sendHttp(httpReq, HttpResponse.BodyHandlers.ofString());
        long elapsed = System.currentTimeMillis() - startTime;

        if (resp.statusCode() != 200) {
            log.error("[LLM-extract] chunk请求失败 status={} 耗时={}ms", resp.statusCode(), elapsed);
            callLogger.logUpstreamError("extract", resp.statusCode(), resp.body());
            http.metrics().recordCall(cfg.modelName(), elapsed, false);
            throw new RuntimeException("LLM 调用失败 HTTP " + resp.statusCode() + "（详情见服务器日志）");
        }

        log.info("[LLM-extract] chunk请求成功 耗时={}ms 响应大小={} chars", elapsed, resp.body().length());
        http.metrics().recordCall(cfg.modelName(), elapsed, true);
        JsonNode root = objectMapper.readTree(resp.body());
        String content = http.stripJsonFence(http.extractContent(root, anthropic));
        callLogger.logLlmResponse("LLM-extract", cfg.modelName(), elapsed, content);
        return objectMapper.readTree(content);
    }

    /**
     * 长文本按字符上限切片，但优先在自然边界（换行 / 句号）切，避免把句子从中间切断。
     * 边界搜索范围限制在 [maxChars/2, maxChars]。
     */
    private List<String> chunkText(String text, int maxChars) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        if (text.length() <= maxChars) { out.add(text); return out; }
        int n = text.length();
        int idx = 0;
        while (idx < n) {
            int end = Math.min(n, idx + maxChars);
            if (end < n) {
                int bp = text.lastIndexOf('\n', end);
                if (bp <= idx + maxChars / 2) bp = text.lastIndexOf('。', end);
                if (bp <= idx + maxChars / 2) bp = text.lastIndexOf('.', end);
                if (bp > idx + maxChars / 2) end = bp + 1;
            }
            out.add(text.substring(idx, end));
            idx = end;
        }
        return out;
    }

    /**
     * 给某 chunk 的所有节点 / 边 id 加 chunk 前缀（如 "c0_n1"），并把边的 from/to 同步重写。
     * <p>多 chunk 抽取时不同 chunk 内 LLM 都用 n1/n2 命名，加前缀避免合并时冲突。
     */
    private JsonNode prefixChunkIds(JsonNode part, String prefix) {
        ObjectNode out = objectMapper.createObjectNode();
        if (part.has("reply")) out.set("reply", part.get("reply"));

        Map<String, String> idMap = new HashMap<>();
        ArrayNode srcNodes = part.has("add_nodes") && part.get("add_nodes").isArray()
                ? (ArrayNode) part.get("add_nodes") : objectMapper.createArrayNode();
        ArrayNode outNodes = objectMapper.createArrayNode();
        for (JsonNode n : srcNodes) {
            ObjectNode copy = n.deepCopy();
            String oldId = copy.path("id").asText("");
            if (oldId.isEmpty()) continue;
            String newId = prefix + oldId;
            idMap.put(oldId, newId);
            copy.put("id", newId);
            outNodes.add(copy);
        }
        ArrayNode srcEdges = part.has("add_edges") && part.get("add_edges").isArray()
                ? (ArrayNode) part.get("add_edges") : objectMapper.createArrayNode();
        ArrayNode outEdges = objectMapper.createArrayNode();
        for (JsonNode e : srcEdges) {
            ObjectNode copy = e.deepCopy();
            String f = copy.path("from").asText("");
            String t = copy.path("to").asText("");
            if (idMap.containsKey(f)) copy.put("from", idMap.get(f));
            if (idMap.containsKey(t)) copy.put("to", idMap.get(t));
            String eid = copy.path("id").asText("");
            if (!eid.isEmpty()) copy.put("id", prefix + eid);
            outEdges.add(copy);
        }
        out.set("add_nodes", outNodes);
        out.set("add_edges", outEdges);
        return out;
    }

    /**
     * 跨 chunk 合并：按 normalized label 去重节点，把重复节点的 props 合并，
     * 并对重复 id 做 from/to 重映射。
     */
    private JsonNode mergeExtractionByLabel(JsonNode a, JsonNode b) {
        ObjectNode out = objectMapper.createObjectNode();
        if (a.has("reply")) out.set("reply", a.get("reply"));

        ArrayNode outNodes = objectMapper.createArrayNode();
        ArrayNode outEdges = objectMapper.createArrayNode();
        Map<String, String> labelToId = new HashMap<>();
        Map<String, ObjectNode> idToNode = new HashMap<>();
        Map<String, String> idRemap = new HashMap<>();

        for (JsonNode n : a.path("add_nodes")) {
            ObjectNode copy = n.deepCopy();
            outNodes.add(copy);
            String norm = normalizeLabel(copy.path("label").asText(""));
            String id = copy.path("id").asText("");
            if (!norm.isEmpty()) labelToId.put(norm, id);
            if (!id.isEmpty()) idToNode.put(id, copy);
        }
        for (JsonNode e : a.path("add_edges")) outEdges.add(e);

        for (JsonNode n : b.path("add_nodes")) {
            String norm = normalizeLabel(n.path("label").asText(""));
            String id = n.path("id").asText("");
            if (!norm.isEmpty() && labelToId.containsKey(norm)) {
                String aId = labelToId.get(norm);
                idRemap.put(id, aId);
                ObjectNode existing = idToNode.get(aId);
                if (existing != null) {
                    mergeNodeProps(existing, n);
                }
            } else {
                ObjectNode copy = n.deepCopy();
                outNodes.add(copy);
                if (!norm.isEmpty() && !id.isEmpty()) {
                    labelToId.put(norm, id);
                    idToNode.put(id, copy);
                }
            }
        }
        for (JsonNode e : b.path("add_edges")) {
            ObjectNode copy = e.deepCopy();
            String f = copy.path("from").asText("");
            String t = copy.path("to").asText("");
            copy.put("from", idRemap.getOrDefault(f, f));
            copy.put("to", idRemap.getOrDefault(t, t));
            outEdges.add(copy);
        }
        out.set("add_nodes", outNodes);
        out.set("add_edges", outEdges);
        return out;
    }

    /** 合并节点 props：以 a 为基准，从 b 加入 a 没有的 key（同 key 取 a 的优先）。 */
    private void mergeNodeProps(ObjectNode aNode, JsonNode bNode) {
        JsonNode aProps = aNode.path("props");
        JsonNode bProps = bNode.path("props");
        if (!bProps.isArray() || bProps.isEmpty()) return;
        ArrayNode merged;
        Set<String> seenKeys = new HashSet<>();
        if (aProps.isArray()) {
            merged = (ArrayNode) aProps;
            for (JsonNode p : aProps) {
                String k = p.path("key").asText("");
                if (!k.isEmpty()) seenKeys.add(k);
            }
        } else {
            merged = objectMapper.createArrayNode();
        }
        for (JsonNode p : bProps) {
            String k = p.path("key").asText("");
            if (k.isEmpty()) continue;
            if (seenKeys.contains(k)) continue;
            merged.add(p.deepCopy());
            seenKeys.add(k);
        }
        aNode.set("props", merged);
    }

    /** label 标准化：trim + 小写 + 多空白合一；用于 chunk 间去重的等价判断。 */
    private static String normalizeLabel(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase().replaceAll("\\s+", " ");
    }
}
