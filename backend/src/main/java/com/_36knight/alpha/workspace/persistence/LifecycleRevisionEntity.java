package com._36knight.alpha.workspace.persistence;

import com.baomidou.mybatisplus.annotation.*;
import java.time.OffsetDateTime;

/** version_artifact_revisions 持久化映射；不直接返回接口。@author Andersen */
@TableName("version_artifact_revisions")
public class LifecycleRevisionEntity {
    /** 不可变记录标识。 */
    @TableId(type = IdType.INPUT)
    public String id;
    /** 所属需求版本。 */
    public String versionId;
    /** REVIEW/PRD/EVENT。 */
    public String kind;
    /** 对应产物保存编号。 */
    public Long revision;
    /** 不可变结构化内容。 */
    public String contentJson;
    /** 当时引用的上游基线，可空。 */
    public String sourceBaselineId;
    /** 当时目标和范围指纹。 */
    public String scopeFingerprint;
    /** 当次来源模式，可空。 */
    public String mode;
    /** 当次来源模型，可空。 */
    public String model;
    /** 保存时间。 */
    public OffsetDateTime createdAt;
}
