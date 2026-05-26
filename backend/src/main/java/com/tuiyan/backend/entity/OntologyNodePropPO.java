package com.tuiyan.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 本体节点属性 PO，对应 ontology_node_prop 表。
 * <p>用 BIGSERIAL 自增主键；同一 (model_id, node_id) 的多条 prop 通过 sort_no 维持顺序。
 */
@TableName("ontology_node_prop")
public class OntologyNodePropPO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String modelId;
    private String nodeId;
    private String propKey;
    private String propValue;
    // string | number | bool | json
    private String valueType;
    private String source;
    private Integer sortNo;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getPropKey() { return propKey; }
    public void setPropKey(String propKey) { this.propKey = propKey; }
    public String getPropValue() { return propValue; }
    public void setPropValue(String propValue) { this.propValue = propValue; }
    public String getValueType() { return valueType; }
    public void setValueType(String valueType) { this.valueType = valueType; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Integer getSortNo() { return sortNo; }
    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }
}
