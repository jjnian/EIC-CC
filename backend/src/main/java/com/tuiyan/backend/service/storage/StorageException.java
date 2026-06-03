package com.tuiyan.backend.service.storage;

/** 对象存储层统一异常，包装底层 MinIO / IO 错误。 */
public class StorageException extends RuntimeException {
    public StorageException(String message, Throwable cause) { super(message, cause); }
}
