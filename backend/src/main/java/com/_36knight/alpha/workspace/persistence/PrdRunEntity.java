package com._36knight.alpha.workspace.persistence;

import com.baomidou.mybatisplus.annotation.*;
import java.time.OffsetDateTime;

/** version_prd_runs 持久化映射；不直接返回接口。@author Andersen */
@TableName("version_prd_runs")
public class PrdRunEntity {
    /** 持久化任务标识。 */
    @TableId(type = IdType.INPUT)
    public String id;
    /** 所属需求版本。 */
    public String versionId;
    /** 固定评审基线。 */
    public String baselineId;
    /** 生成时 PRD 修订。 */
    public Long prdRevision;
    /** 生成幂等标识。 */
    public String requestId;
    /** QUEUED/RUNNING/SUCCEEDED/FAILED/INTERRUPTED。 */
    public String status;
    /** 当次模式。 */
    public String mode;
    /** 当次模型。 */
    public String model;
    /** 候选 PRD，可空。 */
    public String resultJson;
    /** 安全失败说明，可空。 */
    public String errorMessage;
    /** 提交时间。 */
    public OffsetDateTime createdAt;
    /** 终态时间。 */
    public OffsetDateTime updatedAt;
}
