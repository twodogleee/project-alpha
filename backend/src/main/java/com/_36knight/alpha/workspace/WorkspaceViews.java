package com._36knight.alpha.workspace;

import java.time.OffsetDateTime;
import java.util.List;
import com._36knight.alpha.workspace.persistence.*;

/** 公开工作空间响应；不返回运行上下文、内部实体或模型凭证。@author Andersen */
public final class WorkspaceViews {
    /** 禁止构造响应工具容器。 */
    private WorkspaceViews() { }
    /** 有界分页，hasMore 明确表示尚有下一页。 */
    public record Slice<T>(List<T> items, boolean hasMore, long page) { }
    /** 产品容器及元数据锁值。 */
    public record Project(String id, String name, String background, long revision,
            OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime archivedAt) { }
    /** 会话及当前已保存草稿；revision 与草稿保存编号分别表达。 */
    public record Session(String id, String projectId, String versionId, String title, String draftText,
            long draftRevision, long revision, OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime archivedAt, OffsetDateTime deletedAt) { }
    /** 需求版本独立于编辑修订；阶段通过人工确认基线推进。 */
    public record Version(String id, String projectId, String label, String goal, String content, long revision,
            String stage, String lifecycle, String sourceSessionId, Long sourceDraftRevision, String cancelReason,
            OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime archivedAt, OffsetDateTime deletedAt) { }
    /** 只追加的用户/助手消息，公开当次生成来源。 */
    public record Message(String id, long sequence, String role, String content, String mode, String model, String runId, OffsetDateTime createdAt, List<String> attachmentNames) { }
    /** 可轮询的安全执行结果，不包含输入上下文或凭证。 */
    public record Run(String id, String sessionId, String versionId, Long versionRevision, String kind, String status, String mode, String model,
            String resultText, String errorMessage, long draftRevision, OffsetDateTime createdAt,
            com._36knight.alpha.prototype.PrototypeContracts.RunProgress prototype) {
        /** 保留既有任务映射；原型进度由原型服务按父对象可见性补充。 */
        public Run(String id,String sessionId,String versionId,Long versionRevision,String kind,String status,String mode,String model,
                   String resultText,String errorMessage,long draftRevision,OffsetDateTime createdAt){
            this(id,sessionId,versionId,versionRevision,kind,status,mode,model,resultText,errorMessage,draftRevision,createdAt,null);
        }
    }
    /** 会话加载同时返回所属项目、关联版本及近期任务；消息另行分页。 */
    public record SessionDetail(Project project, Session session, Version version, List<Run> runs) { }
    /** 只读范围保存记录，不承担业务版本命名。 */
    public record SavedRevision(long revision, String label, String goal, String content,
            String sourceSessionId, Long sourceDraftRevision, OffsetDateTime createdAt) { }
    /** 删除确认快照；文档修订与是否共同回收均在提交时再次校验。 */
    public record DeletionPreview(Session session, Long versionRevision, Long documentRevision, boolean includesPrd, long otherSessions) { }
    /** 文档删除确认；关联会话保留，文档删除期间只读。 */
    public record VersionDeletionPreview(Version version, Long documentRevision, long linkedSessions) { }
    /** 独立归档目录。 */
    public record Archives(Slice<Project> projects, Slice<Session> sessions, Slice<Version> versions) { }
    /** 映射公开项目字段。 */
    public static Project project(WorkspaceProjectEntity r) { return new Project(r.id,r.name,r.background,r.revision,r.createdAt,r.updatedAt,r.archivedAt); }
    /** 映射公开会话字段。 */
    public static Session session(WorkSessionEntity r) { return new Session(r.id,r.projectId,r.versionId,r.title,r.draftText,r.draftRevision,r.revision,r.createdAt,r.updatedAt,r.archivedAt,r.deletedAt); }
    /** 映射公开版本字段。 */
    public static Version version(RequirementVersionEntity r) { return new Version(r.id,r.projectId,r.label,r.goal,r.content,r.revision,r.stage,r.lifecycle,r.sourceSessionId,r.sourceDraftRevision,r.cancelReason,r.createdAt,r.updatedAt,r.archivedAt,r.deletedAt); }
    /** 映射纯文本消息。 */
    public static Message message(SessionMessageEntity r) { return message(r,List.of()); }
    /** 消息列表只携带文件名，原文按需从有归属校验的附件接口读取。 */
    public static Message message(SessionMessageEntity r,List<String> names) { return new Message(r.id,r.sequence,r.role,r.content,r.mode,r.model,r.runId,r.createdAt,names); }
    /** 映射任务，刻意排除固定运行上下文。 */
    public static Run run(WorkspaceRunEntity r) { return new Run(r.id,r.sessionId,r.versionId,r.versionRevision,r.kind,r.status,r.mode,r.model,r.resultText,r.errorMessage,r.draftRevision,r.createdAt); }
    /** 映射不可变保存快照。 */
    public static SavedRevision saved(VersionRevisionEntity r) { return new SavedRevision(r.revision,r.label,r.goal,r.content,r.sourceSessionId,r.sourceDraftRevision,r.createdAt); }
}
