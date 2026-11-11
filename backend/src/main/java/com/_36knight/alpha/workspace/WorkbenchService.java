package com._36knight.alpha.workspace;

import com._36knight.alpha.agent.AnalysisRuntimeProvider;
import com._36knight.alpha.agent.WorkbenchWorkflow;
import com._36knight.alpha.exception.TaskFailureDiagnostics;
import com._36knight.alpha.project.ProjectException;
import com._36knight.alpha.template.PrdTemplateService;
import com._36knight.alpha.workspace.persistence.RequirementVersionEntity;
import com._36knight.alpha.workspace.persistence.SessionMessageEntity;
import com._36knight.alpha.workspace.persistence.WorkSessionEntity;
import com._36knight.alpha.workspace.persistence.WorkspaceRunEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Validator;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com._36knight.alpha.workspace.WorkbenchContracts.*;
import static com._36knight.alpha.workspace.LifecycleContracts.*;

/** 会话持续编辑同一 PRD；短事务固定输入和应用结果，Graph 不维护业务真值。@author Andersen */
@Service
public class WorkbenchService {
    /** 任务始终使用其文档绑定的发布模板。 */ private final PrdTemplateService templates;
    /** 业务实体及项目根锁。 */ private final WorkspaceRepository repository;
    /** 现有项目、会话和版本业务操作。 */ private final WorkspaceService workspace;
    /** 文档保存、历史和人工定稿。 */ private final LifecycleService lifecycle;
    /** 判断旧 PRD 任务占用。 */ private final LifecycleRepository artifacts;
    /** 执行时固定模型选择。 */ private final AnalysisRuntimeProvider runtime;
    /** 会话编辑 Graph。 */ private final WorkbenchWorkflow edits;
    /** 固定上下文及结果编解码。 */ private final ObjectMapper json;
    /** 非 HTTP 调用同样校验契约。 */ private final Validator validator;
    /** 嵌套业务操作共用一个事务和项目锁。 */ private final TransactionTemplate transactions;
    /** 单实例后台执行，重启由既有扫描标记中断。 */ private final ExecutorService workers=Executors.newFixedThreadPool(2);

