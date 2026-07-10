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

    /** 命名匹配的基础置信度。 */
    private static final double BASE_CONF = 0.4;
    /** 每命中一个佐证信号（类型匹配 / 父列唯一 / 注释提示）的置信增量。 */
    private static final double SIGNAL_STEP = 0.1;
    /**
     * 多信号置信封顶：刻意压在审核队列阈值(0.7)之下——再多的静态信号也仍是推断，
     * 升到 0.7 以上只能靠值包含检验（数据裁决）或专家确认。
     */
    private static final double CONF_CAP = 0.65;

    /**
     * 一条推断出的隐式引用：child.childColumn → parent.parentColumn（表名为内省原名）。
     * @param confidence 多信号打分（{@value #BASE_CONF} 起步，类型匹配/父列唯一/注释提示各
     *                   +{@value #SIGNAL_STEP}，封顶 {@value #CONF_CAP}）
     * @param signals    命中的信号摘要（如「命名·类型匹配·父列唯一」），写进证据供审核参考
     */
    public record ImplicitRef(String childTable, String childColumn,
                              String parentTable, String parentColumn,
                              double confidence, String signals) {}

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
                // 候选按优先级排列（主键 > 同名列 > 后缀列），取第一个类型不冲突的：
                // 主键类型对不上时回退到同名/后缀列（product_code → products.code 而非 products.sku）
                ImplicitRef ref = null;
                for (ParentCol pick : parentColumnCandidates(parent, c.name(), parsed[1])) {
                    ref = score(t, c, parent, pick);
                    if (ref != null) break;
                }
                if (ref != null) out.add(ref);
            }
        }
        return out;
    }

    /**
     * 多信号打分。命名匹配是入场券（{@value #BASE_CONF}），其上叠加内省元数据里的佐证信号：
     * <ul>
     *   <li><b>类型匹配</b>：子列与父列类型族一致 +{@value #SIGNAL_STEP}；
     *       类型族明确冲突（如 bigint → varchar）直接剪掉——同名不同型基本是误报；</li>
     *   <li><b>父列唯一</b>：父列是单列主键或有单列唯一索引 +{@value #SIGNAL_STEP}——
     *       被引用列几乎必然唯一，无唯一性的候选很可疑；</li>
     *   <li><b>注释提示</b>：子列注释里出现父表（裸名或业务注释名） +{@value #SIGNAL_STEP}。</li>
     * </ul>
     * @return 打分后的引用；类型冲突返回 null（剪枝）
     */
    private static ImplicitRef score(TableInfo child, ColumnInfo childCol,
                                     TableInfo parent, ParentCol pick) {
        double conf = BASE_CONF;
        StringBuilder signals = new StringBuilder("命名");

        String cf = typeFamily(childCol.dataType());
        String pf = typeFamily(pick.col().dataType());
        if (!cf.isEmpty() && !pf.isEmpty()) {
            if (!cf.equals(pf)) return null;               // 类型族冲突：剪枝
            conf += SIGNAL_STEP;
            signals.append("·类型匹配");
        }
        if (pick.unique()) {
            conf += SIGNAL_STEP;
            signals.append("·父列唯一");
        }
        if (commentHintsParent(childCol.comment(), parent)) {
            conf += SIGNAL_STEP;
            signals.append("·注释提示");
        }
        return new ImplicitRef(child.name(), childCol.name(), parent.name(), pick.col().name(),
                Math.min(CONF_CAP, Math.round(conf * 100) / 100.0), signals.toString());
    }

    /** 子列注释是否提到父表（裸表名去前缀 / 父表业务注释名，大小写不敏感）。 */
    private static boolean commentHintsParent(String comment, TableInfo parent) {
        if (comment == null || comment.isBlank()) return false;
        String c = comment.toLowerCase(Locale.ROOT);
        String bare = TABLE_PREFIX.matcher(bareName(parent.name())).replaceFirst("");
        if (!bare.isBlank() && c.contains(bare)) return true;
        String pc = parent.comment() == null ? "" : parent.comment().trim().toLowerCase(Locale.ROOT);
        return pc.length() >= 2 && c.contains(pc);
    }

    /**
     * 类型族归一：只求「明确冲突可判」，不求精确——未知类型返回空串（中立，不加分不剪枝）。
     */
    private static String typeFamily(String dataType) {
        if (dataType == null) return "";
        String t = dataType.toLowerCase(Locale.ROOT).replaceAll("\\(.*", "").trim();
        if (t.contains("uuid")) return "uuid";
        if (t.contains("int") || t.contains("serial") || t.contains("number")
                || t.contains("numeric") || t.contains("decimal")) return "num";
        if (t.contains("char") || t.contains("text") || t.contains("string") || t.contains("clob")) return "str";
        if (t.contains("date") || t.contains("time")) return "time";
        return "";
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

    /** 选中的父表侧被引用列 + 是否具备唯一性（单列主键 / 单列唯一索引覆盖）。 */
    private record ParentCol(ColumnInfo col, boolean unique) {}

    /**
     * 父表侧被引用列候选，按优先级排列：单列主键 &gt; 与子列同名列 &gt; 与后缀同名列（如 id/no/code）。
     * 列必须真实存在（不猜）；同一列只出现一次。调用方取第一个类型不冲突的候选——
     * 主键类型对不上（如 varchar 的 code 列指向 bigint 主键）时可回退到同名/后缀列。
     */
    private static List<ParentCol> parentColumnCandidates(TableInfo parent, String childColumn, String suffix) {
        List<ColumnInfo> cols = parent.columns() == null ? List.of() : parent.columns();
        List<ParentCol> out = new ArrayList<>(3);
        Set<String> seen = new HashSet<>();
        List<ColumnInfo> pks = new ArrayList<>();
        for (ColumnInfo c : cols) if (c.primaryKey()) pks.add(c);
        if (pks.size() == 1 && seen.add(pks.get(0).name().toLowerCase(Locale.ROOT))) {
            out.add(new ParentCol(pks.get(0), true));
        }
        String childLower = childColumn.toLowerCase(Locale.ROOT);
        for (ColumnInfo c : cols) {
            if (c.name() == null) continue;
            String lower = c.name().toLowerCase(Locale.ROOT);
            if ((lower.equals(childLower) || lower.equals(suffix)) && seen.add(lower)) {
                out.add(new ParentCol(c, c.primaryKey() || hasSingleColUnique(parent, c.name())));
            }
        }
        // 同名列优先于后缀列：上面的单循环按列序收集，这里把与子列同名的候选提前
        out.sort((a, b) -> {
            boolean an = a.col().name().toLowerCase(Locale.ROOT).equals(childLower);
            boolean bn = b.col().name().toLowerCase(Locale.ROOT).equals(childLower);
            boolean apk = a.col().primaryKey(), bpk = b.col().primaryKey();
            if (apk != bpk) return apk ? -1 : 1;      // 主键最优先
            if (an != bn) return an ? -1 : 1;         // 其次同名列
            return 0;
        });
        return out;
    }

    /** 父表是否有仅覆盖该列的单列唯一索引。 */
    private static boolean hasSingleColUnique(TableInfo parent, String column) {
        if (parent.uniqueKeys() == null) return false;
        for (var uk : parent.uniqueKeys()) {
            if (uk.columns() != null && uk.columns().size() == 1
                    && uk.columns().get(0).equalsIgnoreCase(column)) return true;
        }
        return false;
    }

    /** 裸表名：去 schema 前缀 + 小写。 */
    public static String bareName(String table) {
        if (table == null) return "";
        String s = table.trim().toLowerCase(Locale.ROOT);
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }
}
