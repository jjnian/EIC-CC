package com.tuiyan.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 版本节点属性 PO，对应 ontology_version_node_prop 表。
 */
@TableName("ontology_version_node_prop")
public class OntologyVersionNodePropPO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long versionId;
    private String nodeId;
    private String propKey;
    private String propValue;
    private String valueType;
    private String source;
    private Integer sortNo;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getVersionId() { return versionId; }
    public void setVersionId(Long versionId) { this.versionId = versionId; }
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
