package com.tuiyan.backend.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * 本地数据落盘路径中心：所有 service 通过本类获取目录，避免硬编码。
 * <p>默认根目录是 {@code ~/.tuiyan}，可通过 {@code app.data.dir} 配置项覆盖。
 * 启动时（{@link PostConstruct}）会确保各子目录存在；目录创建失败仅记录警告，
 * 后续读写时再失败也只影响该次请求，不阻断应用启动。
 */
@Component
public class AppPaths {

    private static final Logger log = LoggerFactory.getLogger(AppPaths.class);

    // 数据根目录：默认 ~/.tuiyan，外部可通过 application.yml 中 app.data.dir 覆盖
    @Value("${app.data.dir:#{systemProperties['user.home']}/.tuiyan}")
    private String dataDir;

    /** 启动后创建必要的子目录；个别失败仅记日志，不抛出。 */
    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(rootDir().toPath());
            Files.createDirectories(scenariosDir().toPath());
            Files.createDirectories(ontologyModelsDir().toPath());
            Files.createDirectories(conversationsDir().toPath());
            Files.createDirectories(hypothesisTemplatesDir().toPath());
        } catch (IOException e) {
            log.warn("Failed to prepare data dir {}: {}", dataDir, e.toString(), e);
        }
    }

    /** 数据根目录（{@code ~/.tuiyan} 或自定义）。 */
    public File rootDir() { return new File(dataDir); }

    /** 推演分支（Scenario）落盘目录，每个分支一个 JSON 文件。 */
    public File scenariosDir() { return new File(rootDir(), "scenarios"); }

    /** 本体图谱模型目录，每个模型一个 JSON 文件。 */
    public File ontologyModelsDir() { return new File(rootDir(), "ontology-models"); }

    /** 对话历史目录，每个 conversation 一个 JSON 文件。 */
    public File conversationsDir() { return new File(rootDir(), "conversations"); }

    /** 推演假设模板目录（用户保存的常用推演参数预设）。 */
    public File hypothesisTemplatesDir() { return new File(rootDir(), "hypothesis-templates"); }

    /** 用户偏好设置文件（主题、布局方向、当前模型等）。 */
    public File prefsFile() { return new File(rootDir(), "prefs.json"); }
}
