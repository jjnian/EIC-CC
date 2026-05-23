package com.tuiyan.backend.util;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * 原子写工具：先写 .tmp 文件，再 {@link Files#move} 覆盖目标，避免崩溃时落出半截 JSON。
 * <p>背景：本项目所有 service（Scenario / Conversation / OntologyModel 等）都直接落盘 JSON，
 * 没有数据库事务保护。若 JVM 在 writeValue 中途崩溃，目标文件可能成为损坏 JSON，
 * 下次启动加载就会失败。通过"tmp + 原子重命名"把这种风险降到最低。
 */
public final class JsonAtomic {

    private JsonAtomic() {}

    /**
     * 把对象序列化为 pretty JSON 并原子地写入 target 文件。
     * <p>步骤：
     * <ol>
     *   <li>父目录不存在则创建；</li>
     *   <li>写到 {@code target.tmp}（同目录，确保后续 move 是同卷操作）；</li>
     *   <li>{@link StandardCopyOption#ATOMIC_MOVE} 重命名为 target；</li>
     *   <li>若文件系统不支持 ATOMIC_MOVE，降级到 REPLACE_EXISTING（非原子但仍能覆盖）。</li>
     * </ol>
     */
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
