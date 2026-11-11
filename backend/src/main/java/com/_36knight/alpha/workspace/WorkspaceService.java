package com._36knight.alpha.workspace;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com._36knight.alpha.agent.AnalysisRuntime;
import com._36knight.alpha.agent.AnalysisRuntimeProvider;
import com._36knight.alpha.project.ProjectException;
import com._36knight.alpha.exception.TaskFailureDiagnostics;
import com._36knight.alpha.model.ModelInvocationException;
import com._36knight.alpha.workspace.persistence.*;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Validator;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import static com._36knight.alpha.workspace.WorkspaceRequests.*;
import static com._36knight.alpha.workspace.WorkspaceViews.*;

/** 维护项目归属、会话任务与业务版本；短事务串行写入，外部模型调用独立执行。@author Andersen */
@Service
public class WorkspaceService {
    /** 工作空间关系持久化。 */ private final WorkspaceRepository repository;
    /** 阶段产物任务用于取消边界，不把 PRD 执行伪装为会话任务。 */ private final LifecycleRepository lifecycle;
    /** 每次开始执行固定模型配置，不把凭证写入任务。 */ private final AnalysisRuntimeProvider runtime;
    /** 结构化模型结果校验。 */ private final Validator validator;
    /** 明确划分模型调用与持久化事务。 */ private final TransactionTemplate transactions;
    /** 只提取已持久化附件名称，不向消息列表返回完整任务上下文。 */ private final com.fasterxml.jackson.databind.ObjectMapper json;
    /** 有界并发任务；首期单实例轮询，不宣称 Graph 检查点恢复。 */
    private final ExecutorService workers=Executors.newFixedThreadPool(4);
    /** 原型任务安全视图和原子中断恢复，不交给旧需求执行器。 */
    private final com._36knight.alpha.prototype.PrototypeService prototypes;

