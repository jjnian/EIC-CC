package com.tuiyan.backend.service.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.entity.DataSourcePO;
import com.tuiyan.backend.model.dto.DataSourceTestResponse;
import com.tuiyan.backend.service.DataSourceService;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 文件类数据源(遗留 file_stored、抽取流程产生的音频等)的类型处理器:无需连接测试;
 * 导出用已抽取/归档的纯文本或转写正文作经验正文;删除时清理落桶原件。
 */
@Component
public class FileKindHandler implements SourceKindHandler {

    private final FileStoredService fileStored;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FileKindHandler(FileStoredService fileStored) {
        this.fileStored = fileStored;
    }

    @Override
    public boolean supports(String kind) {
        return SourceKind.FILE_STORED.equals(kind);
    }

    @Override
    public DataSourceTestResponse test(String kind, Map<String, Object> cfg) {
        return new DataSourceTestResponse(true, "无需测试", 0);
    }

    @Override
    public DataSourceService.SourceDocExport exportDoc(DataSourcePO po, Map<String, Object> cfg, int sampleRows) {
        String text = fileStored.readText(cfg, 0, 200_000);
        if (text == null || text.isBlank()) {
            text = readExtraTranscript(po);   // 音频等：转写正文落在 extra_json.transcript（非落桶）
        }
        if (text != null && !text.isBlank()) {
            String title = "「" + po.getName() + "」文件";
            String content = "# " + title + "\n\n> 由文件数据源抽取的文本自动生成\n\n" + text;
            return new DataSourceService.SourceDocExport(po.getName(), title, content, "file", "datasource");
        }
        throw new IllegalArgumentException("该数据源类型(" + po.getKind() + ")没有可抽取到经验库的内容");
    }

    @Override
    public void onDelete(DataSourcePO po) {
        fileStored.deleteFiles(po.getId());
    }

    /** 从 data_source.extra_json 读取音频转写正文（无则 null）。 */
    private String readExtraTranscript(DataSourcePO po) {
        String extra = po.getExtraJson();
        if (extra == null || extra.isBlank()) return null;
        try {
            JsonNode t = objectMapper.readTree(extra).get("transcript");
            return (t != null && t.isTextual() && !t.asText().isBlank()) ? t.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
