package com._36knight.alpha.prototype.persistence;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/** 原型任务扩展与执行租约；workspace_runs 负责通用状态和消息关联。 @author Andersen */
@TableName("prototype_run_details")
public class PrototypeRunDetailEntity {
    /** 与 workspace_runs 一对一的任务身份。 */
    @TableId(type = IdType.INPUT)
    public String runId;
    /** 用于复合外键验证任务与原型属于同一项目。 */
    public String projectId;
    /** 用于复合外键验证任务与原型属于同一 PRD 文档。 */
    public String versionId;
    /** 所属原型。 */
    public String prototypeId;
    /** 本任务固定的不可变输入。 */
    public String sourceId;
    /** GENERATE、EDIT 或 SYNC_PRD。 */
    public String operation;
    /** 提交时当前原型基准；首次生成为空。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String baseRevisionId;
    /** 提交事务递增后的聚合版本，完成时据此判断是否自动应用。 */
    public Long expectedPrototypeRevision;
    /** 提交时 PRD 正文修订，可能与沿用来源不同。 */
    public Long expectedPrdRevision;
    /** 提交时产物聚合版本。 */
    public Long expectedLifecycleRevision;
    /** 提交时文档版本。 */
    public Long expectedVersionRevision;
    /** 完整原始命令规范化摘要，用于幂等冲突识别。 */
    public String commandHash;
    /** 稳定讨论目标，不包含任意执行脚本。 */
    public String targetJson;
    /** 最终生成计划及影响范围，可空。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String planJson;
    /** PREPARING 至 FINISHED 的平台执行阶段。 */
    public String phase;
    /** 已使用自动修复次数，范围为 0 到 2。 */
    public Integer repairAttempts;
    /** 当前执行租约；中断后清空，旧 Worker 不得写回。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String executionToken;
    /** 当前租约最后心跳；未领取或中断时可空。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public OffsetDateTime heartbeatAt;
    /** 可运行结果修订；不直接代表当前稿。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String resultRevisionId;
    /** PENDING、APPLIED、CONFLICT 或 NOT_APPLICABLE。 */
    public String applicationStatus;
    /** 脱敏且限长的失败、检查或临时产物说明。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String diagnosticJson;
    /** 显式重试所引用的同原型旧任务，可空。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String retryOfRunId;
}
