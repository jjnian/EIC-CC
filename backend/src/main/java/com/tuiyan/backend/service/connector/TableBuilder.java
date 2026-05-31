package com.tuiyan.backend.service.connector;

import java.util.ArrayList;
import java.util.List;

/**
 * 内部 builder：边拉数据边累加，最后 build() 成不可变 TableInfo。
 * <p>包内可见，供 MysqlSchemaIntrospector / PgsqlSchemaIntrospector 共用。
 * <p>注意：列/外键/唯一约束/表等元信息 record 仍原地保留在 {@link JdbcConnectorService}，
 * 这里以全限定名引用，避免移动 record 破坏其它调用方。
 */
final class TableBuilder {
    final String name;
    final String comment;
    final long rows;
    final List<JdbcConnectorService.ColumnInfo> columns = new ArrayList<>();
    final List<JdbcConnectorService.ForeignKeyInfo> foreignKeys = new ArrayList<>();
    final List<JdbcConnectorService.UniqueKeyInfo> uniqueKeys = new ArrayList<>();

    TableBuilder(String name, String comment, long rows) {
        this.name = name;
        this.comment = comment;
        this.rows = rows;
    }

    JdbcConnectorService.TableInfo build() {
        return new JdbcConnectorService.TableInfo(name, comment, columns, foreignKeys, uniqueKeys, rows);
    }
}
