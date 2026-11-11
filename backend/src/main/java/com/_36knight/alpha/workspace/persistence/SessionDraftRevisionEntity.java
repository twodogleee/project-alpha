package com._36knight.alpha.workspace.persistence;

import com.baomidou.mybatisplus.annotation.*;
import java.time.OffsetDateTime;

/** SessionDraftRevision 的持久化映射；只由仓储访问，不直接作为接口响应。@author Andersen */
@TableName("session_draft_revisions")
public class SessionDraftRevisionEntity {
    /** 不可变草稿快照标识。 */
    @TableId(type = IdType.INPUT)
    public String id;
    /** 所属会话。 */
    public String sessionId;
    /** 保存编号。 */
    public Long revision;
    /** 草稿纯文本快照。 */
    public String content;
    /** 生成来源运行，可空表示人工。 */
    public String sourceRunId;
    /** 保存时间。 */
    public OffsetDateTime createdAt;
}
