package com.tuiyan.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 音频转写 (ASR) 配置：从 {@code application.yml} 的 {@code app.asr.*} 读取。
 * <p>采用 OpenAI 兼容的 {@code /audio/transcriptions} 协议（multipart 上传音频，返回转写文本）。
 * <p>{@code base-url} / {@code api-key} 留空时，
 * {@link com.tuiyan.backend.service.extraction.AudioTranscriptionService} 会回退使用
 * {@code app.llm} 第一个模型的 base-url / api-key —— 仅当该提供商支持音频转写
 * （如 OpenAI、通义千问 compatible-mode）时才有效；DeepSeek 等不提供该接口的提供商需在此单独配置。
 */
@Component
@ConfigurationProperties(prefix = "app.asr")
public class AsrProperties {

    /** 是否启用音频转写；false 时音频文件按 skipped 处理。 */
    private boolean enabled = true;
    /** ASR 服务 base-url（如 https://api.openai.com/v1）；留空回退 LLM 配置。 */
    private String baseUrl = "";
    /** ASR 服务 api-key；留空回退 LLM 配置。 */
    private String apiKey = "";
    /** 转写模型名（OpenAI 为 whisper-1）。 */
    private String model = "whisper-1";
    /** 音频语言（ISO-639-1，如 zh / en）；留空让服务端自动识别。 */
    private String language = "";
    /**
     * 术语热词提示（Whisper `prompt` 参数）：填入行业术语/系统名/表名等专有词汇（逗号或空格分隔的短语即可），
     * 可显著降低访谈录音里专有名词的转写错误。留空不发送。
     */
    private String prompt = "";
    /** 是否在转写文本中保留分段时间戳 {@code [mm:ss]}（走 verbose_json，便于回溯血缘）。 */
    private boolean timestamps = false;
    /** 单个音频字节上限（默认 25MB，与 OpenAI Whisper 限制一致）。 */
    private long maxBytes = 25L * 1024 * 1024;
    /** 转写请求超时秒数（长音频较慢）。 */
    private int timeoutSeconds = 120;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public boolean isTimestamps() { return timestamps; }
    public void setTimestamps(boolean timestamps) { this.timestamps = timestamps; }
    public long getMaxBytes() { return maxBytes; }
    public void setMaxBytes(long maxBytes) { this.maxBytes = maxBytes; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
}
