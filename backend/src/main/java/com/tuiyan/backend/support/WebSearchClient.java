package com.tuiyan.backend.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.config.WebSearchProperties;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 联网搜索客户端：按 {@code app.web-search.provider} 分派到 Brave / Serper（带 key 的官方 JSON API）
 * 或遗留的 DuckDuckGo 爬取。只做检索，命中页面交给 {@link WebPageFetcher} 抓正文。
 * <p>免 key 的搜索引擎爬取已被广泛反爬封禁，故默认走带 key 的 provider；未配 key 时抛出清晰的
 * 配置提示（而非误导性的“没搜到，请换关键词”）。
 */
@Component
public class WebSearchClient {

    private static final Logger log = LoggerFactory.getLogger(WebSearchClient.class);

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final int TIMEOUT_MS = 12_000;

    private final WebSearchProperties props;
    private final ObjectMapper om = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(TIMEOUT_MS))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public WebSearchClient(WebSearchProperties props) {
        this.props = props;
    }

    /** 单条搜索结果。 */
    public record SearchHit(String title, String url, String snippet) {}

    /**
     * 搜索并返回前 {@code maxHits} 条结果（同域名去重，只保留 http/https）。
     */
    public List<SearchHit> search(String query, int maxHits) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("搜索关键词为空");
        }
        int cap = maxHits <= 0 ? 8 : Math.min(maxHits, 20);
        String provider = props.getProvider() == null ? "brave" : props.getProvider().trim().toLowerCase();
        return switch (provider) {
            case "brave" -> brave(query.strip(), cap);
            case "serper" -> serper(query.strip(), cap);
            case "duckduckgo", "ddg" -> duckduckgo(query.strip(), cap);
            default -> throw new IllegalStateException("未知的搜索后端 app.web-search.provider=" + provider
                    + "（可选 brave / serper / duckduckgo）");
        };
    }

    // ---------- Brave Search API（X-Subscription-Token） ----------
    private List<SearchHit> brave(String query, int cap) {
        requireKey("Brave");
        String url = "https://api.search.brave.com/res/v1/web/search?count=" + cap
                + "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        JsonNode root = getJson(HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .header("X-Subscription-Token", props.getApiKey().trim())
                .header("User-Agent", UA)
                .timeout(Duration.ofMillis(TIMEOUT_MS)).GET().build(), "Brave");
        List<SearchHit> out = new ArrayList<>();
        Set<String> seenHosts = new HashSet<>();
        for (JsonNode r : root.path("web").path("results")) {
            String u = r.path("url").asText("");
            addHit(out, seenHosts, r.path("title").asText(""), u, r.path("description").asText(""), cap);
            if (out.size() >= cap) break;
        }
        log.info("[websearch] brave q=\"{}\" → {} 条", query, out.size());
        return out;
    }

    // ---------- Serper.dev（Google 结果，X-API-KEY） ----------
    private List<SearchHit> serper(String query, int cap) {
        requireKey("Serper");
        String body = "{\"q\":" + jsonStr(query) + ",\"num\":" + Math.min(cap, 10) + "}";
        JsonNode root = getJson(HttpRequest.newBuilder(URI.create("https://google.serper.dev/search"))
                .header("X-API-KEY", props.getApiKey().trim())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(TIMEOUT_MS))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(), "Serper");
        List<SearchHit> out = new ArrayList<>();
        Set<String> seenHosts = new HashSet<>();
        for (JsonNode r : root.path("organic")) {
            addHit(out, seenHosts, r.path("title").asText(""), r.path("link").asText(""),
                    r.path("snippet").asText(""), cap);
            if (out.size() >= cap) break;
        }
        log.info("[websearch] serper q=\"{}\" → {} 条", query, out.size());
        return out;
    }

    // ---------- 遗留：DuckDuckGo HTML 爬取（通常已被反爬封禁，保留兜底） ----------
    private List<SearchHit> duckduckgo(String query, int cap) {
        try {
            Document doc = Jsoup.connect("https://html.duckduckgo.com/html/")
                    .data("q", query).userAgent(UA).timeout(TIMEOUT_MS).get();
            List<SearchHit> out = new ArrayList<>();
            Set<String> seenHosts = new HashSet<>();
            for (Element a : doc.select("a.result__a")) {
                String url = resolveDdgUrl(a.attr("href"));
                if (url == null) continue;
                Element block = a.closest(".result");
                String snippet = block == null ? "" : block.select(".result__snippet").text().strip();
                addHit(out, seenHosts, a.text().strip(), url, snippet, cap);
                if (out.size() >= cap) break;
            }
            if (out.isEmpty()) {
                throw new IllegalStateException("DuckDuckGo 未返回结果（其 HTML 端点已被反爬封禁）。"
                        + "请在 app.web-search 配置 provider=brave 或 serper 并填 api-key。");
            }
            log.info("[websearch] ddg q=\"{}\" → {} 条", query, out.size());
            return out;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("DuckDuckGo 搜索失败（端点已被反爬封禁）：" + e.getMessage()
                    + "。建议改用 provider=brave/serper。", e);
        }
    }

    // ---------- 共用 ----------

    private void requireKey(String name) {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            throw new IllegalStateException("联网调研未配置搜索服务：请设置环境变量 WEB_SEARCH_API_KEY（"
                    + name + " 的 API Key），或改用其它 provider。免 key 的搜索引擎爬取已被封禁。");
        }
    }

    /** 发请求取 JSON；对 401/403/429 等给出清晰文案，网络异常统一转 IllegalStateException。 */
    private JsonNode getJson(HttpRequest req, String name) {
        try {
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code == 401 || code == 403) {
                throw new IllegalStateException(name + " 搜索鉴权失败（HTTP " + code + "），请检查 WEB_SEARCH_API_KEY。");
            }
            if (code == 429) {
                throw new IllegalStateException(name + " 搜索被限流（HTTP 429），已达配额上限，请稍后重试或升级套餐。");
            }
            if (code != 200) {
                throw new IllegalStateException(name + " 搜索失败 HTTP " + code + "：" + shorten(resp.body()));
            }
            return om.readTree(resp.body());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(name + " 搜索请求异常（请确认服务器可访问外网）：" + e.getMessage(), e);
        }
    }

    /** 加入一条结果：解析 host、同域名去重、只留 http(s)。 */
    private void addHit(List<SearchHit> out, Set<String> seenHosts, String title, String url, String snippet, int cap) {
        if (out.size() >= cap) return;
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return;
        String host;
        try { host = URI.create(url).getHost(); } catch (Exception e) { return; }
        if (host == null || !seenHosts.add(host)) return;   // 同域名只取首条，扩大信息面
        out.add(new SearchHit(title == null ? "" : title.strip(), url, snippet == null ? "" : snippet.strip()));
    }

    /** DDG 跳转链接（//duckduckgo.com/l/?uddg=<真实URL>）解出真实 URL；非 http(s) 丢弃。 */
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

    private static String jsonStr(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static String shorten(String s) {
        if (s == null) return "";
        s = s.strip();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
