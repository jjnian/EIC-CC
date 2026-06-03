package com.tuiyan.backend.service.connector.file;

import com.tuiyan.backend.support.FileSniffer;
import com.tuiyan.backend.support.PdfTextExtractor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/** PDF 落桶处理：抽全文本，元信息记录页数。 */
@Component
@Order(10)
public class PdfStoredHandler implements StoredFileHandler {

    private static final Logger log = LoggerFactory.getLogger(PdfStoredHandler.class);
    private static final long LIMIT_BYTES = 12L * 1024 * 1024;

    @Override
    public boolean supports(String lowerFilename, byte[] head) {
        return FileSniffer.isPdfMagic(head) || lowerFilename.endsWith(".pdf");
    }

    @Override
    public long maxBytes() { return LIMIT_BYTES; }

    @Override
    public String label() { return "PDF"; }

    @Override
    public Result extract(byte[] bytes) {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            int pages = doc.getNumberOfPages();
            String text = PdfTextExtractor.extractText(doc);
            return Result.of(text, Map.of("pages", pages));
        } catch (Exception e) {
            log.warn("PDF 抽文本失败: {}", e.toString());
            return Result.of("");
        }
    }
}
