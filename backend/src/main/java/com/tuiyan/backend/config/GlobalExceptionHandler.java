package com.tuiyan.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;
import java.util.Map;

/**
 * 全局异常 → HTTP 状态码映射，统一返回 {@code {"error": "..."}} 形状。
 * <p>让 controller 不再写 try/catch 业务分支。
 * <p><b>注意：</b>SSE 路径在 emitter 建立后异常无法走这里（响应头已发出），
 * 需要 controller 自行通过 {@link com.tuiyan.backend.support.SsePushUtils} 把错误事件写回流。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 资源未找到 → 404。由 service 主动抛出 {@link ResourceNotFoundException} 触发。 */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ResourceNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", e.getMessage() == null ? "资源未找到" : e.getMessage()));
    }

    /** 参数校验类异常统一映射到 400；包含 Spring 的缺失参数异常。 */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class,
                       MissingServletRequestParameterException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", e.getMessage() == null ? "请求参数错误" : e.getMessage()));
    }

    /** 上传文件超过 multipart 大小限制 → 413，而不是落到兜底 500。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("error", "上传文件过大，超过服务端大小限制"));
    }

    /** 文件读写异常 → 500，并打印堆栈，方便排查磁盘 / 权限问题。 */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, Object>> handleIO(IOException e) {
        log.warn("IO 异常: {}", e.toString(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage() == null ? "服务器 IO 异常" : e.getMessage()));
    }

    /** 兜底：任何未匹配到上面规则的异常 → 500。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e) {
        log.warn("未处理异常: {}", e.toString(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage() == null ? "服务器内部错误" : e.getMessage()));
    }
}
