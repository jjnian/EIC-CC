package com.tuiyan.backend.service.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.config.AsrProperties;
import com.tuiyan.backend.config.LlmProperties;
import com.tuiyan.backend.service.llm.LlmConfigResolver;
import com.tuiyan.backend.service.llm.LlmHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 音频转写 (ASR) 服务：把音频字节 POST 到 OpenAI 兼容的 {@code /audio/transcriptions} 端点，拿回文本。
 * <p>端点 / 鉴权按下面优先级解析：
 * <ol>
 *   <li>{@link LlmProperties} 中第一个 enabled 且声明 capability {@code asr} 的模型条目
 *       —— 推荐方式：在 {@code app.llm.models} 里加一条音频转写模型，
 *       和对话模型一样统一管理；</li>
 *   <li>{@link AsrProperties}（{@code app.asr.*}）—— 兼容旧配置；</li>
 *   <li>仍未配齐时回退到 {@link LlmConfigResolver} 的第一个 LLM（仅当该提供商支持音频转写时有效）。</li>
 * </ol>
 * HTTP 发送复用 {@link LlmHttpClient} 的连接池。
 * <p>失败时抛异常，由 {@link AudioFileHandler} 捕获并把该音频标记为 skipped——不影响整批其它文件抽取。
 */
@Service
public class AudioTranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(AudioTranscriptionService.class);
    /** ModelEntry.capabilities 中声明此值表示「该模型用于音频转文字」。 */
    public static final String CAPABILITY_ASR = "asr";

    private final AsrProperties props;
    private final LlmProperties llmProperties;
    private final LlmConfigResolver llmConfigResolver;
    private final LlmHttpClient llmHttp;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AudioTranscriptionService(AsrProperties props,
                                     LlmProperties llmProperties,
                                     LlmConfigResolver llmConfigResolver,
                                     LlmHttpClient llmHttp) {
        this.props = props;
        this.llmProperties = llmProperties;
        this.llmConfigResolver = llmConfigResolver;
        this.llmHttp = llmHttp;
    }

    public boolean enabled() { return props.isEnabled(); }
    public long maxBytes() { return props.getMaxBytes(); }

    /** 找 {@code app.llm.models} 中第一个 enabled 且声明 {@code asr} capability 的模型；无则 null。 */
    private LlmProperties.ModelEntry findAsrModelEntry() {
        for (LlmProperties.ModelEntry m : llmProperties.getModels()) {
            if (!m.isEnabled()) continue;
            List<String> caps = m.getCapabilities();
            if (caps == null) continue;
            for (String c : caps) if (CAPABILITY_ASR.equalsIgnoreCase(c)) return m;
        }
        return null;
    }

    /** 转写结果：文本 + 可选时长（秒）+ 实际使用的模型名。 */
    public record TranscriptResult(String text, Double durationSeconds, String model) {}

    /**
     * 转写一段音频。成功返回文本（{@code app.asr.timestamps=true} 时每段前缀 {@code [mm:ss]}）；
     * 端点未配置 / HTTP 非 200 / 响应不可解析时抛异常。
     */
    public TranscriptResult transcribe(byte[] audio, String filename, String contentType) throws IOException {
        // 1) 优先使用 app.llm.models 中带 capability=asr 的模型
        LlmProperties.ModelEntry asrModel = findAsrModelEntry();
        String baseUrl = null;
        String apiKey = null;
        String modelName = null;
        String source;
        if (asrModel != null) {
            baseUrl = blankToNull(asrModel.getBaseUrl());
            apiKey = blankToNull(asrModel.getApiKey());
            modelName = blankToNull(asrModel.getModelName());
            source = "llm.models[" + asrModel.getId() + "]";
        } else {
            // 2) 兼容旧的 app.asr.* 配置
            baseUrl = blankToNull(props.getBaseUrl());
            apiKey = blankToNull(props.getApiKey());
            modelName = blankToNull(props.getModel());
            source = "asr.properties";
            // 3) 还缺则回退到首个 LLM（仅当该提供商支持 /audio/transcriptions 时才可用）
            if (baseUrl == null || apiKey == null) {
                try {
                    LlmHttpClient.ResolvedConfig llm = llmConfigResolver.resolveConfig(null, null);
                    if (baseUrl == null) baseUrl = llm.baseURL();
                    if (apiKey == null) apiKey = llm.apiKey();
                    source = "llm.first";
                } catch (RuntimeException ignore) {
                    // 留空，下面统一报 "未配置 ASR"
                }
            }
        }

        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank() || modelName == null || modelName.isBlank()) {
            throw new IllegalStateException(
                    "未配置可用的音频转写模型（请在 app.llm.models 中加一条 capabilities: [asr] 的模型，或填写 app.asr.base-url/api-key/model）");
        }

        boolean verbose = props.isTimestamps();
        String url = joinUrl(baseUrl, "audio/transcriptions");
        String boundary = "----TuiyanAsr" + Long.toHexString(System.nanoTime());
        byte[] body = buildMultipart(boundary, audio, filename, guessAudioMime(filename, contentType), verbose, modelName);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(10, props.getTimeoutSeconds())))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        long start = System.currentTimeMillis();
        HttpResponse<String> resp = llmHttp.sendHttp(req, HttpResponse.BodyHandlers.ofString());
        long elapsed = System.currentTimeMillis() - start;

        if (resp.statusCode() != 200) {
            String snippet = resp.body() == null ? "" : resp.body().substring(0, Math.min(300, resp.body().length()));
            log.warn("[ASR] 转写失败 source={} model={} status={} 耗时={}ms body={}",
                    source, modelName, resp.statusCode(), elapsed, snippet);
            throw new RuntimeException("ASR 调用失败 HTTP " + resp.statusCode() + "：" + snippet);
        }
        log.info("[ASR] 转写成功 source={} model={} 耗时={}ms 响应={}字符",
                source, modelName, elapsed, resp.body().length());
        return parseResponse(resp.body(), verbose, modelName);
    }

    /** 解析转写响应：verbose_json 拼 {@code [mm:ss] 文本} 分段，否则直接取 {@code text} 字段。 */
    private TranscriptResult parseResponse(String responseBody, boolean verbose, String modelName) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        Double duration = root.path("duration").isNumber() ? root.get("duration").asDouble() : null;
        if (verbose && root.path("segments").isArray() && root.get("segments").size() > 0) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode seg : root.get("segments")) {
                String segText = seg.path("text").asText("").strip();
                if (segText.isEmpty()) continue;
                sb.append(fmtTimestamp(seg.path("start").asDouble(0))).append(' ').append(segText).append('\n');
            }
            String text = sb.toString().strip();
            if (!text.isEmpty()) return new TranscriptResult(text, duration, modelName);
        }
        // 默认 / 回退：直接取 text 字段
        return new TranscriptResult(root.path("text").asText("").strip(), duration, modelName);
    }

    /** 手工拼 multipart/form-data：model + [language] + [prompt 热词] + response_format + file（{@link HttpRequest} 无内建 multipart）。 */
    private byte[] buildMultipart(String boundary, byte[] audio, String filename, String mime, boolean verbose, String modelName) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<String[]> fields = new ArrayList<>();
        fields.add(new String[]{"model", modelName});
        if (props.getLanguage() != null && !props.getLanguage().isBlank()) {
            fields.add(new String[]{"language", props.getLanguage().strip()});
        }
        // 术语热词：Whisper 会参考 prompt 里的专有词汇拼写，降低行业术语/系统名/表名的转写错误
        if (props.getPrompt() != null && !props.getPrompt().isBlank()) {
            fields.add(new String[]{"prompt", props.getPrompt().strip()});
        }
        fields.add(new String[]{"response_format", verbose ? "verbose_json" : "json"});

        for (String[] kv : fields) {
            writeText(out, "--" + boundary + "\r\n");
            writeText(out, "Content-Disposition: form-data; name=\"" + kv[0] + "\"\r\n\r\n");
            writeText(out, kv[1]);
            writeText(out, "\r\n");
        }
        // file part
        writeText(out, "--" + boundary + "\r\n");
        writeText(out, "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n");
        writeText(out, "Content-Type: " + mime + "\r\n\r\n");
        out.write(audio);
        writeText(out, "\r\n");
        writeText(out, "--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private static void writeText(ByteArrayOutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String fmtTimestamp(double seconds) {
        int total = (int) Math.floor(Math.max(0, seconds));
        return String.format("[%02d:%02d]", total / 60, total % 60);
    }

    /** baseUrl 与 path 之间规范化一个斜杠，避免出现 {@code //} 或缺斜杠。 */
    private static String joinUrl(String baseUrl, String path) {
        String b = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return b + "/" + path;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /** 依据 content-type / 扩展名猜 MIME；猜不出回退 application/octet-stream。 */
    private static String guessAudioMime(String filename, String contentType) {
        if (contentType != null && contentType.toLowerCase().startsWith("audio/")) return contentType;
        String n = filename == null ? "" : filename.toLowerCase();
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".m4a") || n.endsWith(".mp4")) return "audio/mp4";
        if (n.endsWith(".aac")) return "audio/aac";
        if (n.endsWith(".flac")) return "audio/flac";
        if (n.endsWith(".ogg") || n.endsWith(".oga") || n.endsWith(".opus")) return "audio/ogg";
        if (n.endsWith(".amr")) return "audio/amr";
        if (n.endsWith(".webm")) return "audio/webm";
        return "application/octet-stream";
    }
}
