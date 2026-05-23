package com.tuiyan.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 跨域配置。
 * <p>仅放行本机 dev server（vite 默认端口随机，所以用 *），生产场景同源部署不需要 CORS。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                // 仅允许本机来源；用 originPatterns 而非 origins 是为了配合 allowCredentials=true
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                // 暴露所有响应头：前端 SSE 客户端要读 X-Accel-Buffering 等自定义头
                .exposedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
