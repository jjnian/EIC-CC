package com.tuiyan.backend.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 建图来源记录：某模型由「哪些经验的哪个内容版本」构建（复合主键 model_id + experience_id）。
 * 增量建图据此跳过内容未变化的经验。
 */
@TableName("model_build_source")
public class ModelBuildSourcePO {

    private String modelId;
    private String experienceId;
    /** 经验（标题+正文）的 SHA-256 十六进制。 */
    private String contentHash;
    private Long builtAt;

    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public String getExperienceId() { return experienceId; }
    public void setExperienceId(String experienceId) { this.experienceId = experienceId; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public Long getBuiltAt() { return builtAt; }
    public void setBuiltAt(Long builtAt) { this.builtAt = builtAt; }
}
