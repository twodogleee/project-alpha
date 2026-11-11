package com._36knight.alpha.workspace.persistence;

import com.baomidou.mybatisplus.annotation.*;
import java.time.OffsetDateTime;

/** WorkspaceRun 的持久化映射；只由仓储访问，不直接作为接口响应。@author Andersen */
@TableName("workspace_runs")
public class WorkspaceRunEntity {
    /** 运行标识。 */
    @TableId(type = IdType.INPUT)
    public String id;
    /** 所属项目。 */
    public String projectId;
    /** 所属会话。 */
    public String sessionId;
    /** 运行开始时的版本归属，可空表示探索。 */
    public String versionId;
    /** 生成引用的范围保存编号，可空。 */
    public Long versionRevision;
    /** 会话内请求幂等键。 */
    public String requestId;
    /** CHAT、DRAFT、WORKBENCH 或 PROTOTYPE；执行器必须显式分流。 */
    public String kind;
    /** 本次显式用户输入。 */
    public String inputText;
    /** 固定的有界上下文，不含凭证。 */
    public String contextText;
    /** CHAT/DRAFT 为会话草稿锁；WORKBENCH/PROTOTYPE 为提交时 PRD 产物聚合锁。 */
    public Long draftRevision;
    /** QUEUED/RUNNING/SUCCEEDED/FAILED/INTERRUPTED。 */
    public String status;
    /** 当次来源模式。 */
    public String mode;
    /** 当次公开模型名。 */
    public String model;
    /** 成功后的纯文本结果。 */
    public String resultText;
    /** 安全错误，不含上游异常。 */
    public String errorMessage;
    /** 创建时间。 */
    public OffsetDateTime createdAt;
    /** 结束或变更时间。 */
    public OffsetDateTime updatedAt;
}
