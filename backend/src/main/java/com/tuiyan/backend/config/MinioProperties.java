package com.tuiyan.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MinIO 对象存储连接配置，绑定 {@code app.storage.minio.*}。
 * <p>默认指向本地 MinIO（localhost:9000 / minioadmin），生产可用环境变量覆盖。
 */
@ConfigurationProperties(prefix = "app.storage.minio")
public class MinioProperties {

    /** 服务端点，形如 http://host:9000。 */
    private String endpoint = "http://localhost:9000";
    /** Access Key（MinIO root user）。 */
    private String accessKey = "minioadmin";
    /** Secret Key（MinIO root password）。 */
    private String secretKey = "minioadmin";
    /** 存放数据源文件的桶名，缺失时自动创建。 */
    private String bucket = "tuiyan";

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }

    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
}
