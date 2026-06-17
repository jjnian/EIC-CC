package com.tuiyan.backend.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * web 系统接入配置的组装工具：把请求体里的连接字段（baseUrl/username/password/...）合成为待存配置。
 * <p>从 ExperienceController 抽出，纯函数无依赖。编辑时密码为空/遮蔽串、storageState 为空则沿用原值，
 * 避免把敏感字段误清空。
 */
public final class WebSystemConfigAssembler {

    /** 密码字段的遮蔽串：前端回显时用它占位，提交时见到它表示"未改密码"。 */
    private static final String MASKED_PASSWORD = "********";

    private WebSystemConfigAssembler() {}

    /** 在 existing 基础上用 body 覆盖，返回新的配置 Map。 */
    public static Map<String, Object> assemble(Map<String, Object> body, Map<String, Object> existing) {
        Map<String, Object> cfg = new LinkedHashMap<>(existing);
        cfg.put("baseUrl", str(body, "baseUrl"));
        cfg.put("username", str(body, "username"));
        cfg.put("maxSteps", intOr(body, "maxSteps", 15));
        cfg.put("readOnly", !"false".equalsIgnoreCase(str(body, "readOnly"))); // 默认只读
        String pwd = str(body, "password");
        if (pwd != null && !pwd.isBlank() && !MASKED_PASSWORD.equals(pwd)) cfg.put("password", pwd);
        String ss = str(body, "storageState");
        if (ss != null && !ss.isBlank()) cfg.put("storageState", ss);
        return cfg;
    }

    public static String str(Map<String, Object> body, String key) {
        if (body == null) return null;
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static int intOr(Map<String, Object> body, String key, int dflt) {
        String s = str(body, key);
        if (s == null || s.isBlank()) return dflt;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return dflt; }
    }
}
