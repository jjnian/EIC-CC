package com.tuiyan.backend.service;

import com.tuiyan.backend.service.connector.JdbcConnectorService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 {@link JdbcConnectorService} 的 schema record（DatabaseSchemaInfo/TableInfo/
 * ColumnInfo/ForeignKeyInfo/UniqueKeyInfo）序列化成前端契约的 Map 结构。
 * <p>输出结构：{kind, database, tables:[{name, comment, estimatedRows,
 * columns:[...], foreignKeys:[...], uniqueKeys:[...]}]}，字段名与嵌套层级即前端契约，
 * 不可改动。
 */
@Component
public class SchemaInfoDtoMapper {

    /** DatabaseSchemaInfo → 前端契约 Map（结构逐字段等价于原 introspectSchema 内联序列化）。 */
    public Map<String, Object> toMap(JdbcConnectorService.DatabaseSchemaInfo info) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", info.kind());
        out.put("database", info.database());
        List<Map<String, Object>> tables = new ArrayList<>();
        for (JdbcConnectorService.TableInfo t : info.tables()) {
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("name", t.name());
            tm.put("comment", t.comment());
            tm.put("estimatedRows", t.estimatedRows());
            List<Map<String, Object>> cols = new ArrayList<>();
            for (JdbcConnectorService.ColumnInfo c : t.columns()) {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("name", c.name());
                cm.put("dataType", c.dataType());
                cm.put("nullable", c.nullable());
                cm.put("defaultValue", c.defaultValue());
                cm.put("comment", c.comment());
                cm.put("primaryKey", c.primaryKey());
                cols.add(cm);
            }
            tm.put("columns", cols);
            List<Map<String, Object>> fks = new ArrayList<>();
            for (JdbcConnectorService.ForeignKeyInfo fk : t.foreignKeys()) {
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("constraintName", fk.constraintName());
                fm.put("fromColumn", fk.fromColumn());
                fm.put("toTable", fk.toTable());
                fm.put("toColumn", fk.toColumn());
                fks.add(fm);
            }
            tm.put("foreignKeys", fks);
            List<Map<String, Object>> uks = new ArrayList<>();
            for (JdbcConnectorService.UniqueKeyInfo uk : t.uniqueKeys()) {
                Map<String, Object> um = new LinkedHashMap<>();
                um.put("name", uk.name());
                um.put("columns", uk.columns());
                uks.add(um);
            }
            tm.put("uniqueKeys", uks);
            tables.add(tm);
        }
        out.put("tables", tables);
        return out;
    }
}
