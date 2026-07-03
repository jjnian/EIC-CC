package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.service.extraction.ProvenanceValidator;
import com.tuiyan.backend.service.llm.LlmCallLogger;
import com.tuiyan.backend.service.llm.LlmHttpClient;
import com.tuiyan.backend.service.llm.prompt.ExtractPrompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文档抽取业务 facade：把长文本切片喂给 LLM，再按 label 合并去重为统一的节点 / 边集合。
 * <p>设计要点：
 * <ul>
 *   <li>大文档一次喂给 LLM 容易超 context，所以按 {@link #EXTRACT_CHUNK_CHARS} 切分；</li>
 *   <li>切分后跨 chunk 可能重复识别同一实体，由 {@link ExtractionGraphMerger} 按 label 归并；</li>
 *   <li>图片只在第一个 chunk 调用时挂上（避免重复发送 base64）。</li>
 * </ul>
 * <p>本类只负责抽取编排与 LLM 的 HTTP 调用；切片、chunk id 前缀、跨 chunk 合并、图校验等
 * 纯 JSON / 文本处理统一委派给 {@link ExtractionGraphMerger}。
 */
@Service
public class ExtractionLlmService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionLlmService.class);

    // 单次输入文本上限：超过则切分多次调用，最后用 merger 合并
    private static final int EXTRACT_CHUNK_CHARS = 30_000;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LlmHttpClient http;
    private final LlmCallLogger callLogger;
    private final ExtractionGraphMerger merger;

    public ExtractionLlmService(LlmHttpClient http, LlmCallLogger callLogger, ExtractionGraphMerger merger) {
        this.http = http;
        this.callLogger = callLogger;
        this.merger = merger;
    }

    /** 抽取入口：合并文本 + 图片附件 → LLM 抽取 → 跨 chunk 合并去重 → 返回完整 JSON。 */
    public JsonNode extractOntologyFromSources(String combinedText,
                                               List<Map<String, Object>> imageAttachments,
                                               String modelOverride,
                                               String configId) throws IOException {
        return extractOntologyFromSources(combinedText, imageAttachments, modelOverride, configId, null);
    }

    /**
     * 抽取入口（可指定 system prompt）：{@code systemPrompt} 为 null 时用通用文档抽取
     * {@link ExtractPrompts#EXTRACT_SYSTEM}；DDL/schema 类结构化输入可传
     * {@link ExtractPrompts#SCHEMA_TO_ONTOLOGY_SYSTEM}，其映射规则确定、反幻觉约束更严。
     */
    public JsonNode extractOntologyFromSources(String combinedText,
                                               List<Map<String, Object>> imageAttachments,
                                               String modelOverride,
                                               String configId,
                                               String systemPrompt) throws IOException {
        String system = (systemPrompt == null || systemPrompt.isBlank())
                ? ExtractPrompts.EXTRACT_SYSTEM : systemPrompt;
        LlmHttpClient.ResolvedConfig cfg = http.resolveConfig(modelOverride, configId);
        boolean anthropic = http.isAnthropic(cfg.baseURL(), cfg.modelName(), cfg.protocol());

        List<String> chunks = merger.chunkText(combinedText, EXTRACT_CHUNK_CHARS);
        boolean hasImages = imageAttachments != null && !imageAttachments.isEmpty();

        log.info("[LLM-extract] 开始抽取 model={} url={} textChunks={} hasImages={}",
                cfg.modelName(), cfg.baseURL(), chunks.size(), hasImages);

        if (chunks.isEmpty() && !hasImages) {
            throw new IllegalArgumentException("No usable text or images for extraction");
        }
        if (chunks.isEmpty()) chunks = new ArrayList<>(List.of(""));

        JsonNode merged = null;
        ProvenanceValidator.Stats provenance = ProvenanceValidator.Stats.empty();
        // evidence 落地校验只对「纯文本 + 通用抽取规则」的段生效：
        // 图片段的实体来自图内容、无法对回文本；DDL/schema 段的 evidence 是结构引用而非原文引文。
        boolean generalProse = ExtractPrompts.EXTRACT_SYSTEM.equals(system);
        for (int i = 0; i < chunks.size(); i++) {
            List<Map<String, Object>> imgs = (i == 0) ? imageAttachments : null;
            String preface = chunks.size() > 1
                    ? "（这是分 " + chunks.size() + " 段输入的第 " + (i + 1)
                      + " 段；语义相同的概念请保持 label 一致，便于跨段合并。）\n\n"
                    : "";
            // 跨片连边：把前面段落已识别的实体（含其稳定 id）回灌给本段，
            // 这样本段产生的关系才能直接连到“别的段落里”的实体，避免血缘链在段边界断裂。
            preface += merger.knownEntitiesPreface(merged);
            JsonNode part = callExtractOnce(preface + chunks.get(i), imgs, cfg, anthropic, system);
            // 事实性校验：derived 的 evidence 须能在本段原文命中，否则降级 inferred；confidence 越界收敛。
            // 对回的是本段正文（不含 preface），避免回灌的实体清单让幻觉引文误判为"有据"。
            boolean grounding = generalProse && (imgs == null || imgs.isEmpty());
            provenance = provenance.plus(ProvenanceValidator.validate(part, chunks.get(i), grounding));
            if (chunks.size() > 1) part = merger.prefixChunkIds(part, "c" + i + "_");
            merged = (merged == null) ? part : merger.mergeExtractionByLabel(merged, part);
        }
        if (provenance.any()) {
            log.info("[LLM-extract] 事实性校验：{} 个元素 evidence 未命中原文已降级为 inferred，{} 个 confidence 越界已收敛",
                    provenance.downgraded(), provenance.clamped());
        }
        return merger.sanitizeGraph(merged == null ? objectMapper.createObjectNode() : merged);
    }

    /**
     * 单次原样调用：给定 system prompt 与完整输入文本（原样作为 user 消息，不加文档包装、
     * 不切片、不合并、不做图校验），返回 LLM 的 JSON 输出。
     * <p>供「跨批连边」等非文档抽取的结构化小任务复用同一套 LLM 配置 / 日志 / 指标。
     * 调用方自行负责结果过滤与校验。
     */
    public JsonNode extractRaw(String userText, String systemPrompt,
                               String modelOverride, String configId) throws IOException {
        LlmHttpClient.ResolvedConfig cfg = http.resolveConfig(modelOverride, configId);
        boolean anthropic = http.isAnthropic(cfg.baseURL(), cfg.modelName(), cfg.protocol());
        callLogger.logConversation("LLM-extract-raw", cfg.modelName(), systemPrompt, null, userText, null);
        String requestBody = http.buildBody(cfg, systemPrompt, userText,
                null, null, false, true, LlmHttpClient.EXTRACT_TEMPERATURE);
        HttpRequest req = http.buildHttpRequest(cfg.baseURL(), cfg.apiKey(), anthropic, requestBody, cfg.rawUrl());
        long start = System.currentTimeMillis();
        HttpResponse<String> resp = http.sendHttp(req, HttpResponse.BodyHandlers.ofString());
        long elapsed = System.currentTimeMillis() - start;
        if (resp.statusCode() != 200) {
            log.error("[LLM-extract-raw] 请求失败 status={} 耗时={}ms", resp.statusCode(), elapsed);
            callLogger.logUpstreamError("extract-raw", resp.statusCode(), resp.body());
            http.metrics().recordCall(cfg.modelName(), elapsed, false);
            throw new RuntimeException("LLM 调用失败 HTTP " + resp.statusCode() + "（详情见服务器日志）");
        }
        http.metrics().recordCall(cfg.modelName(), elapsed, true);
        JsonNode root = objectMapper.readTree(resp.body());
        String content = http.stripJsonFence(http.extractContent(root, anthropic));
        callLogger.logLlmResponse("LLM-extract-raw", cfg.modelName(), elapsed, content);
        return objectMapper.readTree(content);
    }

    /** 单次 chunk 调用 LLM 抽取节点 / 边。文本与图片同时挂上让 LLM 跨模态理解文档。 */
    private JsonNode callExtractOnce(String userText,
                                     List<Map<String, Object>> imageAttachments,
                                     LlmHttpClient.ResolvedConfig cfg,
                                     boolean anthropic,
                                     String systemPrompt) throws IOException {
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

        callLogger.logConversation("LLM-extract", cfg.modelName(), systemPrompt,
                null, userPrompt.toString(), imageAttachments);

        String requestBody = http.buildBody(cfg, systemPrompt, userPrompt.toString(),
                null, imageAttachments, false, true, LlmHttpClient.EXTRACT_TEMPERATURE);

        HttpRequest httpReq = http.buildHttpRequest(cfg.baseURL(), cfg.apiKey(), anthropic, requestBody, cfg.rawUrl());
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
}
