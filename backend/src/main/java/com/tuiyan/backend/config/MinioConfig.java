package com.tuiyan.backend.config;

import io.minio.MinioClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配 {@link MinioClient} 单例。连接在首次调用时才真正建立，
 * 因此 MinIO 暂不可用时不会阻塞应用启动（落桶时再抛错由上层处理）。
 */
@Configuration
public class MinioConfig {

    @Bean
    public MinioClient minioClient(MinioProperties props) {
        return MinioClient.builder()
                .endpoint(props.getEndpoint())
                .credentials(props.getAccessKey(), props.getSecretKey())
                .build();
    }
}
