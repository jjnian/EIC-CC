package com.tuiyan.backend.service.extraction;

import com.tuiyan.backend.support.FileSniffer;
import com.tuiyan.backend.support.PdfTextExtractor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * PDF 处理：先抽文本；若每页平均字符数低于阈值（疑似扫描件 / 截图），追加把前若干页
 * 渲染为图片识别；超过预算时截断并打 truncated 标记。
 */
@Component
@Order(10)
public class PdfFileHandler implements SourceFileHandler {

    // 单文件抽取文本上限（按字符），超过会截断并打 truncated 标记
    private static final int  TEXT_CHAR_BUDGET   = 60_000;
    // 单个 PDF 文件体积上限（12 MB）
    private static final long FILE_BYTES_LIMIT   = 12L * 1024 * 1024;
    // 每页平均文本字符数下限：低于此阈值视为"扫描件 / 文本稀疏"，触发图片渲染兜底
    private static final int  MIN_TEXT_PER_PAGE  = 200;
    // 渲染兜底时最多渲染前 8 页，分辨率 110 dpi（视觉清晰 + 体积可控）
    private static final int  RENDER_MAX_PAGES   = 8;
    private static final int  RENDER_DPI         = 110;
    // 单张渲染图片字节上限：8 MB
    private static final long IMAGE_BYTE_LIMIT   = 8L * 1024 * 1024;
    // 稀疏文本仍有页眉页脚等价值，但保留太多会污染 prompt，截短到 4000 字符
    private static final int  BARE_TEXT_KEEP     = 4_000;

    @Override
    public boolean supports(FileProbe probe) {
        boolean pdfMagic = FileSniffer.isPdfMagic(probe.head());
        if (!pdfMagic) return false;
        String lname = probe.lowerName();
        String lct = probe.lowerContentType();
        return lct.contains("pdf") || lname.endsWith(".pdf");
    }

    @Override
    public void handle(UploadedFile f, String safeName, ExtractionContext ctx, Map<String, Object> meta) throws IOException {
        long size = f.size();
        if (size > FILE_BYTES_LIMIT) {
            throw new IllegalArgumentException(
                    "PDF " + safeName + " 超过 " + (FILE_BYTES_LIMIT / (1024 * 1024)) + " MB 限制");
        }
        String text;
        int pageCount;
        try (PDDocument doc = Loader.loadPDF(f.bytes())) {
            pageCount = doc.getNumberOfPages();
            meta.put("pages", pageCount);
            text = PdfTextExtractor.extractText(doc);

            int rawLen = text == null ? 0 : text.trim().length();
            boolean textBare = pageCount > 0 && rawLen < pageCount * MIN_TEXT_PER_PAGE;
            if (textBare) {
                ctx.step().emit("rendering_pdf", safeName + " 文字稀疏，正在将页面渲染为图片识别…");
                java.util.List<java.util.Map<String, Object>> rendered0 = new java.util.ArrayList<>(ctx.imageAttachments());
                int before = rendered0.size();
                int rendered = PdfTextExtractor.renderPages(doc, rendered0,
                        RENDER_MAX_PAGES, RENDER_DPI, IMAGE_BYTE_LIMIT, ExtractionContext.TOTAL_IMAGE_BUDGET);
                // renderPages 以"全局已有图片数"控预算，这里只把新渲染的页归到本文件来源下
                for (int ri = before; ri < rendered0.size(); ri++) ctx.addImage(safeName, rendered0.get(ri));
                meta.put("renderedPages", rendered);
                if (text != null && text.length() > BARE_TEXT_KEEP) {
                    text = text.substring(0, BARE_TEXT_KEEP);
                }
            }
        }
        int rawChars = text == null ? 0 : text.length();
        if (text != null && text.length() > TEXT_CHAR_BUDGET) {
            text = text.substring(0, TEXT_CHAR_BUDGET) + "\n[…truncated…]";
            meta.put("truncated", true);
        }
        meta.put("type", "pdf");
        meta.put("chars", rawChars);
        ctx.appendSection(safeName, "# 文件 " + safeName, text);
    }
}
