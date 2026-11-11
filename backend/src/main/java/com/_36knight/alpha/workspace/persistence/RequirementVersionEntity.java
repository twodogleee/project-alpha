package com._36knight.alpha.workspace.persistence;

import com.baomidou.mybatisplus.annotation.*;
import java.time.OffsetDateTime;

/** RequirementVersion 的持久化映射；只由仓储访问，不直接作为接口响应。@author Andersen */
@TableName("requirement_versions")
public class RequirementVersionEntity {
    /** 业务版本稳定标识。 */
    @TableId(type = IdType.INPUT)
    public String id;
    /** 所属项目。 */
    public String projectId;
    /** 项目内唯一自定义名称。 */
    public String label;
    /** 本轮交付目标。 */
    public String goal;
    /** 需求范围纯文本，首期不承担正式审批。 */
    public String content;
    /** 当前保存编号与乐观锁。 */
    public Long revision;
    /** REVIEW / PRD / PROTOTYPE；原型进入等待接入，不代表完成交付。 */
    public String stage;
    /** ACTIVE 或 CANCELED，首期不提供伪完成。 */
    public String lifecycle;
    /** 形成版本的来源会话，可空。 */
    public String sourceSessionId;
    /** 形成时使用的不可变草稿编号，可空。 */
    public Long sourceDraftRevision;
    /** 取消原因。 */
    public String cancelReason;
    /** 创建时间。 */
    public OffsetDateTime createdAt;
    /** 更新时间。 */
    public OffsetDateTime updatedAt;
    /** 回收站时间；恢复不改变归档和定稿状态。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public OffsetDateTime deletedAt;
    /** 单独删除文档时置真，恢复会话不能隐式撤销文档删除。 */
    public Boolean independentlyDeleted;
    /** 归档时间。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public OffsetDateTime archivedAt;
}
