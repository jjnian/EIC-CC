package com.tuiyan.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 节点当前状态 PO，对应 node_state 表（态势层）。
 * <p>每个「状态源绑定」一行：状态查询的最新结果 + 阈值判级。
 * 节点可有多条绑定 = 多个状态源，前端着色取最严重级别。
 */
@TableName("node_state")
public class NodeStatePO {
    @TableId(type = IdType.INPUT)
    private String bindingId;
    private String workspaceId;
    private String modelId;
    private String nodeId;
    /** 状态查询结果（首行首列，文本化）。 */
    private String value;
    /** normal / warn / alert / error（采集失败）。 */
    private String level;
    /** 采集错误信息等。 */
    private String message;
    private Long updatedAt;

    public String getBindingId() { return bindingId; }
    public void setBindingId(String bindingId) { this.bindingId = bindingId; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
