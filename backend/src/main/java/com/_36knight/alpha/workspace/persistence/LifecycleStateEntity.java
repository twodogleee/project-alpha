package com._36knight.alpha.workspace.persistence;

import com.baomidou.mybatisplus.annotation.*;
import java.time.OffsetDateTime;

/** version_lifecycle_state 持久化映射；不直接返回接口。@author Andersen */
@TableName("version_lifecycle_state")
public class LifecycleStateEntity {
    /** 需求版本主键。 */
    @TableId(type = IdType.INPUT)
    public String versionId;
    /** 产物聚合乐观锁。 */
    public Long revision;
    /** 评审保存编号。 */
    public Long reviewRevision;
    /** PRD 保存编号。 */
    public Long prdRevision;
    /** 直接会话编辑 PRD，旧评审只作为历史资料。 */
    public Boolean directPrd;
    /** 待用户采纳的建议，不属于 PRD 正文或正式基线。 */
    public String proposalsJson;
    /** 目标和范围生成基准。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String scopeFingerprint;
    /** 当前结构化评审。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String reviewJson;
    /** 当前 PRD，可空。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String prdJson;
    /** 当前评审基线，可空。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String reviewBaselineId;
    /** 当前 PRD 基线，可空。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String prdBaselineId;
    /** 当前 PRD 引用的评审基线，可空。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String prdSourceBaselineId;
    /** PRD 来源模式。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String prdMode;
    /** PRD 来源模型。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String prdModel;
    /** 当前产物更新时间。 */
    public OffsetDateTime updatedAt;
}
