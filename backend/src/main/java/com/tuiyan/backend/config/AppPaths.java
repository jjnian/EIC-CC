package com.tuiyan.backend.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

@Component
public class AppPaths {

    private static final Logger log = LoggerFactory.getLogger(AppPaths.class);

    @Value("${app.data.dir:#{systemProperties['user.home']}/.tuiyan}")
    private String dataDir;

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(rootDir().toPath());
            Files.createDirectories(scenariosDir().toPath());
            Files.createDirectories(ontologyModelsDir().toPath());
            Files.createDirectories(conversationsDir().toPath());
        } catch (IOException e) {
            log.warn("Failed to prepare data dir {}: {}", dataDir, e.toString(), e);
        }
    }

    public File rootDir() { return new File(dataDir); }

    public File scenariosDir() { return new File(rootDir(), "scenarios"); }

    public File ontologyModelsDir() { return new File(rootDir(), "ontology-models"); }

    public File conversationsDir() { return new File(rootDir(), "conversations"); }

    public File prefsFile() { return new File(rootDir(), "prefs.json"); }
}
