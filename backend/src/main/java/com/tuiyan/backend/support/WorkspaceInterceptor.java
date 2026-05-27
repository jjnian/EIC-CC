package com.tuiyan.backend.support;

import com.tuiyan.backend.repository.WorkspaceRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 工作空间拦截器：从 X-Workspace-Id 头读出当前工作空间，写入 ThreadLocal。
 * <p>白名单路径（workspace 自身管理 + 系统/配置/模型/偏好）不强制要求 header；
 * 业务路径要求 header 存在且对应 workspace 存在，否则返回 400。
 */
public class WorkspaceInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Workspace-Id";

    private final WorkspaceRepository workspaceRepository;

    public WorkspaceInterceptor(WorkspaceRepository workspaceRepository) {
        this.workspaceRepository = workspaceRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        String header = request.getHeader(HEADER);
        if (header != null && !header.isBlank()) {
            if (!workspaceRepository.exists(header)) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"Workspace not found: " + escape(header) + "\"}");
                return false;
            }
            WorkspaceContext.set(header);
            return true;
        }
        if (isWhitelisted(path)) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"Missing X-Workspace-Id header\"}");
        return false;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        WorkspaceContext.clear();
    }

    private boolean isWhitelisted(String path) {
        if (path == null) return false;
        // 工作空间自身管理、健康检查、配置、模型测试、偏好等不需要绑定具体 ws
        return path.startsWith("/api/workspaces")
                || path.startsWith("/api/system/")
                || path.equals("/api/config")
                || path.startsWith("/api/models")
                || path.startsWith("/api/prefs")
                || path.startsWith("/api/extract/");
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
