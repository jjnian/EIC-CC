package com.tuiyan.backend.service.connector;

import com.tuiyan.backend.model.dto.HttpExecuteResponse;
import com.tuiyan.backend.support.WebPageFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * HTTPS 接口数据源执行器：单次同步请求 + 响应截断 + SSRF 校验。
 * <p>响应体阈值：
 * <ul>
 *   <li>≤ 100 KB：完整入 fetch_log；</li>
 *   <li>100 KB ~ 1 MB：截断至 100 KB 入表，标 truncated；</li>
 *   <li>&gt; 1 MB：直接丢弃 body，errorMsg 标记。</li>
 * </ul>
 */
@Service
public class HttpConnectorService {

    private static final Logger log = LoggerFactory.getLogger(HttpConnectorService.class);
    public static final int DEFAULT_TIMEOUT_MS = 15_000;
    public static final int MAX_TIMEOUT_MS = 60_000;
    public static final int RESPONSE_KEEP_MAX = 100 * 1024;     // 100 KB 保留
    public static final int RESPONSE_DROP_THRESHOLD = 1024 * 1024; // 1 MB 丢弃

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public HttpExecuteResponse execute(Map<String, Object> cfg) {
        HttpExecuteResponse out = new HttpExecuteResponse();
        long t0 = System.currentTimeMillis();
        String url = String.valueOf(cfg.getOrDefault("url", ""));
        String method = String.valueOf(cfg.getOrDefault("method", "GET")).toUpperCase();
        Object timeoutObj = cfg.get("timeoutMs");
        int timeoutMs = timeoutObj instanceof Number n
                ? Math.min(Math.max(1000, n.intValue()), MAX_TIMEOUT_MS)
                : DEFAULT_TIMEOUT_MS;
        try {
            // SSRF 校验（复用现有 WebPageFetcher）
            URI uri = WebPageFetcher.parseAndValidate(url);
            HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofMillis(timeoutMs));
            // headers
            Object hdrs = cfg.get("headers");
            if (hdrs instanceof Map<?, ?> mp) {
                for (Map.Entry<?, ?> e : mp.entrySet()) {
                    String k = String.valueOf(e.getKey());
                    String v = String.valueOf(e.getValue());
                    if (!k.isBlank()) req.header(k, v);
                }
            }
            // body
            String body = String.valueOf(cfg.getOrDefault("body", ""));
            HttpRequest.BodyPublisher pub = body.isEmpty()
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
            switch (method) {
                case "GET"    -> req.GET();
                case "DELETE" -> req.DELETE();
                case "POST"   -> req.POST(pub);
                case "PUT"    -> req.PUT(pub);
                default -> throw new IllegalArgumentException("不支持的方法: " + method);
            }
            HttpResponse<byte[]> resp = client.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
            byte[] respBytes = resp.body() == null ? new byte[0] : resp.body();
            out.setStatusCode(resp.statusCode());
            out.setSuccess(resp.statusCode() >= 200 && resp.statusCode() < 400);
            Map<String, String> headerMap = new LinkedHashMap<>();
            resp.headers().map().forEach((k, vs) -> headerMap.put(k, String.join(", ", vs)));
            out.setHeaders(headerMap);
            // body 截断逻辑
            if (respBytes.length > RESPONSE_DROP_THRESHOLD) {
                out.setBody(null);
                out.setErrorMsg("响应过大已丢弃（" + respBytes.length + " 字节，阈值 1 MB）");
                out.setTruncated(true);
            } else if (respBytes.length > RESPONSE_KEEP_MAX) {
                out.setBody(new String(respBytes, 0, RESPONSE_KEEP_MAX, StandardCharsets.UTF_8));
                out.setTruncated(true);
            } else {
                out.setBody(new String(respBytes, StandardCharsets.UTF_8));
                out.setTruncated(false);
            }
        } catch (IllegalArgumentException ie) {
            out.setSuccess(false);
            out.setErrorMsg(ie.getMessage());
        } catch (Exception e) {
            out.setSuccess(false);
            out.setErrorMsg(e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("[ds] http execute failed url={} err={}", url, e.toString());
        } finally {
            out.setDurationMs((int) (System.currentTimeMillis() - t0));
        }
        return out;
    }
}
