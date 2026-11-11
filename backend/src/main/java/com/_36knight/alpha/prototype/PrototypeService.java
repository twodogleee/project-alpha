package com._36knight.alpha.prototype;

import com._36knight.alpha.project.ProjectException;
import com._36knight.alpha.prototype.persistence.*;
import com._36knight.alpha.workspace.*;
import com._36knight.alpha.workspace.persistence.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Validator;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com._36knight.alpha.prototype.PrototypeContracts.*;

/** 原型业务事务与执行租约；模型和文件验证在短事务外，由 P4 执行适配器驱动。@author Andersen */
@Service
public class PrototypeService {
    /** 当前工具链构建的模板修订，历史产物保留各自的模板记录。 */
    private static final int TEMPLATE_REVISION = 2;
    /** 原型聚合与不可变来源/修订。 */ private final PrototypeRepository repository;
    /** 项目根锁、通用任务与会话消息。 */ private final WorkspaceRepository workspace;
    /** PRD 正文、当前基线及旧生成任务。 */ private final LifecycleRepository lifecycle;
    /** 固定输入与保守的来源新鲜度。 */ private final PrototypeSources sources;
    /** 已封存文件必须独立重验，数据库不引用半成品。 */ private final PrototypeArtifactStore artifacts;
    /** 规范编码和命令摘要。 */ private final PrototypeJson json;
    /** 非 HTTP 调用同样遵循有界契约。 */ private final Validator validator;
    /** 可选的 P4 执行器；零实现时不创建无法执行的任务。 */ private final List<PrototypeExecutionGateway> gateways;
    /** 所有对象变更在项目根锁下串行。 */ private final TransactionTemplate transactions;
    /** 任务阶段顺序；修复回退仅在修复次数增长时允许。 */
    private static final List<String> PHASES=List.of("PREPARING","PLANNING","GENERATING","BUILDING","VERIFYING","PERSISTING");
    /** 注入业务基础，禁止从本服务调用付费模型。 */
    public PrototypeService(PrototypeRepository repository,WorkspaceRepository workspace,LifecycleRepository lifecycle,
            PrototypeSources sources,PrototypeArtifactStore artifacts,PrototypeJson json,Validator validator,
            List<PrototypeExecutionGateway> gateways,PlatformTransactionManager manager){
        this.repository=repository;this.workspace=workspace;this.lifecycle=lifecycle;this.sources=sources;this.artifacts=artifacts;
        this.json=json;this.validator=validator;this.gateways=List.copyOf(gateways);transactions=new TransactionTemplate(manager);
    }

    /** 只读查询不存在时返回 EMPTY，不初始化原型、会话或生命周期。 */
    public Detail get(String versionId){return locked(versionId,v->{readable(v);var p=repository.forVersion(v.id);
        if(p==null)return new Detail(false,null,0,"EMPTY",null,null,null,List.of(),null,List.of("请先生成一个可用原型修订"));
        var row=p.currentRevisionId==null?null:ownedRevision(p,p.currentRevisionId);var current=row==null?null:revisionView(p,row,v);
        var state=lifecycle.state(v.id);var contentBlockers=confirmationContentBlockers(row,v,state);
        var latest=p.latestConfirmationId==null?null:repository.confirmation(p.latestConfirmationId);
        boolean confirmed=latest!=null&&Objects.equals(latest.revisionId,p.currentRevisionId)&&state!=null
                &&Objects.equals(latest.prdBaselineId,state.prdBaselineId)&&contentBlockers.isEmpty();
        var blockers=new ArrayList<>(contentBlockers);
        if(!canWrite(v,null))blockers.add("项目或文档已归档、取消或删除，不能确认");
        if(workspace.running(v.projectId,null)||lifecycle.runningProject(v.projectId))blockers.add("请等待当前项目的执行任务结束");
        return new Detail(true,p.id,p.revision,current==null?"EMPTY":confirmed?"CONFIRMED":"DRAFT",p.templateId,p.templateRevision,current,
                repository.runDetails(p.id).stream().map(this::progress).toList(),confirmationView(latest),List.copyOf(blockers));});}
    /** 修订目录包含候选，按不可变序号倒序分页。 */
    public WorkspaceViews.Slice<RevisionView> revisions(String versionId,long page){return locked(versionId,v->{readable(v);page(page);var p=repository.forVersion(v.id);
        if(p==null)return new WorkspaceViews.Slice<>(List.of(),false,page);
        var rows=repository.revisions(p.id,page);return new WorkspaceViews.Slice<>(rows.getRecords().stream().map(r->revisionView(p,r,v)).toList(),rows.hasNext(),page);});}
    /** 跨文档修订 ID 不能被拼接读取。 */
    public RevisionView revision(String versionId,String revisionId){return locked(versionId,v->{readable(v);var p=requirePrototype(v.id);return revisionView(p,ownedRevision(p,revisionId),v);});}
    /** 确认分页保留当时的基线和全部范围，来源失效不会删除历史记录。 */
    public WorkspaceViews.Slice<ConfirmationView> confirmations(String versionId,long page){return locked(versionId,v->{readable(v);page(page);var p=repository.forVersion(v.id);
        if(p==null)return new WorkspaceViews.Slice<>(List.of(),false,page);
        var rows=repository.confirmations(p.id,page);return new WorkspaceViews.Slice<>(rows.getRecords().stream().map(this::confirmationView).toList(),rows.hasNext(),page);});}
    /** 通用任务轮询入口复核父文档，返回安全的原型进度，不暴露执行令牌。 */
    public WorkspaceViews.Run run(String runId){var r=requireRun(runId);return locked(r.versionId,v->{readable(v);return runView(requireRun(runId));});}
    /** 会话详情允许展示历史通用消息；被回收文档不附加原型内部信息。 */
    public RunProgress visibleProgress(String runId){var r=requireRun(runId);var v=workspace.version(r.versionId);return v==null||v.deletedAt!=null?null:progress(requireDetail(runId));}

    /** 对外只投影一期固定能力，不暴露本地目录、模型配置或模板内部白名单。 */
    public List<Template> templates(){var gateway=gateway();return List.of(new Template("zero-admin",TEMPLATE_REVISION,"Zero 管理端原型",
            List.of("LIST","FORM","DETAIL"),10,List.of("QUERY","RESET_QUERY","PAGINATE","CREATE","EDIT","DETAIL","CANCEL","RESET_DEMO"),gateway!=null&&gateway.available()));}

