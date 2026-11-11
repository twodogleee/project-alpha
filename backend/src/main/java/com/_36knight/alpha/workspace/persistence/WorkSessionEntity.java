package com._36knight.alpha.workspace.persistence;

import com.baomidou.mybatisplus.annotation.*;
import java.time.OffsetDateTime;

/** WorkSession 的持久化映射；只由仓储访问，不直接作为接口响应。@author Andersen */
@TableName("work_sessions")
public class WorkSessionEntity {
    /** 稳定会话标识。 */
    @TableId(type = IdType.INPUT)
    public String id;
    /** 所属项目。 */
    public String projectId;
    /** 关联的单个需求版本，可空。 */
    public String versionId;
    /** 会话标题。 */
    public String title;
    /** 当前已保存的候选需求草稿。 */
    public String draftText;
    /** 草稿保存编号，零表示尚未保存。 */
    public Long draftRevision;
    /** 会话元数据与草稿乐观锁。 */
    public Long revision;
    /** 单调递增的消息序号。 */
    public Long messageSequence;
    /** 创建时间。 */
    public OffsetDateTime createdAt;
    /** 更新时间。 */
    public OffsetDateTime updatedAt;
    /** 回收站时间；恢复不改变归档和定稿状态。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public OffsetDateTime deletedAt;
    /** 归档时间。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public OffsetDateTime archivedAt;
}
