package com.tuiyan.backend.service.llm;

import com.tuiyan.backend.service.connector.JdbcConnectorService.ColumnInfo;
import com.tuiyan.backend.service.connector.JdbcConnectorService.DatabaseSchemaInfo;
import com.tuiyan.backend.service.connector.JdbcConnectorService.ForeignKeyInfo;
import com.tuiyan.backend.service.connector.JdbcConnectorService.TableInfo;
import com.tuiyan.backend.service.connector.JdbcConnectorService.TableSample;
import com.tuiyan.backend.service.connector.JdbcConnectorService.UniqueKeyInfo;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把内省得到的 {@link DatabaseSchemaInfo} 渲染成可读的 DDL（CREATE TABLE / VIEW）。
 * <p>用于「导出数据库结构到经验库」：产出一份人可读、也利于 RAG 召回的结构文档。
 * <p>注意：这是基于内省元数据「尽力还原」的 DDL（类型/默认值取自系统表），
 * 主要面向阅读与检索，不保证可直接在目标库原样执行。
 */
@Component
public class DdlRenderer {

    /** 渲染整库 DDL（不含样例数据）。 */
    public String render(DatabaseSchemaInfo s) {
        return render(s, Map.of());
    }

    /**
     * 渲染整库 DDL：表头说明 + 每张表/视图的 CREATE 语句；若提供样例数据,则在对应基表
     * CREATE 之后追加 {@code INSERT ... VALUES} 形式的样例行(便于阅读字段含义与数据形态)。
     * @param samples 表名({@link TableInfo#name()}) → 样例;为空则不输出样例
     */
    public String render(DatabaseSchemaInfo s, Map<String, TableSample> samples) {
        boolean mysql = "mysql".equalsIgnoreCase(s.kind());
        if (samples == null) samples = Map.of();
        StringBuilder sb = new StringBuilder();
        sb.append("-- 数据库类型: ").append(s.kind()).append('\n');
        sb.append("-- 数据库名:   ").append(s.database()).append('\n');
        sb.append("-- 对象数量:   ").append(s.tables().size()).append('\n');
        sb.append("-- 说明: 以下 DDL 由 schema 内省元数据还原，面向阅读与检索，不保证可原样执行。\n");
        if (!samples.isEmpty()) {
            sb.append("-- 含样例数据: 每表 INSERT 为脱敏前的真实抽样,仅供理解字段含义与数据形态。\n");
        }
        sb.append('\n');
        for (TableInfo t : s.tables()) {
            if (t.isView()) {
                appendView(sb, t, mysql);
            } else {
                appendTable(sb, t, mysql);
                TableSample sample = samples.get(t.name());
                if (sample != null && !sample.rows().isEmpty()) {
                    appendSample(sb, t, sample, mysql);
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 把样例行渲染成 INSERT 语句(多行 VALUES);单元格按类型转 SQL 字面量,长串截断。 */
    private void appendSample(StringBuilder sb, TableInfo t, TableSample sample, boolean mysql) {
        sb.append("-- 样例数据（节选 ").append(sample.rows().size()).append(" 行）:\n");
        sb.append("INSERT INTO ").append(quote(t.name(), mysql)).append(" (")
          .append(joinQuoted(sample.columns(), mysql)).append(") VALUES\n");
        List<List<Object>> rows = sample.rows();
        for (int r = 0; r < rows.size(); r++) {
            List<Object> row = rows.get(r);
            sb.append("  (");
            for (int c = 0; c < row.size(); c++) {
                if (c > 0) sb.append(", ");
                sb.append(literal(row.get(c)));
            }
            sb.append(')').append(r < rows.size() - 1 ? ',' : ';').append('\n');
        }
    }

    /** 值 → SQL 字面量：null→NULL，数字/布尔原样，其余加单引号并转义,过长截断。 */
    private static String literal(Object v) {
        if (v == null) return "NULL";
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        String s = String.valueOf(v);
        if (s.length() > 120) s = s.substring(0, 120) + "…";
        return "'" + s.replace("'", "''").replace("\n", " ") + "'";
    }

    private void appendTable(StringBuilder sb, TableInfo t, boolean mysql) {
        if (t.comment() != null && !t.comment().isBlank()) {
            sb.append("-- ").append(t.name()).append(": ").append(t.comment()).append('\n');
        }
        if (t.estimatedRows() != null && t.estimatedRows() > 0) {
            sb.append("-- 估算行数: ~").append(t.estimatedRows()).append('\n');
        }
        sb.append("CREATE TABLE ").append(quote(t.name(), mysql)).append(" (\n");

        // 收集所有成员行（列 + 主键 + 唯一键 + 外键），统一在末尾按逗号拼接，注释跟在逗号后
        List<String[]> members = new ArrayList<>();   // {code, comment}
        for (ColumnInfo c : t.columns()) {
            StringBuilder col = new StringBuilder();
            col.append(quote(c.name(), mysql)).append(' ').append(c.dataType());
            if (!c.nullable()) col.append(" NOT NULL");
            if (c.defaultValue() != null && !c.defaultValue().isBlank()) {
                col.append(" DEFAULT ").append(c.defaultValue());
            }
            String cmt = (c.comment() != null && !c.comment().isBlank()) ? c.comment() : null;
            members.add(new String[]{col.toString(), cmt});
        }

        List<String> pkCols = new ArrayList<>();
        for (ColumnInfo c : t.columns()) if (c.primaryKey()) pkCols.add(quote(c.name(), mysql));
        if (!pkCols.isEmpty()) {
            members.add(new String[]{"PRIMARY KEY (" + String.join(", ", pkCols) + ")", null});
        }

        for (UniqueKeyInfo uk : t.uniqueKeys()) {
            String cols = joinQuoted(uk.columns(), mysql);
            String code = mysql
                    ? "UNIQUE KEY " + quote(uk.name(), true) + " (" + cols + ")"
                    : "CONSTRAINT " + quote(uk.name(), false) + " UNIQUE (" + cols + ")";
            members.add(new String[]{code, null});
        }

        for (ForeignKeyInfo fk : t.foreignKeys()) {
            String code = "CONSTRAINT " + quote(fk.constraintName(), mysql)
                    + " FOREIGN KEY (" + quote(fk.fromColumn(), mysql) + ")"
                    + " REFERENCES " + quote(fk.toTable(), mysql)
                    + " (" + quote(fk.toColumn(), mysql) + ")";
            members.add(new String[]{code, null});
        }

        for (int i = 0; i < members.size(); i++) {
            String[] m = members.get(i);
            sb.append("  ").append(m[0]);
            if (i < members.size() - 1) sb.append(',');
            if (m[1] != null) sb.append("  -- ").append(m[1]);
            sb.append('\n');
        }
        sb.append(");\n");
    }

    private void appendView(StringBuilder sb, TableInfo t, boolean mysql) {
        if (t.comment() != null && !t.comment().isBlank()) {
            sb.append("-- ").append(t.name()).append(": ").append(t.comment()).append('\n');
        }
        sb.append("CREATE VIEW ").append(quote(t.name(), mysql)).append(" AS\n");
        String def = t.definition() == null ? "" : t.definition().strip();
        if (def.isEmpty()) {
            sb.append("  -- （视图定义不可用）\n");
        } else {
            for (String line : def.split("\n")) sb.append("  ").append(line).append('\n');
            if (!def.endsWith(";")) sb.append(";\n");
        }
    }

    private static String joinQuoted(List<String> names, boolean mysql) {
        List<String> q = new ArrayList<>(names.size());
        for (String n : names) q.add(quote(n, mysql));
        return String.join(", ", q);
    }

    /** mysql 用反引号、pgsql 用双引号；内部同名符号做转义。 */
    private static String quote(String ident, boolean mysql) {
        if (ident == null) return mysql ? "``" : "\"\"";
        return mysql
                ? "`" + ident.replace("`", "``") + "`"
                : "\"" + ident.replace("\"", "\"\"") + "\"";
    }
}
