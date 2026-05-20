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
 */
public final class PdfTextExtractor {

    private PdfTextExtractor() {}

    /** PDF 文本抽取(失败时调用方处理 IOException)。 */
    public static String extractText(PDDocument doc) throws IOException {
        return new PDFTextStripper().getText(doc);
    }

    /**
     * 把 PDF 前 maxPages 页渲染成 PNG(dpi 指定),每张过滤体积限制 imageByteLimit。
     * 累计达 globalImageBudget 后停止。每页处理后 flush BufferedImage。
     * 直接把 dataUrl 附件追加到 attachments 列表。返回本次渲染张数。
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
            if (attachments.size() >= globalImageBudget) break;
            BufferedImage img = renderer.renderImageWithDPI(p, dpi);
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(img, "png", baos);
                byte[] bytes = baos.toByteArray();
                if (bytes.length > imageByteLimit) continue;
                String b64 = Base64.getEncoder().encodeToString(bytes);
                Map<String, Object> att = new LinkedHashMap<>();
                att.put("type", "image");
                att.put("dataUrl", "data:image/png;base64," + b64);
                attachments.add(att);
                rendered++;
            } finally {
                img.flush();
            }
        }
        return rendered;
    }
}
