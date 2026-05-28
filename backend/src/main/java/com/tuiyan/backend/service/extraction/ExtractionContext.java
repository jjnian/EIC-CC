package com.tuiyan.backend.service.extraction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 单次抽取的累加上下文：把"待喂给 LLM 的文本 / 图片 / 数据源元信息 / 进度回调"
 * 收拢到一个对象里，替代在各方法间传递多个累加器参数，降低签名耦合。
 * <p>正文追加是线程安全的（URL 抓取并行写入），其余累加器在编排层按文件顺序单线程写入。
 */
public final class ExtractionContext {

    /** 单次抽取允许的总图片数（PDF 渲染 + 用户直传图片合计）。 */
    public static final int TOTAL_IMAGE_BUDGET = 12;

    private final StringBuilder combinedText = new StringBuilder();
    private final List<Map<String, Object>> imageAttachments = new ArrayList<>();
    private final List<Map<String, Object>> sourcesMeta = new ArrayList<>();
    private final StepSink step;

    public ExtractionContext(StepSink step) {
        this.step = step == null ? StepSink.NOOP : step;
    }

    public StepSink step() { return step; }

    public List<Map<String, Object>> imageAttachments() { return imageAttachments; }

    public List<Map<String, Object>> sourcesMeta() { return sourcesMeta; }

    public int imageCount() { return imageAttachments.size(); }

    /** 是否已用尽全局图片预算。 */
    public boolean imageBudgetReached() { return imageAttachments.size() >= TOTAL_IMAGE_BUDGET; }

    public void addImage(Map<String, Object> attachment) { imageAttachments.add(attachment); }

    public void addSource(Map<String, Object> meta) { sourcesMeta.add(meta); }

    public int textLength() {
        synchronized (combinedText) { return combinedText.length(); }
    }

    public String text() {
        synchronized (combinedText) { return combinedText.toString(); }
    }

    /** 以 "# header\n\n text\n\n" 形式线程安全地追加正文；text 为空则忽略。 */
    public void appendSection(String header, String text) {
        if (text == null || text.isBlank()) return;
        synchronized (combinedText) {
            combinedText.append(header).append("\n\n").append(text).append("\n\n");
        }
    }
}
