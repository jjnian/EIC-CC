package com.tuiyan.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Spring Boot 启动入口。
 * <p>{@code @EnableAsync} 用于让 {@link com.tuiyan.backend.config.AsyncConfig} 中
 * 配置的 predictionExecutor 生效，使推演任务能在异步线程中跑（不阻塞 controller 线程）。
 */
@SpringBootApplication
@EnableAsync
public class BackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }
}