    /** 注入已有业务服务，不另建 PRD 或会话存储。 */
    public WorkbenchService(WorkspaceRepository repository,WorkspaceService workspace,LifecycleService lifecycle,
            LifecycleRepository artifacts,AnalysisRuntimeProvider runtime,WorkbenchWorkflow edits,
            ObjectMapper json,Validator validator,PlatformTransactionManager manager,PrdTemplateService templates) {
        this.templates=templates;
        this.repository=repository;this.workspace=workspace;this.lifecycle=lifecycle;this.artifacts=artifacts;
        this.runtime=runtime;this.edits=edits;this.json=json;this.validator=validator;transactions=new TransactionTemplate(manager);
    }
    /** 活动探索会话只建立一次关联文档；存量正文及确认稿完整保留。 */
    public WorkbenchContracts.Detail open(String sessionId) {
        return locked(sessionId,s->{
            if(s.versionId==null&&s.deletedAt==null&&s.archivedAt==null&&repository.project(s.projectId).archivedAt==null) {
                writable(s);
                if(repository.running(s.projectId,s.id))throw conflict("请等待原会话任务结束后打开会话");
                String base=s.title.length()>100?s.title.substring(0,100):s.title;
                String label=base;int suffix=2;
                while(repository.labelExists(s.projectId,label,null))label=base+" · "+suffix++;
                var v=workspace.createVersion(s.projectId,new WorkspaceRequests.NewVersion(label,"",s.draftText));
                workspace.linkVersion(s.id,new WorkspaceRequests.LinkVersion(s.revision,v.id()));
            }
            var current=repository.session(sessionId);
            if(current.versionId!=null&&current.deletedAt==null&&current.archivedAt==null&&repository.project(current.projectId).archivedAt==null) {
                var v=version(current);
                if(v.deletedAt==null&&v.archivedAt==null&&"ACTIVE".equals(v.lifecycle))lifecycle.openConversationPrd(v.id);
            }
            return detail(sessionId);
        });
    }
    /** 版本入口复用最近会话，归档与取消对象保持只读。 */
    public WorkbenchContracts.Detail openVersion(String versionId) {
        var initial=repository.version(versionId);if(initial==null)throw missing();
        return transactions.execute(t->{
            repository.lock(initial.projectId);var v=repository.version(versionId);
            if(v.deletedAt!=null) {
                // 回收站查看只读取已有产物，不自动新建会话或初始化 PRD。
                return new WorkbenchContracts.Detail(new WorkspaceViews.SessionDetail(workspace.getProject(v.projectId),null,workspace.getVersion(v.id),List.of()),artifacts.state(v.id)==null?null:lifecycle.get(v.id));
            }
            var sessions=repository.sessions(v.projectId,versionId,1,false).getRecords();
            if(!sessions.isEmpty())return open(sessions.getFirst().id);
            if(!"ACTIVE".equals(v.lifecycle)||v.archivedAt!=null||repository.project(v.projectId).archivedAt!=null) {
                var archived=repository.sessions(v.projectId,versionId,1,true).getRecords();
                if(!archived.isEmpty())return detail(archived.getFirst().id);
                return new WorkbenchContracts.Detail(new WorkspaceViews.SessionDetail(workspace.getProject(v.projectId),null,workspace.getVersion(v.id),List.of()),lifecycle.get(v.id));
            }
            var session=workspace.createSession(v.projectId,new WorkspaceRequests.NewSession(v.label,v.id));return open(session.id());
        });
    }
    /** 轮询只读，不推进阶段或隐式创建关联。 */
    public WorkbenchContracts.Detail get(String sessionId){return locked(sessionId,s->detail(s.id));}
    /** 自动保存当前文档，待采纳建议由独立决策接口维护。 */
    public WorkbenchContracts.Detail save(String sessionId,Draft command) {
        validate(command);return locked(sessionId,s->{
            writable(s);var v=version(s);var d=lifecycle.get(v.id);
            revisions(v,d,command.expectedVersionRevision(),command.expectedRevision());requireDocument(v,d);
            lifecycle.saveConversationPrd(v.id,d.revision(),command.prd(),d.suggestions(),null,null);return detail(s.id);
        });
    }
    /** 任务固定最新 PRD；幂等重试不会重复追加消息或调用模型。 */
    public WorkspaceViews.Run submit(String sessionId,WorkbenchContracts.Message command) {
        validate(command);
        WorkbenchAttachments.validate(command.attachments());
        Submission submission=locked(sessionId,s->{
            var old=repository.requestRun(s.id,command.requestId());
            if(old!=null) {
                if(!"WORKBENCH".equals(old.kind)||!old.inputText.equals(command.content().strip())||!decode(old.contextText,Context.class).target().equals(command.target())||!decode(old.contextText,Context.class).attachments().equals(command.attachments()))throw conflict("请求标识已用于另一条命令");
                return new Submission(old,false);
            }
            writable(s);var v=version(s);var d=lifecycle.get(v.id);
            revisions(v,d,command.expectedVersionRevision(),command.expectedRevision());requireDocument(v,d);
            if(!PrdDocuments.targetExists(d.prd(),command.target(),templates.forDocument(d.prd())))throw invalid("讨论对象已不存在，请重新选择模块或页面");
            if(repository.running(s.projectId,null)||artifacts.runningProject(s.projectId))throw conflict("请等待当前项目的模型任务结束");
            var r=new WorkspaceRunEntity();r.id=id();r.projectId=s.projectId;r.sessionId=s.id;r.versionId=v.id;
            r.versionRevision=v.revision;r.draftRevision=d.revision();r.requestId=command.requestId();r.kind="WORKBENCH";
            r.inputText=command.content().strip();r.status="QUEUED";r.createdAt=now();r.updatedAt=r.createdAt;
            // 只为默认名称的首次描述取名，Unicode 截断不覆盖自定义标题。
            if(s.messageSequence==0&&"新会话".equals(s.title)) {
                String title=r.inputText.replaceAll("\\s+"," ");int count=title.codePointCount(0,title.length());
                s.title=count>32?title.substring(0,title.offsetByCodePoints(0,32))+"…":title;s.revision++;
            }
            append(s,"USER",r.inputText,null,null,r.id);
            r.contextText=encode(new Context(command.target(),d.revision(),v.revision,d.prd(),d.suggestions(),repository.project(s.projectId).background,turns(s.id),r.inputText,command.attachments(),command.attachments().isEmpty()?recentAttachments(s.id):List.of()));
            repository.insert(r);return new Submission(r,true);
        });
        if(submission.created())workers.submit(()->execute(submission.run().id));return WorkspaceViews.run(submission.run());
    }
    /** 只返回指定会话中该消息提交的附件，绝不暴露整个运行上下文。 */
    public List<Attachment> attachments(String sessionId,String runId) {
        return locked(sessionId,s->{var run=repository.run(runId);
            if(run==null||!s.id.equals(run.sessionId)||!"WORKBENCH".equals(run.kind))throw missing();
            return decode(run.contextText,Context.class).attachments();});
    }
    /** 后续讨论沿用最近一批参考资料；上传新批次后替换参考上下文，不删除旧消息附件。 */
    private List<Attachment> recentAttachments(String sessionId) {
        for(var run:repository.runs(sessionId)) {
            if(!"WORKBENCH".equals(run.kind))continue;
            var context=decode(run.contextText,Context.class);
            if(!context.attachments().isEmpty())return context.attachments();
            if(!context.referenceAttachments().isEmpty())return context.referenceAttachments();
        }
        return List.of();
    }
    /** 定稿后明确继续编辑；旧确认稿不受后续保存影响。 */
    public WorkbenchContracts.Detail reopen(String sessionId,Reopen command) {
        return locked(sessionId,s->{writable(s);lifecycle.resumePrd(version(s).id,command);return detail(s.id);});
    }
    /** 仅人工明确动作可以确认 PRD；确认不会替代原型自己的来源和验收。 */
    public WorkbenchContracts.Detail confirmPrd(String sessionId,Confirm command) {
        return locked(sessionId,s->{writable(s);lifecycle.confirmPrd(version(s).id,command);return detail(s.id);});
    }
    /** 采纳建议追加原文到目标章节，忽略只移除建议；两者都保留历史。 */
    public WorkbenchContracts.Detail decide(String sessionId,Decision command) {
        validate(command);return locked(sessionId,s->{writable(s);var v=version(s);var d=lifecycle.get(v.id);
            revisions(v,d,command.expectedVersionRevision(),command.expectedRevision());requireDocument(v,d);
            var proposal=d.suggestions().stream().filter(x->x.id().equals(command.suggestionId())).findFirst().orElseThrow(()->conflict("建议已处理，请重新载入"));
            Prd next=d.prd();
            if("ACCEPT".equals(command.decision())) {
                String original=PrdDocuments.sections(next).get(proposal.section());
                if(original==null)throw conflict("建议关联的模块或页面已删除，可忽略该建议或通过会话重新讨论");
                next=PrdDocuments.patch(next,Map.of(proposal.section(),original.isBlank()?proposal.text():original+"\n\n"+proposal.text()),null);
            }
            lifecycle.saveConversationPrd(v.id,d.revision(),next,d.suggestions().stream().filter(x->!x.id().equals(proposal.id())).toList(),null,null);
            lifecycle.recordProposalDecision(v.id,proposal,command.decision());return detail(s.id);
        });
    }
    /** 模型调用在事务外，失败只保存安全诊断，不伪造模板结果。 */
    private void execute(String runId) {
        long started=System.nanoTime();var initial=repository.run(runId);
        var diagnostic=new TaskFailureDiagnostics.Context("WORKBENCH",runId,initial.projectId,initial.sessionId,initial.versionId,null);
        try {
            var snapshot=runtime.snapshot();
            boolean start=locked(initial.sessionId,s->{var r=repository.run(runId);if(!"QUEUED".equals(r.status))return false;
                r.status="RUNNING";r.mode=snapshot.mode();r.model=snapshot.model();r.updatedAt=now();repository.update(r);return true;});
            if(!start)return;
            TaskFailureDiagnostics.started(diagnostic);
            Context source=decode(initial.contextText,Context.class);Reply reply=edits.execute(source,snapshot);validate(reply);finish(runId,source,reply);
            TaskFailureDiagnostics.succeeded(diagnostic,started);
        } catch(RuntimeException e) {
            String message=TaskFailureDiagnostics.report(diagnostic,started,e);
            try{locked(initial.sessionId,s->{var r=repository.run(runId);if(!running(r.status))return null;
                r.status="FAILED";r.errorMessage="处理失败，现有内容已保留。"+message+"；请重试。";r.updatedAt=now();repository.update(r);return null;});}
            catch(RuntimeException storage){TaskFailureDiagnostics.report(diagnostic,started,storage);}
        }
    }
    /** 同一事务保存文档、任务和消息；迟到结果不能覆盖人工新稿或定稿。 */
    private void finish(String runId,Context source,Reply reply) {
        var initial=repository.run(runId);
        locked(initial.sessionId,s->{
            var r=repository.run(runId);if(!running(r.status))return null;
            var v=version(s);var d=lifecycle.get(v.id);Prd next;
            try{next=PrdDocuments.apply(source.prd(),reply,templates.forDocument(source.prd()));}
            catch(IllegalArgumentException e){throw invalid("模型返回的 PRD 结构或章节无效："+e.getMessage());}
            validate(next);
            var proposals=new ArrayList<>(source.suggestions());
            if(reply.suggestions()!=null)for(var proposal:reply.suggestions()) {
                if(!PrdDocuments.sections(next).containsKey(proposal.section()))throw invalid("建议必须关联现有正文章节");
                if(proposals.stream().anyMatch(x->x.id().equals(proposal.id())))throw invalid("模型不能改写或重复既有建议");proposals.add(proposal);
            }
            if(proposals.size()>30)throw invalid("设计建议最多 30 项");
            var changes=PrdDocuments.changedSections(source.prd(),next,templates.forDocument(source.prd()),templates.forDocument(next));
            var labels=PrdDocuments.labels(source.prd(),templates.forDocument(source.prd()));labels.putAll(PrdDocuments.labels(next,templates.forDocument(next)));labels.put("modules","功能模块结构");
            boolean documentChanged=!next.equals(source.prd());
            boolean changed=documentChanged||!proposals.equals(source.suggestions());
            boolean current=v.revision==source.versionRevision()&&d.revision()==source.revision()&&documentOpen(v,d)
                    &&s.deletedAt==null&&v.deletedAt==null&&s.archivedAt==null&&v.archivedAt==null&&"ACTIVE".equals(v.lifecycle)&&repository.project(v.projectId).archivedAt==null;
            if(current&&changed)lifecycle.saveConversationPrd(v.id,d.revision(),next,proposals,r.mode,r.model);
            String prefix="demo".equals(r.mode)?"【本地模板联调，未调用大模型】\n":"";
            String status=changed&&!current?"生成期间文档发生变化，本次结果未应用。请核对保留结果后重新提出修改。"
                    :current&&changed?(documentChanged?"已更新 PRD 并自动保存。":"设计建议已保存，PRD 正文未修改。") : "本轮未修改 PRD。";
            String brief=conversationBrief(reply.reply());
            String text=prefix+status+(brief.isBlank()?"":"\n"+brief);
            if(current&&!changes.isEmpty())text+="\n已更新："+String.join("、",changes.stream().limit(3).map(key->"issues".equals(key)?"待确认事项":labels.getOrDefault(key,key)).toList())+(changes.size()>3?"等 "+changes.size()+" 处":"")+"。";
            r.resultText=encode(new Result(text,current&&changed,source.target(),changed?next:null,proposals,changes,reply.reply()));
            r.status="SUCCEEDED";r.updatedAt=now();repository.update(r);append(s,"ASSISTANT",text,r.mode,r.model,r.id);return null;
        });
    }
    /** 会话只显示简短说明；保存、应用和定稿状态由业务事务给出，原始回答留在任务结果。 */
    private String conversationBrief(String reply) {
        String text=java.util.Arrays.stream(reply.split("(?<=[。！？])|\\n"))
                .map(String::strip).filter(line->!line.isBlank())
                .filter(line->!line.matches(".*(保存|应用|定稿|候选|未调用大模型).*"))
                .collect(java.util.stream.Collectors.joining("\n"));
        return text.length()>500?text.substring(0,500)+"…":text;
    }
    /** 文档写入不依赖旧需求基线；原型里程碑不替代当前 PRD 的开放编辑状态。 */
    private void requireDocument(RequirementVersionEntity v,LifecycleContracts.Detail d) {
        if(!d.directPrd()||d.prd()==null)throw conflict("请重新打开会话以编辑 PRD");
        if(!documentOpen(v,d))throw conflict("PRD 已定稿或未开放编辑，请先继续编辑");
    }
    /** 保存、讨论和迟到结果应用共享同一开放规则，避免 PROTOTYPE 阶段吞掉正常修改。 */
    private boolean documentOpen(RequirementVersionEntity v,LifecycleContracts.Detail d) {
        return d.directPrd()&&d.prd()!=null&&d.prdBaseline()==null&&List.of("PRD","PROTOTYPE").contains(v.stage);
    }
    /** 最近消息有界保留，当前需求与文档完整独立提供，不依赖截断历史恢复事实。 */
    private List<String> turns(String sessionId) {
        var turns=new ArrayList<String>();int remaining=24000;
        for(var message:repository.messages(sessionId,null).stream().limit(20).toList()) {
            String text=message.role+": "+message.content;if(text.length()>remaining)break;
            turns.add(text);remaining-=text.length();
        }
        Collections.reverse(turns);return turns;
    }
    /** 在项目锁中追加消息并保持序号唯一，不改写已有消息。 */
    private void append(WorkSessionEntity session,String role,String text,String mode,String model,String runId) {
        var message=new SessionMessageEntity();message.id=id();message.sessionId=session.id;message.sequence=++session.messageSequence;
        message.role=role;message.content=text;message.mode=mode;message.model=model;message.runId=runId;message.createdAt=now();
        repository.insert(message);session.updatedAt=message.createdAt;repository.update(session);
    }
    /** 统一详情来自已保存业务真值。 */
    private WorkbenchContracts.Detail detail(String id) {
        var session=workspace.getSession(id);
        var version=session.version();
        boolean missingArtifact=version==null||(version.deletedAt()!=null&&artifacts.state(version.id())==null);
        return new WorkbenchContracts.Detail(session,missingArtifact?null:lifecycle.get(version.id()));
    }
    /** 关联版本缺失时不静默新建或改绑。 */
    private RequirementVersionEntity version(WorkSessionEntity session) {
        if(session.versionId==null)throw conflict("请先打开会话");
        var version=repository.version(session.versionId);if(version==null)throw missing();return version;
    }
    /** 所有写入使用同一个项目根锁，并在锁内重新读取会话。 */
    private <T>T locked(String sessionId,Function<WorkSessionEntity,T> action) {
        var current=repository.session(sessionId);if(current==null)throw missing();
        return transactions.execute(t->{repository.lock(current.projectId);return action.apply(repository.session(sessionId));});
    }
    /** 归档与取消对象只读，直接调用服务也不能绕过。 */
    private void writable(WorkSessionEntity s) {
        if(s.deletedAt!=null||s.archivedAt!=null||repository.project(s.projectId).archivedAt!=null)throw conflict("已归档的会话或项目只读");
        if(s.versionId!=null){var v=version(s);if(v.deletedAt!=null||v.archivedAt!=null||!"ACTIVE".equals(v.lifecycle))throw conflict("已取消或归档迭代只读");}
    }
    /** 草稿锁和版本锁都必须与用户看到的内容一致。 */
    private void revisions(RequirementVersionEntity v,LifecycleContracts.Detail d,long versionRevision,long revision) {
        if(v.revision!=versionRevision||d.revision()!=revision)throw new ProjectException(HttpStatus.CONFLICT,"WORKSPACE_REVISION_CONFLICT","其他窗口已修改内容；本地输入保留，请核对后重新载入");
    }
    /** 所有内部命令与模型输出遵循有界契约。 */ private void validate(Object value){if(value==null||!validator.validate(value).isEmpty())throw invalid("内容不符合会话输入要求");}
    /** 有界快照不含凭证，不用于 HTML 渲染。 */ private String encode(Object value){try{String text=json.writeValueAsString(value);if(text.length()>650000)throw invalid("会话内容过长，请拆分本期需求");return text;}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("工作空间序列化失败",e);}}
    /** 仅解析服务器生成的持久化结构。 */ private <T>T decode(String text,Class<T> type){try{return json.readValue(text,type);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("工作空间任务数据无效",e);}}
    /** 固定 UTC 存储。 */ private OffsetDateTime now(){return OffsetDateTime.now(ZoneOffset.UTC);}
    /** 独立业务标识。 */ private String id(){return UUID.randomUUID().toString();}
    /** 终态不接受迟到结果。 */ private boolean running(String status){return List.of("QUEUED","RUNNING").contains(status);}
    /** 并发或阶段冲突。 */ private ProjectException conflict(String text){return new ProjectException(HttpStatus.CONFLICT,"WORKSPACE_REVISION_CONFLICT",text);}
    /** 输入校验失败。 */ private ProjectException invalid(String text){return new ProjectException(HttpStatus.BAD_REQUEST,"INVALID_INPUT",text);}
    /** 不存在的业务对象。 */ private ProjectException missing(){return new ProjectException(HttpStatus.NOT_FOUND,"WORKSPACE_NOT_FOUND","会话或关联需求不存在");}
    /** 防止重复幂等命令再次调度。 */ private record Submission(WorkspaceRunEntity run,boolean created) { }
    /** 释放执行线程，重启后的任务由既有中断机制标记。 */ @PreDestroy public void close(){workers.shutdownNow();}
}
