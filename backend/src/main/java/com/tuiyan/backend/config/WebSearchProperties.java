package com.tuiyan.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 联网调研的搜索后端配置：从 {@code application.yml} 的 {@code app.web-search.*} 读取。
 * <p>免 key 的搜索引擎爬取(DuckDuckGo 等)已被反爬封禁，故改为可配置的带 key provider。
 * provider=brave|serper 需配 api-key；provider=duckduckgo 为遗留爬取(通常已不可用)。
 */
@Component
@ConfigurationProperties(prefix = "app.web-search")
public class WebSearchProperties {

    /** 搜索后端：brave | serper | duckduckgo。 */
    private String provider = "brave";
    /** provider 的 API Key（brave/serper 必填；duckduckgo 忽略）。 */
    private String apiKey;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
}
