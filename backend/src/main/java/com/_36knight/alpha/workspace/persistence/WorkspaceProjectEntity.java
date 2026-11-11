package com._36knight.alpha.workspace.persistence;

import com.baomidou.mybatisplus.annotation.*;
import java.time.OffsetDateTime;

/** WorkspaceProject 的持久化映射；只由仓储访问，不直接作为接口响应。@author Andersen */
@TableName("workspace_projects")
public class WorkspaceProjectEntity {
    /** 稳定项目标识。 */
    @TableId(type = IdType.INPUT)
    public String id;
    /** 产品或业务系统名称。 */
    public String name;
    /** 模型上下文使用的项目背景。 */
    public String background;
    /** 元数据乐观锁版本。 */
    public Long revision;
    /** 创建时间。 */
    public OffsetDateTime createdAt;
    /** 更新时间。 */
    public OffsetDateTime updatedAt;
    /** 归档时间，空表示活动。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public OffsetDateTime archivedAt;
}
