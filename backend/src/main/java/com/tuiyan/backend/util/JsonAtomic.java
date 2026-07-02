package com.tuiyan.backend.util;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 原子写工具：先写 .tmp 文件，再 {@link Files#move} 覆盖目标，避免崩溃时落出半截 JSON。
 * <p>背景：本项目部分 service（Prefs 等）直接落盘 JSON，
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
     *   <li>写到每次唯一的 {@code target.<uuid>.tmp}（同目录，确保后续 move 是同卷操作，
     *       且并发写同一目标时各线程的临时文件互不覆盖）；</li>
     *   <li>flush 到磁盘后用 {@link StandardCopyOption#ATOMIC_MOVE} 重命名为 target；</li>
     *   <li>若文件系统不支持 ATOMIC_MOVE，降级到 REPLACE_EXISTING（非原子但仍能覆盖）。</li>
     * </ol>
     */
    public static void write(ObjectMapper mapper, File target, Object value) throws IOException {
        if (target == null) throw new IllegalArgumentException("target file null");
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            Files.createDirectories(parent.toPath());
        }
        // 唯一临时名：并发写同一目标时，两个线程不会写到同一个 .tmp 而互相损坏
        File tmp = new File(target.getAbsolutePath() + "." + UUID.randomUUID() + ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp, value);
            // 在重命名前把临时文件刷到磁盘，降低崩溃后落出空/半截文件的概率
            try (var ch = java.nio.channels.FileChannel.open(tmp.toPath(),
                    java.nio.file.StandardOpenOption.WRITE)) {
                ch.force(true);
            }
            try {
                Files.move(tmp.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFail) {
                // 某些文件系统（如某些 Windows / 跨卷）不支持 ATOMIC_MOVE，退回到 REPLACE_EXISTING
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            // move 成功后 tmp 已不存在；失败时清理残留，避免临时文件堆积
            Files.deleteIfExists(tmp.toPath());
        }
    }
}
