package com.tuiyan.backend;



import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量查询 API Key 剩余配额工具 - 纯 JDK，健壮字符串解析
 */
public class ApiKeyQuotaChecker {

    private static final String API_BASE = "https://his.ppchat.vip/api/token-logs";
    private static final int PAGE = 1;
    private static final int PAGE_SIZE = 10;
    private static final int TIMEOUT_SECONDS = 10;
    private static final boolean DEBUG = false;  // 开启后打印原始 JSON 帮助调试

    private final HttpClient httpClient;

    public ApiKeyQuotaChecker() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build();
    }


    public static void main(String[] args) {
        List<String> apiKeys;
        if (args.length > 0) {
            apiKeys = List.of(args);
        } else {
            apiKeys = new ArrayList<>();
            // 在这里填入你的 API Key
            apiKeys.add("sk-RwsXfq5v3zrFQVB2YrK0Fw6Usyq4hl8umcmkBvpsf2pXCF1O");
            apiKeys.add("sk-jOyIXxTeUcAHXjeXzOldJUu0HceEcwXIdRHU4PCEswLpy2Sh");
            apiKeys.add("sk-elLA4vQexbIFjbO7SayiagHVtrU9E8McsFdD6OHE0JRNnvap");
            apiKeys.add("sk-BRmc7JGxMtAfsPEctMoOK6mf05eIm3xP5L89wEUbrvHwOJ3Z");
            apiKeys.add("sk-1J6HDHDbKPp4MJggfkHUYQV8uwlQwtRB1XqQdh8fgumoYpPx");
            apiKeys.add("sk-jyywXvBYy5HzPAl601vNNjPSfaDA3Y5iw4V3ZKCGyVjvzopU");

            System.out.println("请修改源代码中的 apiKeys 列表，或通过命令行传入 Key。");
            if (apiKeys.get(0).startsWith("你的")) {
                System.err.println("错误：未设置真实的 API Key。");
                System.exit(1);
            }
        }

        ApiKeyQuotaChecker checker = new ApiKeyQuotaChecker();
        checker.batchQuery(apiKeys);
    }


    public void batchQuery(List<String> apiKeys) {
        if (apiKeys == null || apiKeys.isEmpty()) {
            System.out.println("未提供任何 API Key");
            return;
        }

        System.out.println("\n========== 批量查询 API Key 剩余配额 ==========");
        System.out.printf("%-4s %-22s %-12s %-20s %-10s %-12s%n",
                "序号", "Token名称", "剩余配额", "过期时间", "今日用量", "状态");
        System.out.println("--------------------------------------------------------------------------------------------");

        int index = 1;
        for (String key : apiKeys) {
            System.out.printf("[%d] 查询 %s ...%n", index, maskKey(key));
            TokenInfo info = queryQuota(key);
            if (info != null) {
                System.out.printf("%-4d %-22s %-12d %-20s %-10d %-12s%n",
                        index,
                        truncate(info.name, 22),
                        info.remainQuota,
                        info.expiredTime,
                        info.todayUsed,
                        info.statusText);
            } else {
                System.out.printf("%-4d %-22s %-12s %-20s %-10s %-12s%n",
                        index, maskKey(key), "失败", "-", "-", "-");
            }
            index++;

        }
        System.out.println("========== 查询完成 ==========\n");
    }

    /**
     * 从 JSON 字符串中提取 token_info 里指定字段的值（自动处理字符串或数字）
     * @param json 完整 JSON 响应
     * @param fieldName 字段名，如 "remain_quota_display"
     * @return 字段值的字符串形式，未找到返回 null
     */
    private String extractFieldFromTokenInfo(String json, String fieldName) {
        // 先找到 "token_info": { 的位置
        int tokenInfoStart = json.indexOf("\"token_info\"");
        if (tokenInfoStart == -1) return null;
        int braceStart = json.indexOf("{", tokenInfoStart);
        if (braceStart == -1) return null;
        // 找到匹配的结束大括号（简单计数，假设 JSON 内部没有嵌套对象复杂但足够用）
        int braceEnd = findMatchingBrace(json, braceStart);
        if (braceEnd == -1) return null;
        String tokenInfoJson = json.substring(braceStart, braceEnd + 1);

        // 在 tokenInfoJson 中查找字段
        String searchKey = "\"" + fieldName + "\"";
        int keyPos = tokenInfoJson.indexOf(searchKey);
        if (keyPos == -1) return null;
        int colonPos = tokenInfoJson.indexOf(":", keyPos);
        if (colonPos == -1) return null;
        // 跳过空白字符
        int valueStart = colonPos + 1;
        while (valueStart < tokenInfoJson.length() && Character.isWhitespace(tokenInfoJson.charAt(valueStart))) {
            valueStart++;
        }
        if (valueStart >= tokenInfoJson.length()) return null;

        char firstChar = tokenInfoJson.charAt(valueStart);
        if (firstChar == '"') {
            // 字符串值
            int valueEnd = tokenInfoJson.indexOf("\"", valueStart + 1);
            if (valueEnd == -1) return null;
            return tokenInfoJson.substring(valueStart + 1, valueEnd);
        } else {
            // 数字值（可能带负号）
            int valueEnd = valueStart;
            while (valueEnd < tokenInfoJson.length() &&
                    (Character.isDigit(tokenInfoJson.charAt(valueEnd)) ||
                            tokenInfoJson.charAt(valueEnd) == '-' ||
                            tokenInfoJson.charAt(valueEnd) == '.')) {
                valueEnd++;
            }
            return tokenInfoJson.substring(valueStart, valueEnd);
        }
    }

    /**
     * 找到从 startPos 开始的第一个 '{' 对应的匹配 '}' 位置（忽略字符串内的括号）
     */
    private int findMatchingBrace(String s, int startPos) {
        int braceCount = 0;
        boolean inString = false;
        for (int i = startPos; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (!inString) {
                if (c == '{') braceCount++;
                else if (c == '}') {
                    braceCount--;
                    if (braceCount == 0) return i;
                }
            }
        }
        return -1;
    }

    /**
     * 查询单个 API Key
     */
    private TokenInfo queryQuota(String apiKey) {
        try {
            String encodedKey = URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
            String url = String.format("%s?token_key=%s&page=%d&page_size=%d",
                    API_BASE, encodedKey, PAGE, PAGE_SIZE);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.err.printf("HTTP %d 错误 [%s]%n", response.statusCode(), maskKey(apiKey));
                return null;
            }

            String json = response.body();
            if (DEBUG) {
                System.out.println("===== 原始响应 (前500字符) =====");
                System.out.println(json.length() > 500 ? json.substring(0, 500) + "..." : json);
                System.out.println("================================");
            }

            // 提取各个字段
            String name = extractFieldFromTokenInfo(json, "name");
            String remainQuotaStr = extractFieldFromTokenInfo(json, "remain_quota_display");
            String expiredTime = extractFieldFromTokenInfo(json, "expired_time_formatted");
            String todayUsedStr = extractFieldFromTokenInfo(json, "today_used_quota");
            String statusText = extractFieldFromTokenInfo(json, "status");
            // status 是对象，需要进一步提取 text
            if (statusText != null && statusText.startsWith("{")) {
                // 简单提取 status 内的 text 字段
                int textKey = statusText.indexOf("\"text\"");
                if (textKey != -1) {
                    int colon = statusText.indexOf(":", textKey);
                    if (colon != -1) {
                        int valStart = colon + 1;
                        while (valStart < statusText.length() && Character.isWhitespace(statusText.charAt(valStart))) valStart++;
                        if (statusText.charAt(valStart) == '"') {
                            int valEnd = statusText.indexOf("\"", valStart + 1);
                            if (valEnd != -1) {
                                statusText = statusText.substring(valStart + 1, valEnd);
                            } else statusText = "";
                        } else {
                            // 可能是裸字
                            int valEnd = valStart;
                            while (valEnd < statusText.length() && !Character.isWhitespace(statusText.charAt(valEnd)) && statusText.charAt(valEnd) != ',' && statusText.charAt(valEnd) != '}') valEnd++;
                            statusText = statusText.substring(valStart, valEnd);
                        }
                    }
                }
            }

            int remainQuota = 0;
            if (remainQuotaStr != null && !remainQuotaStr.isEmpty()) {
                try {
                    remainQuota = Integer.parseInt(remainQuotaStr);
                } catch (NumberFormatException ignored) {}
            }

            int todayUsed = 0;
            if (todayUsedStr != null && !todayUsedStr.isEmpty()) {
                try {
                    todayUsed = Integer.parseInt(todayUsedStr);
                } catch (NumberFormatException ignored) {}
            }

            if (name == null) name = "未知";
            if (expiredTime == null) expiredTime = "未知";
            if (statusText == null) statusText = "未知";

            return new TokenInfo(name, remainQuota, expiredTime, statusText, todayUsed, apiKey);

        } catch (IOException | InterruptedException e) {
            System.err.printf("网络错误 [%s] : %s%n", maskKey(apiKey), e.getMessage());
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            System.err.printf("解析错误 [%s] : %s%n", maskKey(apiKey), e.getMessage());
            if (DEBUG) e.printStackTrace();
            return null;
        }
    }

    private String maskKey(String key) {
        if (key == null || key.length() <= 8) return "***";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }



    private String truncate(String str, int maxLen) {
        if (str == null) return "";
        return str.length() > maxLen ? str.substring(0, maxLen - 3) + "..." : str;
    }

    private static class TokenInfo {
        String name;
        int remainQuota;
        String expiredTime;
        String statusText;
        int todayUsed;
        String apiKey;

        TokenInfo(String name, int remainQuota, String expiredTime, String statusText, int todayUsed, String apiKey) {
            this.name = name;
            this.remainQuota = remainQuota;
            this.expiredTime = expiredTime;
            this.statusText = statusText;
            this.todayUsed = todayUsed;
            this.apiKey = apiKey;
        }
    }


}