    /** 每个预览资源请求复核归属与回收状态；归档和取消仍允许读取已冻结修订。 */
    public boolean previewReadable(String versionId,String revisionId){
        try{return locked(versionId,v->{readable(v);ownedRevision(requirePrototype(v.id),revisionId);return true;});}
        catch(ProjectException unavailable){return false;}
    }

    /** 提交先查完整命令幂等，再检查可变状态；来源、任务与用户消息原子写入。 */
    public WorkspaceViews.Run submit(String versionId,Submit command){
        validate(command);String hash=json.hash(Map.of("versionId",versionId,"command",command));
        Submission submission=locked(versionId,v->{
            readable(v);var session=associatedSession(v,command.sessionId());var previous=workspace.requestRun(session.id,command.requestId());
            if(previous!=null){var detail=repository.runDetail(previous.id);
                if(!"PROTOTYPE".equals(previous.kind)||detail==null||!hash.equals(detail.commandHash))throw conflict("请求标识已用于不同的原型命令");
                return new Submission(runView(previous),false);
            }
            writable(v,session);var state=state(v);locks(v,state,command.expectedVersionRevision(),command.expectedLifecycleRevision(),command.expectedPrdRevision());
            var gateway=gateway();if(gateway==null||!gateway.available())throw unavailable();
            idle(v.projectId);
            if(!"zero-admin".equals(command.templateId())||command.templateRevision()!=TEMPLATE_REVISION)throw invalid("只支持固定 zero-admin@2 模板");
            var p=repository.forVersion(v.id);
            if(p==null){
                if(command.expectedPrototypeRevision()!=0||command.baseRevisionId()!=null||!"GENERATE".equals(command.operation()))throw conflict("首次生成需要空原型基准和锁值 0");
                p=new PrototypeEntity();p.id=id();p.projectId=v.projectId;p.versionId=v.id;p.revision=1L;p.nextRevisionNumber=1L;
                p.templateId=command.templateId();p.templateRevision=command.templateRevision();p.createdAt=now();p.updatedAt=p.createdAt;repository.insert(p);
            }else if(p.revision!=command.expectedPrototypeRevision()||!Objects.equals(p.currentRevisionId,command.baseRevisionId())
                    ||!p.templateId.equals(command.templateId())||(p.templateRevision!=1 && p.templateRevision!=TEMPLATE_REVISION))throw conflict("原型基准或固定模板已变化");
            // 旧聚合的下一次生成使用新模板；既有修订的模板版本与封存产物保持可追溯。
            p.templateRevision=command.templateRevision();
            if("GENERATE".equals(command.operation())&&p.currentRevisionId!=null)throw conflict("已有原型请使用编辑或同步命令");
            if(!"GENERATE".equals(command.operation())&&p.currentRevisionId==null)throw conflict("请先生成一个可用修订");
            PrototypeSourceEntity source;
            if("EDIT".equals(command.operation())){
                var base=ownedRevision(p,p.currentRevisionId);source=repository.source(base.sourceId);
                if(!"EXISTING".equals(command.source().kind())||!source.id.equals(command.source().id()))throw sourceChanged("编辑必须沿用当前原型的来源快照");
                var selection="ALL".equals(command.scope().mode())&&!command.scope().pageRefs().isEmpty()?new Scope("SELECTED",command.scope().pageRefs()):command.scope();
                var chosen=sources.scope(json.decode(source.prdJson,LifecycleContracts.Prd.class),selection);
                if(!chosen.pageRefs().equals(json.decode(source.scopeJson,Scope.class).pageRefs()))throw sourceChanged("编辑不能隐式改变原型范围，请使用同步命令");
            }else{source=sources.freeze(p.id,workspace.project(v.projectId),v,state,command.source(),command.scope());repository.insert(source);}
            target(command.target(),json.decode(source.scopeJson,Scope.class));
            if(command.retryOfRunId()!=null){var retry=requireRun(command.retryOfRunId());var prior=requireDetail(retry.id);
                if(!p.id.equals(prior.prototypeId)||!Set.of("FAILED","INTERRUPTED").contains(retry.status))throw invalid("重试来源必须是同一原型的失败或中断任务");}
            var r=new WorkspaceRunEntity();r.id=id();r.projectId=v.projectId;r.sessionId=session.id;r.versionId=v.id;r.versionRevision=v.revision;
            r.requestId=command.requestId();r.kind="PROTOTYPE";r.inputText=command.content();r.contextText=json.encode(Map.of("sourceId",source.id,"expectedProjectRevision",workspace.project(v.projectId).revision,"notice","完整输入保存在不可变来源快照"));
            r.draftRevision=state.revision;r.status="QUEUED";r.createdAt=now();r.updatedAt=r.createdAt;workspace.insert(r);
            p.revision++;p.updatedAt=now();repository.update(p);
            var detail=new PrototypeRunDetailEntity();detail.runId=r.id;detail.projectId=v.projectId;detail.versionId=v.id;detail.prototypeId=p.id;detail.sourceId=source.id;
            detail.operation=command.operation();detail.baseRevisionId=command.baseRevisionId();detail.expectedPrototypeRevision=p.revision;
            detail.expectedPrdRevision=state.prdRevision;detail.expectedLifecycleRevision=state.revision;detail.expectedVersionRevision=v.revision;
            detail.commandHash=hash;detail.targetJson=json.encode(command.target());detail.phase="PREPARING";detail.repairAttempts=0;detail.applicationStatus="PENDING";detail.retryOfRunId=command.retryOfRunId();repository.insert(detail);
            append(session,"USER",command.content(),null,null,r.id);return new Submission(runView(r),true);
        });
        if(submission.created())try{gateway().dispatch(submission.run().id());}catch(RuntimeException ignored){failQueued(submission.run().id());}
        return submission.run();
    }

