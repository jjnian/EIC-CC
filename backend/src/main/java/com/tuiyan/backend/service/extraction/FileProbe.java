package com.tuiyan.backend.service.extraction;

/**
 * 文件类型识别所需的原始素材：头部字节 + 文件名 + content-type。
 * 由编排层构造一次，传给每个 {@link SourceFileHandler#supports} 自行判定。
 */
public record FileProbe(byte[] head, String safeName, String contentType) {

    public String lowerName() {
        return safeName == null ? "" : safeName.toLowerCase();
    }

    public String lowerContentType() {
        return contentType == null ? "" : contentType.toLowerCase();
    }
}
