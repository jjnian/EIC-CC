package com.tuiyan.backend.support;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * web 系统入口地址的规范化与校验。
 * <p>用户常只填 {@code www.example.com}（缺 scheme），会让 Playwright 报
 * "Cannot navigate to invalid URL"。这里统一补全 {@code https://} 并校验 host 非空，
 * 在启动浏览器之前就给出可读的错误，避免把底层栈直接抛给用户。
 * <p>注意：单标签主机名（如 {@code https://gitlab}、{@code https://asdasd}）是合法 URL，
 * 内网/开发环境很常见，这里不强制要求域名含「.」；解析不了的地址交由浏览器导航时报错。
 */
public final class WebUrls {

    private WebUrls() {}

    private static final String EXAMPLE = "（示例：https://example.com）";

    /**
     * 规范化系统入口地址：缺少 {@code http(s)://} 时补 {@code https://}，并校验 host 非空。
     * @return 规范化后的 URL（一定带 scheme）
     * @throws IllegalArgumentException 地址为空、或不是合法网址（解析失败 / 无 host）
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
        // host 为空（如 "https://" 后面没东西、含非法字符）才判非法；
        // 单标签主机名（asdasd / gitlab 等内网地址）放行，能否访问交给浏览器导航时判断。
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("系统入口地址「" + raw.trim() + "」不是合法的网址" + EXAMPLE);
        }
        rejectCloudMetadata(host, raw.trim());
        return url;
    }

    /**
     * 拒绝指向「链路本地地址」(169.254.0.0/16 / fe80::/10) 的入口，主要挡住云元数据服务
     * 169.254.169.254（窃取实例临时凭据的经典 SSRF 目标）。
     * <p>有意只拦链路本地，不拦 loopback / 私网（10./192.168./127.0.0.1）——探索内网业务系统
     * 是本功能的正当用途。解析不了的主机名（纯内网域名）放行，保持原有行为。
     */
    private static void rejectCloudMetadata(String host, String raw) {
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return; // 解析不了：交由后续导航判断，保持原行为
        }
        for (InetAddress a : addrs) {
            if (a.isLinkLocalAddress()) {
                throw new IllegalArgumentException(
                        "系统入口地址「" + raw + "」指向链路本地/云元数据地址（169.254.x.x），已拒绝");
            }
        }
    }
}
