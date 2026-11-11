package com._36knight.alpha.model.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * model_profiles 持久化实体，不作为接口返回对象。@author Andersen
 */
@TableName("model_profiles")
public class ModelProfileEntity {
    /**
     * 模型配置稳定标识。
     */
    @TableId(type = IdType.INPUT)
    private String id;
    /**
     * 所属接入平台外键。
     */
    private String platformId;
    /**
     * 平台内唯一的配置显示名称。
     */
    private String name;
    /**
     * API 模型标识。
     */
    private String model;
    /**
     * 仅后端可用的 API Key 密文，可为空。
     */
    private String apiKeyCiphertext;
    /**
     * 最后修改时间，UTC。
     */
    private OffsetDateTime updatedAt;

    /**
     * 读取模型配置稳定标识。
     */
    public String getId() {
        return id;
    }

    /**
     * 更新模型配置稳定标识。
     */
    public void setId(String value) {
        id = value;
    }

    /**
     * 读取所属接入平台外键。
     */
    public String getPlatformId() {
        return platformId;
    }

    /**
     * 更新所属接入平台外键。
     */
    public void setPlatformId(String value) {
        platformId = value;
    }

    /**
     * 读取平台内唯一的配置显示名称。
     */
    public String getName() {
        return name;
    }

    /**
     * 更新平台内唯一的配置显示名称。
     */
    public void setName(String value) {
        name = value;
    }

    /**
     * 读取API 模型标识。
     */
    public String getModel() {
        return model;
    }

    /**
     * 更新API 模型标识。
     */
    public void setModel(String value) {
        model = value;
    }

    /**
     * 读取仅后端可用的 API Key 密文，可为空。
     */
    public String getApiKeyCiphertext() {
        return apiKeyCiphertext;
    }

    /**
     * 更新仅后端可用的 API Key 密文，可为空。
     */
    public void setApiKeyCiphertext(String value) {
        apiKeyCiphertext = value;
    }

    /**
     * 读取最后修改时间，UTC。
     */
    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    /**
     * 更新最后修改时间，UTC。
     */
    public void setUpdatedAt(OffsetDateTime value) {
        updatedAt = value;
    }
}
