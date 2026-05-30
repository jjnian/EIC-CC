package com.tuiyan.backend;

import com.tuiyan.backend.config.LlmProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.List;

/**
 * Spring Boot 启动入口。
 * <p>{@code @EnableAsync} 用于让 {@link com.tuiyan.backend.config.AsyncConfig} 中
 * 配置的 predictionExecutor 生效，使推演任务能在异步线程中跑（不阻塞 controller 线程）。
 * <p>{@code @MapperScan} 让 MyBatis-Plus 扫描 mapper 包，自动注册所有 BaseMapper 子接口。
 */
@SpringBootApplication
@EnableAsync
@MapperScan("com.tuiyan.backend.mapper")
public class BackendApplication {

    private static final Logger log = LoggerFactory.getLogger(BackendApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }

    @Bean
    public ApplicationRunner sqlInitStartupLogger(
            @Value("${spring.sql.init.enabled:true}") boolean sqlInitEnabled,
            @Value("${spring.sql.init.mode:embedded}") String sqlInitMode,
            @Value("${spring.sql.init.schema-locations:}") String sqlInitSchemaLocations) {
        return args -> {
            if (sqlInitEnabled) {
                log.info("[DB-Init] 已执行启动脚本: mode={}, schema-locations={}",
                        sqlInitMode, sqlInitSchemaLocations);
            } else {
                log.warn("[DB-Init] 已跳过启动脚本 (spring.sql.init.enabled=false / DB_INIT_SCHEMA=false)");
            }
        };
    }

    @Bean
    public ApplicationRunner llmStartupLogger(LlmProperties llmProperties) {
        return args -> {
            List<LlmProperties.ModelEntry> models = llmProperties.getModels();
            if (models.isEmpty()) {
                log.warn("[LLM] 未配置任何模型 (app.llm.models 为空)");
                return;
            }
            LlmProperties.ModelEntry def = models.get(0);
            log.info("[LLM] 默认模型: id={}, name={}, provider={}, model-name={}, base-url={}, protocol={}",
                    def.getId(), def.getName(), def.getProvider(), def.getModelName(), def.getBaseUrl(), def.getProtocol());
            if (models.size() > 1) {
                for (int i = 1; i < models.size(); i++) {
                    LlmProperties.ModelEntry m = models.get(i);
                    log.info("[LLM] 可选模型[{}]: id={}, model-name={}, base-url={}, enabled={}",
                            i, m.getId(), m.getModelName(), m.getBaseUrl(), m.isEnabled());
                }
            }
        };
    }
}
