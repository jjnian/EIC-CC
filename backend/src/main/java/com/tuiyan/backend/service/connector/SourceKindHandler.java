package com.tuiyan.backend.service.connector;

import com.tuiyan.backend.entity.DataSourcePO;
import com.tuiyan.backend.model.dto.DataSourceTestResponse;
import com.tuiyan.backend.service.DataSourceService;

import java.util.Map;

/**
 * 数据源类型处理器：把「按 kind 变化的行为」(连接测试 / 导出成经验文档 / 创建·删除生命周期)封装到
 * 每种来源自己的实现里,消除 {@code DataSourceService} 里按 kind 的 if-else 分派。
 * <p>仿 {@link SqlDialect} 注册表的开闭设计:<b>加一种数据源 = 加一个 {@code SourceKindHandler} 实现</b>,
 * 不改动 {@code DataSourceService}。由 {@link SourceKindHandlerRegistry} 按 {@link #supports(String)} 解析。
 * <p>注:jdbc-only 的操作(listTables/executeSql/introspect 等 http 源本就没有的)不属于此分派——
 * 它们由 {@code DataSourceService.requireJdbc} 守卫,是类型专有能力而非多态行为。
 */
public interface SourceKindHandler {

    /** 本处理器是否服务该 kind(如 JDBC 处理器服务 mysql/pgsql/oracle/dm/gbase)。 */
    boolean supports(String kind);

    /** 连接测试:cfg 为已解析的配置。 */
    DataSourceTestResponse test(String kind, Map<String, Object> cfg);

    /** 把数据源抽取成一篇可入经验库的 Markdown 文档;不支持时抛 {@link IllegalArgumentException}。 */
    DataSourceService.SourceDocExport exportDoc(DataSourcePO po, Map<String, Object> cfg, int sampleRows);

    /** 创建 / 更新落库后:如 https 接口按 schedule 配置注册定时拉取。默认无操作。 */
    default void onPersist(DataSourcePO po, Map<String, Object> cfg) {}

    /** 删除前:如 https 取消定时、file 清理落桶原件。默认无操作。 */
    default void onDelete(DataSourcePO po) {}
}
