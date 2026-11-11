package com._36knight.alpha.model.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * model_runtime_selection 持久化实体，不作为接口返回对象。@author Andersen
 */
@TableName("model_runtime_selection")
public class ModelSelectionEntity {
    /**
     * 应用唯一运行选择，固定为 1。
     */
    @TableId(type = IdType.INPUT)
    private Long id;
    /**
     * 整个目录的并发控制版本。
     */
    private Long revision;
    /**
     * demo 或 ai。
     */
    private String mode;
    /**
     * 选择的模型外键，demo 可为空。
     */
    private String activeModelId;
    /**
     * 最后修改时间，UTC。
     */
    private OffsetDateTime updatedAt;

    /**
     * 读取应用唯一运行选择，固定为 1。
     */
    public Long getId() {
        return id;
    }

    /**
     * 更新应用唯一运行选择，固定为 1。
     */
    public void setId(Long value) {
        id = value;
    }

    /**
     * 读取整个目录的并发控制版本。
     */
    public Long getRevision() {
        return revision;
    }

    /**
     * 更新整个目录的并发控制版本。
     */
    public void setRevision(Long value) {
        revision = value;
    }

    /**
     * 读取demo 或 ai。
     */
    public String getMode() {
        return mode;
    }

    /**
     * 更新demo 或 ai。
     */
    public void setMode(String value) {
        mode = value;
    }

    /**
     * 读取选择的模型外键，demo 可为空。
     */
    public String getActiveModelId() {
        return activeModelId;
    }

    /**
     * 更新选择的模型外键，demo 可为空。
     */
    public void setActiveModelId(String value) {
        activeModelId = value;
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
