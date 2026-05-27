package com.tuiyan.backend.support;

/**
 * 工作空间上下文：通过 ThreadLocal 在请求生命周期内承载当前 workspaceId，
 * 让 Repository / Service 不必显式传递参数。
 * <p>由 {@code WorkspaceInterceptor} 在请求进入时 set，请求结束时 clear。
 */
public final class WorkspaceContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private WorkspaceContext() {}

    public static void set(String workspaceId) {
        CURRENT.set(workspaceId);
    }

    /** 当前 workspaceId；可能为 null（白名单接口或未传 header）。 */
    public static String get() {
        return CURRENT.get();
    }

    /** 业务接口里使用：要求当前必须有 workspaceId，否则抛 IllegalStateException。 */
    public static String required() {
        String id = CURRENT.get();
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("Missing workspace context (X-Workspace-Id header)");
        }
        return id;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
