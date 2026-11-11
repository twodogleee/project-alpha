package com._36knight.alpha;

import com._36knight.alpha.project.ProjectException;
import com._36knight.alpha.prototype.*;
import com._36knight.alpha.workspace.*;
import com._36knight.alpha.workspace.LifecycleContracts.Prd;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static com._36knight.alpha.prototype.PrototypeContracts.*;
import static com._36knight.alpha.workspace.WorkspaceRequests.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** P6 恢复和人工确认的真实数据库事务；文件边界为明确 synthetic 替身，实际文件另由存储测试验收。@author Andersen */
@SpringBootTest(properties="spring.datasource.dynamic.datasource.master.url=jdbc:h2:mem:prototype-delivery-tests;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class PrototypeDeliveryIntegrationTest {
    /** 交付闭环的业务入口。 */ @Autowired private PrototypeService prototypes;
    /** 不可变记录与控制命令断言。 */ @Autowired private PrototypeRepository repository;
    /** 项目与文档生命周期入口。 */ @Autowired private WorkspaceService workspace;
    /** PRD 保存、定稿和重新打开入口。 */ @Autowired private WorkbenchService workbench;
    /** PRD 基线与生命周期持久化断言。 */ @Autowired private LifecycleRepository lifecycles;
    /** 合成执行器只手动领取，不发起模型请求。 */ @MockitoBean private PrototypeExecutionGateway gateway;
    /** 本类验证事务对文件失败的处理，不把替身报告当真实浏览器证据。 */ @MockitoBean private PrototypeArtifactStore artifacts;
    /** 与控制器一致的 JSON 编解码。 */ @Autowired private ObjectMapper json;
    /** 真实 HTTP 参数校验与安全投影。 */ @Autowired private MockMvc mvc;
    /** 每个合成对象对应固定报告，允许模拟文件核验边界的损坏与失效。 */
    private final Map<String,PrototypeArtifactStore.VerifiedArtifact> objects=new HashMap<>();

    /** 每次测试重建可执行但无模型调用的明确替身。 */
    @BeforeEach void setup(){objects.clear();when(gateway.available()).thenReturn(true);when(artifacts.verify(anyString())).thenAnswer(call->objects.get(call.getArgument(0)));}

    /** 恢复追加新修订，复制全部来源和产物证据，同时保留父链且不继承旧人工确认。 */
    @Test void restoreAppendsImmutableHistoryWithoutInheritingConfirmation(){
        var d=finalized(create());var first=publish(d,"GENERATE",'a');var confirmed=prototypes.confirm(version(d),confirmation(d));
        String frozen=repository.revision(first.id()).manifestJson;var second=publish(d,"EDIT",'b');
        clearInvocations(gateway);var result=prototypes.restore(version(d),restoration(d,first.id()));var restored=prototypes.get(version(d));
        var row=repository.revision(result.revisionId());var old=repository.revision(first.id());
        assertThat(row.revisionNumber).isEqualTo(3);assertThat(row.parentRevisionId).isEqualTo(second.id());assertThat(row.restoredFromRevisionId).isEqualTo(first.id());
        assertThat(row.sourceRunId).isNull();assertThat(row.sourceId).isEqualTo(old.sourceId);assertThat(row.artifactJson).isEqualTo(old.artifactJson);
        assertThat(row.validationJson).isEqualTo(old.validationJson);assertThat(row.manifestJson).isEqualTo(frozen);assertThat(row.mode).isEqualTo("synthetic");assertThat(row.model).isEqualTo(old.model);
        assertThat(repository.revision(first.id()).manifestJson).isEqualTo(frozen);assertThat(restored.status()).isEqualTo("DRAFT");
        assertThat(restored.latestConfirmation()).isEqualTo(confirmed);assertThat(restored.current().latestConfirmation()).isNull();
        assertThat(prototypes.revision(version(d),first.id()).latestConfirmation()).isEqualTo(confirmed);assertThat(prototypes.revision(version(d),second.id()).latestConfirmation()).isNull();
        verify(gateway,never()).dispatch(anyString());
    }

    /** 完整幂等重放返回原结果，不能因后续编辑把当前指针再移回恢复结果。 */
    @Test void restoreReplaysOriginalResultAndRejectsAnyChangedCommand(){
        var d=finalized(create());var first=publish(d,"GENERATE",'a');var command=restoration(d,first.id());
        var restored=prototypes.restore(version(d),command);var later=publish(d,"EDIT",'b');clearInvocations(artifacts);
        assertThat(prototypes.restore(version(d),command)).isEqualTo(restored);assertThat(prototypes.get(version(d)).current().id()).isEqualTo(later.id());
        verify(artifacts,never()).verify(anyString());
        for(var changed:List.of(new Restore(command.requestId(),later.id(),command.expectedPrototypeRevision(),command.expectedVersionRevision(),command.expectedLifecycleRevision(),command.expectedPrdRevision()),
                new Restore(command.requestId(),first.id(),command.expectedPrototypeRevision()+1,command.expectedVersionRevision(),command.expectedLifecycleRevision(),command.expectedPrdRevision()),
                new Restore(command.requestId(),first.id(),command.expectedPrototypeRevision(),command.expectedVersionRevision()+1,command.expectedLifecycleRevision(),command.expectedPrdRevision()),
                new Restore(command.requestId(),first.id(),command.expectedPrototypeRevision(),command.expectedVersionRevision(),command.expectedLifecycleRevision()+1,command.expectedPrdRevision()),
                new Restore(command.requestId(),first.id(),command.expectedPrototypeRevision(),command.expectedVersionRevision(),command.expectedLifecycleRevision(),command.expectedPrdRevision()+1)))
            assertThatThrownBy(()->prototypes.restore(version(d),changed)).isInstanceOf(ProjectException.class);
        assertThat(prototypes.revisions(version(d),1).items()).hasSize(3);
    }

    /** 已选子集可以独立确认，但确认命令不得缩小或把 SELECTED 偷换成 ALL。 */
    @Test void confirmationFreezesExactSelectedScopeAndDoesNotCompleteDelivery(){
        var d=finalized(create());var selected=new Scope("SELECTED",List.of(new PageRef("people","list")));
        publish(d,"GENERATE",'a',selected,ignored->{},ignored->{});var command=confirmation(d);
        var incorrect=new Confirm(UUID.randomUUID().toString(),command.revisionId(),command.prdBaselineId(),new Scope("ALL",selected.pageRefs()),command.expectedPrototypeRevision(),command.expectedVersionRevision(),command.expectedLifecycleRevision(),command.expectedPrdRevision());
        assertThatThrownBy(()->prototypes.confirm(version(d),incorrect)).isInstanceOf(ProjectException.class);
        clearInvocations(gateway);var confirmation=prototypes.confirm(version(d),command);var current=prototypes.get(version(d));
        assertThat(confirmation.scope()).isEqualTo(selected);assertThat(current.status()).isEqualTo("CONFIRMED");assertThat(current.confirmationBlockers()).isEmpty();
        assertThat(current.current().mode()).isEqualTo("synthetic");assertThat(current.current().latestConfirmation()).isEqualTo(confirmation);
        assertThat(workspace.getVersion(version(d)).stage()).isEqualTo("PROTOTYPE");assertThat(prototypes.confirmations(version(d),1).items()).containsExactly(confirmation);
        verify(gateway,never()).dispatch(anyString());
    }

    /** 确认响应丢失后的原样重放不依赖新锁或后来当前修订，异义重用必须拒绝。 */
    @Test void confirmationIdempotencySurvivesLaterEditAndChecksEntirePayload(){
        var d=finalized(create());publish(d,"GENERATE",'a');var command=confirmation(d);var first=prototypes.confirm(version(d),command);
        var later=publish(d,"EDIT",'b');clearInvocations(artifacts);assertThat(prototypes.confirm(version(d),command)).isEqualTo(first);
        verify(artifacts,never()).verify(anyString());assertThat(prototypes.get(version(d)).status()).isEqualTo("DRAFT");
        var changes=List.of(new Confirm(command.requestId(),later.id(),command.prdBaselineId(),command.scope(),command.expectedPrototypeRevision(),command.expectedVersionRevision(),command.expectedLifecycleRevision(),command.expectedPrdRevision()),
                new Confirm(command.requestId(),command.revisionId(),UUID.randomUUID().toString(),command.scope(),command.expectedPrototypeRevision(),command.expectedVersionRevision(),command.expectedLifecycleRevision(),command.expectedPrdRevision()),
                new Confirm(command.requestId(),command.revisionId(),command.prdBaselineId(),new Scope("SELECTED",List.of(new PageRef("people","list"))),command.expectedPrototypeRevision(),command.expectedVersionRevision(),command.expectedLifecycleRevision(),command.expectedPrdRevision()),
                new Confirm(command.requestId(),command.revisionId(),command.prdBaselineId(),command.scope(),command.expectedPrototypeRevision()+1,command.expectedVersionRevision(),command.expectedLifecycleRevision(),command.expectedPrdRevision()));
        changes.forEach(changed->assertThatThrownBy(()->prototypes.confirm(version(d),changed)).isInstanceOf(ProjectException.class));
        assertThat(prototypes.confirmations(version(d),1).items()).containsExactly(first);
    }

    /** 草稿来源即使之后 PRD 定稿也不能被重标来源；显式同步生成后才开放确认。 */
    @Test void draftRequiresExplicitSyncToValidBaseline(){
        var initial=create();publish(initial,"GENERATE",'a');var d=finalized(initial);
        assertThat(prototypes.get(version(d)).confirmationBlockers()).anyMatch(reason->reason.contains("定稿"));
        assertThatThrownBy(()->prototypes.confirm(version(d),confirmation(d))).isInstanceOf(ProjectException.class);
        var synchronizedRevision=publish(d,"SYNC_PRD",'b');assertThat(synchronizedRevision.source().kind()).isEqualTo("BASELINE");
        prototypes.confirm(version(d),confirmation(d));assertThat(prototypes.get(version(d)).status()).isEqualTo("CONFIRMED");
    }

    /** PRD 重新打开使已确认状态失效，历史记录保留；恢复旧基线也不能伪装为当前来源。 */
    @Test void reopenedPrdInvalidatesCurrentConfirmationAndPreservesHistory(){
        var d=finalized(create());var original=publish(d,"GENERATE",'a');var confirmation=prototypes.confirm(version(d),confirmation(d));
        var latest=workbench.get(session(d));var reopened=workbench.reopen(session(d),new LifecycleContracts.Reopen(latest.lifecycle().revision(),latest.workspace().version().revision(),"业务基线需要重新核对"));
        assertThat(prototypes.get(version(d)).status()).isEqualTo("DRAFT");assertThat(prototypes.get(version(d)).latestConfirmation()).isEqualTo(confirmation);
        var result=prototypes.restore(version(d),restoration(reopened,original.id()));var restored=prototypes.revision(version(d),result.revisionId());
        assertThat(restored.source().prdBaselineId()).isEqualTo(original.source().prdBaselineId());assertThat(restored.freshness()).isEqualTo("STALE");
        assertThat(restored.latestConfirmation()).isNull();assertThat(prototypes.confirmations(version(d),1).items()).containsExactly(confirmation);
    }

    /** 自报 PASSED、缺页覆盖、待确认语义或遗漏平台检查都不能取得人工确认资格。 */
    @Test void confirmationRequiresCoverageResolvedPlanAndEveryRequiredCheck(){
        List<Consumer<ObjectNode>> manifests=List.of(
                manifest->((ObjectNode)manifest.path("coverage").get(0)).put("status","PENDING"),
                manifest->((ObjectNode)manifest.path("coverage").get(0)).put("status","UNSUPPORTED"),
                manifest->((ObjectNode)manifest.path("plan")).putArray("gaps").addObject().put("text","业务规则未定义").put("blocking",false),
                manifest->((ObjectNode)manifest.path("plan")).putArray("pages"),
                manifest->manifest.putArray("assumptions").add("库存不足规则待确认"));
        for(var mutate:manifests){var d=finalized(create());publish(d,"GENERATE",'a',new Scope("ALL",List.of()),mutate,ignored->{});
            assertThat(prototypes.get(version(d)).confirmationBlockers()).isNotEmpty();assertThatThrownBy(()->prototypes.confirm(version(d),confirmation(d))).isInstanceOf(ProjectException.class);
            assertThat(prototypes.confirmations(version(d),1).items()).isEmpty();}
        List<Consumer<ObjectNode>> reports=List.of(report->report.putArray("cases").addObject().put("name","自报通过").put("status","PASSED"),
                report->((ObjectNode)report.path("cases").get(0)).put("status","FAILED"),report->report.putArray("cases"));
        for(var mutate:reports){var d=finalized(create());publish(d,"GENERATE",'a',new Scope("ALL",List.of()),ignored->{},mutate);
            assertThat(prototypes.get(version(d)).confirmationBlockers()).isNotEmpty();assertThatThrownBy(()->prototypes.confirm(version(d),confirmation(d))).isInstanceOf(ProjectException.class);}
    }

    /** 文件损坏、验证证据被替换均不能插入恢复/确认记录或推进聚合锁。 */
    @Test void artifactFailureAndMetadataMismatchLeaveNoBusinessWrites(){
        var d=finalized(create());var first=publish(d,"GENERATE",'a');var restore=restoration(d,first.id());var confirm=confirmation(d);long before=prototypes.get(version(d)).revision();
        when(artifacts.verify("a".repeat(64))).thenThrow(new ProjectException(HttpStatus.CONFLICT,"PROTOTYPE_ARTIFACT_UNAVAILABLE","封存文件不可用"));
        assertThatThrownBy(()->prototypes.restore(version(d),restore)).isInstanceOf(ProjectException.class);assertThatThrownBy(()->prototypes.confirm(version(d),confirm)).isInstanceOf(ProjectException.class);
        var valid=objects.get("a".repeat(64));doReturn(new PrototypeArtifactStore.VerifiedArtifact(valid.objectKey(),"changed-toolchain",valid.validation(),valid.artifact())).when(artifacts).verify("a".repeat(64));
        assertThatThrownBy(()->prototypes.restore(version(d),restore)).isInstanceOf(ProjectException.class);assertThatThrownBy(()->prototypes.confirm(version(d),confirm)).isInstanceOf(ProjectException.class);
        assertThat(prototypes.get(version(d)).revision()).isEqualTo(before);assertThat(prototypes.revisions(version(d),1).items()).hasSize(1);
        assertThat(repository.command(prototypes.get(version(d)).id(),"RESTORE",restore.requestId())).isNull();assertThat(prototypes.confirmations(version(d),1).items()).isEmpty();
    }

    /** 实际存储以 long 构造字节数，数据库 JSON 读回 int 时仍为同一证据；字段或值改变继续拒绝。 */
    @Test void artifactEvidenceSurvivesLongNodeDatabaseRoundTripWithoutWeakeningComparison() throws Exception {
        var d=finalized(create());var first=publish(d,"GENERATE",'a');var original=objects.get("a".repeat(64));
        assertThat(original.artifact().at("/sources/0/bytes").isLong()).isTrue();
        assertThat(json.readTree(repository.revision(first.id()).artifactJson).at("/sources/0/bytes").isInt()).isTrue();
        assertThat(prototypes.verifiedArtifact(version(d),first.id()).artifact().at("/sources/0/bytes").asLong()).isEqualTo(24);
        prototypes.confirm(version(d),confirmation(d));var restored=prototypes.restore(version(d),restoration(d,first.id()));
        assertThat(prototypes.verifiedArtifact(version(d),restored.revisionId()).objectKey()).isEqualTo(original.objectKey());
        for(String change:List.of("number","type","missing","extra")){
            ObjectNode changed=original.artifact().deepCopy();var file=(ObjectNode)changed.path("sources").get(0);
            switch(change){case "number"->file.put("bytes",25L);case "type"->file.put("bytes","24");case "missing"->file.remove("sha256");case "extra"->file.put("unrecorded",true);default->throw new IllegalStateException();}
            objects.put(original.objectKey(),new PrototypeArtifactStore.VerifiedArtifact(original.objectKey(),original.toolchainId(),original.validation(),changed));
            assertThatThrownBy(()->prototypes.verifiedArtifact(version(d),restored.revisionId())).isInstanceOf(ProjectException.class);
        }
        objects.put(original.objectKey(),original);
    }

    /** 交付操作重验四锁、当前修订、项目执行互斥和归属，不把旧历史当当前人工确认。 */
    @Test void deliveryRejectsStaleLocksForeignRevisionsAndActiveTasks(){
        var d=finalized(create());var first=publish(d,"GENERATE",'a');var other=finalized(create());var foreign=publish(other,"GENERATE",'b');
        assertThatThrownBy(()->prototypes.restore(version(d),restoration(d,foreign.id()))).isInstanceOf(ProjectException.class);
        var staleRestore=restoration(d,first.id());var staleConfirm=confirmation(d);prototypes.restore(version(d),restoration(d,first.id()));
        assertThatThrownBy(()->prototypes.restore(version(d),staleRestore)).isInstanceOf(ProjectException.class);assertThatThrownBy(()->prototypes.confirm(version(d),staleConfirm)).isInstanceOf(ProjectException.class);
        var task=prototypes.submit(version(d),submission(d,"EDIT",new Scope("ALL",List.of())));var blocked=prototypes.get(version(d));
        assertThat(blocked.confirmationBlockers()).anyMatch(reason->reason.contains("执行任务"));
        assertThatThrownBy(()->prototypes.restore(version(d),restoration(d,first.id()))).isInstanceOf(ProjectException.class);assertThatThrownBy(()->prototypes.confirm(version(d),confirmation(d))).isInstanceOf(ProjectException.class);
        prototypes.rejectQueued(task.id(),"PROTOTYPE_INTERRUPTED");assertThat(prototypes.get(version(d)).confirmationBlockers()).isEmpty();
    }

    /** 四种独立锁各自拒绝新命令；文件读取期间变更业务状态后，第二阶段不能提交。 */
    @Test void everyLockAndChangesDuringFileVerificationAreRechecked(){
        var d=finalized(create());var first=publish(d,"GENERATE",'a');var base=restoration(d,first.id());var confirm=confirmation(d);
        for(int index=0;index<4;index++){
            long prototype=base.expectedPrototypeRevision()+(index==0?1:0),version=base.expectedVersionRevision()+(index==1?1:0);
            long lifecycle=base.expectedLifecycleRevision()+(index==2?1:0),prd=base.expectedPrdRevision()+(index==3?1:0);
            var restore=new Restore(UUID.randomUUID().toString(),first.id(),prototype,version,lifecycle,prd);
            var confirmation=new Confirm(UUID.randomUUID().toString(),first.id(),confirm.prdBaselineId(),confirm.scope(),prototype,version,lifecycle,prd);
            assertThatThrownBy(()->prototypes.restore(version(d),restore)).isInstanceOf(ProjectException.class);
            assertThatThrownBy(()->prototypes.confirm(version(d),confirmation)).isInstanceOf(ProjectException.class);
        }
        var valid=objects.get("a".repeat(64));when(artifacts.verify("a".repeat(64))).thenAnswer(call->{
            workbench.reopen(session(d),new LifecycleContracts.Reopen(d.lifecycle().revision(),d.workspace().version().revision(),"验证读取期间来源发生变化"));return valid;
        });
        assertThatThrownBy(()->prototypes.restore(version(d),base)).isInstanceOf(ProjectException.class);
        assertThat(prototypes.revisions(version(d),1).items()).hasSize(1);assertThat(prototypes.confirmations(version(d),1).items()).isEmpty();
        assertThat(repository.command(prototypes.get(version(d)).id(),"RESTORE",base.requestId())).isNull();
    }

    /** 取消和归档只保留读取，不能借历史修订继续恢复或确认。 */
    @Test void readOnlyParentsPreserveConfirmationHistoryButRejectWrites(){
        var d=finalized(create());var first=publish(d,"GENERATE",'a');prototypes.confirm(version(d),confirmation(d));
        var version=workspace.getVersion(version(d));workspace.cancelVersion(version.id(),new CancelVersion(version.revision(),"测试交付取消后只读"));
        var current=workbench.get(session(d));assertThat(prototypes.get(version(d)).latestConfirmation()).isNotNull();
        assertThat(prototypes.get(version(d)).confirmationBlockers()).anyMatch(reason->reason.contains("取消"));
        assertThatThrownBy(()->prototypes.restore(version(d),restoration(current,first.id()))).isInstanceOf(ProjectException.class);
        assertThatThrownBy(()->prototypes.confirm(version(d),confirmation(current))).isInstanceOf(ProjectException.class);
    }

    /** HTTP 契约包含恢复来源、确认范围及阻塞理由，不泄漏幂等摘要和执行凭证。 */
    @Test void controllerExposesSafeConfirmationAndRestoreContracts() throws Exception {
        var d=finalized(create());var first=publish(d,"GENERATE",'a');String endpoint="/api/alpha/workspace/versions/"+version(d)+"/prototype";
        String body=mvc.perform(post(endpoint+"/confirm").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(confirmation(d))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revisionId").value(first.id())).andExpect(jsonPath("$.data.scope.pageRefs.length()").value(2)).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("commandHash","requestId","executionToken","contextJson");
        mvc.perform(get(endpoint+"/confirmations")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));
        mvc.perform(post(endpoint+"/restore").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(restoration(d,first.id())))).andExpect(status().isOk());
        mvc.perform(get(endpoint)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.current.restoredFromRevisionId").value(first.id())).andExpect(jsonPath("$.data.current.latestConfirmation").doesNotExist());
        mvc.perform(get(endpoint+"/confirmations?page=0")).andExpect(status().isBadRequest());
    }

    /** 两页直接 PRD 用真实保存入口构造，未调用模型。 */
    private WorkbenchContracts.Detail create(){
        var project=workspace.createProject(new NewProject("原型交付测试","稳定业务背景"));var version=workspace.createVersion(project.id(),new NewVersion("文档-"+UUID.randomUUID(),"目标","范围"));
        var d=workbench.open(workspace.createSession(project.id(),new NewSession("原型交付",version.id())).id());
        var list=new PrdStructure.Page("list","人员列表","LIST",Map.of("overview","人员维护","fields","工号、姓名","search","按姓名查询","sorting","工号升序","actions","编辑、详情","states","空数据提示","acceptance","查询显示数据"));
        var detail=new PrdStructure.Page("detail","人员详情","DETAIL",Map.of("overview","查看资料","fields","工号、姓名","actions","返回列表","states","不存在提示","acceptance","与列表数据一致"));
        var module=new PrdStructure.Module("people","人员管理",Map.of("overview","人员维护","rules","工号唯一","permissions","演示权限","acceptance","列表详情数据一致"),List.of(list,detail));
        var prd=new Prd("背景","目标","范围","流程","规则","异常","验收","非功能","排除",List.of(),List.of(),2,List.of(module));
        return workbench.save(session(d),new WorkbenchContracts.Draft(d.lifecycle().revision(),d.workspace().version().revision(),prd));
    }
    /** 使用既有 PRD 人工定稿服务建立有效基线。 */
    private WorkbenchContracts.Detail finalized(WorkbenchContracts.Detail d){return workbench.confirmPrd(session(d),new LifecycleContracts.Confirm(d.lifecycle().revision(),d.lifecycle().prdRevision(),UUID.randomUUID().toString()));}
    /** 按当前聚合和来源构造生成、编辑或同步，保留真实四锁语义。 */
    private Submit submission(WorkbenchContracts.Detail d,String operation,Scope scope){
        var current=prototypes.get(version(d));var source="EDIT".equals(operation)?new Source("EXISTING",current.current().source().id()):d.lifecycle().prdBaseline()==null?new Source("DRAFT",null):new Source("BASELINE",d.lifecycle().prdBaseline().id());
        var selected="EDIT".equals(operation)?current.current().source().scope():scope;
        return new Submit(session(d),UUID.randomUUID().toString(),operation,d.workspace().version().revision(),d.lifecycle().revision(),d.lifecycle().prdRevision(),current.revision(),current.current()==null?null:current.current().id(),"zero-admin",2,source,selected,new Target("PROTOTYPE",null,null),"执行合成测试原型操作",null);
    }
    /** 标准生成结果覆盖全部范围，合成报告不可冒充真实 Worker 执行。 */
    private RevisionView publish(WorkbenchContracts.Detail d,String operation,char key){return publish(d,operation,key,new Scope("ALL",List.of()),ignored->{},ignored->{});}
    /** 控制生成输入和文件报告以覆盖失败边界，业务持久化仍经过真实服务。 */
    private RevisionView publish(WorkbenchContracts.Detail d,String operation,char key,Scope scope,Consumer<ObjectNode> manifestChange,Consumer<ObjectNode> reportChange){
        var run=prototypes.submit(version(d),submission(d,operation,scope));var execution=prototypes.claim(run.id(),"synthetic","p6-transaction-test");assertThat(execution).isNotNull();
        ObjectNode manifest=json.createObjectNode().put("schemaVersion",1);manifest.set("scope",json.valueToTree(execution.source().scope()));
        var plan=manifest.putObject("plan");var pages=plan.putArray("pages");plan.putArray("gaps");var coverage=manifest.putArray("coverage");
        ObjectNode report=json.createObjectNode().put("status","PASSED");var checks=report.putArray("cases");
        for(var ref:execution.source().scope().pageRefs()){
            pages.addObject().put("moduleId",ref.moduleId()).put("pageId",ref.pageId()).put("path","/people/"+ref.pageId()).put("component","src/features/generated/"+ref.pageId()+".vue");
            coverage.addObject().put("moduleId",ref.moduleId()).put("pageId",ref.pageId()).put("status","IMPLEMENTED");checks.addObject().put("name","平台路由："+ref.moduleId()+"/"+ref.pageId()).put("status","PASSED");
        }
        manifest.putArray("cases").addObject().put("name","查询行为");checks.addObject().put("name","补充交互：查询行为").put("status","PASSED");
        checks.addObject().put("name","平台重置演示数据与重新加载").put("status","PASSED");checks.addObject().put("name","平台无运行错误及外部请求").put("status","PASSED");
        report.putArray("notVerified").add("业务需求语义完整性").add("全部用户路径").add("人工需求确认").add("真实模型生成");manifestChange.accept(manifest);reportChange.accept(report);
        String objectKey=String.valueOf(key).repeat(64);JsonNode artifact=json.valueToTree(Map.of("objectKey",objectKey,"mode","synthetic","generation","test-fixture","sources",List.of(Map.of("path","src/features/generated/list.vue","sha256","1".repeat(64),"bytes",24L))));
        objects.put(objectKey,new PrototypeArtifactStore.VerifiedArtifact(objectKey,"sha256:"+"2".repeat(64),report,artifact));
        assertThat(prototypes.complete(run.id(),execution.token(),new Completion(objectKey,manifest,"明确合成边界，未调用模型"))).isTrue();return prototypes.get(version(d)).current();
    }
    /** 恢复命令始终使用调用时当前原型锁和已保存文档锁。 */
    private Restore restoration(WorkbenchContracts.Detail d,String target){return new Restore(UUID.randomUUID().toString(),target,prototypes.get(version(d)).revision(),d.workspace().version().revision(),d.lifecycle().revision(),d.lifecycle().prdRevision());}
    /** 确认命令显式携带当前完整冻结范围；没有基线的测试使用合法但不存在的 ID。 */
    private Confirm confirmation(WorkbenchContracts.Detail d){var current=prototypes.get(version(d));return new Confirm(UUID.randomUUID().toString(),current.current().id(),d.lifecycle().prdBaseline()==null?UUID.randomUUID().toString():d.lifecycle().prdBaseline().id(),current.current().source().scope(),current.revision(),d.workspace().version().revision(),d.lifecycle().revision(),d.lifecycle().prdRevision());}
    /** 文档 ID 与会话 ID 分离，保证每个测试明确归属。 */ private String version(WorkbenchContracts.Detail d){return d.workspace().version().id();}
    /** 工作台服务入口引用当前文档会话。 */ private String session(WorkbenchContracts.Detail d){return d.workspace().session().id();}
}
