package com._36knight.alpha.workspace.persistence;

import com.baomidou.mybatisplus.annotation.*;
import java.time.OffsetDateTime;

/** version_artifact_baselines 持久化映射；不直接返回接口。@author Andersen */
@TableName("version_artifact_baselines")
public class LifecycleBaselineEntity {
    /** 不可变确认基线标识。 */
    @TableId(type = IdType.INPUT)
    public String id;
    /** 所属需求版本。 */
    public String versionId;
    /** REVIEW/PRD。 */
    public String kind;
    /** 确认的已保存产物编号。 */
    public Long sourceRevision;
    /** 同版本确认幂等标识。 */
    public String requestId;
    /** 确认时不可变产物。 */
    public String contentJson;
    /** PRD 引用的评审基线，可空。 */
    public String sourceBaselineId;
    /** 确认时目标和范围指纹。 */
    public String scopeFingerprint;
    /** 确认时业务版本名称。 */
    public String label;
    /** 确认时目标。 */
    public String goal;
    /** 确认时范围。 */
    public String scope;
    /** 确认时来源模式。 */
    public String mode;
    /** 确认时来源模型。 */
    public String model;
    /** 人工确认时间。 */
    public OffsetDateTime createdAt;
}
