package com.tuiyan.backend.repository;

/**
 * Repository 层公用的弱类型取值工具：把 Domain 层 {@code Map<String,Object>} 中的松散字段
 * 安全转换为目标 Java 类型。集中在此消除各 RowMapper 中重复的 asString/asDouble/asInt 实现。
 * <p>包内可见、纯静态、无状态。
 */
final class RepoValueUtils {

    private RepoValueUtils() {
    }

    static String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    static Double asDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(v.toString());
        } catch (Exception ex) {
            return null;
        }
    }

    static Integer asInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(v.toString());
        } catch (Exception ex) {
            return null;
        }
    }
}
