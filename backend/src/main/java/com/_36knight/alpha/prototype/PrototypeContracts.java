package com._36knight.alpha.prototype;

import com._36knight.alpha.workspace.LifecycleContracts.Prd;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;

/** 原型业务命令与安全视图；执行令牌、磁盘路径及模型原文不进入公开响应。@author Andersen */
public final class PrototypeContracts {
    /** 契约容器不允许实例化。 */
    private PrototypeContracts() { }

    /** 页面 ID 在模块内唯一，所有引用必须保留模块身份。 */
    public record PageRef(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String moduleId,
                          @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String pageId) { }
    /** ALL 在冻结时展开为有序页面引用；SELECTED 不得为空，最多十页。 */
    public record Scope(@NotNull @Pattern(regexp="ALL|SELECTED") String mode,
                        @NotNull @Size(max=10) List<@Valid @NotNull PageRef> pageRefs) { }
    /** DRAFT 无引用，BASELINE 引用当前有效定稿，EXISTING 仅用于沿用原型来源。 */
    public record Source(@NotNull @Pattern(regexp="DRAFT|BASELINE|EXISTING") String kind,
                         @Size(max=36) String id) { }
    /** 一期讨论目标为整个原型或某个稳定页面；弹层身份由后续生成计划解释。 */
    public record Target(@NotNull @Pattern(regexp="PROTOTYPE|PAGE") String kind,
                         @Valid PageRef page, @Size(max=80) String overlayId) { }
    /** 全命令参与幂等摘要；四种锁各自保留其业务含义。 */
    public record Submit(@NotBlank @Size(max=36) String sessionId,
                         @NotBlank @Pattern(regexp="[A-Za-z0-9-]{16,64}") String requestId,
                         @NotNull @Pattern(regexp="GENERATE|EDIT|SYNC_PRD") String operation,
                         @Min(1) long expectedVersionRevision, @Min(1) long expectedLifecycleRevision,
                         @Min(0) long expectedPrdRevision, @Min(0) long expectedPrototypeRevision,
                         @Size(max=36) String baseRevisionId,
                         @NotBlank @Size(max=80) String templateId, @Min(1) long templateRevision,
                         @NotNull @Valid Source source, @NotNull @Valid Scope scope,
                         @NotNull @Valid Target target, @NotBlank @Size(max=12000) String content,
                         @Size(max=36) String retryOfRunId) { }
    /** 应用候选是显式控制命令，不调用模型、不改写不可变修订。 */
    public record Apply(@NotBlank @Pattern(regexp="[A-Za-z0-9-]{16,64}") String requestId,
                        @NotBlank @Size(max=36) String sourceRunId,
                        @NotBlank @Size(max=36) String targetRevisionId,
                        @Min(1) long expectedPrototypeRevision, @Min(1) long expectedVersionRevision,
                        @Min(1) long expectedLifecycleRevision, @Min(0) long expectedPrdRevision) { }
    /** 恢复指定历史为新修订；完整四锁参与命令摘要，不调用模型或沿用旧确认。 */
    public record Restore(@NotBlank @Pattern(regexp="[A-Za-z0-9-]{16,64}") String requestId,
                          @NotBlank @Size(max=36) String targetRevisionId,
                          @Min(1) long expectedPrototypeRevision, @Min(1) long expectedVersionRevision,
                          @Min(1) long expectedLifecycleRevision, @Min(0) long expectedPrdRevision) { }
    /** 人工确认同时固定当前修订、有效 PRD 定稿及该修订完整范围，不支持再次缩小范围。 */
    public record Confirm(@NotBlank @Pattern(regexp="[A-Za-z0-9-]{16,64}") String requestId,
                          @NotBlank @Size(max=36) String revisionId,
                          @NotBlank @Size(max=36) String prdBaselineId, @NotNull @Valid Scope scope,
                          @Min(1) long expectedPrototypeRevision, @Min(1) long expectedVersionRevision,
                          @Min(1) long expectedLifecycleRevision, @Min(0) long expectedPrdRevision) { }
    /** 不可变人工确认记录；历史存在不表示当前来源仍有效，实际状态以聚合 status 判断。 */
    public record ConfirmationView(String id, String prototypeId, String revisionId, String prdBaselineId,
                                   Scope scope, OffsetDateTime createdAt) { }
    /** 公开任务补充信息不包含 executionToken、context 或内部诊断原文。 */
    public record RunProgress(String prototypeId, String sourceId, String operation, String phase,
                              int repairAttempts, String resultRevisionId, String applicationStatus,
                              String retryOfRunId, String runId, String status, String mode, String model,
                              String errorMessage, String errorCode, OffsetDateTime createdAt) { }
    /** 固定模板的公开能力；可用性仅表示当前执行器可以接收任务，不调用模型探测。 */
    public record Template(String id, long revision, String name, List<String> supportedPageTypes,
                           int maxPages, List<String> supportedActions, boolean available) { }
    /** 可追溯冻结正文；项目背景和模板定义保存在内部上下文，不自动返回给轮询接口。 */
    public record SourceView(String id, String kind, long prdRevision, String prdBaselineId,
                             String contentSha256, Scope scope, Prd prd, OffsetDateTime createdAt) { }
    /** 修订视图只包含已校验元数据和来源；文件读取仍以服务端对象身份为边界。 */
    public record RevisionView(String id, long revisionNumber, String parentRevisionId, String sourceRunId,
                               String mode, String model, String changeSummary, JsonNode manifest,
                               JsonNode validation, SourceView source, String freshness,
                               boolean current, OffsetDateTime createdAt, String restoredFromRevisionId,
                               ConfirmationView latestConfirmation) {
        /** 兼容既有受信测试和内部读取方，生产视图必须携带真实恢复及确认记录。 */
        public RevisionView(String id,long revisionNumber,String parentRevisionId,String sourceRunId,String mode,String model,
                String changeSummary,JsonNode manifest,JsonNode validation,SourceView source,String freshness,boolean current,OffsetDateTime createdAt) {
            this(id,revisionNumber,parentRevisionId,sourceRunId,mode,model,changeSummary,manifest,validation,source,freshness,current,createdAt,null,null);
        }
    }
    /** GET 返回不存在状态而不初始化聚合、生命周期或会话。 */
    public record Detail(boolean exists, String id, long revision, String status,
                         String templateId, Long templateRevision, RevisionView current,
                         List<RunProgress> runs, ConfirmationView latestConfirmation, List<String> confirmationBlockers) {
        /** 旧构造方不伪造确认资格；仅兼容无 P6 状态的内部样例。 */
        public Detail(boolean exists,String id,long revision,String status,String templateId,Long templateRevision,
                RevisionView current,List<RunProgress> runs) {
            this(exists,id,revision,status,templateId,templateRevision,current,runs,null,List.of("请刷新原型状态后核对确认条件"));
        }
    }
    /** 应用结果持久化以便重放原命令，不随后来当前指针变化。 */
    public record Applied(String prototypeId, long prototypeRevision, String revisionId) { }
    /** 仅给受信执行适配器的固定输入；没有 HTTP 执行回调或可执行脚本参数。 */
    public record Execution(String runId, String token, SourceView source, String contextJson,
                            String baseRevisionId, Target target, String content, String operation,
                            String baseObjectKey, JsonNode baseManifest) {
        /** 兼容 P3 的手动回调测试；生产领取必须附带固定操作及已归属校验的基准引用。 */
        public Execution(String runId, String token, SourceView source, String contextJson,
                String baseRevisionId, Target target, String content) {
            this(runId, token, source, contextJson, baseRevisionId, target, content, "GENERATE", null, null);
        }
    }
    /** 受信适配器提交对象身份和业务清单；文件先封存，业务事务再复核引用。 */
    public record Completion(String objectKey, JsonNode manifest, String changeSummary) { }
}
