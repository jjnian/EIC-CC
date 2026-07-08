package com.tuiyan.backend.service.connector;

import com.tuiyan.backend.entity.DataSourcePO;
import com.tuiyan.backend.model.dto.DataSourceTestResponse;
import com.tuiyan.backend.service.DataSourceService;
import com.tuiyan.backend.service.llm.DdlRenderer;
import com.tuiyan.backend.service.llm.SchemaGraphFragmentRenderer;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 关系型数据库(mysql/pgsql/oracle/dm/gbase)的类型处理器:连接测试 + 导出库结构 DDL 文档
 * (含外键/视图定义/存储过程血缘的确定性结构化片段 + 过程源码节选)入经验库。
 */
@Component
public class JdbcKindHandler implements SourceKindHandler {

    /** 采样的最大表数与每表最大行数:防大库打太多查询 / 经验正文过长。 */
    private static final int SAMPLE_MAX_TABLES = 60;
    private static final int SAMPLE_MAX_PER_TABLE = 10;

    private final JdbcConnectorService jdbc;
    private final DdlRenderer ddlRenderer;
    private final SchemaGraphFragmentRenderer schemaFragmentRenderer;

    public JdbcKindHandler(JdbcConnectorService jdbc, DdlRenderer ddlRenderer,
                           SchemaGraphFragmentRenderer schemaFragmentRenderer) {
        this.jdbc = jdbc;
        this.ddlRenderer = ddlRenderer;
        this.schemaFragmentRenderer = schemaFragmentRenderer;
    }

    @Override
    public boolean supports(String kind) {
        return SourceKind.isJdbc(kind);
    }

    @Override
    public DataSourceTestResponse test(String kind, Map<String, Object> cfg) {
        return jdbc.test(kind, cfg);
    }

    @Override
    public DataSourceService.SourceDocExport exportDoc(DataSourcePO po, Map<String, Object> cfg, int sampleRows) {
        JdbcConnectorService.DatabaseSchemaInfo info = jdbc.introspectSchema(po.getKind(), cfg, 500);
        Map<String, JdbcConnectorService.TableSample> samples = Map.of();
        if (sampleRows > 0) {
            samples = jdbc.sampleRows(po.getKind(), cfg, info.tables(),
                    Math.min(sampleRows, SAMPLE_MAX_PER_TABLE), SAMPLE_MAX_TABLES);
        }
        String ddl = ddlRenderer.render(info, samples);
        // 外键/视图定义/存储过程 = 血缘 ground truth：确定性直出结构化片段
        // （source=derived/confidence=1.0），建图时免 LLM 重抽。
        String graphBlock = schemaFragmentRenderer.renderEmbeddedBlock(info);

        String title = "「" + po.getName() + "」数据库 DDL";
        String content = "# " + title + "\n\n"
                + "> 库: `" + info.database() + "` · 对象数: " + info.tables().size()
                + (info.routines().isEmpty() ? "" : " · 存储过程/函数: " + info.routines().size())
                + (!samples.isEmpty() ? " · 含样例数据" : "")
                + (graphBlock.isBlank() ? "" : " · 含确定性血缘(外键/视图/存储过程)")
                + " · 由数据源结构内省自动生成\n\n"
                + "```sql\n" + ddl + "\n```\n"
                // 确定性血缘的结构化图片段（隐藏注释）：建图时直连合并，人读 markdown 不受影响
                + graphBlock;
        return new DataSourceService.SourceDocExport(po.getName(), title, content, "DDL,schema", "ddl");
    }
}