    /** 注入存储、运行快照和事务管理器。 */
    public WorkspaceService(WorkspaceRepository repository,LifecycleRepository lifecycle,AnalysisRuntimeProvider runtime,Validator validator,
            org.springframework.transaction.PlatformTransactionManager manager,com.fasterxml.jackson.databind.ObjectMapper json,
            com._36knight.alpha.prototype.PrototypeService prototypes) {
        this.repository=repository; this.lifecycle=lifecycle; this.runtime=runtime; this.validator=validator;
        transactions=new TransactionTemplate(manager);this.json=json;this.prototypes=prototypes;
    }
    /** 有界读取产品目录。 */
    public Slice<Project> projects(long page,boolean archived) { return slice(repository.projects(page(page),archived),WorkspaceViews::project); }
    /** 读取产品背景与修订。 */
    public Project getProject(String id) { return project(requireProject(id)); }
    /** 创建产品容器，不调用模型。 */
    public Project createProject(NewProject request) {
        var row=new WorkspaceProjectEntity(); row.id=id(); row.name=request.name().strip(); row.background=request.background().strip();
        row.revision=1L; row.createdAt=now(); row.updatedAt=row.createdAt;
        transactions.executeWithoutResult(t -> repository.insert(row)); return project(row);
    }
    /** 更新产品背景，历史消息与生成结果不随其改写。 */
    public Project editProject(String id,EditProject request) {
        return locked(id,p -> { active(p); revision(p.revision,request.expectedRevision());
            p.name=request.name().strip(); p.background=request.background().strip(); p.revision++; p.updatedAt=now(); repository.update(p); return project(p); });
    }
    /** 活动版本或执行任务存在时不能归档项目；恢复不恢复版本生命周期。 */
    public Project archiveProject(String id,Archive request) {
        return locked(id,p -> { revision(p.revision,request.expectedRevision());
            if(request.archived()&&(repository.activeVersions(id)||repository.running(id,null))) throw conflict("项目仍有活动需求版本或执行中任务");
            p.archivedAt=request.archived()?now():null; p.revision++; p.updatedAt=now(); repository.update(p); return project(p); });
    }
    /** 创建会话，版本归属只能在同一项目内。 */
    public Session createSession(String projectId,NewSession request) {
        return locked(projectId,p -> { active(p); String versionId=blankNull(request.versionId());
            if(versionId!=null) belongsActive(requireVersion(versionId),projectId);
            var s=new WorkSessionEntity(); s.id=id(); s.projectId=projectId; s.versionId=versionId; s.title=request.title().strip();
            s.draftText=""; s.draftRevision=0L; s.revision=1L; s.messageSequence=0L; s.createdAt=now(); s.updatedAt=s.createdAt;
            repository.insert(s); return session(s); });
    }
    /** 分页读取当前项目的会话；版本过滤也由服务器验证归属。 */
    public Slice<Session> sessions(String projectId,String versionId,long page,boolean archived) {
        requireProject(projectId);
        if(versionId!=null&&!requireVersion(versionId).projectId.equals(projectId)) throw invalid("需求版本不属于该项目");
        return slice(repository.sessions(projectId,versionId,page(page),archived),WorkspaceViews::session);
    }
    /** 刷新会话恢复已保存草稿与任务状态。 */
    public SessionDetail getSession(String id) {
        var s=requireSession(id);
        return new SessionDetail(project(requireProject(s.projectId)),session(s),s.versionId==null?null:version(requireVersion(s.versionId)),
                repository.runs(id).stream().map(this::runView).toList());
    }
    /** 读取较早消息，返回稳定正序；上下文与列表长度分开管理。 */
    public Slice<Message> messages(String id,Long before) {
        requireSession(id); if(before!=null&&before<1) throw invalid("消息序号无效");
        var rows=new ArrayList<>(repository.messages(id,before)); boolean more=rows.size()>50;
        if(more) rows.removeLast(); Collections.reverse(rows);
        var names=new HashMap<String,List<String>>();
        for(var run:repository.messageRuns(id,rows.stream().filter(row->"USER".equals(row.role)&&row.runId!=null).map(row->row.runId).distinct().toList())) {
            try { var files=json.readTree(run.contextText).path("attachments");var values=new ArrayList<String>();
                if(files.isArray())for(var file:files)if(file.path("name").isTextual())values.add(file.path("name").asText());
                names.put(run.id,values);
            } catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("会话附件索引无效",e); }
        }
        return new Slice<>(rows.stream().map(row->WorkspaceViews.message(row,"USER".equals(row.role)?names.getOrDefault(row.runId,List.of()):List.of())).toList(),more,1);
    }
    /** 改名只影响会话元数据。 */
    public Session renameSession(String id,RenameSession request) {
        var current=requireSession(id);
        return locked(current.projectId,p -> { active(p); var s=requireSession(id); editable(s); revision(s.revision,request.expectedRevision());
            s.title=request.title().strip(); s.revision++; s.updatedAt=now(); repository.update(s); return session(s); });
    }
    /** 归档会话不结束版本，执行中任务必须先结束。 */
    public Session archiveSession(String id,Archive request) {
        var current=requireSession(id);
        return locked(current.projectId,p -> { active(p); var s=requireSession(id); if(s.deletedAt!=null)throw conflict("会话已删除，请先从回收站恢复"); revision(s.revision,request.expectedRevision());
            if(repository.running(p.id,id)) throw conflict("会话仍有执行中任务，请等待结束");
            s.archivedAt=request.archived()?now():null; s.revision++; s.updatedAt=now(); repository.update(s); return session(s); });
    }
    /** 删除前读取准确影响范围；不创建 PRD 或会话。 */
    public DeletionPreview deletionPreview(String id) {
        var current=requireSession(id);
        return locked(current.projectId,p -> deletionPreviewOf(requireSession(id)));
    }
    /** 回收站独立分页，恢复入口不依赖被隐藏的普通目录。 */
    public Slice<Session> deletedSessions(long page) { return slice(repository.deletedSessions(page(page)),WorkspaceViews::session); }
    /** 确认快照只包含当前关联，归档会话仍计入共享使用。 */
    private DeletionPreview deletionPreviewOf(WorkSessionEntity s) {
        var v=s.versionId==null?null:requireVersion(s.versionId);
        long others=v==null?0:repository.otherSessions(v.id,s.id);
        var state=v==null?null:lifecycle.state(v.id);
        return new DeletionPreview(session(s),v==null?null:v.revision,state==null?null:state.revision,v!=null&&v.deletedAt==null&&others==0,others);
    }
    /** 会话与最后独立文档在项目锁内共同回收；恢复任一关联会话即可重新使用原文档。 */
    public Session deleteSession(String id,SessionDeletion command) {
        if(command==null||!validator.validate(command).isEmpty())throw invalid("删除命令不完整，请刷新后重试");
        var current=requireSession(id);
        return locked(current.projectId,p -> {
            active(p);var s=requireSession(id);revision(s.revision,command.expectedRevision());
            if((s.deletedAt!=null)==command.deleted())return session(s);
            var preview=deletionPreviewOf(s);var v=s.versionId==null?null:requireVersion(s.versionId);
            if(command.deleted()) {
                if(preview.includesPrd()!=command.includesPrd()||!Objects.equals(preview.versionRevision(),command.expectedVersionRevision())
                        ||!Objects.equals(preview.documentRevision(),command.expectedDocumentRevision()))
                    throw conflict("关联文档或使用关系已变化，请重新确认删除范围");
                if(repository.running(p.id,s.id)||(preview.includesPrd()&&(repository.versionRunning(v.id)||lifecycle.running(v.id))))
                    throw conflict("会话或关联文档仍有执行中任务，请等待结束后删除");
                s.deletedAt=now();
                if(preview.includesPrd()){v.deletedAt=s.deletedAt;v.revision++;v.updatedAt=now();repository.update(v);snapshot(v,null,null);}
            } else {
                s.deletedAt=null;
                if(v!=null&&v.deletedAt!=null&&!Boolean.TRUE.equals(v.independentlyDeleted)){v.deletedAt=null;v.revision++;v.updatedAt=now();repository.update(v);snapshot(v,null,null);}
            }
            s.revision++;s.updatedAt=now();repository.update(s);return session(s);
        });
    }
    /** 文档回收目录不依赖会话是否仍在普通列表。 */
    public Slice<Version> deletedVersions(long page) { return slice(repository.deletedVersions(page(page)),WorkspaceViews::version); }
    /** 在项目锁下读取文档删除范围，避免拼接跨时点修订。 */
    public VersionDeletionPreview versionDeletionPreview(String id) {
        var current=requireVersion(id);
        return locked(current.projectId,p -> versionDeletionPreviewOf(requireVersion(id)));
    }
    /** 正文未初始化时修订为空，不为删除确认创建 PRD。 */
    private VersionDeletionPreview versionDeletionPreviewOf(RequirementVersionEntity v) {
        var state=lifecycle.state(v.id);
        return new VersionDeletionPreview(version(v),state==null?null:state.revision,repository.linkedSessions(v.id));
    }
    /** 单独删除文档仅改变文档可用性，保留会话、内容、模板绑定与定稿；恢复不自动恢复已删会话。 */
    public Version deleteVersion(String id,VersionDeletion command) {
        if(command==null||!validator.validate(command).isEmpty())throw invalid("文档删除命令不完整，请刷新后重试");
        var current=requireVersion(id);
        return locked(current.projectId,p -> {
            active(p);var v=requireVersion(id);revision(v.revision,command.expectedRevision());
            if((v.deletedAt!=null)==command.deleted())return version(v);
            if(command.deleted()) {
                var preview=versionDeletionPreviewOf(v);
                if(!Objects.equals(preview.documentRevision(),command.expectedDocumentRevision())
                        ||!Objects.equals(preview.linkedSessions(),command.expectedLinkedSessions()))
                    throw conflict("文档内容或关联会话已变化，请重新确认删除范围");
                if(repository.versionRunning(id)||lifecycle.running(id))throw conflict("文档仍有执行中任务，请等待结束后删除");
                v.deletedAt=now();v.independentlyDeleted=true;
            } else { v.deletedAt=null;v.independentlyDeleted=false; }
            v.revision++;v.updatedAt=now();repository.update(v);snapshot(v,null,null);return version(v);
        });
    }
    /** 绑定探索会话一次，不自动合并或覆盖目标产物。 */
    public Session linkVersion(String id,LinkVersion request) {
        var current=requireSession(id);
        return locked(current.projectId,p -> { active(p); var s=requireSession(id); editable(s); revision(s.revision,request.expectedRevision());
            if(s.versionId!=null) throw conflict("会话已关联需求版本，请为其他交付范围新建会话");
            if(repository.running(p.id,id)) throw conflict("请等待会话任务结束后关联版本");
            belongsActive(requireVersion(request.versionId()),p.id); s.versionId=request.versionId(); s.revision++; s.updatedAt=now(); repository.update(s); return session(s); });
    }
    /** 先保存用户消息与幂等任务，再交给执行器；重试相同命令不会重复调用模型。 */
    public Run submit(String sessionId,SubmitRun request) {
        if(request==null||!validator.validate(request).isEmpty()||!Set.of("CHAT","DRAFT").contains(request.kind()))throw invalid("此入口仅接受会话或草稿任务");
        var current=requireSession(sessionId);
        var result=locked(current.projectId,p -> {
            var previous=repository.requestRun(sessionId,request.requestId());
            if(previous!=null) {
                if(!previous.kind.equals(request.kind())||!previous.inputText.equals(request.content().strip())) throw conflict("请求标识已用于另一条命令");
                return new Submission(previous,false);
            }
            active(p); var s=requireSession(sessionId); editable(s);
            if(s.versionId!=null) belongsActive(requireVersion(s.versionId),p.id);
            if(repository.running(p.id,null)||lifecycle.runningProject(p.id)) throw conflict("项目正在处理上一条请求");
            String input=request.content().strip();
            if("CHAT".equals(request.kind())&&input.isBlank()) throw invalid("消息不能为空");
            var r=new WorkspaceRunEntity(); r.id=id(); r.projectId=p.id; r.sessionId=s.id; r.requestId=request.requestId(); r.kind=request.kind();
            r.versionId=s.versionId; r.versionRevision=s.versionId==null?null:requireVersion(s.versionId).revision;
            r.inputText=input; r.contextText=""; r.draftRevision=s.draftRevision; r.status="QUEUED"; r.createdAt=now(); r.updatedAt=r.createdAt;
            append(s,"USER","DRAFT".equals(r.kind)?"整理本会话的需求草稿" : input,null,null,r.id);
            r.contextText=context(p,s); repository.insert(r); return new Submission(r,true);
        });
        if(result.created()) workers.submit(() -> execute(result.run().id));
        return run(result.run());
    }
    /** 读取安全任务状态，输入上下文不进入响应。 */
    public Run getRun(String id) { var r=requireRun(id);return "PROTOTYPE".equals(r.kind)?prototypes.run(id):run(r); }
    /** 会话详情只扩展可见原型进度，不把上下文、令牌或文件路径放入通用结果。 */
    private Run runView(WorkspaceRunEntity r){return new Run(r.id,r.sessionId,r.versionId,r.versionRevision,r.kind,r.status,r.mode,r.model,r.resultText,r.errorMessage,r.draftRevision,r.createdAt,
            "PROTOTYPE".equals(r.kind)?prototypes.visibleProgress(r.id):null);}
    /** 保存草稿只增加其内部修订，生成内容过期时明确拒绝应用。 */
    public Session saveDraft(String sessionId,SaveDraft request) {
        var current=requireSession(sessionId);
        return locked(current.projectId,p -> { active(p); var s=requireSession(sessionId); editable(s); revision(s.revision,request.expectedRevision());
            if(s.versionId!=null) belongsActive(requireVersion(s.versionId),p.id);
            String source=blankNull(request.sourceRunId());
            if(source!=null) {
                var r=requireRun(source);
                if(!r.sessionId.equals(sessionId)||!"DRAFT".equals(r.kind)||!"SUCCEEDED".equals(r.status)) throw invalid("草稿来源无效");
                if(!r.draftRevision.equals(s.draftRevision)||!Objects.equals(r.versionId,s.versionId)
                        ||(s.versionId!=null&&!Objects.equals(r.versionRevision,requireVersion(s.versionId).revision))) throw new ProjectException(HttpStatus.CONFLICT,"WORKSPACE_REVISION_CONFLICT","生成基准已变化，请重新整理或手工合并草稿");
            }
            s.draftText=request.content().strip(); s.draftRevision++; s.revision++; s.updatedAt=now(); repository.update(s);
            var d=new SessionDraftRevisionEntity(); d.id=id(); d.sessionId=s.id; d.revision=s.draftRevision; d.content=s.draftText; d.sourceRunId=source; d.createdAt=s.updatedAt;
            repository.insert(d); return session(s); });
    }
    /** 先定义范围和目标也可以创建版本，无需会话或模型成功。 */
    public Version createVersion(String projectId,NewVersion request) {
        return locked(projectId,p -> { active(p); return version(newVersion(p.id,request.label(),request.goal(),request.content(),null,null)); });
    }
    /** 指定已保存草稿形成一个交付版本；重复命令返回同一版本。 */
    public Version promote(String sessionId,PromoteDraft request) {
        var current=requireSession(sessionId);
        return locked(current.projectId,p -> { active(p); var s=requireSession(sessionId); editable(s);
            if(s.versionId!=null) {
                var v=requireVersion(s.versionId);
                if(sessionId.equals(v.sourceSessionId)&&Objects.equals(v.sourceDraftRevision,request.draftRevision())
                        &&v.label.equals(request.label().strip())&&v.goal.equals(request.goal().strip())) return version(v);
                throw conflict("会话已经关联需求版本");
            }
            revision(s.revision,request.expectedRevision()); revision(s.draftRevision,request.draftRevision());
            if(repository.running(p.id,s.id)) throw conflict("请等待会话任务结束后形成版本");
            var d=repository.draft(sessionId,request.draftRevision()); if(d==null) throw invalid("请先保存需求草稿");
            var v=newVersion(p.id,request.label(),request.goal(),d.content,s.id,d.revision);
            s.versionId=v.id; s.revision++; s.updatedAt=now(); repository.update(s); return version(v); });
    }
    /** 读取业务版本，不把范围修订当成业务版本。 */
    public Version getVersion(String id) { return version(requireVersion(id)); }
    /** 项目版本分页目录。 */
    public Slice<Version> versions(String projectId,long page,boolean archived) { requireProject(projectId); return slice(repository.versions(projectId,page(page),archived),WorkspaceViews::version); }
    /** 修改范围并保存不可变修订，阶段仍为 REVIEW。 */
    public Version saveVersion(String id,EditVersion request) {
        var current=requireVersion(id);
        return locked(current.projectId,p -> { active(p); var v=requireVersion(id); belongsActive(v,p.id); revision(v.revision,request.expectedRevision());
            if(!"REVIEW".equals(v.stage)&&(!v.goal.equals(request.goal().strip())||!v.content.equals(request.content().strip()))) throw conflict("目标和范围已形成确认基线，请先重新评审需求变更");
            uniqueLabel(p.id,request.label().strip(),id); v.label=request.label().strip(); v.goal=request.goal().strip(); v.content=request.content().strip();
            v.revision++; v.updatedAt=now(); repository.update(v); snapshot(v,null,null); return version(v); });
    }
    /** 显式把关联会话的已保存草稿同步为范围的新修订。 */
    public Version syncDraft(String sessionId,SyncDraft request) {
        var current=requireSession(sessionId);
        return locked(current.projectId,p -> { active(p); var s=requireSession(sessionId); editable(s);
            if(s.versionId==null) throw invalid("会话尚未关联需求版本");
            var v=requireVersion(s.versionId); belongsActive(v,p.id); revision(v.revision,request.expectedRevision()); revision(s.draftRevision,request.draftRevision());
            if(!"REVIEW".equals(v.stage)) throw conflict("请先重新评审需求变更，再同步会话草稿");
            var d=repository.draft(s.id,request.draftRevision()); if(d==null) throw invalid("请先保存草稿");
            v.content=d.content; v.revision++; v.updatedAt=now(); repository.update(v); snapshot(v,s.id,d.revision); return version(v); });
    }
    /** 取消版本要记录原因，不能伪装为完成。 */
    public Version cancelVersion(String id,CancelVersion request) {
        var current=requireVersion(id);
        return locked(current.projectId,p -> { active(p); var v=requireVersion(id); belongsActive(v,p.id); revision(v.revision,request.expectedRevision());
            if(repository.running(p.id,null)) throw conflict("项目仍有执行中任务，请等待结束");
            if(lifecycle.running(id)) throw conflict("PRD 整理仍在进行，请等待任务结束");
            v.lifecycle="CANCELED"; v.cancelReason=request.reason().strip(); v.revision++; v.updatedAt=now(); repository.update(v); snapshot(v,null,null); return version(v); });
    }
    /** 首期只能归档已取消版本，恢复不会把取消状态改成活动。 */
    public Version archiveVersion(String id,Archive request) {
        var current=requireVersion(id);
        return locked(current.projectId,p -> { active(p); var v=requireVersion(id); if(v.deletedAt!=null)throw conflict("文档已删除，请先从回收站恢复文档"); revision(v.revision,request.expectedRevision());
            if(request.archived()&&!"CANCELED".equals(v.lifecycle)) throw conflict("活动版本不能归档；中止交付请先记录取消原因");
            v.archivedAt=request.archived()?now():null; v.revision++; v.updatedAt=now(); repository.update(v); snapshot(v,null,null); return version(v); });
    }
    /** 范围快照只读。 */
    public Slice<SavedRevision> revisions(String id,long page) { requireVersion(id); return slice(repository.revisions(id,page(page)),WorkspaceViews::saved); }
    /** 读取列表之外的指定保存记录。 */
    public SavedRevision savedRevision(String id,long revision) { requireVersion(id); var r=repository.revision(id,revision); if(r==null) throw missing("保存记录不存在"); return saved(r); }
    /** 独立归档目录，不删除业务数据。 */
    public Archives archives(long page) { long n=page(page); return new Archives(projects(n,true),slice(repository.sessions(null,null,n,true),WorkspaceViews::session),slice(repository.versions(null,n,true),WorkspaceViews::version)); }

    /** 单进程重启将旧运行标记中断，显式重试才会再次调用模型。 */
    @EventListener(ApplicationReadyEvent.class)
    public void interruptPreviousRuns() {
        prototypes.interruptPreviousRuns();
        for(var stale:repository.unfinished()) locked(stale.projectId,p -> { var r=requireRun(stale.id);
            if("PROTOTYPE".equals(r.kind))return null;
            if(Set.of("QUEUED","RUNNING").contains(r.status)) { r.status="INTERRUPTED"; r.errorMessage="服务重启，任务已中断；输入已保存，请显式重试"; r.updatedAt=now(); repository.update(r); } return null; });
    }
    /** 关闭执行器，不让任务生命周期依赖浏览器标签。 */
    @PreDestroy public void close() { workers.shutdownNow(); }
    /** 外部请求不占数据库事务；结束写入保留当次模型来源。 */
    private void execute(String id) {
        long startedAt=System.nanoTime();
        var context=new TaskFailureDiagnostics.Context("SESSION",id,null,null,null,null);
        try {
            var initial=requireRun(id);
            if(!Set.of("CHAT","DRAFT").contains(initial.kind))return;
            context=new TaskFailureDiagnostics.Context(initial.kind,id,initial.projectId,initial.sessionId,initial.versionId,null);
            AnalysisRuntime snapshot=runtime.snapshot();
            boolean started=locked(initial.projectId,p -> { var r=requireRun(id); if(!"QUEUED".equals(r.status)) return false;
                r.status="RUNNING"; r.mode=snapshot.mode(); r.model=snapshot.model(); r.updatedAt=now(); repository.update(r); return true; });
            if(!started) return;
            TaskFailureDiagnostics.started(context);
            String result;
            if("CHAT".equals(initial.kind)) result=snapshot.analyzer().converse(initial.contextText);
            else {
                var draft=snapshot.analyzer().analyze(snapshot.isDemo()?limit(initial.contextText,10000):initial.contextText);
                if(draft==null||!validator.validate(draft).isEmpty()) throw ModelInvocationException.invalidOutput();
                result=(snapshot.isDemo()?"> 来源：本地模板联调，未调用大模型。\n\n":"")+"# "+draft.title()+"\n\n## 背景与目标\n"+draft.overview()+"\n\n## 待确认需求\n"+bullets(draft.requirements())
                        +"\n\n## 澄清问题\n"+bullets(draft.openQuestions())+"\n\n## 设计建议（未接受）\n"+bullets(draft.assumptions());
            }
            if(result==null||result.isBlank()||result.length()>60000) throw ModelInvocationException.invalidOutput();
            if(finish(id,result,null))TaskFailureDiagnostics.succeeded(context,startedAt);
        } catch(RuntimeException exception) {
            String diagnosis=TaskFailureDiagnostics.report(context,startedAt,exception);
            // 失败终态写入再次出错时仍记录诊断，避免后台执行器隐藏存储故障。
            try{finish(id,null,"处理失败，输入已保存。"+diagnosis+"；请显式重试，不会自动回退到模板");}
            catch(RuntimeException storage){TaskFailureDiagnostics.report(context,startedAt,storage);}
        }
    }
    /** 原子写任务终态与助手消息，重启后的迟到结果不能覆盖中断状态。 */
    private boolean finish(String id,String result,String error) {
        var initial=requireRun(id);
        return locked(initial.projectId,p -> { var r=requireRun(id); if(!Set.of("QUEUED","RUNNING").contains(r.status)) return false;
            r.status=error==null?"SUCCEEDED":"FAILED"; r.resultText=result; r.errorMessage=error; r.updatedAt=now(); repository.update(r);
            if(error==null) append(requireSession(r.sessionId),"ASSISTANT","DRAFT".equals(r.kind)?"已整理候选草稿，请查看并明确应用；尚未保存或形成需求版本。":result,r.mode,r.model,id);
            return true; });
    }
    /** 追加消息序号在项目锁中递增，不增加草稿元数据修订。 */
    private void append(WorkSessionEntity s,String role,String content,String mode,String model,String runId) {
        var m=new SessionMessageEntity(); m.id=id(); m.sessionId=s.id; m.sequence=++s.messageSequence; m.role=role; m.content=content;
        m.mode=mode; m.model=model; m.runId=runId; m.createdAt=now(); repository.insert(m); s.updatedAt=m.createdAt; repository.update(s);
    }
    /** 固定最多 20 条近期消息和有界背景/范围；完整历史仍保存在数据库。 */
    private String context(WorkspaceProjectEntity p,WorkSessionEntity s) {
        String header="项目："+p.name+"（背景修订 "+p.revision+"）\n背景："+limit(p.background,6000)+"\n";
        if(s.versionId!=null) { var v=requireVersion(s.versionId); header+="关联版本："+v.label+"，范围修订 "+v.revision+"\n目标："+limit(v.goal,4000)+"\n范围："+limit(v.content,8000)+"\n"; }
        var turns=new ArrayList<String>(); int remaining=24000;
        for(var m:repository.messages(s.id,null).stream().limit(20).toList()) {
            String turn=m.role+": "+m.content+"\n"; if(turn.length()>remaining) break; turns.add(turn); remaining-=turn.length();
        }
        Collections.reverse(turns); return header+"当前会话（近期消息）：\n"+String.join("",turns);
    }
    /** 创建版本与首份不可变范围快照，来源由服务器固定。 */
    private RequirementVersionEntity newVersion(String projectId,String label,String goal,String content,String source,Long draftRevision) {
        uniqueLabel(projectId,label.strip(),null); var v=new RequirementVersionEntity(); v.id=id(); v.projectId=projectId; v.label=label.strip();
        v.goal=goal.strip(); v.content=content.strip(); v.revision=1L; v.stage="REVIEW"; v.lifecycle="ACTIVE"; v.sourceSessionId=source; v.sourceDraftRevision=draftRevision;
        v.createdAt=now(); v.updatedAt=v.createdAt; repository.insert(v); snapshot(v,source,draftRevision); return v;
    }
    /** 所有范围快照只新增，不覆盖历史。 */
    private void snapshot(RequirementVersionEntity v,String source,Long draftRevision) {
        var r=new VersionRevisionEntity(); r.id=id(); r.versionId=v.id; r.revision=v.revision; r.label=v.label; r.goal=v.goal; r.content=v.content;
        r.sourceSessionId=source; r.sourceDraftRevision=draftRevision; r.createdAt=v.updatedAt; repository.insert(r);
    }
    /** 同项目关联写入使用数据库根行锁，失败回滚整条命令。 */
    private <T> T locked(String id,Function<WorkspaceProjectEntity,T> command) {
        return transactions.execute(t -> { var p=repository.lock(id); if(p==null) throw missing("项目不存在"); return command.apply(p); });
    }
    /** 活动项目才允许写入。 */ private void active(WorkspaceProjectEntity p) { if(p.archivedAt!=null) throw conflict("项目已归档，请先恢复"); }
    /** 已删除或归档会话只读。 */ private void editable(WorkSessionEntity s) { if(s.deletedAt!=null||s.archivedAt!=null) throw conflict("会话已归档或移入回收站，请先恢复"); }
    /** 同项目、活动版本才接受新的交付内容。 */
    private void belongsActive(RequirementVersionEntity v,String projectId) {
        if(!v.projectId.equals(projectId)) throw invalid("需求版本不属于该项目");
        if(v.deletedAt!=null||v.archivedAt!=null||!"ACTIVE".equals(v.lifecycle)) throw conflict("需求版本已取消、归档或移入回收站，不能继续修改交付内容");
    }
    /** 统一乐观锁校验。 */ private void revision(long actual,long expected) { if(actual!=expected) throw new ProjectException(HttpStatus.CONFLICT,"WORKSPACE_REVISION_CONFLICT","内容已在其他窗口改变，请保留输入并重新载入"); }
    /** 项目内业务名称唯一，改名不影响 UUID 引用。 */ private void uniqueLabel(String projectId,String label,String except) { if(repository.labelExists(projectId,label,except)) throw conflict("该项目已存在同名需求版本"); }
    /** 校验有界分页。 */ private long page(long value) { if(value<1||value>100000) throw invalid("分页参数无效"); return value; }
    /** 通用分页响应映射，数据库实体不作为公开契约。 */ private <R,V> Slice<V> slice(Page<R> rows,Function<R,V> convert) { return new Slice<>(rows.getRecords().stream().map(convert).toList(),rows.hasNext(),rows.getCurrent()); }
    /** 读取项目或返回 404。 */ private WorkspaceProjectEntity requireProject(String id) { var r=repository.project(id); if(r==null) throw missing("项目不存在"); return r; }
    /** 读取会话或返回 404。 */ private WorkSessionEntity requireSession(String id) { var r=repository.session(id); if(r==null) throw missing("会话不存在"); return r; }
    /** 读取业务版本或返回 404。 */ private RequirementVersionEntity requireVersion(String id) { var r=repository.version(id); if(r==null) throw missing("需求版本不存在"); return r; }
    /** 读取任务或返回 404。 */ private WorkspaceRunEntity requireRun(String id) { var r=repository.run(id); if(r==null) throw missing("任务不存在"); return r; }
    /** 生成不依赖数组位置的标识。 */ private String id() { return UUID.randomUUID().toString(); }
    /** 数据库时间统一使用 UTC。 */ private OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
    /** 规范化可空关联字段。 */ private String blankNull(String value) { return value==null||value.isBlank()?null:value.strip(); }
    /** 上下文截断不影响已保存的完整记录。 */ private String limit(String value,int size) { return value.substring(0,Math.min(value.length(),size)); }
    /** 结构化候选列表按纯文本整理，无 HTML。 */ private String bullets(List<String> values) { return values.isEmpty()?"暂无":String.join("\n",values.stream().map(v -> "- "+v).toList()); }
    /** 业务关联或格式错误。 */ private ProjectException invalid(String message) { return new ProjectException(HttpStatus.BAD_REQUEST,"INVALID_INPUT",message); }
    /** 可重试业务冲突，不返回 SQL。 */ private ProjectException conflict(String message) { return new ProjectException(HttpStatus.CONFLICT,"WORKSPACE_CONFLICT",message); }
    /** 安全的不存在响应。 */ private ProjectException missing(String message) { return new ProjectException(HttpStatus.NOT_FOUND,"WORKSPACE_NOT_FOUND",message); }
    /** 区分首次提交和幂等读取，只有首次提交安排外部执行。 */
    private record Submission(WorkspaceRunEntity run,boolean created) { }
}
