package com._36knight.alpha.workspace.persistence;

import com.baomidou.mybatisplus.annotation.*;
import java.time.OffsetDateTime;

/** SessionMessage 的持久化映射；只由仓储访问，不直接作为接口响应。@author Andersen */
@TableName("session_messages")
public class SessionMessageEntity {
    /** 消息标识。 */
    @TableId(type = IdType.INPUT)
    public String id;
    /** 所属会话。 */
    public String sessionId;
    /** 会话内稳定顺序。 */
    public Long sequence;
    /** USER 或 ASSISTANT。 */
    public String role;
    /** 纯文本消息，不含隐藏推理或凭证。 */
    public String content;
    /** 当次来源模式，可空。 */
    public String mode;
    /** 当次公开模型名，可空。 */
    public String model;
    /** 关联运行标识。 */
    public String runId;
    /** 创建时间。 */
    public OffsetDateTime createdAt;
}