    /** 领取一次执行租约；重试领取不产生第二个执行令牌。 */
    public Execution claim(String runId,String mode,String model){
        if(!Set.of("demo","synthetic","live").contains(mode)||model==null||model.isBlank()||model.length()>120)throw invalid("执行来源标识无效");
        var initial=requireRun(runId);return locked(initial.versionId,v->{var r=requireRun(runId);var d=requireDetail(runId);
            if(!"QUEUED".equals(r.status))return null;
            if(expired(r,d)){terminate(r,d,"INTERRUPTED","PROTOTYPE_TIMEOUT");return null;}
            if(!canWrite(v,associatedSession(v,r.sessionId))){terminate(r,d,"INTERRUPTED","PROTOTYPE_PARENT_UNAVAILABLE");return null;}
            r.status="RUNNING";r.mode=mode;r.model=model;r.updatedAt=now();workspace.update(r);
            d.executionToken=id();d.heartbeatAt=now();repository.update(d);var source=repository.source(d.sourceId);
            var base=d.baseRevisionId==null?null:ownedRevision(requirePrototype(v.id),d.baseRevisionId);
            return new Execution(r.id,d.executionToken,sources.view(source),source.contextJson,d.baseRevisionId,json.decode(d.targetJson,Target.class),r.inputText,
                    d.operation,base==null?null:json.tree(base.artifactJson).path("objectKey").asText(),base==null?null:json.tree(base.manifestJson));
        });
    }
    /** 只有当前租约可推进阶段，最多两次修复，计划始终作为普通有界 JSON。 */
    public boolean heartbeat(String runId,String token,String phase,int attempts,JsonNode plan){
        if(!PHASES.contains(phase)||attempts<0||attempts>2)throw invalid("原型阶段或修复次数无效");
        String encoded=plan==null?null:json.encode(plan);var initial=requireRun(runId);
        return locked(initial.versionId,v->{var r=requireRun(runId);var d=requireDetail(runId);if(!lease(r,d,token))return false;
            if(attempts<d.repairAttempts||(attempts==d.repairAttempts&&PHASES.indexOf(phase)<PHASES.indexOf(d.phase)))throw conflict("执行阶段不能无故回退");
            d.phase=phase;d.repairAttempts=attempts;
            // 普通心跳没有计划时保留已冻结计划，不能在 BUILDING/VERIFYING 阶段悄悄清空。
            if(encoded!=null)d.planJson=encoded;
            d.heartbeatAt=now();r.updatedAt=now();repository.update(d);workspace.update(r);return true;});
    }
    /** 文件验证在事务外；令牌在验证前后复核，重启后旧 Worker 无法写入。 */
    public boolean complete(String runId,String token,Completion completion){
        var initial=requireRun(runId);if(!activeLease(runId,token))return false;
        if(completion==null||completion.changeSummary()==null||completion.changeSummary().length()>4000)throw invalid("完成结果说明无效");
        var verified=artifacts.verify(completion.objectKey());
        return locked(initial.versionId,v->{var r=requireRun(runId);var d=requireDetail(runId);if(!lease(r,d,token))return false;
            if(!canWrite(v,associatedSession(v,r.sessionId))){terminate(r,d,"INTERRUPTED","PROTOTYPE_PARENT_UNAVAILABLE");return false;}
            if(!Objects.equals(r.mode,verified.artifact().path("mode").asText()))throw invalid("封存产物与任务来源模式不一致，样板或合成结果不能登记为模型生成");
            var p=requirePrototype(v.id);var source=repository.source(d.sourceId);var manifest=manifest(completion.manifest(),source,verified.artifact());
            var revision=new PrototypeRevisionEntity();revision.id=id();revision.prototypeId=p.id;revision.revisionNumber=p.nextRevisionNumber++;
            revision.parentRevisionId=d.baseRevisionId;revision.sourceId=source.id;revision.sourceRunId=r.id;revision.templateId=p.templateId;revision.templateRevision=p.templateRevision;
            revision.toolchainId=verified.toolchainId();revision.manifestJson=json.encode(manifest);revision.validationJson=json.encode(verified.validation());revision.artifactJson=json.encode(verified.artifact());
            revision.mode=r.mode;revision.model=r.model;revision.changeSummary=completion.changeSummary();revision.createdAt=now();repository.insert(revision);
            var state=state(v);
            boolean current=p.revision.equals(d.expectedPrototypeRevision)&&Objects.equals(p.currentRevisionId,d.baseRevisionId)
                    &&v.revision.equals(d.expectedVersionRevision)&&state.revision.equals(d.expectedLifecycleRevision)&&state.prdRevision.equals(d.expectedPrdRevision)
                    &&workspace.project(v.projectId).revision==json.tree(r.contextText).path("expectedProjectRevision").asLong();
            // 纯编辑可以继续引用旧来源，但必须明确显示该来源的新鲜度，不能替换来源快照。
            if(current)p.currentRevisionId=revision.id;
            p.revision++;p.updatedAt=now();repository.update(p);
            d.resultRevisionId=revision.id;d.applicationStatus=current?"APPLIED":"CONFLICT";
            succeed(r,d,current?"原型修订已保存并设为当前稿。":"生成期间文档或原型基准变化，结果已保留为候选，当前稿未改变。");return true;
        });
    }
    /** 无变化是明确终态，不制造空修订或移动当前指针。 */
    public boolean completeUnchanged(String runId,String token){var initial=requireRun(runId);return locked(initial.versionId,v->{var r=requireRun(runId);var d=requireDetail(runId);
        if(!lease(r,d,token))return false;
        if(!canWrite(v,associatedSession(v,r.sessionId))){terminate(r,d,"INTERRUPTED","PROTOTYPE_PARENT_UNAVAILABLE");return false;}
        d.applicationStatus="NOT_APPLICABLE";succeed(r,d,"本轮未修改原型，当前修订保持不变。");return true;});}
    /** 仅接受平台错误类别，不持久化异常原文、密钥或任意宿主路径。 */
    public boolean fail(String runId,String token,String errorCode){var initial=requireRun(runId);return locked(initial.versionId,v->{var r=requireRun(runId);var d=requireDetail(runId);
        if(!lease(r,d,token))return false;String code=safeCode(errorCode);terminate(r,d,"PROTOTYPE_INTERRUPTED".equals(code)?"INTERRUPTED":"FAILED",code);return true;});}
    /** 模型配置或执行环境在领取前失效时结束排队；不能覆盖已被其他执行者领取的租约。 */
    public void rejectQueued(String runId,String errorCode){var initial=requireRun(runId);locked(initial.versionId,v->{var r=requireRun(runId);
        if("QUEUED".equals(r.status))terminate(r,requireDetail(runId),"FAILED",safeCode(errorCode));return null;});}
    /** 只返回候选应用原结果；重传不重复移动指针，也不受后来锁变化影响。 */
    public Applied apply(String versionId,Apply command){
        validate(command);String hash=json.hash(Map.of("versionId",versionId,"command",command));
        ApplyPreparation preparation=locked(versionId,v->{readable(v);var p=requirePrototype(v.id);var old=repository.command(p.id,"APPLY",command.requestId());
            if(old!=null){if(!hash.equals(old.commandHash))throw conflict("请求标识已用于另一条应用命令");return new ApplyPreparation(json.decode(old.resultJson,Applied.class),null);}
            var revision=ownedRevision(p,command.targetRevisionId());return new ApplyPreparation(null,json.tree(revision.artifactJson).path("objectKey").asText());});
        if(preparation.replayed()!=null)return preparation.replayed();
        artifacts.verify(preparation.objectKey());
        return locked(versionId,v->{readable(v);var p=requirePrototype(v.id);var old=repository.command(p.id,"APPLY",command.requestId());
            if(old!=null){if(!hash.equals(old.commandHash))throw conflict("请求标识已用于另一条应用命令");return json.decode(old.resultJson,Applied.class);}
            writable(v,null);idle(v.projectId);var state=state(v);locks(v,state,command.expectedVersionRevision(),command.expectedLifecycleRevision(),command.expectedPrdRevision());
            if(p.revision!=command.expectedPrototypeRevision())throw conflict("原型聚合已变化，请核对当前稿");
            var revision=ownedRevision(p,command.targetRevisionId());var run=requireRun(command.sourceRunId());var detail=requireDetail(run.id);
            if(!p.id.equals(detail.prototypeId)||!revision.id.equals(detail.resultRevisionId)||!run.id.equals(revision.sourceRunId)||!"SUCCEEDED".equals(run.status)||!"CONFLICT".equals(detail.applicationStatus))throw conflict("目标不是该原型待应用的成功候选");
            if(!"CURRENT".equals(sources.freshness(repository.source(revision.sourceId),workspace.project(v.projectId),v,state)))throw sourceChanged("候选来源已变化，请先同步 PRD 或重新生成");
            p.currentRevisionId=revision.id;p.revision++;p.updatedAt=now();repository.update(p);detail.applicationStatus="APPLIED";repository.update(detail);
            var result=new Applied(p.id,p.revision,revision.id);var record=new PrototypeCommandEntity();record.id=id();record.prototypeId=p.id;record.kind="APPLY";record.requestId=command.requestId();record.commandHash=hash;
            record.targetRevisionId=revision.id;record.resultRevisionId=revision.id;record.resultJson=json.encode(result);record.createdAt=now();repository.insert(record);return result;
        });
    }
    /** 恢复在文件核验后追加新修订；重放原命令只返回原结果，不回滚后续当前指针。 */
    public Applied restore(String versionId,Restore command){
        validate(command);String hash=json.hash(Map.of("versionId",versionId,"command",command));
        ApplyPreparation preparation=locked(versionId,v->{readable(v);var p=requirePrototype(v.id);var old=repository.command(p.id,"RESTORE",command.requestId());
            if(old!=null){sameCommand(hash,old.commandHash,"恢复");return new ApplyPreparation(json.decode(old.resultJson,Applied.class),null);}
            var target=ownedRevision(p,command.targetRevisionId());return new ApplyPreparation(null,json.tree(target.artifactJson).path("objectKey").asText());});
        if(preparation.replayed()!=null)return preparation.replayed();
        var verified=artifacts.verify(preparation.objectKey());
        return locked(versionId,v->{readable(v);var p=requirePrototype(v.id);var old=repository.command(p.id,"RESTORE",command.requestId());
            if(old!=null){sameCommand(hash,old.commandHash,"恢复");return json.decode(old.resultJson,Applied.class);}
            writable(v,null);idle(v.projectId);var state=state(v);locks(v,state,command.expectedVersionRevision(),command.expectedLifecycleRevision(),command.expectedPrdRevision());
            if(p.revision!=command.expectedPrototypeRevision())throw conflict("原型聚合已变化，请重新核对恢复目标");
            var target=ownedRevision(p,command.targetRevisionId());verifiedRevision(target,verified);
            var restored=new PrototypeRevisionEntity();restored.id=id();restored.prototypeId=p.id;restored.revisionNumber=p.nextRevisionNumber++;
            restored.parentRevisionId=p.currentRevisionId;restored.restoredFromRevisionId=target.id;restored.sourceId=target.sourceId;
            restored.templateId=target.templateId;restored.templateRevision=target.templateRevision;restored.toolchainId=target.toolchainId;
            restored.manifestJson=target.manifestJson;restored.validationJson=target.validationJson;restored.artifactJson=target.artifactJson;
            restored.mode=target.mode;restored.model=target.model;restored.changeSummary="人工恢复历史修订 r"+target.revisionNumber+"；保留原来源及验证产物，需重新人工确认。";
            restored.createdAt=now();repository.insert(restored);
            p.currentRevisionId=restored.id;p.revision++;p.updatedAt=now();repository.update(p);
            var result=new Applied(p.id,p.revision,restored.id);var record=new PrototypeCommandEntity();record.id=id();record.prototypeId=p.id;
            record.kind="RESTORE";record.requestId=command.requestId();record.commandHash=hash;record.targetRevisionId=target.id;
            record.resultRevisionId=restored.id;record.resultJson=json.encode(result);record.createdAt=now();repository.insert(record);return result;
        });
    }
    /** 人工确认固定当前有效基线和确切范围；文件核验在事务外，提交时复核全部业务资格。 */
    public ConfirmationView confirm(String versionId,Confirm command){
        validate(command);String hash=json.hash(Map.of("versionId",versionId,"command",command));
        ConfirmationPreparation preparation=locked(versionId,v->{readable(v);var p=requirePrototype(v.id);var old=repository.confirmation(p.id,command.requestId());
            if(old!=null){sameCommand(hash,old.commandHash,"确认");return new ConfirmationPreparation(confirmationView(old),null);}
            var row=ownedRevision(p,command.revisionId());return new ConfirmationPreparation(null,json.tree(row.artifactJson).path("objectKey").asText());});
        if(preparation.replayed()!=null)return preparation.replayed();
        var verified=artifacts.verify(preparation.objectKey());
        return locked(versionId,v->{readable(v);var p=requirePrototype(v.id);var old=repository.confirmation(p.id,command.requestId());
            if(old!=null){sameCommand(hash,old.commandHash,"确认");return confirmationView(old);}
            writable(v,null);idle(v.projectId);var state=state(v);locks(v,state,command.expectedVersionRevision(),command.expectedLifecycleRevision(),command.expectedPrdRevision());
            if(p.revision!=command.expectedPrototypeRevision()||!Objects.equals(p.currentRevisionId,command.revisionId()))throw conflict("只能确认当前原型修订，请重新核对当前稿");
            var row=ownedRevision(p,command.revisionId());verifiedRevision(row,verified);
            var blockers=confirmationContentBlockers(row,v,state);
            if(!blockers.isEmpty())throw confirmationBlocked(blockers);
            var source=repository.source(row.sourceId);var scope=json.decode(source.scopeJson,Scope.class);
            if(!Objects.equals(command.prdBaselineId(),state.prdBaselineId)||!Objects.equals(command.prdBaselineId(),source.prdBaselineId))throw sourceChanged("确认基线与当前有效 PRD 定稿不一致");
            if(!scope.equals(command.scope())||!json.tree(row.manifestJson).path("scope").equals(json.node(command.scope())))throw invalid("确认范围必须等于该修订的完整冻结范围");
            var record=new PrototypeConfirmationEntity();record.id=id();record.prototypeId=p.id;record.versionId=v.id;record.revisionId=row.id;
            record.prdBaselineId=source.prdBaselineId;record.scopeJson=source.scopeJson;record.requestId=command.requestId();record.commandHash=hash;record.createdAt=now();repository.insert(record);
            p.latestConfirmationId=record.id;p.revision++;p.updatedAt=now();repository.update(p);return confirmationView(record);
        });
    }
    /** 启动时统一中断原型任务及租约，不重新调用执行器；由 WorkspaceService 的启动恢复调用。 */
    public void interruptPreviousRuns(){for(var r:workspace.unfinished())if("PROTOTYPE".equals(r.kind))locked(r.versionId,v->{var current=requireRun(r.id);
        if(Set.of("QUEUED","RUNNING").contains(current.status))terminate(current,requireDetail(r.id),"INTERRUPTED","PROTOTYPE_RESTARTED");return null;});}
    /** 定时回收失联或超总预算任务，只撤销业务写入权，不自动重试或删除文件。 */
    public void recoverExpiredRuns(){for(var r:workspace.unfinished())if("PROTOTYPE".equals(r.kind))locked(r.versionId,v->{var current=requireRun(r.id);var detail=requireDetail(r.id);
        if(Set.of("QUEUED","RUNNING").contains(current.status)&&expired(current,detail))terminate(current,detail,"INTERRUPTED","PROTOTYPE_TIMEOUT");return null;});}

