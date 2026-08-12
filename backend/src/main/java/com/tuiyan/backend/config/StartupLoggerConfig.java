package com.tuiyan.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 启动日志配置 —— 在应用启动时打印 SQL 初始化 & LLM 模型配置信息。
 */
@Configuration
public class StartupLoggerConfig {

    private static final Logger log = LoggerFactory.getLogger(StartupLoggerConfig.class);

    @Bean
    public ApplicationRunner sqlInitStartupLogger(SqlInitProperties sqlInitProperties) {
        return args -> {
            if (sqlInitProperties.isEnabled()) {
                log.info("[DB-Init] 已执行启动脚本: mode={}, schema-locations={}",
                        sqlInitProperties.getMode(), sqlInitProperties.getSchemaLocations());
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
