package com._36knight.alpha.model.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * model_platforms 持久化实体，不作为接口返回对象。@author Andersen
 */
@TableName("model_platforms")
public class ModelPlatformEntity {
    /**
     * 平台稳定标识。
     */
    @TableId(type = IdType.INPUT)
    private String id;
    /**
     * 平台显示名称，全局唯一。
     */
    private String name;
    /**
     * 已实现的客户端协议。
     */
    private String protocol;
    /**
     * 不含凭证的 API 基础地址。
     */
    private String baseUrl;
    /**
     * 可选的同源模型列表地址，空值更新必须实际清空数据库字段。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String modelCatalogUrl;
    /**
     * 最后修改时间，UTC。
     */
    private OffsetDateTime updatedAt;

    /**
     * 读取平台稳定标识。
     */
    public String getId() {
        return id;
    }

    /**
     * 更新平台稳定标识。
     */
    public void setId(String value) {
        id = value;
    }

    /**
     * 读取平台显示名称，全局唯一。
     */
    public String getName() {
        return name;
    }

    /**
     * 更新平台显示名称，全局唯一。
     */
    public void setName(String value) {
        name = value;
    }

    /**
     * 读取已实现的客户端协议。
     */
    public String getProtocol() {
        return protocol;
    }

    /**
     * 更新已实现的客户端协议。
     */
    public void setProtocol(String value) {
        protocol = value;
    }

    /**
     * 读取不含凭证的 API 基础地址。
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * 更新不含凭证的 API 基础地址。
     */
    public void setBaseUrl(String value) {
        baseUrl = value;
    }

    /**
     * 读取平台模型目录 GET 地址。
     */
    public String getModelCatalogUrl() {
        return modelCatalogUrl;
    }

    /**
     * 设置或清空模型目录地址。
     */
    public void setModelCatalogUrl(String value) {
        modelCatalogUrl = value;
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
