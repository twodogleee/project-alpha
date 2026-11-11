package com._36knight.alpha.workspace;

import com._36knight.alpha.agent.AnalysisRuntimeProvider;
import com._36knight.alpha.agent.PrdWorkflow;
import com._36knight.alpha.project.ProjectException;
import com._36knight.alpha.template.PrdTemplateService;
import com._36knight.alpha.exception.TaskFailureDiagnostics;
import com._36knight.alpha.model.ModelInvocationException;
import com._36knight.alpha.workspace.persistence.LifecycleStateEntity;
import com._36knight.alpha.workspace.persistence.LifecycleBaselineEntity;
import com._36knight.alpha.workspace.persistence.LifecycleRevisionEntity;
import com._36knight.alpha.workspace.persistence.PrdRunEntity;
import com._36knight.alpha.workspace.persistence.RequirementVersionEntity;
import com._36knight.alpha.workspace.persistence.VersionRevisionEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com._36knight.alpha.workspace.LifecycleContracts.*;

/** 需求版本正式产物、人工门槛和来源链；确认是明确业务命令。@author Andersen */
@Service
public class LifecycleService {
    /** 精确解析发布模板，草稿不能影响业务文档。 */ private final PrdTemplateService templates;
    /** 项目根锁和版本真值。 */ private final WorkspaceRepository workspace;
    /** 当前产物、不可变基线与任务。 */ private final LifecycleRepository repository;
    /** 有界 JSON 内容序列化。 */ private final ObjectMapper json;
    /** 输入和结构化模型验证。 */ private final Validator validator;
    /** 执行时固定的模型选择。 */ private final AnalysisRuntimeProvider runtime;
    /** PRD 阶段编排。 */ private final PrdWorkflow workflow;
    /** 短数据库事务，不包含云调用。 */ private final TransactionTemplate transactions;
    /** 单实例后台生成，两任务并发，浏览器不控制其生命周期。 */ private final ExecutorService workers=Executors.newFixedThreadPool(2);
    /** 注入业务存储、Graph 和模型运行边界。 */
    public LifecycleService(WorkspaceRepository w,LifecycleRepository r,ObjectMapper j,Validator v,
            AnalysisRuntimeProvider a,PrdWorkflow g,PlatformTransactionManager manager,PrdTemplateService templates){
        this.templates=templates;
        workspace=w;repository=r;json=j;validator=v;runtime=a;workflow=g;transactions=new TransactionTemplate(manager);
    }
    /** 首次读取创建未确认评审，不从原文推断验收标准。 */
    public Detail get(String id){return locked(id,false,v->detail(v,state(v)));}
    /** 显式打开会话时接入同一 PRD，保留旧草稿、评审和不可变确认历史。 */
    public Detail openConversationPrd(String id) {
        return locked(id,true,v->{
            var s=state(v);if(Boolean.TRUE.equals(s.directPrd))return detail(v,s);
            if(repository.running(id)||workspace.running(v.projectId,null))throw conflict("请等待已有任务结束后打开会话");
            Review old=decode(s.reviewJson,Review.class);
            boolean newDocument=s.prdJson==null;
            Prd p=newDocument?initialConversationPrd(v,old):decode(s.prdJson,Prd.class);
            if(!newDocument && s.prdBaselineId==null && !reviewCurrent(v,s)) {
                var issues=new ArrayList<>(p.unresolvedIssues());
                if(issues.size()<30)issues.add("旧需求资料曾有调整，请核对原始资料与当前 PRD 是否一致。");
                p=PrdDocuments.patch(p,null,issues);
            }
            var proposals=old.suggestions().stream().filter(x->"PENDING".equals(x.decision())).limit(30)
                    .map(x->new WorkbenchContracts.Proposal(x.id(),"scope",x.text())).toList();
            s.proposalsJson=encode(proposals);s.directPrd=true;s.revision++;s.updatedAt=now();
            if(newDocument || !p.equals(decode(s.prdJson,Prd.class))) {
                s.prdJson=encode(p);s.prdRevision++;s.prdMode=newDocument?"manual":s.prdMode;
                history(s,"PRD",s.prdRevision,conversationSnapshot(p,proposals),s.prdSourceBaselineId,s.prdMode,s.prdModel);
            }
            history(s,"EVENT",s.revision,encode(Map.of("reason","转为会话直接编辑同一份 PRD；旧需求资料与历史保留","fromStage",v.stage)),s.reviewBaselineId,"manual",null);
            repository.update(s);
            if("REVIEW".equals(v.stage)){v.stage="PRD";touchVersion(v);}
            return detail(v,s);
        });
    }
    /** 旧资料仅复制明确内容，超长或未知信息保持待补充，原文仍可查阅。 */
    private Prd initialConversationPrd(RequirementVersionEntity v,Review review) {
        String scope=review.requirements().isEmpty()?v.content:review.requirements().stream().map(r->r.title()+"\n"+r.description()).collect(java.util.stream.Collectors.joining("\n\n"));
        String acceptance=review.requirements().stream().filter(r->!r.acceptance().isBlank()).map(r->r.title()+"："+r.acceptance()).collect(java.util.stream.Collectors.joining("\n"));
        var issues=new ArrayList<String>();
        if(scope.length()>8000||acceptance.length()>10000)issues.add("原始需求资料较长，请查阅原文并整理到对应 PRD 章节。");
        for(var q:review.questions())if(!"RESOLVED".equals(q.status())&&issues.size()<29)issues.add(q.text());
        if(review.questions().stream().filter(q->!"RESOLVED".equals(q.status())).count()>29)issues.add("更多历史待确认问题请查阅原始需求资料。");
        return new Prd("",v.goal,scope.length()>8000?"":scope,"","","",acceptance.length()>10000?"":acceptance,"","",List.of(),issues,2,List.of());
    }
    /** 直接文档保存不依赖需求确认，所有改动形成历史；定稿后必须先明确继续编辑。 */
    public Detail saveConversationPrd(String id,long expectedRevision,Prd prd,List<WorkbenchContracts.Proposal> proposals,String mode,String model) {
        validate(prd);
        try{PrdDocuments.validateStructure(prd,templates.forDocument(prd));}catch(IllegalArgumentException e){throw invalid(e.getMessage());}
        if(proposals==null||proposals.size()>30)throw invalid("设计建议最多 30 项");
        proposals.forEach(this::validate);unique(proposals.stream().map(WorkbenchContracts.Proposal::id).toList());
        return locked(id,true,v->{
            var s=state(v);conversationWritable(v,s);revision(s.revision,expectedRevision);
            templates.validateBinding(decode(s.prdJson,Prd.class),prd);
            if(prd.templateVersion()<decode(s.prdJson,Prd.class).templateVersion())throw invalid("不能降级 PRD 模板，请刷新页面后重试");
            if(!prd.requirementIds().equals(decode(s.prdJson,Prd.class).requirementIds()))throw invalid("历史需求来源标识不能通过文档编辑改写");
            s.prdJson=encode(prd);s.proposalsJson=encode(proposals);s.prdBaselineId=null;
            if(mode!=null){s.prdMode=mode;s.prdModel=model;}
            s.prdRevision++;s.revision++;s.updatedAt=now();repository.update(s);
            history(s,"PRD",s.prdRevision,conversationSnapshot(prd,proposals),s.prdSourceBaselineId,s.prdMode,s.prdModel);
            return detail(v,s);
        });
    }
    /** 保存建议决策的来源事件，与文档保存共用项目事务。 */
    public void recordProposalDecision(String id,WorkbenchContracts.Proposal proposal,String decision) {
        locked(id,true,v->{var s=state(v);conversation(s);
            history(s,"EVENT",s.revision,encode(Map.of("suggestion",proposal,"decision",decision)),s.prdSourceBaselineId,"manual",null);return null;});
    }
    /** 文档历史同时记录当时的待采纳建议，正文结构保持兼容。 */
    private String conversationSnapshot(Prd prd,List<WorkbenchContracts.Proposal> proposals) {
        var node=json.valueToTree(prd);((com.fasterxml.jackson.databind.node.ObjectNode)node).set("suggestions",json.valueToTree(proposals));return encode(node);
    }
    /** 未接入直接编辑模式时拒绝新文档命令，避免跳过存量兼容转换。 */
    private void conversation(LifecycleStateEntity s){if(!Boolean.TRUE.equals(s.directPrd))throw conflict("请重新打开会话以编辑 PRD");}
    /** 直接 PRD 的确认指针控制编辑状态，已到达的原型阶段不阻止开放草稿继续保存。 */
    private void conversationWritable(RequirementVersionEntity v,LifecycleStateEntity s) {
        conversation(s);
        if(s.prdJson==null||!List.of("PRD","PROTOTYPE").contains(v.stage))throw conflict("当前文档尚未开放 PRD 编辑");
        if(s.prdBaselineId!=null)throw conflict("PRD 已定稿，请先继续编辑");
    }
    /** 建议独立解码，不混入正式 PRD。 */
    private List<WorkbenchContracts.Proposal> proposals(LifecycleStateEntity s) {
        return s.proposalsJson==null?List.of():List.of(decode(s.proposalsJson,WorkbenchContracts.Proposal[].class));
    }
    /** 保存评审时撤销新增和正文变更的确认，接受建议只产生待确认条目。 */
    public Detail saveReview(String id,SaveReview command){
        validate(command);return locked(id,true,v->{stage(v,"REVIEW");var s=state(v);revision(s.revision,command.expectedRevision());
            Review old=decode(s.reviewJson,Review.class);Review next=normalizeReview(old,command.review(),!s.scopeFingerprint.equals(fingerprint(v)));
            s.reviewJson=encode(next);s.scopeFingerprint=fingerprint(v);s.reviewRevision++;s.revision++;s.updatedAt=now();repository.update(s);
            history(s,"REVIEW",s.reviewRevision,s.reviewJson,null,"manual",null);return detail(v,s);
        });
    }
    /** 确认已保存评审，基线与 PRD 阶段推进在同一事务。 */
    public Detail confirmReview(String id,Confirm command){return confirm(id,command,"REVIEW");}
    /** 统一入口一次确认当前全部需求；未采纳建议明确留待后续，不纳入本期范围。 */
    public Detail confirmCollected(String id, Confirm command) {
        validate(command);
        return locked(id, true, v -> {
            var previous = repository.confirmation(id, command.requestId());
            if (previous != null) {
                if (!"REVIEW".equals(previous.kind) || previous.sourceRevision != command.sourceRevision() + 1) throw conflict("确认标识已用于另一份产物");
                return detail(v, state(v));
            }
            var current = get(id);
            revision(current.revision(), command.expectedRevision());
            revision(current.reviewRevision(), command.sourceRevision());
            var review = current.review();
            var accepted = new Review(review.requirements().stream().map(r -> new Requirement(r.id(), r.title(), r.description(), r.acceptance(), true, r.sourceSuggestionId())).toList(),
                    review.questions(), review.suggestions().stream().map(s -> "PENDING".equals(s.decision())
                    ? new Suggestion(s.id(), s.text(), "DEFERRED", "确认本期需求时未采纳，留待后续讨论") : s).toList());
            var saved = saveReview(id, new SaveReview(current.revision(), accepted));
            return confirmReview(id, new Confirm(saved.revision(), saved.reviewRevision(), command.requestId()));
        });
    }
    /** PRD 继续编辑解除当前确认；直接会话保留原型里程碑，旧流程仍回到 PRD 阶段。 */
    public Detail resumePrd(String id, Reopen command) {
        validate(command);
        return locked(id, true, v -> {
            var s = state(v); revision(s.revision, command.expectedRevision()); revision(v.revision, command.expectedVersionRevision());
            stage(v, "PROTOTYPE");
            if(s.prdBaselineId==null)throw conflict("PRD 已开放编辑，请继续修改当前草稿");
            if (repository.running(id) || workspace.running(v.projectId, null)) throw conflict("请等待模型任务结束");
            history(s, "EVENT", ++s.revision, encode(Map.of("reason", command.reason(), "fromStage", v.stage)), s.prdBaselineId, "manual", null);
            s.prdBaselineId = null; s.updatedAt = now(); repository.update(s);
            if(!Boolean.TRUE.equals(s.directPrd))v.stage = "PRD";
            touchVersion(v);
            return detail(v, s);
        });
    }
    /** 确认已保存 PRD，记录原型阶段里程碑，不结束交付。 */
    public Detail confirmPrd(String id,Confirm command){return confirm(id,command,"PRD");}
    /** 幂等确认只接受原产物种类与来源编号，重试不再次推进。 */
    private Detail confirm(String id,Confirm command,String kind){
        validate(command);return locked(id,true,v->{var s=state(v);var previous=repository.confirmation(id,command.requestId());
            if(previous!=null){if(!previous.kind.equals(kind)||previous.sourceRevision!=command.sourceRevision())throw conflict("确认标识已用于另一份产物");return detail(v,s);}
            revision(s.revision,command.expectedRevision());
            if("PRD".equals(kind)&&Boolean.TRUE.equals(s.directPrd))conversationWritable(v,s);else stage(v,kind);
            long source="REVIEW".equals(kind)?s.reviewRevision:s.prdRevision;revision(source,command.sourceRevision());
            List<String> blockers="REVIEW".equals(kind)?reviewBlockers(v,s):prdBlockers(v,s);
            if(!blockers.isEmpty())throw conflict("尚不能确认："+String.join("；",blockers));
            if(repository.running(id)||workspace.running(v.projectId,null))throw conflict("请等待项目中的模型任务结束后确认基线");
            var b=new LifecycleBaselineEntity();b.id=id();b.versionId=id;b.kind=kind;b.sourceRevision=source;b.requestId=command.requestId();
            b.contentJson="REVIEW".equals(kind)?s.reviewJson:s.prdJson;b.sourceBaselineId="REVIEW".equals(kind)?null:s.prdSourceBaselineId;
            b.scopeFingerprint=fingerprint(v);b.label=v.label;b.goal=v.goal;b.scope=v.content;
            if(Boolean.TRUE.equals(s.directPrd)&&"PRD".equals(kind)){var prd=decode(s.prdJson,Prd.class);b.goal=prd.objectives();b.scope=prd.scope();}
            b.mode="REVIEW".equals(kind)?"manual":s.prdMode;b.model="REVIEW".equals(kind)?null:s.prdModel;b.createdAt=now();repository.insert(b);
            if("REVIEW".equals(kind)){s.reviewBaselineId=b.id;v.stage="PRD";}else{s.prdBaselineId=b.id;v.stage="PROTOTYPE";}
            s.revision++;s.updatedAt=b.createdAt;repository.update(s);touchVersion(v);return detail(v,s);
        });
    }
    /** 人工重新评审清除当前确认指针，旧产物及基线仍保存；原因记录不可变事件。 */
    public Detail reopen(String id,Reopen command){
        validate(command);return locked(id,true,v->{var s=state(v);revision(s.revision,command.expectedRevision());revision(v.revision,command.expectedVersionRevision());
            if(Boolean.TRUE.equals(s.directPrd))throw conflict("请直接在 PRD 中修改需求，已定稿文档可继续编辑");
            if("REVIEW".equals(v.stage))throw conflict("当前已在需求评审阶段");
            if(repository.running(id)||workspace.running(v.projectId,null))throw conflict("请等待项目模型任务结束后重新评审");
            String previous=s.reviewBaselineId;s.reviewBaselineId=null;s.prdBaselineId=null;s.revision++;s.updatedAt=now();
            history(s,"EVENT",s.revision,encode(Map.of("reason",command.reason().strip(),"fromStage",v.stage)),previous,"manual",null);
            repository.update(s);v.stage="REVIEW";touchVersion(v);return detail(v,s);
        });
    }
    /** PRD 保存明确采用有效需求基线；候选过期时拒绝，编辑不会自动确认。 */
    public Detail savePrd(String id,SavePrd command){
        return savePrd(id, command, null, null);
    }
    /** 统一会话任务在相同锁下自动落稿，并保留实际生成来源。 */
    public Detail saveWorkbenchPrd(String id, SavePrd command, String mode, String model) {
        return savePrd(id, command, mode, model);
    }
    /** 手工编辑沿用既有来源标识，避免修改模板后丢失联调来源。 */
    private Detail savePrd(String id, SavePrd command, String sourceMode, String sourceModel) {
        validate(command);
        try{PrdDocuments.validateStructure(command.prd(),templates.forDocument(command.prd()));}catch(IllegalArgumentException e){throw invalid(e.getMessage());}
        return locked(id,true,v->{stage(v,"PRD");var s=state(v);revision(s.revision,command.expectedRevision());
            templates.validateBinding(s.prdJson==null?null:decode(s.prdJson,Prd.class),command.prd());
            currentBaseline(v,s,command.sourceBaselineId());String mode=sourceMode==null?(s.prdMode==null?"manual":s.prdMode):sourceMode,model=sourceMode==null?s.prdModel:sourceModel;
            if(command.sourceRunId()!=null&&!command.sourceRunId().isBlank()){
                var r=requireRun(command.sourceRunId());
                if(!r.versionId.equals(id)||!r.baselineId.equals(command.sourceBaselineId())||!"SUCCEEDED".equals(r.status))throw conflict("候选 PRD 来源不属于当前版本基线");
                revision(s.prdRevision,r.prdRevision);mode=r.mode;model=r.model;
            }
            coverage(command.prd(),baseline(command.sourceBaselineId()).review());
            s.prdJson=encode(command.prd());s.prdSourceBaselineId=command.sourceBaselineId();s.prdBaselineId=null;s.prdMode=mode;s.prdModel=model;
            s.prdRevision++;s.revision++;s.updatedAt=now();repository.update(s);
            history(s,"PRD",s.prdRevision,s.prdJson,s.prdSourceBaselineId,mode,model);return detail(v,s);
        });
    }
    /** 显式生成候选，固定上游和 PRD 编号，相同命令重试不重复调用。 */
    public Run generate(String id,GeneratePrd command){
        validate(command);var result=locked(id,true,v->{var s=state(v);var old=repository.requestRun(id,command.requestId());
            if(old!=null){if(!old.baselineId.equals(command.baselineId()))throw conflict("请求标识已用于另一基线");return new Submission(old,false);}
            stage(v,"PRD");revision(s.revision,command.expectedRevision());currentBaseline(v,s,command.baselineId());
            if(repository.runningProject(v.projectId)||workspace.running(v.projectId,null))throw conflict("项目正在执行上一份任务");
            var r=new PrdRunEntity();r.id=id();r.versionId=id;r.baselineId=s.reviewBaselineId;r.prdRevision=s.prdRevision;r.requestId=command.requestId();
            r.status="QUEUED";r.createdAt=now();r.updatedAt=r.createdAt;repository.insert(r);return new Submission(r,true);
        });
        if(result.created())workers.submit(()->execute(result.run().id));return run(result.run());
    }
    /** 任务读取只返回安全状态，不返回凭证或输入上下文。 */ public Run getRun(String id){return run(requireRun(id));}
    /** 校验任务所属版本，禁止跨版本拼接运行标识。 */ public Run getRun(String version,String id){var r=requireRun(id);if(!version.equals(r.versionId))throw invalid("任务不属于当前需求版本");return run(r);}
    /** 已确认基线必须属于请求版本，防止跨版本伪造来源。 */
    public Baseline getBaseline(String version,String id){version(version);var b=requireBaseline(id);if(!b.versionId.equals(version))throw invalid("基线不属于当前需求版本");return view(b);}
    /** 不可变基线分页。 */
    public WorkspaceViews.Slice<Baseline> baselines(String id,long page){version(id);var p=repository.baselines(id,page(page));return new WorkspaceViews.Slice<>(p.getRecords().stream().map(this::view).toList(),p.getCurrent()<p.getPages(),p.getCurrent());}
    /** 不可变评审、PRD 或事件历史分页，不恢复当前状态。 */
    public WorkspaceViews.Slice<History> history(String id,String kind,long page){
        version(id);if(!List.of("REVIEW","PRD","EVENT").contains(kind))throw invalid("产物类型无效");var p=repository.history(id,kind,page(page));
        return new WorkspaceViews.Slice<>(p.getRecords().stream().map(r->new History(r.kind,r.revision,r.contentJson,r.sourceBaselineId,r.mode,r.model,r.createdAt)).toList(),p.getCurrent()<p.getPages(),p.getCurrent());
    }
    /** 服务重启只中断遗留任务，不自动再次调用供应商。 */
    @EventListener(ApplicationReadyEvent.class) public void interruptPreviousRuns(){
        for(var old:repository.unfinished())locked(old.versionId,false,v->{var r=requireRun(old.id);if(running(r.status)){r.status="INTERRUPTED";r.errorMessage="服务重启，PRD 整理已中断；请明确重新生成";r.updatedAt=now();repository.update(r);}return null;});
    }
    /** 结束单实例执行器。 */ @PreDestroy public void close(){workers.shutdownNow();}
    /** 模型调用不占数据库锁，过期结果仍可查看但不能应用。 */
    private void execute(String id){
        long startedAt=System.nanoTime();
        var context=new TaskFailureDiagnostics.Context("PRD",id,null,null,null,null);
        try{
            var initial=requireRun(id);
            context=new TaskFailureDiagnostics.Context("PRD",id,null,null,initial.versionId,initial.baselineId);
            var snapshot=runtime.snapshot();boolean started=locked(initial.versionId,false,v->{var r=requireRun(id);if(!"QUEUED".equals(r.status))return false;
                r.status="RUNNING";r.mode=snapshot.mode();r.model=snapshot.model();r.updatedAt=now();repository.update(r);return true;});
            if(!started)return;
            TaskFailureDiagnostics.started(context);
            Baseline source=baseline(initial.baselineId);Prd result=workflow.execute(source,snapshot);validate(result);coverage(result,source.review());
            if(!sectionsComplete(result))throw ModelInvocationException.invalidOutput();
            if(finish(id,encode(result),null))TaskFailureDiagnostics.succeeded(context,startedAt);
        }catch(RuntimeException e){
            String diagnosis=TaskFailureDiagnostics.report(context,startedAt,e);
            // 数据库故障可能阻止终态保存，也必须留下诊断，不能被执行器 Future 静默吞掉。
            try{finish(id,null,"PRD 整理失败，"+diagnosis+"；请明确重新生成，不会自动回退到模板");}
            catch(RuntimeException storage){TaskFailureDiagnostics.report(context,startedAt,storage);}
        }
    }
    /** 迟到结果不能覆盖重启中断状态。 */
    private boolean finish(String id,String result,String error){var initial=requireRun(id);return locked(initial.versionId,false,v->{var r=requireRun(id);if(!running(r.status))return false;
        r.status=error==null?"SUCCEEDED":"FAILED";r.resultJson=result;r.errorMessage=error;r.updatedAt=now();repository.update(r);return true;});}
    /** 初始评审只复制范围事实，不自动确认；仅在项目锁内创建一次。 */
    private LifecycleStateEntity state(RequirementVersionEntity v){
        var s=repository.state(v.id);if(s!=null)return s;
        s=new LifecycleStateEntity();s.directPrd=false;s.proposalsJson="[]";s.versionId=v.id;s.revision=1L;s.reviewRevision=1L;s.prdRevision=0L;s.scopeFingerprint=fingerprint(v);s.updatedAt=now();
        List<Requirement> initial=v.content.isBlank()||v.content.length()>12000?List.of():List.of(new Requirement(id(),"本期范围",v.content,"",false,null));
        s.reviewJson=encode(new Review(initial,List.of(),List.of()));repository.insert(s);history(s,"REVIEW",1,s.reviewJson,null,"manual",null);return s;
    }
    /** 校验建议来源并保留稳定 ID；正文变更不能同一次保存仍维持已确认。 */
    private Review normalizeReview(Review old,Review requested,boolean scopeChanged){
        unique(requested.requirements().stream().map(Requirement::id).toList());unique(requested.questions().stream().map(Question::id).toList());unique(requested.suggestions().stream().map(Suggestion::id).toList());
        var requirements=new ArrayList<Requirement>();
        for(var r:requested.requirements()){
            var prior=old.requirements().stream().filter(x->x.id().equals(r.id())).findFirst().orElse(null);
            if(r.sourceSuggestionId()!=null&&(prior==null||!Objects.equals(r.sourceSuggestionId(),prior.sourceSuggestionId())))throw invalid("需求建议来源只能由后端生成");
            boolean unchanged=prior!=null&&prior.title().equals(r.title().strip())&&prior.description().equals(r.description().strip())&&prior.acceptance().equals(r.acceptance().strip());
            requirements.add(new Requirement(r.id(),r.title().strip(),r.description().strip(),r.acceptance().strip(),r.confirmed()&&unchanged&&!scopeChanged,r.sourceSuggestionId()));
        }
        for(var q:requested.questions()){
            if("RESOLVED".equals(q.status())&&q.answer().isBlank())throw invalid("已解决问题需要填写结论");
            if("DEFERRED".equals(q.status())&&q.reason().isBlank())throw invalid("延期问题需要说明理由");
        }
        for(var suggestion:requested.suggestions()){
            if(List.of("REJECTED","DEFERRED").contains(suggestion.decision())&&suggestion.reason().isBlank())throw invalid("拒绝或延期建议需要理由");
            if("ACCEPTED".equals(suggestion.decision())){
                var prior=old.suggestions().stream().filter(x->x.id().equals(suggestion.id())&&"ACCEPTED".equals(x.decision())).findFirst().orElse(null);
                if(prior!=null&&!prior.text().equals(suggestion.text()))throw invalid("已接受建议正文不可改写；请编辑来源需求或新增建议");
                if(requirements.stream().noneMatch(r->suggestion.id().equals(r.sourceSuggestionId()))){
                    if(prior!=null)throw invalid("已接受建议的来源需求不能删除");
                    requirements.add(new Requirement(id(),suggestion.text().substring(0,Math.min(120,suggestion.text().length())),suggestion.text(),"",false,suggestion.id()));
                }
            }
        }
        for(var r:requirements)if(r.sourceSuggestionId()!=null&&requested.suggestions().stream().noneMatch(s->s.id().equals(r.sourceSuggestionId())&&"ACCEPTED".equals(s.decision())))throw invalid("保留来源需求时不能撤销对应建议接受状态");
        if(requirements.size()>100)throw invalid("需求最多 100 项");
        return new Review(List.copyOf(requirements),requested.questions(),requested.suggestions());
    }
    /** 评审门槛在服务器计算，不能依赖前端禁用按钮。 */
    private List<String> reviewBlockers(RequirementVersionEntity v,LifecycleStateEntity s){
        var review=decode(s.reviewJson,Review.class);var errors=new ArrayList<String>();
        if(v.content.isBlank()||v.goal.isBlank())errors.add("交付目标和范围不能为空");
        if(!s.scopeFingerprint.equals(fingerprint(v)))errors.add("范围已变化，请重新保存评审并确认需求");
        if(review.requirements().isEmpty())errors.add("至少需要一项正式需求");
        if(review.requirements().stream().anyMatch(r->r.title().isBlank()||r.description().isBlank()))errors.add("需求名称与正文不能为空");
        if(review.requirements().stream().anyMatch(r->!r.confirmed()||r.acceptance().isBlank()))errors.add("所有需求需填写验收标准并明确确认");
        if(review.questions().stream().anyMatch(q->q.blocking()&&!"RESOLVED".equals(q.status())))errors.add("仍有未解决的阻塞问题");
        if(review.suggestions().stream().anyMatch(suggestion->"PENDING".equals(suggestion.decision())))errors.add("仍有待处理的设计建议");
        return errors;
    }
    /** PRD 门槛要求有效上游、所有章节、完整需求覆盖。 */
    private List<String> prdBlockers(RequirementVersionEntity v,LifecycleStateEntity s){
        var errors=new ArrayList<String>();
        if(!Boolean.TRUE.equals(s.directPrd)&&!reviewCurrent(v,s))errors.add("尚无有效的已确认评审基线");
        if(s.prdJson==null){errors.add("请先保存 PRD");return errors;}
        if(!Boolean.TRUE.equals(s.directPrd)&&!Objects.equals(s.prdSourceBaselineId,s.reviewBaselineId))errors.add("PRD 引用旧评审基线，需要显式同步");
        Prd prd=decode(s.prdJson,Prd.class);
        errors.addAll(PrdDocuments.blockers(prd,templates.forDocument(prd)));
        if(!prd.unresolvedIssues().isEmpty())errors.add("PRD 仍有待确认事项，请解决并更新对应章节");
        if(templatePlaceholders(prd))errors.add("PRD 模板占位章节尚未完善，请补充流程、异常与非功能边界");
        if(!Boolean.TRUE.equals(s.directPrd)&&s.reviewBaselineId!=null)try{coverage(prd,baseline(s.reviewBaselineId).review());}catch(ProjectException e){errors.add("PRD 需要覆盖基线中的全部需求");}
        return errors;
    }
    /** 精确覆盖已确认需求，重复或额外 ID 都不被接受。 */
    private void coverage(Prd p,Review review){
        unique(p.requirementIds());var expected=new HashSet<>(review.requirements().stream().map(Requirement::id).toList());
        if(!expected.equals(new HashSet<>(p.requirementIds())))throw invalid("PRD 必须且只能覆盖当前基线全部需求 ID");
    }
    /** 必要章节不能为空，待确认说明仍需人工决定能否定稿。 */
    private boolean sectionsComplete(Prd p){return List.of(p.background(),p.objectives(),p.scope(),p.workflows(),p.rules(),p.exceptions(),p.acceptance(),p.nonfunctional(),p.exclusions()).stream().noneMatch(String::isBlank);}
    /** 清空待确认清单不能把已知联调占位段落伪装为完整 PRD；不推断其他业务语义。 */
    private boolean templatePlaceholders(Prd p){return p.workflows().strip().equals("待人工补充并核实业务流程。")
            ||p.exceptions().strip().equals("待人工补充异常路径，不推断错误处理规则。")
            ||p.nonfunctional().strip().equals("待人工明确性能、安全及其他非功能边界。");}
    /** 组装公开产物，历史上游不会自动替换为最新基线。 */
    private Detail detail(RequirementVersionEntity v,LifecycleStateEntity s){
        boolean current=reviewCurrent(v,s);
        return new Detail(s.revision,s.reviewRevision,s.prdRevision,decode(s.reviewJson,Review.class),s.prdJson==null?null:decode(s.prdJson,Prd.class),
                s.reviewBaselineId==null?null:baseline(s.reviewBaselineId),s.prdBaselineId==null?null:baseline(s.prdBaselineId),s.prdSourceBaselineId,
                current,s.prdJson!=null&&(Boolean.TRUE.equals(s.directPrd)||(current&&Objects.equals(s.reviewBaselineId,s.prdSourceBaselineId))),s.prdMode,s.prdModel,Boolean.TRUE.equals(s.directPrd)?List.of():reviewBlockers(v,s),prdBlockers(v,s),repository.runs(v.id).stream().map(this::run).toList(),Boolean.TRUE.equals(s.directPrd),proposals(s));
    }
    /** 有效需求基线属于当前版本且与当前范围相同，重新评审阶段没有有效指针。 */
    private boolean reviewCurrent(RequirementVersionEntity v,LifecycleStateEntity s){return !Boolean.TRUE.equals(s.directPrd)&&s.reviewBaselineId!=null&&!"REVIEW".equals(v.stage)&&requireBaseline(s.reviewBaselineId).scopeFingerprint.equals(fingerprint(v));}
    /** 当前基线校验包含版本归属和种类。 */
    private void currentBaseline(RequirementVersionEntity v,LifecycleStateEntity s,String id){
        if(Boolean.TRUE.equals(s.directPrd))throw conflict("此会话已改为直接编辑 PRD，请刷新页面");
        var b=requireBaseline(id);if(!b.versionId.equals(v.id)||!"REVIEW".equals(b.kind)||!reviewCurrent(v,s)||!id.equals(s.reviewBaselineId))throw conflict("评审基线已失效或不属于当前版本，请重新评审并同步");
    }
    /** 公开基线解码为独立响应。 */ private Baseline baseline(String id){return view(requireBaseline(id));}
    /** 不可变基线视图没有运行上下文和凭证。 */
    private Baseline view(LifecycleBaselineEntity b){return new Baseline(b.id,b.kind,b.sourceRevision,b.sourceBaselineId,b.label,b.goal,b.scope,
        "REVIEW".equals(b.kind)?decode(b.contentJson,Review.class):null,"PRD".equals(b.kind)?decode(b.contentJson,Prd.class):null,b.mode,b.model,b.createdAt);}
    /** 任务结果仅为候选。 */ private Run run(PrdRunEntity r){return new Run(r.id,r.versionId,r.baselineId,r.prdRevision,r.status,r.mode,r.model,r.resultJson==null?null:decode(r.resultJson,Prd.class),r.errorMessage,r.createdAt);}
    /** 同一项目写入串行；读取初始化也在锁内避免双建。 */
    private <T>T locked(String id,boolean writable,Function<RequirementVersionEntity,T> action){
        var current=version(id);return transactions.execute(t->{var p=workspace.lock(current.projectId);var v=version(id);
            if(writable&&(p.archivedAt!=null||v.deletedAt!=null||v.archivedAt!=null||!"ACTIVE".equals(v.lifecycle)))throw conflict("归档或已取消版本只读");return action.apply(v);});
    }
    /** 阶段命令限定真实入口，不能通过一般保存跳过门槛。 */ private void stage(RequirementVersionEntity v,String stage){if(!v.stage.equals(stage))throw conflict("当前阶段不允许此操作，请先明确重新评审需求变更");}
    /** 递增版本锁并保存不可变范围元信息，阶段变更不改写旧记录。 */
    private void touchVersion(RequirementVersionEntity v){v.revision++;v.updatedAt=now();workspace.update(v);var h=new VersionRevisionEntity();h.id=id();h.versionId=v.id;h.revision=v.revision;h.label=v.label;h.goal=v.goal;h.content=v.content;h.createdAt=v.updatedAt;workspace.insert(h);}
    /** 当前产物每次保存对应只追加的快照。 */
    private void history(LifecycleStateEntity s,String kind,long revision,String content,String source,String mode,String model){
        var h=new LifecycleRevisionEntity();h.id=id();h.versionId=s.versionId;h.kind=kind;h.revision=revision;h.contentJson=content;h.sourceBaselineId=source;h.scopeFingerprint=s.scopeFingerprint;h.mode=mode;h.model=model;h.createdAt=now();repository.insert(h);
    }
    /** 显式对象验证也保护非 HTTP 服务调用。 */ private void validate(Object value){if(value==null||!validator.validate(value).isEmpty())throw invalid("评审或 PRD 内容不符合输入契约");}
    /** 有界结构化文本，避免响应与模型上下文无上限增长。 */
    private String encode(Object value){try{String text=json.writeValueAsString(value);if(text.length()>240000)throw invalid("产物总内容超过 240000 字符");return text;}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("产物序列化失败",e);}}
    /** 只读取服务写入的指定结构。 */ private <T>T decode(String text,Class<T> type){try{return json.readValue(text,type);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("产物数据无效",e);}}
    /** 名称变化不影响基线，目标和范围变化必须重新确认。 */
    private String fingerprint(RequirementVersionEntity v){try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((v.goal+"\u0000"+v.content).getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    /** 条目标识不允许重复，避免确认与覆盖关联歧义。 */ private void unique(List<String> ids){if(new HashSet<>(ids).size()!=ids.size())throw invalid("条目标识不能重复");}
    /** 产物与候选基准冲突保留客户端输入。 */ private void revision(long actual,long expected){if(actual!=expected)throw new ProjectException(HttpStatus.CONFLICT,"WORKSPACE_REVISION_CONFLICT","内容已改变，请保留输入并重新载入");}
    /** 读取需求版本或安全报错。 */ private RequirementVersionEntity version(String id){var v=workspace.version(id);if(v==null)throw missing("需求版本不存在");return v;}
    /** 读取安全任务或报错。 */ private PrdRunEntity requireRun(String id){var r=repository.run(id);if(r==null)throw missing("PRD 任务不存在");return r;}
    /** 读取确认基线或报错。 */ private LifecycleBaselineEntity requireBaseline(String id){var b=repository.baseline(id);if(b==null)throw missing("确认基线不存在");return b;}
    /** 标准 UTC 保存时间。 */ private OffsetDateTime now(){return OffsetDateTime.now(ZoneOffset.UTC);}
    /** 稳定业务 UUID。 */ private String id(){return UUID.randomUUID().toString();}
    /** 后端单实例运行态。 */ private boolean running(String status){return List.of("QUEUED","RUNNING").contains(status);}
    /** 分页输入范围。 */ private long page(long n){if(n<1||n>1000000)throw invalid("页码无效");return n;}
    /** 业务状态冲突。 */ private ProjectException conflict(String message){return new ProjectException(HttpStatus.CONFLICT,"LIFECYCLE_CONFLICT",message);}
    /** 输入语义不合法。 */ private ProjectException invalid(String message){return new ProjectException(HttpStatus.BAD_REQUEST,"INVALID_INPUT",message);}
    /** 安全不存在响应。 */ private ProjectException missing(String message){return new ProjectException(HttpStatus.NOT_FOUND,"WORKSPACE_NOT_FOUND",message);}
    /** 提交时决定是否启动新任务。 */ private record Submission(PrdRunEntity run,boolean created){ }
}
