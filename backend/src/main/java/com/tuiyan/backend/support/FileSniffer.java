package com.tuiyan.backend.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;

/**
 * 文件签名嗅探 + 文件名清洗工具集。
 * <p>仅依赖文件头几个字节，无需读取整个文件；
 * 用来在 {@link com.tuiyan.backend.controller.DocumentTextController} 收到附件时
 * 快速判断真实类型（不能只信用户传的扩展名 / Content-Type，避免被绕过）。
 */
public final class FileSniffer {

    private static final Logger log = LoggerFactory.getLogger(FileSniffer.class);

    private FileSniffer() {}

    /**
     * 去除路径片段，仅保留字母 / 数字 / 点 / 下划线 / 横线；空时返回 {@code "upload"}。
     * <p>防止用户传入 {@code "../../etc/passwd"} 这类穿越路径名。
     */
    public static String sanitizeFilename(String raw) {
        if (raw == null || raw.isBlank()) return "upload";
        // 用 Paths.getFileName 先剥掉路径
        String base = Paths.get(raw).getFileName().toString();
        String cleaned = base.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        if (cleaned.isBlank()) return "upload";
        return cleaned;
    }

    /**
     * 读取文件头 n 字节（不消耗主流）。
     * <p>用独立的 getInputStream 读 → 不影响 MultipartFile 主体后续被使用；
     * 失败时返回空数组而非抛异常，让调用方按"未匹配任何签名"处理。
     */
    public static byte[] readHead(MultipartFile f, int n) {
        try (InputStream is = f.getInputStream()) {
            return is.readNBytes(n);
        } catch (IOException e) {
            log.warn("readHead failed for {}: {}", f.getOriginalFilename(), e.toString());
            return new byte[0];
        }
    }

    /** PDF magic bytes: 25 50 44 46 ({@code %PDF}) */
    public static boolean isPdfMagic(byte[] head) {
        if (head == null || head.length < 4) return false;
        return (head[0] & 0xff) == 0x25
                && (head[1] & 0xff) == 0x50
                && (head[2] & 0xff) == 0x44
                && (head[3] & 0xff) == 0x46;
    }

    /**
     * ZIP magic bytes: 50 4B 03 04 ({@code PK\x03\x04})。
     * <p>DOCX / XLSX / PPTX 都是 ZIP 容器，需要再结合扩展名 / 内容类型区分具体子类型。
     */
    public static boolean isZipMagic(byte[] head) {
        if (head == null || head.length < 4) return false;
        return (head[0] & 0xff) == 0x50
                && (head[1] & 0xff) == 0x4B
                && (head[2] & 0xff) == 0x03
                && (head[3] & 0xff) == 0x04;
    }

    /** PNG / JPEG / GIF / WebP magic bytes 统一检查。 */
    public static boolean isImageMagic(byte[] head) {
        if (head == null || head.length < 4) return false;
        int b0 = head[0] & 0xff, b1 = head[1] & 0xff, b2 = head[2] & 0xff, b3 = head[3] & 0xff;
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return true;     // PNG
        if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return true;                     // JPEG
        if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46 && b3 == 0x38) return true;     // GIF
        // WebP 需要看到 RIFF....WEBP，前 4 字节是 RIFF，第 8~11 字节是 WEBP
        if (head.length >= 12 && b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46
                && (head[8] & 0xff) == 0x57 && (head[9] & 0xff) == 0x45
                && (head[10] & 0xff) == 0x42 && (head[11] & 0xff) == 0x50) {        // WebP
            return true;
        }
        return false;
    }
}
