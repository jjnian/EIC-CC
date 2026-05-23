package com.tuiyan.backend.support;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PDF 文本抽取 + 分页渲染为 PNG。
 * <p>用于把上传的 PDF 同时变成"可粘进 prompt 的文本"和"可作为视觉输入的图片"：
 * 大多数 LLM 在表格 / 图表丰富的 PDF 上读图比读文本更准，所以两路输出都提供。
 */
public final class PdfTextExtractor {

    private PdfTextExtractor() {}

    /** 直接抽取全文本；遇到加密 / 损坏 PDF 时抛 IOException，由调用方决定降级策略。 */
    public static String extractText(PDDocument doc) throws IOException {
        return new PDFTextStripper().getText(doc);
    }

    /**
     * 把 PDF 前 maxPages 页渲染成 PNG（dpi 指定），单张 > imageByteLimit 时跳过。
     * <p>累计 attachments.size() 达 globalImageBudget 后停止：避免一份 PDF 把整个对话的图片配额吃光。
     * <p>每页处理后 {@code img.flush()} 释放 BufferedImage 占用的堆外内存，
     * 否则连续处理大 PDF 会触发 Java 堆外内存告警。
     *
     * @param attachments        直接被追加 {@code {type:"image", dataUrl:"data:image/png;base64,..."}} 项的列表
     * @param maxPages           本次最多渲染的页数（上层根据文件大小自适应）
     * @param dpi                渲染分辨率，越高越清晰但体积也越大
     * @param imageByteLimit     单张 PNG 体积上限，超过则跳过该页
     * @param globalImageBudget  对话级总图片数上限
     * @return 实际渲染并加入 attachments 的张数
     */
    public static int renderPages(PDDocument doc,
                                  List<Map<String, Object>> attachments,
                                  int maxPages,
                                  int dpi,
                                  long imageByteLimit,
                                  int globalImageBudget) throws IOException {
        PDFRenderer renderer = new PDFRenderer(doc);
        int total = Math.min(doc.getNumberOfPages(), maxPages);
        int rendered = 0;
        for (int p = 0; p < total; p++) {
            // 总额度已用完则立即停止，不再渲染后续页（节省 CPU）
            if (attachments.size() >= globalImageBudget) break;
            BufferedImage img = renderer.renderImageWithDPI(p, dpi);
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(img, "png", baos);
                byte[] bytes = baos.toByteArray();
                // 体积超限的页静默跳过，不中断整个文档
                if (bytes.length > imageByteLimit) continue;
                String b64 = Base64.getEncoder().encodeToString(bytes);
                Map<String, Object> att = new LinkedHashMap<>();
                att.put("type", "image");
                att.put("dataUrl", "data:image/png;base64," + b64);
                attachments.add(att);
                rendered++;
            } finally {
                // 必须 flush，否则 BufferedImage 持有的堆外内存要等 GC 才释放
                img.flush();
            }
        }
        return rendered;
    }
}
