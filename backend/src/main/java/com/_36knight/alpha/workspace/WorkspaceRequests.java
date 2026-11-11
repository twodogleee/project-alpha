package com._36knight.alpha.workspace;

import jakarta.validation.constraints.*;

/** 工作空间命令输入；服务端维护归属、来源、阶段和生命周期。@author Andersen */
public final class WorkspaceRequests {
    /** 工具容器不可实例化。 */
    private WorkspaceRequests() { }
    /** 创建产品容器，不隐式调用模型；背景最多 12000 字。 */
    public record NewProject(@NotBlank @Size(max=120) String name, @NotNull @Size(max=12000) String background) { }
    /** 修改项目元数据，expectedRevision 是读取时的乐观锁值。 */
    public record EditProject(@Min(1) long expectedRevision, @NotBlank @Size(max=120) String name, @NotNull @Size(max=12000) String background) { }
    /** 创建项目内会话，可选绑定同项目活动版本。 */
    public record NewSession(@NotBlank @Size(max=120) String title, @Size(max=36) String versionId) { }
    /** 会话改名不会改写历史消息。 */
    public record RenameSession(@Min(1) long expectedRevision, @NotBlank @Size(max=120) String title) { }
    /** 会话消息或整理草稿命令；requestId 在会话内唯一，相同命令重试返回原任务。 */
    public record SubmitRun(@NotBlank @Pattern(regexp="[A-Za-z0-9-]{16,64}") String requestId,
            @NotBlank @Pattern(regexp="CHAT|DRAFT") String kind, @NotNull @Size(max=12000) String content) { }
    /** 保存候选草稿；sourceRunId 可空，带来源时校验生成基准尚未过期。 */
    public record SaveDraft(@Min(1) long expectedRevision, @NotBlank @Size(max=60000) String content, @Size(max=36) String sourceRunId) { }
    /** 手工定义交付版本；scope 可空以支持先定目标再讨论。 */
    public record NewVersion(@NotBlank @Size(max=120) String label, @NotBlank @Size(max=4000) String goal,
            @NotNull @Size(max=60000) String content) { }
    /** 使用明确的已保存草稿形成版本，不隐式采用当前未保存编辑。 */
    public record PromoteDraft(@Min(1) long expectedRevision, @Min(1) long draftRevision,
            @NotBlank @Size(max=120) String label, @NotBlank @Size(max=4000) String goal) { }
    /** 保存业务范围产生内部修订，不推进阶段。 */
    public record EditVersion(@Min(1) long expectedRevision, @NotBlank @Size(max=120) String label,
            @NotBlank @Size(max=4000) String goal, @NotNull @Size(max=60000) String content) { }
    /** 关联探索会话；只设置归属，不自动覆盖目标版本范围。 */
    public record LinkVersion(@Min(1) long expectedRevision, @NotBlank @Size(max=36) String versionId) { }
    /** 以指定草稿同步关联版本，双修订校验阻止跨窗口覆盖。 */
    public record SyncDraft(@Min(1) long expectedRevision, @Min(1) long draftRevision) { }
    /** 归档与恢复仅改变可见性，仍校验读取时的修订。 */
    public record Archive(@Min(1) long expectedRevision, boolean archived) { }
    /** 可恢复删除命令；删除时携带已确认的文档影响范围和修订，恢复仅需会话修订。 */
    public record SessionDeletion(@Min(1) long expectedRevision, @NotNull Boolean deleted,
            Long expectedVersionRevision, Long expectedDocumentRevision, @NotNull Boolean includesPrd) { }
    /** 文档单独回收或恢复；删除需确认正文修订和受影响的未删除会话数量。 */
    public record VersionDeletion(@Min(1) long expectedRevision, @NotNull Boolean deleted,
            Long expectedDocumentRevision, @Min(0) Long expectedLinkedSessions) { }
    /** 中止交付必须明确原因，不计为已完成。 */
    public record CancelVersion(@Min(1) long expectedRevision, @NotBlank @Size(max=2000) String reason) { }
}
