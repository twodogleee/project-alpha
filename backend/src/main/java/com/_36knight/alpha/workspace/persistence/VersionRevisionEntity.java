package com._36knight.alpha.workspace.persistence;

import com.baomidou.mybatisplus.annotation.*;
import java.time.OffsetDateTime;

/** VersionRevision 的持久化映射；只由仓储访问，不直接作为接口响应。@author Andersen */
@TableName("requirement_version_revisions")
public class VersionRevisionEntity {
    /** 不可变范围快照标识。 */
    @TableId(type = IdType.INPUT)
    public String id;
    /** 所属业务版本。 */
    public String versionId;
    /** 保存编号。 */
    public Long revision;
    /** 当次版本名称。 */
    public String label;
    /** 当次目标。 */
    public String goal;
    /** 当次范围正文。 */
    public String content;
    /** 本次同步来源会话，可空。 */
    public String sourceSessionId;
    /** 本次同步来源草稿编号，可空。 */
    public Long sourceDraftRevision;
    /** 保存时间。 */
    public OffsetDateTime createdAt;
}
