package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.config.AppPaths;
import com.tuiyan.backend.model.Conversation;
import com.tuiyan.backend.util.JsonAtomic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AppPaths appPaths;

    public ConversationService(AppPaths appPaths) {
        this.appPaths = appPaths;
        File d = appPaths.conversationsDir();
        if (!d.exists()) d.mkdirs();
    }

    public List<Conversation> list() {
        File d = appPaths.conversationsDir();
        File[] files = d.listFiles((f, n) -> n.endsWith(".json"));
        List<Conversation> out = new ArrayList<>();
        if (files == null) return out;
        for (File f : files) {
            try {
                out.add(objectMapper.readValue(f, Conversation.class));
            } catch (IOException ioe) {
                log.warn("skip malformed conversation file {}: {}", f, ioe.toString());
            }
        }
        out.sort(Comparator.comparingLong(Conversation::getUpdatedAt).reversed());
        return out;
    }

    public Conversation get(String id) throws IOException {
        File f = fileFor(id);
        if (!f.exists()) return null;
        return objectMapper.readValue(f, Conversation.class);
    }

    public Conversation save(Conversation c) throws IOException {
        if (c.getId() == null || c.getId().isBlank()) {
            c.setId("conv_" + System.currentTimeMillis());
        }
        long now = System.currentTimeMillis();
        if (c.getCreatedAt() == 0L) c.setCreatedAt(now);
        c.setUpdatedAt(now);
        JsonAtomic.write(objectMapper, fileFor(c.getId()), c);
        return c;
    }

    public boolean delete(String id) {
        File f = fileFor(id);
        return f.exists() && f.delete();
    }

    private File fileFor(String id) {
        String safe = id.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        return new File(appPaths.conversationsDir(), safe + ".json");
    }
}
