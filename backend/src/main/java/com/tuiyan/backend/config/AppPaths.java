package com.tuiyan.backend.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 统一管理应用持久化数据目录，避免在 jar 运行时回写 src/main/resources。
 * 通过 application.yml 的 app.data.dir 覆盖，默认 ${user.home}/.tuiyan。
 * 启动时会把旧 src/main/resources 下的同名文件迁移到新目录。
 */
@Component
public class AppPaths {

    private static final Logger log = LoggerFactory.getLogger(AppPaths.class);

    private static final String LEGACY_BASE = "src/main/resources";

    @Value("${app.data.dir:#{systemProperties['user.home']}/.tuiyan}")
    private String dataDir;

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(rootDir().toPath());
            Files.createDirectories(scenariosDir().toPath());
            Files.createDirectories(ontologyModelsDir().toPath());

            migrateIfExists(legacyModelsConfigFile(), modelsConfigFile());
            migrateIfExists(legacyConfigFile(), legacyConfigTargetFile());
            migrateIfExists(legacyPrefsFile(), prefsFile());
            migrateDirIfPresent(new File(LEGACY_BASE + "/scenarios"), scenariosDir());
            migrateDirIfPresent(new File(LEGACY_BASE + "/ontology-models"), ontologyModelsDir());
        } catch (IOException e) {
            log.warn("Failed to prepare data dir {}: {}", dataDir, e.toString(), e);
        }
    }

    public File rootDir() { return new File(dataDir); }

    public File modelsConfigFile() { return new File(rootDir(), "llm-models.json"); }

    public File legacyConfigTargetFile() { return new File(rootDir(), "llm-config.json"); }

    public File scenariosDir() { return new File(rootDir(), "scenarios"); }

    public File ontologyModelsDir() { return new File(rootDir(), "ontology-models"); }

    public File prefsFile() { return new File(rootDir(), "prefs.json"); }

    public File legacyModelsConfigFile() { return new File(LEGACY_BASE + "/llm-models.json"); }

    public File legacyConfigFile() { return new File(LEGACY_BASE + "/llm-config.json"); }

    public File legacyPrefsFile() { return new File(LEGACY_BASE + "/prefs.json"); }

    private static void migrateIfExists(File legacy, File target) {
        if (legacy == null || target == null) return;
        if (!legacy.exists() || target.exists()) return;
        try {
            Files.createDirectories(target.getParentFile().toPath());
            Files.move(legacy.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            log.info("Migrated legacy file {} -> {}", legacy, target);
        } catch (IOException e) {
            log.warn("Migration of {} -> {} failed: {}", legacy, target, e.toString(), e);
        }
    }

    private static void migrateDirIfPresent(File legacyDir, File targetDir) {
        if (legacyDir == null || !legacyDir.exists() || !legacyDir.isDirectory()) return;
        File[] files = legacyDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.isFile() || !f.getName().endsWith(".json")) continue;
            File dst = new File(targetDir, f.getName());
            if (dst.exists()) continue;
            try {
                Files.createDirectories(targetDir.toPath());
                Files.move(f.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
                log.info("Migrated legacy {} -> {}", f, dst);
            } catch (IOException e) {
                log.warn("Migration of {} -> {} failed: {}", f, dst, e.toString(), e);
            }
        }
    }
}
