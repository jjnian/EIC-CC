package com.tuiyan.backend.controller;

import com.tuiyan.backend.model.dto.ApiResult;
import com.tuiyan.backend.service.DocxExtractionService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * 轻量文档抽取：仅返回纯文本，不走 LLM。供对话框上传场景使用，
 * 便于把 DOCX 内容拼到 chat prompt 里（像文本附件一样）。
 */
@RestController
@RequestMapping("/api/extract")
public class DocumentTextController {

    private final DocxExtractionService docxExtractionService;

    public DocumentTextController(DocxExtractionService docxExtractionService) {
        this.docxExtractionService = docxExtractionService;
    }

    @PostMapping(value = "/docx-text", consumes = {"multipart/form-data"})
    public ApiResult<Map<String, Object>> extractDocxText(@RequestParam("file") MultipartFile file) throws IOException {
        return ApiResult.ok(docxExtractionService.extractText(file));
    }
}