    /** 返回只读验证对象身份供后续受控预览桥接使用，每次重新核对父对象与文件。 */
    public PrototypeArtifactStore.VerifiedArtifact verifiedArtifact(String versionId,String revisionId){
        var row=locked(versionId,v->{readable(v);return ownedRevision(requirePrototype(v.id),revisionId);});
        var verified=artifacts.verify(json.tree(row.artifactJson).path("objectKey").asText());verifiedRevision(row,verified);return verified;
    }
    /** 短事务内复核执行租约，过期任务不能进行后续文件读取。 */
    private boolean activeLease(String id,String token){var run=requireRun(id);return locked(run.versionId,v->lease(requireRun(id),requireDetail(id),token));}
    /** 状态与令牌同时匹配，终态无法复活。 */
    private boolean lease(WorkspaceRunEntity r,PrototypeRunDetailEntity d,String token){
        if(!"RUNNING".equals(r.status)||token==null||!token.equals(d.executionToken))return false;
        if(expired(r,d)){terminate(r,d,"INTERRUPTED","PROTOTYPE_TIMEOUT");return false;}
        return true;
    }
    /** 心跳两分钟、排队两分钟和全任务十五分钟上限均不可被续租延长。 */
    private boolean expired(WorkspaceRunEntity r,PrototypeRunDetailEntity d){var time=now();return r.createdAt.isBefore(time.minusMinutes(15))
            ||("QUEUED".equals(r.status)&&r.createdAt.isBefore(time.minusMinutes(2)))
            ||("RUNNING".equals(r.status)&&(d.heartbeatAt==null||d.heartbeatAt.isBefore(time.minusMinutes(2))));}
    /** 派发失败仍留下有界失败任务，项目不会被永久 QUEUED 占用。 */
    private void failQueued(String id){var r=requireRun(id);locked(r.versionId,v->{var current=requireRun(id);if("QUEUED".equals(current.status))terminate(current,requireDetail(id),"FAILED","PROTOTYPE_RUNNER_UNAVAILABLE");return null;});}
    /** 终态清空执行权并追加安全助手消息；不修改当前预览指针。 */
    private void terminate(WorkspaceRunEntity r,PrototypeRunDetailEntity d,String status,String code){
        r.status=status;r.errorMessage="原型任务未完成，输入与已有修订已保留；请显式重试。";r.updatedAt=now();workspace.update(r);
        d.phase="FINISHED";d.executionToken=null;d.heartbeatAt=null;d.applicationStatus="NOT_APPLICABLE";d.diagnosticJson=json.encode(Map.of("code",code));repository.update(d);
        append(workspace.session(r.sessionId),"ASSISTANT",r.errorMessage,r.mode,r.model,r.id);
    }
    /** 同一事务保存成功任务、扩展状态和助手摘要，不以模型文字声明决定应用结果。 */
    private void succeed(WorkspaceRunEntity r,PrototypeRunDetailEntity d,String text){
        String prefix="synthetic".equals(r.mode)?"【合成结果验证，未调用大模型】":"demo".equals(r.mode)?"【本地模板联调，未调用大模型】":"";
        r.status="SUCCEEDED";r.resultText=json.encode(Map.of("message",prefix+text,"revisionId",d.resultRevisionId==null?"":d.resultRevisionId,"applicationStatus",d.applicationStatus));r.updatedAt=now();workspace.update(r);
        d.phase="FINISHED";d.executionToken=null;d.heartbeatAt=null;repository.update(d);append(workspace.session(r.sessionId),"ASSISTANT",prefix+text,r.mode,r.model,r.id);
    }
    /** 业务清单不可自报文件摘要或来源范围；覆盖身份必须是当前冻结复合页面。 */
    private JsonNode manifest(JsonNode value,PrototypeSourceEntity source,JsonNode artifact){
        if(value==null||!value.isObject()||value.path("schemaVersion").asInt()!=1||!value.path("scope").equals(json.tree(source.scopeJson)))throw invalid("原型清单必须引用完整冻结范围");
        var expected=new HashSet<>(json.decode(source.scopeJson,Scope.class).pageRefs());var found=new HashSet<PageRef>();
        if(!value.path("coverage").isArray())throw invalid("原型清单缺少范围覆盖记录");
        for(var item:value.path("coverage")){var ref=new PageRef(item.path("moduleId").asText(),item.path("pageId").asText());
            if(!expected.contains(ref)||!found.add(ref)||!Set.of("IMPLEMENTED","PENDING","UNSUPPORTED").contains(item.path("status").asText()))throw invalid("原型覆盖记录身份或状态无效");}
        if(!found.equals(expected))throw invalid("原型清单必须明确列出全部选定页面");
        ObjectNode result=(ObjectNode)json.node(value);result.set("files",artifact.path("sources"));result.put("sourceId",source.id);json.encode(result);return result;
    }
    /** 确认资格来自当前业务事实及冻结证据，不接受模型自报“可确认”；普通验证局限仍由人工核对。 */
    private List<String> confirmationContentBlockers(PrototypeRevisionEntity row,RequirementVersionEntity v,LifecycleStateEntity state){
        if(row==null)return List.of("请先生成一个可用原型修订");
        var blockers=new ArrayList<String>();var source=repository.source(row.sourceId);
        if(source==null||!Objects.equals(source.prototypeId,row.prototypeId)||!Objects.equals(source.versionId,v.id))return List.of("原型来源快照不可用");
        var baseline=source.prdBaselineId==null?null:lifecycle.baseline(source.prdBaselineId);
        boolean validBaseline="BASELINE".equals(source.sourceKind)&&state!=null&&Boolean.TRUE.equals(state.directPrd)&&state.prdJson!=null
                &&baseline!=null&&v.id.equals(baseline.versionId)&&"PRD".equals(baseline.kind)
                &&Objects.equals(source.prdBaselineId,state.prdBaselineId)&&Objects.equals(baseline.sourceRevision,state.prdRevision)
                &&Objects.equals(source.prdRevision,baseline.sourceRevision)
                &&json.tree(baseline.contentJson).equals(json.tree(state.prdJson))&&json.tree(baseline.contentJson).equals(json.tree(source.prdJson));
        if(!validBaseline)blockers.add("请先将 PRD 定稿，并同步原型到当前有效 PRD 基线");
        if(!"CURRENT".equals(sources.freshness(source,workspace.project(v.projectId),v,state)))blockers.add("原型来源已变化或待复核，请先同步 PRD");
        if(!json.decode(source.prdJson,LifecycleContracts.Prd.class).unresolvedIssues().isEmpty())blockers.add("PRD 仍有未解决的待确认事项");
        var scope=json.decode(source.scopeJson,Scope.class);var expected=new HashSet<>(scope.pageRefs());var manifest=json.tree(row.manifestJson);
        if(expected.isEmpty()||expected.size()>10||expected.size()!=scope.pageRefs().size()||!Set.of("ALL","SELECTED").contains(scope.mode())
                ||!manifest.path("scope").equals(json.node(scope)))blockers.add("原型范围与冻结来源不一致");
        var covered=new HashSet<PageRef>();boolean complete=manifest.path("coverage").isArray();
        for(var item:manifest.path("coverage")){
            var ref=new PageRef(item.path("moduleId").asText(),item.path("pageId").asText());
            complete&=expected.contains(ref)&&covered.add(ref)&&"IMPLEMENTED".equals(item.path("status").asText());
        }
        if(!complete||!covered.equals(expected))blockers.add("选定范围存在待实现、不支持或缺失的页面覆盖");
        var planned=new HashSet<PageRef>();boolean plannedComplete=manifest.path("plan").path("pages").isArray();
        for(var item:manifest.path("plan").path("pages")){
            var ref=new PageRef(item.path("moduleId").asText(),item.path("pageId").asText());
            plannedComplete&=expected.contains(ref)&&planned.add(ref)&&!item.path("path").asText().isBlank()&&!item.path("component").asText().isBlank();
        }
        if(!plannedComplete||!planned.equals(expected))blockers.add("生成计划未完整列出选定页面及其实现入口");
        // gaps 的既有契约只表示待核实语义，没有可信的“已解决”标记，不能凭模型填写 blocking:false 绕过。
        if(!manifest.path("plan").path("gaps").isArray()||!manifest.path("plan").path("gaps").isEmpty()
                ||hasPending(manifest.path("assumptions"))||hasPending(manifest.path("unsupported"))||hasPending(manifest.path("pending")))
            blockers.add("生成计划仍有未解决的业务假设、缺口或不支持项");
        var validation=json.tree(row.validationJson);var passed=new HashSet<String>();boolean checksPassed="PASSED".equals(validation.path("status").asText())
                &&validation.path("cases").isArray()&&!validation.path("cases").isEmpty();
        for(var check:validation.path("cases")){
            String name=check.path("name").asText();checksPassed&=!name.isBlank()&&"PASSED".equals(check.path("status").asText())&&passed.add(name);
        }
        if(!checksPassed)blockers.add("浏览器验证记录缺失、重复或存在未通过用例");
        var required=new HashSet<String>();required.add("平台重置演示数据与重新加载");required.add("平台无运行错误及外部请求");
        expected.forEach(ref->required.add("平台路由："+ref.moduleId()+"/"+ref.pageId()));
        if(!passed.containsAll(required))blockers.add("缺少选定页面、重置演示数据或运行错误检查的通过记录");
        boolean interactions=manifest.path("cases").isArray();var names=new HashSet<String>();
        for(var check:manifest.path("cases")){
            String name=check.path("name").asText();interactions&=!name.isBlank()&&names.add(name)&&passed.contains("补充交互："+name);
        }
        if(!interactions)blockers.add("原型声明的交互用例未全部通过验证");
        return List.copyOf(blockers);
    }
    /** 兼容旧清单的待处理集合；未知非空值也保守阻止人工确认。 */
    private boolean hasPending(JsonNode node){return !node.isMissingNode()&&!node.isNull()&&!(node.isContainerNode()&&node.isEmpty())&&!(node.isTextual()&&node.asText().isBlank());}
    /** 文件身份与数据库冻结证据必须完全一致，不能将其他通过对象套在目标修订上。 */
    private void verifiedRevision(PrototypeRevisionEntity row,PrototypeArtifactStore.VerifiedArtifact verified){
        if(verified==null||!Objects.equals(json.tree(row.artifactJson).path("objectKey").asText(),verified.objectKey())
                ||!Objects.equals(row.toolchainId,verified.toolchainId())||!Objects.equals(row.mode,verified.artifact().path("mode").asText())
                ||!sameEvidence(row.artifactJson,verified.artifact())||!sameEvidence(row.validationJson,verified.validation()))
            throw new ProjectException(HttpStatus.CONFLICT,"PROTOTYPE_ARTIFACT_UNAVAILABLE","修订文件或验证记录与冻结证据不一致，请重新生成");
    }
    /** 两端统一经过 JSON 编码解析，消除 DB 往返的 IntNode/LongNode 差异，仍逐字段比较完整证据。 */
    private boolean sameEvidence(String frozen,JsonNode verified){return json.tree(frozen).equals(json.node(verified));}
    /** 幂等记录保存完整命令摘要，重放不得借旧标识改变目标、范围或任一锁。 */
    private void sameCommand(String supplied,String stored,String kind){if(!Objects.equals(supplied,stored))throw conflict("请求标识已用于不同的"+kind+"命令");}
    /** 确认失败返回可操作的业务原因，不把底层文件或内部诊断拼接进响应。 */
    private ProjectException confirmationBlocked(List<String> reasons){return new ProjectException(HttpStatus.CONFLICT,"PROTOTYPE_CONFIRMATION_BLOCKED",String.join("；",reasons));}
    /** 只投影当时冻结的修订、基线和范围，幂等摘要不返回浏览器。 */
    private ConfirmationView confirmationView(PrototypeConfirmationEntity row){return row==null?null:new ConfirmationView(row.id,row.prototypeId,row.revisionId,row.prdBaselineId,json.decode(row.scopeJson,Scope.class),row.createdAt);}
    /** 历史确认只来自此精确修订；聚合最新确认可能属于另一个修订，不能冒用。 */
    private RevisionView revisionView(PrototypeEntity p,PrototypeRevisionEntity r,RequirementVersionEntity v){var source=repository.source(r.sourceId);return new RevisionView(r.id,r.revisionNumber,r.parentRevisionId,r.sourceRunId,r.mode,r.model,r.changeSummary,json.tree(r.manifestJson),json.tree(r.validationJson),sources.view(source),sources.freshness(source,workspace.project(v.projectId),v,lifecycle.state(v.id)),Objects.equals(r.id,p.currentRevisionId),r.createdAt,r.restoredFromRevisionId,confirmationView(repository.latestConfirmationForRevision(p.id,r.id)));}
    /** 安全通用任务记录与原型进度一起返回。 */
    private WorkspaceViews.Run runView(WorkspaceRunEntity r){return new WorkspaceViews.Run(r.id,r.sessionId,r.versionId,r.versionRevision,r.kind,r.status,r.mode,r.model,r.resultText,r.errorMessage,r.draftRevision,r.createdAt,progress(requireDetail(r.id)));}
    /** 任务目录可独立恢复轮询；错误仅投影服务端已归一化代码及通用说明，不含诊断原文。 */
    private RunProgress progress(PrototypeRunDetailEntity d){var r=requireRun(d.runId);
        String code=d.diagnosticJson==null?null:json.tree(d.diagnosticJson).path("code").asText(null);
        return new RunProgress(d.prototypeId,d.sourceId,d.operation,d.phase,d.repairAttempts,d.resultRevisionId,d.applicationStatus,d.retryOfRunId,
                r.id,r.status,r.mode,r.model,r.errorMessage,code,r.createdAt);}
    /** 原型归属文档；删除时隐藏，归档和取消只保留读取能力。 */
    private void readable(RequirementVersionEntity v){if(v.deletedAt!=null||workspace.project(v.projectId)==null)throw missing();}
    /** 写入同时检查父项目、文档及发起会话，不能经服务方法绕过。 */
    private void writable(RequirementVersionEntity v,WorkSessionEntity s){if(!canWrite(v,s))throw conflict("项目、文档或会话已归档、取消或删除");}
    /** 完成回调使用同一资格判断，但失败会释放任务而不写入修订。 */
    private boolean canWrite(RequirementVersionEntity v,WorkSessionEntity s){var p=workspace.project(v.projectId);return p!=null&&p.archivedAt==null&&v.deletedAt==null&&v.archivedAt==null&&"ACTIVE".equals(v.lifecycle)&&(s==null||(s.deletedAt==null&&s.archivedAt==null&&v.id.equals(s.versionId)&&v.projectId.equals(s.projectId)));}
    /** 版本、正文和产物锁分别比较，不把其中任何一个当原型锁。 */
    private void locks(RequirementVersionEntity v,LifecycleStateEntity s,long version,long lifecycleRevision,long prd){if(v.revision!=version||s.revision!=lifecycleRevision||s.prdRevision!=prd)throw sourceChanged("PRD 或文档状态已变化，请使用当前已保存版本");}
    /** 一期只接受进入 directPrd 的已保存正文，不初始化旧流程。 */
    private LifecycleStateEntity state(RequirementVersionEntity v){var state=lifecycle.state(v.id);if(state==null||state.prdJson==null||!Boolean.TRUE.equals(state.directPrd))throw conflict("请先进入统一工作区并保存 PRD");return state;}
    /** 同项目通用任务与旧 PRD 任务共用活动限制。 */
    private void idle(String projectId){
        // 调用方已持有此项目锁，只回收同项目行，避免跨项目嵌套锁。
        for(var r:workspace.unfinished())if(projectId.equals(r.projectId)&&"PROTOTYPE".equals(r.kind)){
            var d=requireDetail(r.id);if(expired(r,d))terminate(r,d,"INTERRUPTED","PROTOTYPE_TIMEOUT");
        }
        if(workspace.running(projectId,null)||lifecycle.runningProject(projectId))throw conflict("请等待当前项目的执行任务结束");
    }
    /** 原型操作必须来自同文档已有会话。 */
    private WorkSessionEntity associatedSession(RequirementVersionEntity v,String id){var s=workspace.session(id);if(s==null||!v.id.equals(s.versionId)||!v.projectId.equals(s.projectId))throw missing();return s;}
    /** PAGE 必须位于冻结范围；整个原型不能混用页面字段。 */
    private void target(Target target,Scope scope){if("PAGE".equals(target.kind())){if(target.page()==null||!scope.pageRefs().contains(target.page()))throw invalid("讨论页面不在原型范围内");}else if(target.page()!=null||target.overlayId()!=null)throw invalid("整个原型目标不能包含页面或弹层身份");}
    /** 所有读写用项目根锁形成一致快照，读取不插入任何业务行。 */
    private <T>T locked(String versionId,Function<RequirementVersionEntity,T> action){var v=workspace.version(versionId);if(v==null)throw missing();return transactions.execute(t->{if(workspace.lock(v.projectId)==null)throw missing();var current=workspace.version(versionId);if(current==null)throw missing();return action.apply(current);});}
    /** 同项目锁内追加消息，原型不另建消息表。 */
    private void append(WorkSessionEntity s,String role,String content,String mode,String model,String runId){var m=new SessionMessageEntity();m.id=id();m.sessionId=s.id;m.sequence=++s.messageSequence;m.role=role;m.content=content;m.mode=mode;m.model=model;m.runId=runId;m.createdAt=now();workspace.insert(m);s.updatedAt=m.createdAt;workspace.update(s);}
    /** 只允许单个显式执行适配器，防止不确定选择。 */
    private PrototypeExecutionGateway gateway(){if(gateways.size()>1)throw unavailable();return gateways.isEmpty()?null:gateways.getFirst();}
    /** 校验服务入口的有限字段与嵌套结构。 */ private void validate(Object value){if(value==null||!validator.validate(value).isEmpty())throw invalid("原型命令格式或长度无效");}
    /** 指定任务必须真的是原型任务。 */ private WorkspaceRunEntity requireRun(String id){var r=workspace.run(id);if(r==null||!"PROTOTYPE".equals(r.kind))throw missing();return r;}
    /** 任务扩展必须与通用行同时存在。 */ private PrototypeRunDetailEntity requireDetail(String id){var d=repository.runDetail(id);if(d==null)throw missing();return d;}
    /** 不存在的原型不经读取自动创建。 */ private PrototypeEntity requirePrototype(String version){var p=repository.forVersion(version);if(p==null)throw missing();return p;}
    /** 修订身份必须属于当前聚合。 */ private PrototypeRevisionEntity ownedRevision(PrototypeEntity p,String id){var r=repository.revision(id);if(r==null||!p.id.equals(r.prototypeId))throw missing();return r;}
    /** 分页不接受无限或负数。 */ private void page(long page){if(page<1||page>100000)throw invalid("分页参数无效");}
    /** 失败分类来自平台白名单，异常正文不得作为分类传入。 */ private String safeCode(String code){return code!=null&&Set.of("PROTOTYPE_RUNNER_UNAVAILABLE","PROTOTYPE_FILE_REJECTED","PROTOTYPE_BUILD_FAILED","PROTOTYPE_PREVIEW_FAILED","PROTOTYPE_ARTIFACT_UNAVAILABLE","PROTOTYPE_TIMEOUT","PROTOTYPE_MODEL_UNAVAILABLE","PROTOTYPE_MODEL_FAILED","PROTOTYPE_MODEL_TIMEOUT","PROTOTYPE_MODEL_OUTPUT_INVALID","PROTOTYPE_INTERRUPTED").contains(code)?code:"PROTOTYPE_BUILD_FAILED";}
    /** 业务标识不复用文件对象键。 */ private String id(){return UUID.randomUUID().toString();}
    /** 数据库统一保存 UTC。 */ private OffsetDateTime now(){return OffsetDateTime.now(ZoneOffset.UTC);}
    /** 关联或格式错误。 */ private ProjectException invalid(String message){return new ProjectException(HttpStatus.BAD_REQUEST,"INVALID_INPUT",message);}
    /** 聚合或状态冲突。 */ private ProjectException conflict(String message){return new ProjectException(HttpStatus.CONFLICT,"PROTOTYPE_REVISION_CONFLICT",message);}
    /** 来源变更单独分类。 */ private ProjectException sourceChanged(String message){return new ProjectException(HttpStatus.CONFLICT,"PROTOTYPE_SOURCE_CHANGED",message);}
    /** 安全不存在响应不泄露关联详情。 */ private ProjectException missing(){return new ProjectException(HttpStatus.NOT_FOUND,"PROTOTYPE_NOT_FOUND","原型、来源或任务不存在或当前不可访问");}
    /** 模型、固定工具链或执行槽不可用时拒绝，不制造无法执行的任务。 */ private ProjectException unavailable(){return new ProjectException(HttpStatus.SERVICE_UNAVAILABLE,"PROTOTYPE_RUNNER_UNAVAILABLE","原型生成暂不可用，请检查真实模型配置、固定 Worker 工具链及执行槽状态");}
    /** 提交事务返回后仅首次请求派发。 */ private record Submission(WorkspaceViews.Run run,boolean created) { }
    /** 文件验证与应用事务分开，幂等重放不重复读取文件。 */ private record ApplyPreparation(Applied replayed,String objectKey) { }
    /** 确认记录与文件核验分开，已提交确认重放不依赖后来变更的锁或文件。 */ private record ConfirmationPreparation(ConfirmationView replayed,String objectKey) { }
}
