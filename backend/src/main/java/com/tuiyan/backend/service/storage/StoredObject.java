package com.tuiyan.backend.service.storage;

import java.io.InputStream;

/**
 * 从对象存储取回的文件句柄：用于流式下载，避免把整个文件读进堆内存。
 *
 * @param stream      对象内容流（调用方负责关闭）
 * @param size        字节大小，-1 表示未知
 * @param contentType MIME 类型
 * @param filename    原始文件名（供 Content-Disposition 使用）
 */
public record StoredObject(InputStream stream, long size, String contentType, String filename) {}
