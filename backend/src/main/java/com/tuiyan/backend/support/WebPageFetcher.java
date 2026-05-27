package com.tuiyan.backend.support;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

/**
 * 抓取网页 → 提取正文文本。
 * <p>策略：
 * <ol>
 *   <li>校验 URL：仅 http/https，禁止解析到内网 / link-local / metadata 段（防 SSRF）；</li>
 *   <li>Jsoup 拉静态 HTML，按启发式去掉 nav/footer/script/style，优先取 article/main；</li>
 *   <li>若文本过短（&lt; 500 字符），疑似 SPA，回落到 Playwright headless 渲染再抽一次；</li>
 *   <li>Playwright 懒加载 + 全局单例，首次失败（如未下载 Chromium）会永久禁用 headless 兜底。</li>
 * </ol>
 */
public final class WebPageFetcher {

    private static final Logger log = LoggerFactory.getLogger(WebPageFetcher.class);

    public static final int  READ_TIMEOUT_MS    = 15_000;
    public static final long HTML_BYTE_LIMIT    = 5L * 1024 * 1024;
    public static final int  TEXT_CHAR_BUDGET   = 60_000;
    public static final int  HEADLESS_THRESHOLD = 500;
    public static final int  HEADLESS_TIMEOUT_MS = 25_000;

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/123.0 Safari/537.36 EIC-CC/1.0";

    private static volatile Playwright playwrightInstance;
    private static volatile Browser sharedBrowser;
    private static volatile boolean headlessDisabled = false;

    private WebPageFetcher() {}

    public static Result fetch(String rawUrl) throws IOException {
        URI uri = parseAndValidate(rawUrl);
        String urlStr = uri.toString();

        Result out = new Result();
        out.url = urlStr;
        out.title = "";
        out.text = "";

        try {
            Document doc = Jsoup.connect(urlStr)
                    .userAgent(UA)
                    .timeout(READ_TIMEOUT_MS)
                    .maxBodySize((int) HTML_BYTE_LIMIT)
                    .followRedirects(true)
                    .get();
            out.title = doc.title();
            out.text = extractMainText(doc);
        } catch (IOException e) {
            log.warn("[web] jsoup 抓取失败 url={} err={}", rawUrl, e.toString());
            // 抓不到也走 headless 兜底，错误信息回带
            out.errorMsg = e.getMessage();
        }

        if (out.text == null || out.text.length() < HEADLESS_THRESHOLD) {
            String rendered = tryHeadless(urlStr);
            if (rendered != null && rendered.length() > (out.text == null ? 0 : out.text.length())) {
                out.text = rendered;
                out.usedHeadless = true;
            }
        }

        if (out.text != null && out.text.length() > TEXT_CHAR_BUDGET) {
            out.text = out.text.substring(0, TEXT_CHAR_BUDGET) + "\n[…truncated…]";
            out.truncated = true;
        }
        out.chars = out.text == null ? 0 : out.text.length();
        return out;
    }

    /**
     * 解析 URL + SSRF 防护。失败抛 IllegalArgumentException 让上层映射 400。
     * <p>说明：DNS 解析后的 IP 校验与后续 jsoup/playwright 二次解析存在 TOCTOU
     * 窗口（DNS rebinding 理论上可绕），但本系统只面向单用户本地工具场景，
     * 攻击者已能本地访问后端，引入 IP 绑定的复杂度不划算。
     */
    public static URI parseAndValidate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("URL 为空");
        }
        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("URL 格式错误: " + raw);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("仅支持 http/https: " + raw);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL 缺少 host: " + raw);
        }
        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            for (InetAddress a : addrs) {
                if (isBlockedAddress(a)) {
                    throw new IllegalArgumentException("禁止访问内网/本机地址: " + host);
                }
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("无法解析 host: " + host);
        }
        return uri;
    }

    private static boolean isBlockedAddress(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress()
                || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return true;
        }
        // 169.254.169.254 等 metadata 服务地址被 link-local 已覆盖；
        // 这里额外拦 CGNAT 100.64.0.0/10 段
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int b0 = b[0] & 0xff;
            int b1 = b[1] & 0xff;
            if (b0 == 100 && b1 >= 64 && b1 <= 127) return true;
        }
        return false;
    }

    static String extractMainText(Document doc) {
        doc.select("script, style, noscript, iframe, nav, footer, aside, header, form, button, svg").remove();
        doc.select("[role=navigation], [role=banner], [role=contentinfo], [aria-hidden=true]").remove();

        Element root = doc.selectFirst("article");
        if (root == null) root = doc.selectFirst("main");
        if (root == null) root = doc.selectFirst("[role=main]");
        if (root == null) root = doc.body();
        if (root == null) return "";

        String text = root.text();
        String title = doc.title();
        if (title != null && !title.isBlank()) {
            return title.trim() + "\n\n" + text;
        }
        return text;
    }

    /**
     * Playwright 渲染。失败一次就永久禁用，避免每次请求都尝试初始化拖慢响应。
     * <p>每次调用使用独立 BrowserContext 隔离 cookies/storage，避免跨 URL 串味。
     */
    private static String tryHeadless(String url) {
        if (headlessDisabled) return null;
        try {
            Browser browser = getOrInitBrowser();
            try (BrowserContext ctx = browser.newContext(new Browser.NewContextOptions().setUserAgent(UA));
                 Page page = ctx.newPage()) {
                page.setDefaultTimeout(HEADLESS_TIMEOUT_MS);
                page.navigate(url);
                page.waitForLoadState();
                String html = page.content();
                Document doc = Jsoup.parse(html, url);
                return extractMainText(doc);
            }
        } catch (Throwable e) {
            log.warn("[web] headless 渲染失败 url={} err={}", url, e.toString());
            String msg = e.toString();
            if (msg.contains("Executable doesn't exist") || msg.contains("install chromium")) {
                headlessDisabled = true;
                log.warn("[web] 已禁用 headless 兜底；如需启用请运行：mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args=\"install chromium\"");
            }
            return null;
        }
    }

    private static synchronized Browser getOrInitBrowser() {
        if (sharedBrowser != null) return sharedBrowser;
        playwrightInstance = Playwright.create();
        sharedBrowser = playwrightInstance.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(true));
        log.info("[web] Playwright Chromium 已启动（用于 SPA 页面 headless 兜底）");
        return sharedBrowser;
    }

    public static synchronized void shutdown() {
        try {
            if (sharedBrowser != null) sharedBrowser.close();
        } catch (Exception ignore) {}
        try {
            if (playwrightInstance != null) playwrightInstance.close();
        } catch (Exception ignore) {}
        sharedBrowser = null;
        playwrightInstance = null;
    }

    public static final class Result {
        public String url;
        public String title;
        public String text;
        public int chars;
        public boolean truncated;
        public boolean usedHeadless;
        public String errorMsg;
    }
}

