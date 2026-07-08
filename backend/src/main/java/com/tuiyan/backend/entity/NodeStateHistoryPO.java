package com.tuiyan.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 节点状态历史 PO，对应 node_state_history 表（态势层时序，按绑定保留最近 500 条）。
 */
@TableName("node_state_history")
public class NodeStateHistoryPO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String bindingId;
    private String modelId;
    private String nodeId;
    private String value;
    private String level;
    private Long collectedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBindingId() { return bindingId; }
    public void setBindingId(String bindingId) { this.bindingId = bindingId; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public Long getCollectedAt() { return collectedAt; }
    public void setCollectedAt(Long collectedAt) { this.collectedAt = collectedAt; }
}
