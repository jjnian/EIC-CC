package com.tuiyan.backend.service.connector.file;

import java.util.Map;

/**
 * 数据源「落桶文件」的单类型处理策略。新增一种可归档的格式（如 pptx / csv / 视频）
 * 只需新增一个 {@code @Component} 实现并标注 {@code @Order}，{@code FileStoredService}
 * 无需任何改动即可识别——遵循开闭原则。
 * <p>多个 handler 按 {@code @Order} 升序匹配，命中第一个 {@link #supports} 即处理。
 * <p>注意：本接口面向「归档存储 + 抽全文本」，与 {@code service.extraction.SourceFileHandler}
 * （面向 LLM 本体抽取，含图片渲染）目的不同，两套策略各自独立演进。
 */
public interface StoredFileHandler {

    /**
     * 是否处理该文件。
     * @param lowerFilename 已小写化的安全文件名（含扩展名）
     * @param head          文件头若干字节，用于 magic 嗅探
     */
    boolean supports(String lowerFilename, byte[] head);

    /** 本类型允许的最大字节数，超过由 {@code FileStoredService} 统一报 400。 */
    long maxBytes();

    /** 类型名（用于「仅支持 X / Y」「X 超过 N MB 限制」等提示），如 {@code "PDF"}。 */
    String label();

    /**
     * 从原始字节抽取纯文本与元信息。实现内部应自行兜底（损坏文件返回空文本而非抛错），
     * 抽不出文本是正常降级，不应让整单上传失败。
     */
    Result extract(byte[] bytes);

    /**
     * 抽取结果：纯文本 + 元信息（pages / paragraphs / tables / audio 等，按类型而定）。
     * meta 仅放本类型有意义的键，{@code FileStoredService} 会原样并入 config_json。
     */
    record Result(String text, Map<String, Object> meta) {
        public static Result of(String text) { return new Result(text, Map.of()); }
        public static Result of(String text, Map<String, Object> meta) { return new Result(text, meta); }
    }
}
