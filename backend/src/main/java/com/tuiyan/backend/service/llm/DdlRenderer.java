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
        // mysql 与 gbase（8a 走 MySQL 协议）用反引号；pgsql/oracle/dm 用双引号
        boolean mysql = "mysql".equalsIgnoreCase(s.kind()) || "gbase".equalsIgnoreCase(s.kind());
        if (samples == null) samples = Map.of();
        StringBuilder sb = new StringBuilder();
        sb.append("-- 数据库类型: ").append(s.kind()).append('\n');
        sb.append("-- 数据库名:   ").append(s.database()).append('\n');
        sb.append("-- 对象数量:   ").append(s.tables().size()).append('\n');
        sb.append("-- 说明: 以下 DDL 由 schema 内省元数据还原，面向阅读与检索，不保证可原样执行。\n");
        if (!samples.isEmpty()) {
            sb.append("-- 含样例数据: 真实抽样,已对疑似敏感字段(密码/手机/邮箱/证件/卡号/地址等)自动脱敏。\n");
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

    /** 把样例行渲染成 INSERT 语句(多行 VALUES);疑似敏感列按列名自动脱敏,其余按类型转字面量。 */
    private void appendSample(StringBuilder sb, TableInfo t, TableSample sample, boolean mysql) {
        List<String> cols = sample.columns();
        // 预判每列的脱敏策略(按列名),避免逐单元格重复判断
        List<Mask> masks = new ArrayList<>(cols.size());
        for (String c : cols) masks.add(maskOf(c));

        sb.append("-- 样例数据（节选 ").append(sample.rows().size()).append(" 行，敏感字段已脱敏）:\n");
        sb.append("INSERT INTO ").append(quote(t.name(), mysql)).append(" (")
          .append(joinQuoted(cols, mysql)).append(") VALUES\n");
        List<List<Object>> rows = sample.rows();
        for (int r = 0; r < rows.size(); r++) {
            List<Object> row = rows.get(r);
            sb.append("  (");
            for (int c = 0; c < row.size(); c++) {
                if (c > 0) sb.append(", ");
                Object cell = row.get(c);
                Mask m = c < masks.size() ? masks.get(c) : Mask.NONE;
                if (m != Mask.NONE && cell != null) {
                    sb.append(literal(applyMask(m, String.valueOf(cell))));
                } else {
                    sb.append(literal(cell));
                }
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

    // ── 样例数据脱敏 ──────────────────────────────────────────
    /** 脱敏类型，按列名启发式判定。 */
    private enum Mask { NONE, SECRET, PHONE, EMAIL, IDCARD, BANKCARD, ADDRESS, NAME }

    /** 按列名(小写,含中文)判断该列的脱敏策略;命中多类时取更敏感的(靠前)。 */
    private static Mask maskOf(String col) {
        String c = col == null ? "" : col.toLowerCase();
        if (containsAny(c, "password", "passwd", "pwd", "secret", "token", "apikey", "api_key",
                "private", "salt", "credential", "密码", "密钥")) return Mask.SECRET;
        if (containsAny(c, "id_card", "idcard", "id_no", "idno", "identity", "id_number",
                "身份证", "证件")) return Mask.IDCARD;
        if (containsAny(c, "bank", "card_no", "cardno", "card_number", "account_no", "acct_no",
                "银行卡", "卡号")) return Mask.BANKCARD;
        if (containsAny(c, "phone", "mobile", "tel", "手机", "电话", "联系方式")) return Mask.PHONE;
        if (containsAny(c, "email", "e_mail", "邮箱")) return Mask.EMAIL;
        if (containsAny(c, "address", "addr", "住址", "地址")) return Mask.ADDRESS;
        if (containsAny(c, "real_name", "realname", "full_name", "fullname", "true_name",
                "姓名", "真实姓名")) return Mask.NAME;
        return Mask.NONE;
    }

    private static boolean containsAny(String s, String... keys) {
        for (String k : keys) if (s.contains(k)) return true;
        return false;
    }

    /** 按策略对单值脱敏。 */
    private static String applyMask(Mask m, String v) {
        if (v.isBlank()) return v;
        return switch (m) {
            case SECRET   -> "***";
            case PHONE    -> maskMid(v, 3, 4);                 // 138****1234
            case IDCARD   -> maskMid(v, 4, 2);                 // 4101**********56
            case BANKCARD -> maskMid(v, 0, 4);                 // ************6789
            case NAME     -> v.substring(0, 1) + "**";          // 张**
            case EMAIL    -> maskEmail(v);                      // a***@x.com
            case ADDRESS  -> v.length() <= 6 ? v.charAt(0) + "***" : v.substring(0, 6) + "…";
            case NONE     -> v;
        };
    }

    /** 保留前 front、后 back,中间固定 4 个星;过短则整体打码。 */
    private static String maskMid(String s, int front, int back) {
        int len = s.length();
        if (len <= front + back) return "*".repeat(Math.max(3, len));
        return s.substring(0, front) + "****" + s.substring(len - back);
    }

    private static String maskEmail(String s) {
        int at = s.indexOf('@');
        if (at <= 0) return s.charAt(0) + "***";
        return s.charAt(0) + "***" + s.substring(at);
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
