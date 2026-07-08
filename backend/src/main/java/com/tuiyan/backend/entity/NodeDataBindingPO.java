package com.tuiyan.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 节点数据供血绑定 PO，对应 node_data_binding 表。
 * <p>把「已建好的本体血缘图节点」绑定到某个数据源的表（及可选列映射），
 * 运行时即可按绑定从数据源取数，为图节点供血。一个节点可有多条绑定。
 */
@TableName("node_data_binding")
public class NodeDataBindingPO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String workspaceId;
    /** 所属本体模型 id */
    private String modelId;
    /** 模型图内的节点 id */
    private String nodeId;
    /** 供血数据源 id（mysql/pgsql） */
    private String dataSourceId;
    /** 绑定的表名 */
    private String tableName;
    /** 列→属性映射 JSON：[{"column":"..","attribute":".."}]，可空 */
    private String columnMap;
    /** 可选只读 WHERE 片段（不含 where 关键字），用于过滤取数 */
    private String filterSql;
    /** 态势层·状态查询：只读 SQL，首行首列作为节点状态值（如 SELECT count(*) FROM orders WHERE status='blocked'）。 */
    private String statusQuery;
    /** 态势层·阈值规则 JSON：[{level,op,value}]，按序首个命中生效，都不命中为 normal。 */
    private String statusRulesJson;
    /** 态势层·是否纳入定时刷新。 */
    private Boolean statusEnabled;
    /** 态势层·刷新间隔（秒，最小 60）。 */
    private Integer statusIntervalSec;
    private Long createdAt;
    private Long updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getDataSourceId() { return dataSourceId; }
    public void setDataSourceId(String dataSourceId) { this.dataSourceId = dataSourceId; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public String getColumnMap() { return columnMap; }
    public void setColumnMap(String columnMap) { this.columnMap = columnMap; }
    public String getFilterSql() { return filterSql; }
    public void setFilterSql(String filterSql) { this.filterSql = filterSql; }
    public String getStatusQuery() { return statusQuery; }
    public void setStatusQuery(String statusQuery) { this.statusQuery = statusQuery; }
    public String getStatusRulesJson() { return statusRulesJson; }
    public void setStatusRulesJson(String statusRulesJson) { this.statusRulesJson = statusRulesJson; }
    public Boolean getStatusEnabled() { return statusEnabled; }
    public void setStatusEnabled(Boolean statusEnabled) { this.statusEnabled = statusEnabled; }
    public Integer getStatusIntervalSec() { return statusIntervalSec; }
    public void setStatusIntervalSec(Integer statusIntervalSec) { this.statusIntervalSec = statusIntervalSec; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
