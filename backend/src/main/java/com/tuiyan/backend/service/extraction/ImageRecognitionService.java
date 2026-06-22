package com.tuiyan.backend.service.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.config.LlmProperties;
import com.tuiyan.backend.service.llm.LlmHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 图片识别服务：把图片交给多模态(视觉)大模型，识别出其中的文字(OCR)与关键信息，返回纯文本。
 * <p>模型解析优先级：
 * <ol>
 *   <li>{@link LlmProperties} 中第一个 enabled 且声明 capability {@code vision} 的模型条目（推荐）；</li>
 *   <li>否则回退到默认(首个) LLM —— 仅当其支持视觉输入时才有效（如 DeepSeek 纯文本模型会识别失败）。</li>
 * </ol>
 * 复用 {@link LlmHttpClient}（连接池 + 协议适配 + 响应解析）。失败时抛异常，由调用方决定降级。
 */
@Service
public class ImageRecognitionService {

    private static final Logger log = LoggerFactory.getLogger(ImageRecognitionService.class);
    /** ModelEntry.capabilities 中声明此值表示「该模型支持图片识别(视觉)」。 */
    public static final String CAPABILITY_VISION = "vision";
    /** 单张图片字节上限（8MB，与抽取管线 ImageFileHandler 一致）。 */
    private static final long MAX_BYTES = 8L * 1024 * 1024;

    private static final String SYSTEM_PROMPT =
            "你是图片内容识别助手。请仔细观察图片，完整输出其中的信息，用于沉淀成知识文档：\n"
            + "1）逐字转写图片中所有可见文字（OCR），保持原有顺序与层级；\n"
            + "2）若是流程图/架构图/表格/示意图，描述其中的实体、节点、箭头指向与关系；\n"
            + "3）用简洁中文组织，不要编造图中没有的内容。直接输出正文，不要寒暄。";

    private final LlmProperties llmProperties;
    private final LlmHttpClient http;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ImageRecognitionService(LlmProperties llmProperties, LlmHttpClient http) {
        this.llmProperties = llmProperties;
        this.http = http;
    }

    public long maxBytes() { return MAX_BYTES; }

    /** 找 {@code app.llm.models} 中第一个 enabled 且声明 {@code vision} capability 的模型 id；无则 null。 */
    private String findVisionModelId() {
        for (LlmProperties.ModelEntry m : llmProperties.getModels()) {
            if (!m.isEnabled()) continue;
            List<String> caps = m.getCapabilities();
            if (caps == null) continue;
            for (String c : caps) if (CAPABILITY_VISION.equalsIgnoreCase(c)) return m.getId();
        }
        return null;
    }

    /**
     * 识别一张图片，返回文字 + 关键信息描述。
     * @return 识别正文；模型不支持视觉 / HTTP 非 200 / 响应不可解析时抛异常。
     */
    public String recognize(byte[] image, String filename, String contentType) throws IOException {
        String visionId = findVisionModelId();
        LlmHttpClient.ResolvedConfig cfg = http.resolveConfig(null, visionId); // visionId=null 时回退默认首个模型
        boolean anthropic = http.isAnthropic(cfg.baseURL(), cfg.modelName(), cfg.protocol());

        String b64 = Base64.getEncoder().encodeToString(image);
        String mediaType = (contentType != null && contentType.toLowerCase().startsWith("image/"))
                ? contentType : "image/png";
        Map<String, Object> attachment = Map.of(
                "type", "image",
                "dataUrl", "data:" + mediaType + ";base64," + b64);

        String userText = "请识别这张图片（文件名：" + filename + "）的内容并按要求输出。";
        String body = http.buildBody(cfg, SYSTEM_PROMPT, userText, null, List.of(attachment),
                false, false, LlmHttpClient.EXTRACT_TEMPERATURE);
        HttpRequest req = http.buildHttpRequest(cfg.baseURL(), cfg.apiKey(), anthropic, body, cfg.rawUrl());

        long start = System.currentTimeMillis();
        HttpResponse<String> resp = http.sendHttp(req, HttpResponse.BodyHandlers.ofString());
        long elapsed = System.currentTimeMillis() - start;

        if (resp.statusCode() != 200) {
            String snippet = resp.body() == null ? "" : resp.body().substring(0, Math.min(300, resp.body().length()));
            log.warn("[Vision] 图片识别失败 model={} status={} 耗时={}ms body={}",
                    cfg.modelName(), resp.statusCode(), elapsed, snippet);
            throw new RuntimeException("图片识别调用失败 HTTP " + resp.statusCode() + "：" + snippet);
        }
        JsonNode root = objectMapper.readTree(resp.body());
        String text = http.extractContent(root, anthropic);
        log.info("[Vision] 图片识别成功 model={} 耗时={}ms 文本={}字符",
                cfg.modelName(), elapsed, text == null ? 0 : text.length());
        return text == null ? "" : text.strip();
    }
}
