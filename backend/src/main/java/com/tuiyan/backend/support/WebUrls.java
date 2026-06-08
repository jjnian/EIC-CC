package com.tuiyan.backend.support;

import java.net.URI;

/**
 * web 系统入口地址的规范化与校验。
 * <p>用户常只填 {@code www.example.com} 甚至 {@code asdasd}：前者缺 scheme 会让 Playwright 报
 * "Cannot navigate to invalid URL"，后者根本不是网址。这里统一补全 {@code https://} 并校验 host，
 * 在启动浏览器之前就给出可读的错误，避免把底层栈直接抛给用户。
 */
public final class WebUrls {

    private WebUrls() {}

    private static final String EXAMPLE = "（示例：https://example.com）";

    /**
     * 规范化系统入口地址：缺少 {@code http(s)://} 时补 {@code https://}，并校验 host 合法。
     * @return 规范化后的 URL（一定带 scheme）
     * @throws IllegalArgumentException 地址为空、或不是合法网址（无 host / host 无点且非 localhost）
     */
    public static String normalizeEntryUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("系统入口地址不能为空");
        }
        String url = raw.trim();
        if (!url.matches("(?i)^https?://.*")) {
            url = "https://" + url;
        }
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("系统入口地址「" + raw.trim() + "」不是合法的网址" + EXAMPLE);
        }
        // host 为空，或既不是 localhost 又不含「.」（如 asdasd）→ 不是可访问的网址
        if (host == null || host.isBlank()
                || (!host.equalsIgnoreCase("localhost") && !host.contains("."))) {
            throw new IllegalArgumentException("系统入口地址「" + raw.trim() + "」不是合法的网址" + EXAMPLE);
        }
        return url;
    }
}
