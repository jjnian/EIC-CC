package com.tuiyan.backend.service.extraction;

import java.io.IOException;
import java.util.Map;

/**
 * 单一文件类型的处理策略。新增一种可抽取的文件格式（如 txt / csv / pptx）只需新增一个
 * {@code @Component} 实现并标注 {@code @Order}，编排层 {@code DocumentExtractionService}
 * 无需任何改动即可识别——遵循开闭原则。
 * <p>多个 handler 按 {@code @Order} 升序匹配，命中第一个 {@link #supports} 即处理。
 */
public interface SourceFileHandler {

    /** 依据文件头 + 扩展名 + content-type 判断本 handler 是否处理该文件。 */
    boolean supports(FileProbe probe);

    /**
     * 处理文件：把抽取出的文本 / 图片写入 {@code ctx}，并把本文件的元信息填入 {@code meta}
     * （至少要 {@code meta.put("type", ...)}）。入参非法（如超大文件）时抛
     * {@link IllegalArgumentException}，由上层映射为 400。
     */
    void handle(UploadedFile file, String safeName, ExtractionContext ctx, Map<String, Object> meta) throws IOException;
}
