package com.tuiyan.backend.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * 本地数据落盘路径中心：仅用于 prefs.json 等非数据库的小文件。
 * <p>业务数据（本体模型 / 分支 / 对话 / 模板等）已迁移到 PostgreSQL，
 * 这里仅保留少量本地配置文件（prefs.json、LLM 配置文件等）的目录管理。
 */
@Component
@ConfigurationProperties(prefix = "app.data")
public class AppPaths {

    private static final Logger log = LoggerFactory.getLogger(AppPaths.class);

    /** 数据根目录，默认 ~/.tuiyan，可通过 application.yml 中 app.data.dir 覆盖。 */
    private String dir = System.getProperty("user.home") + File.separator + ".tuiyan";

    public void setDir(String dir) { this.dir = dir; }
    public String getDir() { return dir; }

    /** 启动后确保根目录存在；个别失败仅记日志，不抛出。 */
    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(rootDir().toPath());
        } catch (IOException e) {
            log.warn("Failed to prepare data dir {}: {}", dir, e.toString(), e);
        }
    }

    /** 数据根目录（{@code ~/.tuiyan} 或自定义）。 */
    public File rootDir() { return new File(dir); }

    /** 用户偏好设置文件（主题、布局方向、当前模型等）。 */
    public File prefsFile() { return new File(rootDir(), "prefs.json"); }

    // 注：数据源原文件（PDF/Word/TXT/音频等）已改为上传到 MinIO 对象存储，
    // 不再落本地磁盘，见 ObjectStorage / FileStoredService。
}
