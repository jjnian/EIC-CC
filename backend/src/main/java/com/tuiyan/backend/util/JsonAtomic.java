package com.tuiyan.backend.util;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * 原子写工具：先写 tmp，再 Files.move(ATOMIC_MOVE) 覆盖目标，避免崩溃时落出半截 JSON。
 */
public final class JsonAtomic {

    private JsonAtomic() {}

    public static void write(ObjectMapper mapper, File target, Object value) throws IOException {
        if (target == null) throw new IllegalArgumentException("target file null");
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            Files.createDirectories(parent.toPath());
        }
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(tmp, value);
        try {
            Files.move(tmp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicFail) {
            // 某些文件系统（如某些 Windows / 跨卷）不支持 ATOMIC_MOVE，退回到 REPLACE_EXISTING
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
