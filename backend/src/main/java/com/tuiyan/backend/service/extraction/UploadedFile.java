package com.tuiyan.backend.service.extraction;

/**
 * 已读入内存的上传文件：从 MultipartFile 提前拷出字节，使抽取可以脱离 servlet
 * 请求线程在后台线程池执行（multipart 的临时文件随请求结束即被回收）。
 */
public record UploadedFile(String name, String contentType, long size, byte[] bytes) {}
