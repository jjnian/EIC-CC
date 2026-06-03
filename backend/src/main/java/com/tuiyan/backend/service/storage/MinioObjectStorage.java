package com.tuiyan.backend.service.storage;

import com.tuiyan.backend.config.MinioProperties;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.DeleteError;
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
 * {@link ObjectStorage} 的 MinIO 实现。
 * <p>桶在首次使用时惰性创建（{@link #ensureBucket()}），因此 MinIO 暂不可用时不会阻塞应用启动。
 */
@Service
public class MinioObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(MinioObjectStorage.class);
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final MinioClient client;
    private final String bucket;
    private volatile boolean bucketReady = false;

    public MinioObjectStorage(MinioClient client, MinioProperties props) {
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

    @Override
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

    @Override
    public byte[] getBytes(String key) {
        ensureBucket();
        try (InputStream in = client.getObject(
                GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            return in.readAllBytes();
        } catch (ErrorResponseException e) {
            if (isNotFound(e)) return null;
            throw new StorageException("读取对象失败: " + key + " - " + e.getMessage(), e);
        } catch (Exception e) {
            throw new StorageException("读取对象失败: " + key + " - " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream openStream(String key) {
        ensureBucket();
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (ErrorResponseException e) {
            if (isNotFound(e)) return null;
            throw new StorageException("打开对象流失败: " + key + " - " + e.getMessage(), e);
        } catch (Exception e) {
            throw new StorageException("打开对象流失败: " + key + " - " + e.getMessage(), e);
        }
    }

    @Override
    public long size(String key) {
        ensureBucket();
        try {
            StatObjectResponse stat = client.statObject(
                    StatObjectArgs.builder().bucket(bucket).object(key).build());
            return stat.size();
        } catch (ErrorResponseException e) {
            if (isNotFound(e)) return -1;
            throw new StorageException("查询对象失败: " + key + " - " + e.getMessage(), e);
        } catch (Exception e) {
            throw new StorageException("查询对象失败: " + key + " - " + e.getMessage(), e);
        }
    }

    @Override
    public boolean exists(String key) {
        return size(key) >= 0;
    }

    @Override
    public void delete(String key) {
        ensureBucket();
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception e) {
            log.warn("删除对象失败: {} - {}", key, e.toString());
        }
    }

    @Override
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
            Iterable<Result<DeleteError>> errors = client.removeObjects(
                    RemoveObjectsArgs.builder().bucket(bucket).objects(toDelete).build());
            // removeObjects 是惰性的，必须遍历返回值才会真正发起删除
            for (Result<DeleteError> err : errors) {
                DeleteError de = err.get();
                log.warn("删除对象失败: {} - {}", de.objectName(), de.message());
            }
        } catch (Exception e) {
            log.warn("按前缀清理对象失败: {} - {}", prefix, e.toString());
        }
    }

    private static boolean isNotFound(ErrorResponseException e) {
        String code = e.errorResponse() == null ? "" : e.errorResponse().code();
        return "NoSuchKey".equals(code) || "NoSuchObject".equals(code) || "NotFound".equals(code);
    }
}
