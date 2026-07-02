package com.tuiyan.backend.service.extraction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单次抽取的累加上下文：把"待喂给 LLM 的文本 / 图片 / 数据源元信息 / 进度回调"
 * 收拢到一个对象里，替代在各方法间传递多个累加器参数，降低签名耦合。
 * <p>正文追加是线程安全的（URL 抓取并行写入），其余累加器在编排层按文件顺序单线程写入。
 * <p>文本与图片均按「来源名」分组记录：多来源时编排层可以按来源分路并行抽取，
 * 并把每个节点/边的 derived_source 精确标到具体文件/URL 上。
 */
public final class ExtractionContext {

    /** 单次抽取允许的总图片数（PDF 渲染 + 用户直传图片合计）。 */
    public static final int TOTAL_IMAGE_BUDGET = 12;

    private final StringBuilder combinedText = new StringBuilder();
    private final List<Map<String, Object>> imageAttachments = new ArrayList<>();
    /** 来源名 → 该来源贡献的正文段（保持加入顺序）。与 combinedText 同步写入。 */
    private final Map<String, StringBuilder> sourceTexts = new LinkedHashMap<>();
    /** 来源名 → 该来源贡献的图片附件。与 imageAttachments 同步写入。 */
    private final Map<String, List<Map<String, Object>>> sourceImages = new LinkedHashMap<>();
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

    /** 记录一张图片附件并归到其来源名下。 */
    public void addImage(String source, Map<String, Object> attachment) {
        synchronized (combinedText) {
            imageAttachments.add(attachment);
            sourceImages.computeIfAbsent(nz(source), k -> new ArrayList<>()).add(attachment);
        }
    }

    public void addSource(Map<String, Object> meta) { sourcesMeta.add(meta); }

    public int textLength() {
        synchronized (combinedText) { return combinedText.length(); }
    }

    public String text() {
        synchronized (combinedText) { return combinedText.toString(); }
    }

    /** 以 "# header\n\n text\n\n" 形式线程安全地追加正文，并归到来源名下；text 为空则忽略。 */
    public void appendSection(String source, String header, String text) {
        if (text == null || text.isBlank()) return;
        String section = header + "\n\n" + text + "\n\n";
        synchronized (combinedText) {
            combinedText.append(section);
            sourceTexts.computeIfAbsent(nz(source), k -> new StringBuilder()).append(section);
        }
    }

    /** 有正文或图片贡献的来源名列表（按首次贡献顺序）。 */
    public List<String> contributingSources() {
        synchronized (combinedText) {
            List<String> out = new ArrayList<>(sourceTexts.keySet());
            for (String s : sourceImages.keySet()) {
                if (!out.contains(s)) out.add(s);
            }
            return out;
        }
    }

    /** 某来源贡献的正文（无则空串）。 */
    public String sourceText(String source) {
        synchronized (combinedText) {
            StringBuilder sb = sourceTexts.get(nz(source));
            return sb == null ? "" : sb.toString();
        }
    }

    /** 某来源贡献的图片附件（无则空列表）。 */
    public List<Map<String, Object>> sourceImages(String source) {
        synchronized (combinedText) {
            List<Map<String, Object>> list = sourceImages.get(nz(source));
            return list == null ? List.of() : List.copyOf(list);
        }
    }

    private static String nz(String source) {
        return source == null || source.isBlank() ? "(unknown)" : source;
    }
}
