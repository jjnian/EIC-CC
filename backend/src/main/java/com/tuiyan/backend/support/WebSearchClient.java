package com.tuiyan.backend.support;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 联网搜索客户端：查 DuckDuckGo 的 HTML 端点（免 API Key），解析出结果标题 / 链接 / 摘要。
 * <p>只做检索，不抓正文——命中的页面交给 {@link WebPageFetcher} 抓取（同一套超时/体积护栏）。
 * 搜索端点不可达（内网部署 / 无外网）时抛 IllegalStateException，由调用方转成友好报错。
 */
public final class WebSearchClient {

    private static final Logger log = LoggerFactory.getLogger(WebSearchClient.class);

    private static final String ENDPOINT = "https://html.duckduckgo.com/html/";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final int TIMEOUT_MS = 12_000;

    private WebSearchClient() {}

    /** 单条搜索结果。 */
    public record SearchHit(String title, String url, String snippet) {}

    /**
     * 搜索并返回前 {@code maxHits} 条结果（同域名去重，只保留 http/https）。
     */
    public static List<SearchHit> search(String query, int maxHits) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("搜索关键词为空");
        }
        int cap = maxHits <= 0 ? 8 : Math.min(maxHits, 20);
        try {
            Document doc = Jsoup.connect(ENDPOINT)
                    .data("q", query.strip())
                    .userAgent(UA)
                    .timeout(TIMEOUT_MS)
                    .get();
            List<SearchHit> out = new ArrayList<>();
            Set<String> seenHosts = new HashSet<>();
            for (Element a : doc.select("a.result__a")) {
                String url = resolveDdgUrl(a.attr("href"));
                if (url == null) continue;
                String host;
                try { host = java.net.URI.create(url).getHost(); } catch (Exception e) { continue; }
                if (host == null || !seenHosts.add(host)) continue; // 同域名只取首条，扩大信息面
                String title = a.text().strip();
                Element block = a.closest(".result");
                String snippet = block == null ? "" : block.select(".result__snippet").text().strip();
                out.add(new SearchHit(title, url, snippet));
                if (out.size() >= cap) break;
            }
            log.info("[websearch] q=\"{}\" → {} 条结果", query, out.size());
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("联网搜索失败（请确认服务器可访问外网）: " + e.getMessage(), e);
        }
    }

    /** DDG 的结果链接是跳转形式（//duckduckgo.com/l/?uddg=<真实URL>），解出真实 URL；非 http(s) 丢弃。 */
    private static String resolveDdgUrl(String href) {
        if (href == null || href.isBlank()) return null;
        String url = href;
        int i = href.indexOf("uddg=");
        if (i >= 0) {
            String enc = href.substring(i + 5);
            int amp = enc.indexOf('&');
            if (amp >= 0) enc = enc.substring(0, amp);
            url = URLDecoder.decode(enc, StandardCharsets.UTF_8);
        } else if (href.startsWith("//")) {
            url = "https:" + href;
        }
        return url.startsWith("http://") || url.startsWith("https://") ? url : null;
    }
}
