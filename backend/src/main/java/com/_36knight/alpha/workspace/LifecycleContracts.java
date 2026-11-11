package com._36knight.alpha.workspace;

import jakarta.validation.Valid;
import com._36knight.alpha.workspace.PrdStructure.Module;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 需求评审与 PRD 公开契约；确认、阶段和基线由后端维护。@author Andersen */
public final class LifecycleContracts {
    /** 不允许实例化契约容器。 */ private LifecycleContracts() { }
    /** 稳定需求条目，sourceSuggestionId 仅由后端接受建议时设置。 */
    public record Requirement(@NotBlank @Size(max=80) String id, @NotNull @Size(max=120) String title,
            @NotNull @Size(max=12000) String description, @NotNull @Size(max=6000) String acceptance,
            boolean confirmed, @Size(max=80) String sourceSuggestionId) { }
    /** 问题结论与阻塞性分开；延期必须有理由，阻塞延期不能通过基线。 */
    public record Question(@NotBlank @Size(max=80) String id, @NotBlank @Size(max=4000) String text,
            @NotNull Boolean blocking, @Pattern(regexp="OPEN|RESOLVED|DEFERRED") @NotNull String status,
            @NotNull @Size(max=6000) String answer, @NotNull @Size(max=2000) String reason) { }
    /** 建议接受仅生成待确认需求；拒绝和延期需理由。 */
    public record Suggestion(@NotBlank @Size(max=80) String id, @NotBlank @Size(max=4000) String text,
            @Pattern(regexp="PENDING|ACCEPTED|REJECTED|DEFERRED") @NotNull String decision,
            @NotNull @Size(max=2000) String reason) { }
    /** 三类信息独立维护，总长度由服务进一步限制。 */
    public record Review(@NotNull @Size(max=100) List<@Valid Requirement> requirements,
            @NotNull @Size(max=100) List<@Valid Question> questions, @NotNull @Size(max=100) List<@Valid Suggestion> suggestions) { }
    /** PRD 章节纯文本和需求覆盖，保存不代表确认；templateVersion 是结构版本，templateId/templateRevision 固定写作模板。 */
    public record Prd(@NotNull @Size(max=8000) String background, @NotNull @Size(max=8000) String objectives,
            @NotNull @Size(max=8000) String scope, @NotNull @Size(max=10000) String workflows,
            @NotNull @Size(max=10000) String rules, @NotNull @Size(max=8000) String exceptions,
            @NotNull @Size(max=10000) String acceptance, @NotNull @Size(max=8000) String nonfunctional,
            @NotNull @Size(max=8000) String exclusions, @NotNull @Size(max=100) List<@NotBlank @Size(max=80) String> requirementIds,
            @NotNull @Size(max=100) List<@NotBlank @Size(max=4000) String> unresolvedIssues,
            @Min(1) @Max(2) int templateVersion,
            @NotNull @Size(max=30) List<@NotNull @Valid Module> modules,
            @NotNull @Size(max=100) Map<@NotBlank @Size(max=4000) String, @NotNull @Size(max=20) List<@NotBlank @Size(max=220) String>> issueLinks,
            @NotBlank @Size(max=80) String templateId, @Min(1) int templateRevision) {
        /** 旧 JSON 未携带模板版本和模块时按旧版读取，历史原文不作推断迁移。 */
        public Prd {
            if(templateVersion==0)templateVersion=1; if(modules==null)modules=List.of(); if(issueLinks==null)issueLinks=Map.of();
            if(templateId==null&&templateRevision==0){templateId=PrdTemplates.LEGACY_ID;templateRevision=PrdTemplates.LEGACY_REVISION;}
        }
        /** 兼容无模板引用的历史正文，固定首版管理系统模板，不采用目录最新版本。 */
        public Prd(String background,String objectives,String scope,String workflows,String rules,String exceptions,
                String acceptance,String nonfunctional,String exclusions,List<String> requirementIds,List<String> unresolvedIssues,
                int templateVersion,List<Module> modules,Map<String,List<String>> issueLinks) {
            this(background,objectives,scope,workflows,rules,exceptions,acceptance,nonfunctional,exclusions,requirementIds,unresolvedIssues,
                    templateVersion,modules,issueLinks,PrdTemplates.LEGACY_ID,PrdTemplates.LEGACY_REVISION);
        }
        /** 旧模块草稿没有问题关联，读取时不根据文字猜测目标。 */
        public Prd(String background,String objectives,String scope,String workflows,String rules,String exceptions,
                String acceptance,String nonfunctional,String exclusions,List<String> requirementIds,List<String> unresolvedIssues,
                int templateVersion,List<Module> modules) {
            this(background,objectives,scope,workflows,rules,exceptions,acceptance,nonfunctional,exclusions,requirementIds,unresolvedIssues,templateVersion,modules,Map.of());
        }
        /** 旧生成流程和历史调用继续构建九章节文档。 */
        public Prd(String background,String objectives,String scope,String workflows,String rules,String exceptions,
                String acceptance,String nonfunctional,String exclusions,List<String> requirementIds,List<String> unresolvedIssues) {
            this(background,objectives,scope,workflows,rules,exceptions,acceptance,nonfunctional,exclusions,requirementIds,unresolvedIssues,1,List.of());
        }
    }
    /** 修改评审的聚合锁；服务器撤销新增或改动条目的确认。 */
    public record SaveReview(@Min(1) long expectedRevision, @Valid @NotNull Review review) { }
    /** 人工确认引用具体已保存编号，幂等键不能用于另一种确认。 */
    public record Confirm(@Min(1) long expectedRevision, @Min(1) long sourceRevision,
            @NotBlank @Pattern(regexp="[A-Za-z0-9-]{16,64}") String requestId) { }
    /** 重新评审必须说明原因，并校验版本和产物两个锁值。 */
    public record Reopen(@Min(1) long expectedRevision, @Min(1) long expectedVersionRevision,
            @NotBlank @Size(max=2000) String reason) { }
    /** PRD 保存引用明确评审基线；带候选来源时还校验生成基准。 */
    public record SavePrd(@Min(1) long expectedRevision, @NotBlank @Size(max=36) String sourceBaselineId,
            @Size(max=36) String sourceRunId, @Valid @NotNull Prd prd) { }
    /** PRD 生成是显式持久化命令，不自动保存或确认候选。 */
    public record GeneratePrd(@Min(1) long expectedRevision, @NotBlank @Size(max=36) String baselineId,
            @NotBlank @Pattern(regexp="[A-Za-z0-9-]{16,64}") String requestId) { }
    /** 不可变人工基线，含当时目标、范围、来源与内容。 */
    public record Baseline(String id, String kind, long sourceRevision, String sourceBaselineId, String label,
            String goal, String scope, Review review, Prd prd, String mode, String model, OffsetDateTime createdAt) { }
    /** 保存记录与重新评审原因只读，JSON 文本不作为 HTML 执行。 */
    public record History(String kind,long revision,String contentJson,String sourceBaselineId,String mode,String model,OffsetDateTime createdAt) { }
    /** 安全任务视图，结果是候选不是当前 PRD。 */
    public record Run(String id,String versionId,String baselineId,long prdRevision,String status,String mode,String model,
            Prd result,String errorMessage,OffsetDateTime createdAt) { }
    /** 页面一次获取产物真值、有效基线和服务器门槛；不混入版本主体锁值。 */
    public record Detail(long revision,long reviewRevision,long prdRevision,Review review,Prd prd,
            Baseline reviewBaseline,Baseline prdBaseline,String prdSourceBaselineId,boolean reviewCurrent,boolean prdCurrent,
            String prdMode,String prdModel,List<String> reviewBlockers,List<String> prdBlockers,List<Run> runs,
            boolean directPrd,List<WorkbenchContracts.Proposal> suggestions) { }
}
