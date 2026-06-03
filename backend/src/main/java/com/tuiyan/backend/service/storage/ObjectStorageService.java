package com.tuiyan.backend.service.storage;

import com.tuiyan.backend.config.MinioProperties;
import io.minio.*;
import io.minio.messages.DeleteObject;
import io.minio.messages.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * MinIO 对象存储封装：put / get / stat / delete / deletePrefix。
 * <p>桶在首次使用时惰性创建（{@link #ensureBucket()}），异常统一包成
 * {@link StorageException} 由上层转 4xx/5xx。键即对象路径，形如
 * {@code datasource-files/<dataSourceId>/<filename>}。
 */
@Service
public class ObjectStorageService {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorageService.class);
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final MinioClient client;
    private final String bucket;
    private volatile boolean bucketReady = false;

    public ObjectStorageService(MinioClient client, MinioProperties props) {
        this.client = client;
        this.bucket = props.getBucket();
    }

    /** 惰性确保桶存在；只在第一次真正用到存储时连一次 MinIO。 */
    private void ensureBucket() {
        if (bucketReady) return;
        synchronized (this) {
            if (bucketReady) return;
            try {
                boolean exists = client.bucketExists(
                        BucketExistsArgs.builder().bucket(bucket).build());
                if (!exists) {
                    client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                    log.info("MinIO 桶已创建: {}", bucket);
                }
                bucketReady = true;
            } catch (Exception e) {
                throw new StorageException("MinIO 存储桶不可用: " + bucket + " - " + e.getMessage(), e);
            }
        }
    }

    /** 上传一段字节为对象。 */
    public void putBytes(String key, byte[] data, String contentType) {
        ensureBucket();
        try (InputStream in = new ByteArrayInputStream(data)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket).object(key)
                    .stream(in, data.length, -1)
                    .contentType(contentType == null || contentType.isBlank()
                            ? DEFAULT_CONTENT_TYPE : contentType)
                    .build());
        } catch (Exception e) {
            throw new StorageException("上传对象失败: " + key + " - " + e.getMessage(), e);
        }
    }

    /** 读取整个对象为字节；对象不存在返回 null。 */
    public byte[] getBytes(String key) {
        ensureBucket();
        try (InputStream in = client.getObject(
                GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            return in.readAllBytes();
        } catch (io.minio.errors.ErrorResponseException e) {
            if (isNotFound(e)) return null;
            throw new StorageException("读取对象失败: " + key + " - " + e.getMessage(), e);
        } catch (Exception e) {
            throw new StorageException("读取对象失败: " + key + " - " + e.getMessage(), e);
        }
    }

    /** 打开对象内容流（调用方负责关闭）；对象不存在返回 null。 */
    public InputStream openStream(String key) {
        ensureBucket();
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (io.minio.errors.ErrorResponseException e) {
            if (isNotFound(e)) return null;
            throw new StorageException("打开对象流失败: " + key + " - " + e.getMessage(), e);
        } catch (Exception e) {
            throw new StorageException("打开对象流失败: " + key + " - " + e.getMessage(), e);
        }
    }

    /** 对象字节大小；对象不存在返回 -1。 */
    public long size(String key) {
        ensureBucket();
        try {
            StatObjectResponse stat = client.statObject(
                    StatObjectArgs.builder().bucket(bucket).object(key).build());
            return stat.size();
        } catch (io.minio.errors.ErrorResponseException e) {
            if (isNotFound(e)) return -1;
            throw new StorageException("查询对象失败: " + key + " - " + e.getMessage(), e);
        } catch (Exception e) {
            throw new StorageException("查询对象失败: " + key + " - " + e.getMessage(), e);
        }
    }

    /** 对象是否存在。 */
    public boolean exists(String key) {
        return size(key) >= 0;
    }

    /** 删除单个对象（不存在则静默）。 */
    public void delete(String key) {
        ensureBucket();
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception e) {
            log.warn("删除对象失败: {} - {}", key, e.toString());
        }
    }

    /** 删除某前缀下的所有对象（用于级联清理一个数据源目录）。 */
    public void deletePrefix(String prefix) {
        ensureBucket();
        try {
            Iterable<Result<Item>> objects = client.listObjects(
                    ListObjectsArgs.builder().bucket(bucket).prefix(prefix).recursive(true).build());
            List<DeleteObject> toDelete = new ArrayList<>();
            for (Result<Item> r : objects) {
                toDelete.add(new DeleteObject(r.get().objectName()));
            }
            if (toDelete.isEmpty()) return;
            Iterable<Result<io.minio.messages.DeleteError>> errors = client.removeObjects(
                    RemoveObjectsArgs.builder().bucket(bucket).objects(toDelete).build());
            // removeObjects 是惰性的，必须遍历才会真正发起删除
            for (Result<io.minio.messages.DeleteError> err : errors) {
                io.minio.messages.DeleteError de = err.get();
                log.warn("删除对象失败: {} - {}", de.objectName(), de.message());
            }
        } catch (Exception e) {
            log.warn("按前缀清理对象失败: {} - {}", prefix, e.toString());
        }
    }

    private static boolean isNotFound(io.minio.errors.ErrorResponseException e) {
        String code = e.errorResponse() == null ? "" : e.errorResponse().code();
        return "NoSuchKey".equals(code) || "NoSuchObject".equals(code) || "NotFound".equals(code);
    }

    /** 存储层异常，统一包装底层 MinIO/IO 错误。 */
    public static class StorageException extends RuntimeException {
        public StorageException(String message, Throwable cause) { super(message, cause); }
    }
}
