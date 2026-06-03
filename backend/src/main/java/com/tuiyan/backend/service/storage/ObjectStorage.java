package com.tuiyan.backend.service.storage;

import java.io.InputStream;

/**
 * 对象存储抽象：上层（如 {@code FileStoredService}）只依赖本接口，不感知具体后端。
 * <p>默认实现是 {@link MinioObjectStorage}（MinIO）；将来要换 S3 / 阿里云 OSS / 本地磁盘，
 * 只需新增一个实现 Bean，调用方无需改动——遵循依赖倒置 + 开闭原则。
 * <p>键即对象路径，形如 {@code datasource-files/<dataSourceId>/<filename>}。
 * 失败统一抛 {@link StorageException}（unchecked），由上层映射为 4xx/5xx。
 */
public interface ObjectStorage {

    /** 上传一段字节为对象。{@code contentType} 为空时按二进制流处理。 */
    void putBytes(String key, byte[] data, String contentType);

    /** 读取整个对象为字节；对象不存在返回 {@code null}。 */
    byte[] getBytes(String key);

    /** 打开对象内容流（调用方负责关闭）；对象不存在返回 {@code null}。 */
    InputStream openStream(String key);

    /** 对象字节大小；对象不存在返回 {@code -1}。 */
    long size(String key);

    /** 对象是否存在。 */
    boolean exists(String key);

    /** 删除单个对象（不存在则静默）。 */
    void delete(String key);

    /** 删除某前缀下的所有对象（用于级联清理一个数据源目录）。 */
    void deletePrefix(String prefix);
}
