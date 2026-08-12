package com.tuiyan.backend;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Spring Boot 启动入口。
 * <p>{@code @EnableAsync} 用于让 {@link com.tuiyan.backend.config.AsyncConfig} 中
 * 配置的 appTaskExecutor 生效，使耗时任务能在异步线程中跑（不阻塞 controller 线程）。
 * <p>{@code @MapperScan} 让 MyBatis-Plus 扫描 mapper 包，自动注册所有 BaseMapper 子接口。
 */
@SpringBootApplication
@EnableAsync
@MapperScan("com.tuiyan.backend.mapper")
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }
}
