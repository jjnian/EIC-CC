package com.tuiyan.backend.support;

import com.tuiyan.backend.service.connector.JdbcConnectorService.ColumnInfo;
import com.tuiyan.backend.service.connector.JdbcConnectorService.DatabaseSchemaInfo;
import com.tuiyan.backend.service.connector.JdbcConnectorService.ForeignKeyInfo;
import com.tuiyan.backend.service.connector.JdbcConnectorService.TableInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 隐式引用血缘推断：生产库普遍<b>不建外键</b>（互联网/大表场景几乎是惯例），引用关系只体现在
 * 命名约定里——{@code orders.customer_id} 指向 {@code customers.id}，但没有 FK 声明。
 * 只依赖 FK 的确定性血缘在这类库上是空的，这是血缘图完整性最大的洞。
 * <p>本推断器从内省 schema 按命名规则补全这层引用：
 * <ul>
 *   <li>列名形如 {@code xxx_id / xxxId / xxx_no / xxx_code / xxx_key}，且前缀能对上某张表
 *       （支持复数、驼峰、t_/tb_/tbl_/sys_/biz_ 等常见表前缀差异）；</li>
 *   <li>父表侧取单列主键，否则退化为与子列同名列 / 后缀同名列（须真实存在）；</li>
 *   <li>已被声明 FK 覆盖的列、自引用（parent_id 型）一律跳过——FK 走确定性通路，自环画不出来。</li>
 * </ul>
 * 产出仅是<b>候选</b>：{@code source=inferred、confidence=0.5}，证据文本按
 * {@code child.col → parent.col} 格式给出，可被「值包含检验」一键佐证升级为数据证实，
 * 也会自动进入人工审核队列——推断补全 + 数据/人工双闭环，不污染确定性血缘。
 */
public final class ImplicitRefInferencer {

    /** 单库最多产出的推断引用数，防巨库组合爆炸。 */
    private static final int MAX_REFS = 500;

    /** snake_case：prefix_id / prefix_no / prefix_code / prefix_key。 */
    private static final Pattern SNAKE = Pattern.compile("^(.{2,}?)_(id|no|code|key)$");
    /** camelCase：prefixId / prefixNo / prefixCode / prefixKey。 */
    private static final Pattern CAMEL = Pattern.compile("^(.{2,}?)(Id|No|Code|Key)$");
    /** 常见表名修饰前缀，比对时剥掉。 */
    private static final Pattern TABLE_PREFIX = Pattern.compile("^(t|tb|tbl|sys|biz)_");

    private ImplicitRefInferencer() {}

    /** 一条推断出的隐式引用：child.childColumn → parent.parentColumn（表名为内省原名）。 */
    public record ImplicitRef(String childTable, String childColumn, String parentTable, String parentColumn) {}

    /** 从内省 schema 推断隐式引用（确定性规则，无 LLM；解析不出即不产出，宁缺毋滥）。 */
    public static List<ImplicitRef> infer(DatabaseSchemaInfo schema) {
        List<ImplicitRef> out = new ArrayList<>();
        if (schema == null || schema.tables() == null || schema.tables().isEmpty()) return out;

        // 归一表名（裸名 + 剥修饰前缀）→ TableInfo，首见优先
        Map<String, TableInfo> byKey = new LinkedHashMap<>();
        for (TableInfo t : schema.tables()) {
            String bare = bareName(t.name());
            byKey.putIfAbsent(bare, t);
            String stripped = TABLE_PREFIX.matcher(bare).replaceFirst("");
            if (!stripped.equals(bare)) byKey.putIfAbsent(stripped, t);
        }

        for (TableInfo t : schema.tables()) {
            if (t.isView() || t.columns() == null) continue;   // 视图血缘走定义体解析
            // 已被声明 FK 覆盖的列：走确定性通路，不重复推断
            Set<String> fkCols = new HashSet<>();
            for (ForeignKeyInfo fk : t.foreignKeys()) {
                if (fk != null && fk.fromColumn() != null) fkCols.add(fk.fromColumn().toLowerCase(Locale.ROOT));
            }
            for (ColumnInfo c : t.columns()) {
                if (out.size() >= MAX_REFS) return out;
                if (c == null || c.name() == null) continue;
                if (fkCols.contains(c.name().toLowerCase(Locale.ROOT))) continue;
                String[] parsed = parseRefColumn(c.name());
                if (parsed == null) continue;
                TableInfo parent = lookupParent(byKey, parsed[0]);
                if (parent == null || parent == t) continue;   // 找不到父表 / 自引用跳过
                String parentColumn = pickParentColumn(parent, c.name(), parsed[1]);
                if (parentColumn == null) continue;
                out.add(new ImplicitRef(t.name(), c.name(), parent.name(), parentColumn));
            }
        }
        return out;
    }

    /** 解析引用形态的列名：返回 {前缀(小写下划线归一), 后缀(小写)}，非引用形态返回 null。 */
    private static String[] parseRefColumn(String column) {
        Matcher m = SNAKE.matcher(column.toLowerCase(Locale.ROOT));
        if (m.matches()) return new String[]{m.group(1), m.group(2)};
        m = CAMEL.matcher(column);
        if (m.matches()) {
            // 驼峰前缀归一为下划线小写（orderItem → order_item）
            String prefix = m.group(1).replaceAll("(?<=[a-z0-9])([A-Z])", "_$1").toLowerCase(Locale.ROOT);
            return new String[]{prefix, m.group(2).toLowerCase(Locale.ROOT)};
        }
        return null;
    }

    /** 按前缀找父表：原样 / 复数 s / es / y→ies 四种候选，命中归一表名索引即返回。 */
    private static TableInfo lookupParent(Map<String, TableInfo> byKey, String prefix) {
        List<String> candidates = new ArrayList<>(4);
        candidates.add(prefix);
        candidates.add(prefix + "s");
        candidates.add(prefix + "es");
        if (prefix.endsWith("y") && prefix.length() > 2) {
            candidates.add(prefix.substring(0, prefix.length() - 1) + "ies");
        }
        for (String cand : candidates) {
            TableInfo hit = byKey.get(cand);
            if (hit != null) return hit;
        }
        return null;
    }

    /**
     * 选父表侧被引用列：单列主键 &gt; 与子列同名列 &gt; 与后缀同名列（如 id/no/code）。
     * 列必须真实存在，否则返回 null（不猜）。
     */
    private static String pickParentColumn(TableInfo parent, String childColumn, String suffix) {
        List<ColumnInfo> cols = parent.columns() == null ? List.of() : parent.columns();
        List<String> pks = new ArrayList<>();
        for (ColumnInfo c : cols) if (c.primaryKey()) pks.add(c.name());
        if (pks.size() == 1) return pks.get(0);
        String childLower = childColumn.toLowerCase(Locale.ROOT);
        for (ColumnInfo c : cols) if (c.name() != null && c.name().toLowerCase(Locale.ROOT).equals(childLower)) return c.name();
        for (ColumnInfo c : cols) if (c.name() != null && c.name().toLowerCase(Locale.ROOT).equals(suffix)) return c.name();
        return null;
    }

    /** 裸表名：去 schema 前缀 + 小写。 */
    public static String bareName(String table) {
        if (table == null) return "";
        String s = table.trim().toLowerCase(Locale.ROOT);
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }
}
