package com._36knight.alpha;

import com._36knight.alpha.project.ProjectException;
import com._36knight.alpha.prototype.PrototypeArtifactStore;
import com._36knight.alpha.prototype.PrototypeContracts;
import com._36knight.alpha.prototype.PrototypeExecutionGateway;
import com._36knight.alpha.prototype.PrototypeRepository;
import com._36knight.alpha.prototype.PrototypeService;
import com._36knight.alpha.workspace.LifecycleRepository;
import com._36knight.alpha.workspace.LifecycleContracts.Prd;
import com._36knight.alpha.workspace.LifecycleContracts.Confirm;
import com._36knight.alpha.workspace.PrdDocuments;
import com._36knight.alpha.workspace.PrdStructure;
import com._36knight.alpha.workspace.WorkbenchContracts;
import com._36knight.alpha.workspace.WorkbenchService;
import com._36knight.alpha.workspace.WorkspaceService;
import com._36knight.alpha.workspace.WorkspaceRepository;
import com._36knight.alpha.workspace.WorkspaceViews;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/** 原型 P3 合成执行边界的真实数据库事务；不调用模型、不运行伪造业务文件。@author Andersen */
@SpringBootTest(properties="spring.datasource.dynamic.datasource.master.url=jdbc:h2:mem:prototype-business-tests;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class PrototypePersistenceIntegrationTest {
    /** 被验收的原型业务事务。 */ @Autowired private PrototypeService prototypes;
    /** 业务持久化记录供归属及不可变断言。 */ @Autowired private PrototypeRepository repository;
    /** 既有项目、会话和文档生命周期。 */ @Autowired private WorkspaceService workspace;
    /** 直接 PRD 保存入口，用于制造真实并发修订。 */ @Autowired private WorkbenchService workbench;
    /** 检查 GET 未隐式初始化 PRD。 */ @Autowired private LifecycleRepository lifecycles;
    /** 只在超时测试中调整任务持久化时间，不等待真实十五分钟。 */ @Autowired private WorkspaceRepository workspaces;
    /** 原型测试仅手动领取及回调，不存在后台模型调用。 */ @MockitoBean private PrototypeExecutionGateway gateway;
    /** 本类专注事务；真实文件完整性由独立 ArtifactStore 测试验证。 */ @MockitoBean private PrototypeArtifactStore artifacts;
    /** 与业务服务相同的 JSON 契约。 */ @Autowired private ObjectMapper json;
    /** 经过真实 Spring Controller、校验和统一异常响应的 HTTP 契约。 */ @Autowired private MockMvc mvc;

    /** 显式启用合成提供方，返回明确标记的受信文件验收对象。 */
    @BeforeEach void setup() {
        when(gateway.available()).thenReturn(true);
        when(artifacts.verify(anyString())).thenAnswer(call -> verified(call.getArgument(0)));
    }

    /** 首次 GET 和无法执行的提交不能创建空业务聚合、消息或任务。 */
    @Test void readAndUnavailableRunnerHaveNoCreationSideEffects() {
        var project=workspace.createProject(new NewProject("只读目录","背景"));
        var untouched=workspace.createVersion(project.id(),new NewVersion("未初始化","目标","范围"));
        assertThat(prototypes.get(untouched.id()).exists()).isFalse();
        assertThat(repository.forVersion(untouched.id())).isNull();assertThat(lifecycles.state(untouched.id())).isNull();
        var document=create(project);var command=generate(document);
        when(gateway.available()).thenReturn(false);
        assertThatThrownBy(()->prototypes.submit(version(document),command)).isInstanceOfSatisfying(ProjectException.class,
                error -> assertThat(error.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(repository.forVersion(version(document))).isNull();
        assertThat(workspace.messages(session(document),null).items()).isEmpty();
        assertThat(workspace.getSession(session(document)).runs()).isEmpty();verify(gateway,never()).dispatch(anyString());
    }

    /** 幂等摘要覆盖原始输入、目标、范围与锁；来源不跟随之后的正文及背景变化。 */
    @Test void submitFreezesSourceAndDeduplicatesEntireCommand() {
        var document=create();var command=generate(document);var run=prototypes.submit(version(document),command);
        var detail=repository.runDetail(run.id());var source=repository.source(detail.sourceId);
        assertThat(run.kind()).isEqualTo("PROTOTYPE");assertThat(run.status()).isEqualTo("QUEUED");
        assertThat(source.prdJson).contains("人员资料维护");assertThat(source.contextJson).contains("最初产品背景");
        assertThat(source.scopeJson).contains("people","list");assertThat(source.contentSha256).matches("[a-f0-9]{64}");
        String frozenPrd=source.prdJson,frozenContext=source.contextJson,frozenHash=source.contentSha256;
        save(document,PrdDocuments.patch(document.lifecycle().prd(),Map.of("rules","后来修改的全局规则"),null));
        var project=workspace.getProject(document.workspace().project().id());
        workspace.editProject(project.id(),new EditProject(project.revision(),project.name(),"后来修改的背景"));
        assertThat(prototypes.submit(version(document),command).id()).isEqualTo(run.id());
        for(var changed:List.of(
                changed(command,command.content()+" ",command.target(),command.scope(),command.expectedPrdRevision()),
                changed(command,command.content(),new Target("PAGE",new PageRef("people","list"),null),command.scope(),command.expectedPrdRevision()),
                changed(command,command.content(),command.target(),new Scope("SELECTED",List.of(new PageRef("people","list"))),command.expectedPrdRevision()),
                changed(command,command.content(),command.target(),command.scope(),command.expectedPrdRevision()+1))) {
            assertThatThrownBy(()->prototypes.submit(version(document),changed)).isInstanceOf(ProjectException.class);
        }
        var stored=repository.source(detail.sourceId);
        assertThat(stored.prdJson).isEqualTo(frozenPrd);assertThat(stored.contextJson).isEqualTo(frozenContext);assertThat(stored.contentSha256).isEqualTo(frozenHash);
        assertThat(workspace.messages(session(document),null).items()).hasSize(1);verify(gateway,times(1)).dispatch(run.id());
    }

    /** 直接 PRD 的有效定稿来自正文，不能被旧文档元数据范围误判失效。 */
    @Test void baselineSourceUsesCurrentPrdFinalizationRatherThanLegacyScopeMetadata() {
        var document=create();document=save(document,PrdDocuments.patch(document.lifecycle().prd(),Map.of("objectives","正式产品目标","scope","正式人员管理范围"),null));
        document=workbench.confirmPrd(session(document),new Confirm(document.lifecycle().revision(),document.lifecycle().prdRevision(),UUID.randomUUID().toString()));
        var baseline=document.lifecycle().prdBaseline();var draft=generate(document);
        var command=new Submit(draft.sessionId(),draft.requestId(),draft.operation(),draft.expectedVersionRevision(),draft.expectedLifecycleRevision(),draft.expectedPrdRevision(),0,null,draft.templateId(),draft.templateRevision(),new Source("BASELINE",baseline.id()),draft.scope(),draft.target(),draft.content(),null);
        var run=prototypes.submit(version(document),command);var execution=claim(run.id());
        assertThat(execution.source().kind()).isEqualTo("BASELINE");assertThat(execution.source().prdBaselineId()).isEqualTo(baseline.id());
        assertThat(execution.source().prd().scope()).isEqualTo("正式人员管理范围");
        assertThat(prototypes.complete(run.id(),execution.token(),completion(execution,'a'))).isTrue();
        assertThat(prototypes.get(version(document)).current().freshness()).isEqualTo("CURRENT");
    }

    /** 完成事务同时保存修订、当前指针、终态和消息，重复或旧令牌回调没有副作用。 */
    @Test void completionPersistsOneRevisionAndExposesOnlySafeRunProgress() throws Exception {
        var document=create();var run=prototypes.submit(version(document),generate(document));var execution=claim(run.id());
        assertThat(prototypes.heartbeat(run.id(),"wrong-token","BUILDING",0,json.createObjectNode())).isFalse();
        var plan=json.createObjectNode().put("summary","固定人员列表计划");
        assertThat(prototypes.heartbeat(run.id(),execution.token(),"BUILDING",0,plan)).isTrue();
        assertThat(prototypes.heartbeat(run.id(),execution.token(),"VERIFYING",0,null)).isTrue();
        assertThat(json.readTree(repository.runDetail(run.id()).planJson)).isEqualTo(plan);
        assertThat(prototypes.complete(run.id(),execution.token(),completion(execution,'a'))).isTrue();
        var current=prototypes.get(version(document));var row=repository.runDetail(run.id());
        assertThat(current.current()).isNotNull();assertThat(current.current().revisionNumber()).isEqualTo(1);
        assertThat(current.current().mode()).isEqualTo("synthetic");assertThat(current.current().model()).isEqualTo("p3-test");
        assertThat(row.applicationStatus).isEqualTo("APPLIED");assertThat(row.resultRevisionId).isEqualTo(current.current().id());
        assertThat(row.executionToken).isNull();assertThat(workspace.getRun(run.id()).status()).isEqualTo("SUCCEEDED");
        assertThat(current.current().manifest().path("files").size()).isPositive();
        assertThat(workspace.messages(session(document),null).items()).hasSize(2);
        assertThat(prototypes.complete(run.id(),execution.token(),completion(execution,'a'))).isFalse();
        assertThat(prototypes.heartbeat(run.id(),execution.token(),"VERIFYING",0,null)).isFalse();
        assertThat(prototypes.revisions(version(document),1).items()).hasSize(1);
        String safe=json.writeValueAsString(workspace.getRun(run.id()));
        assertThat(safe).doesNotContain(execution.token(),"contextJson","executionToken","最初产品背景");
    }

    /** 文件复核失败不落半成品；显式失败也不改变上一可用稿。 */
    @Test void invalidArtifactAndFailurePreserveCurrentRevision() {
        var document=create();var first=publish(document,'a');var before=prototypes.get(version(document));
        document=workbench.get(session(document));var run=prototypes.submit(version(document),edit(document,before));var execution=claim(run.id());
        String rejected="b".repeat(64);
        when(artifacts.verify(rejected)).thenThrow(new ProjectException(HttpStatus.CONFLICT,"PROTOTYPE_ARTIFACT_UNAVAILABLE","封存产物校验失败"));
        assertThatThrownBy(()->prototypes.complete(run.id(),execution.token(),completion(execution,'b'))).isInstanceOf(ProjectException.class);
        assertThat(prototypes.get(version(document)).current().id()).isEqualTo(first);
        assertThat(repository.revisionForRun(run.id())).isNull();assertThat(repository.runDetail(run.id()).resultRevisionId).isNull();
        assertThat(prototypes.fail(run.id(),execution.token(),"PROTOTYPE_ARTIFACT_UNAVAILABLE")).isTrue();
        assertThat(workspace.getRun(run.id()).status()).isEqualTo("FAILED");
        assertThat(prototypes.get(version(document)).current().id()).isEqualTo(first);
        assertThat(prototypes.revisions(version(document),1).items()).hasSize(1);
    }

    /** 模板联调产物不能因执行器自报模式而被登记为合成结果或真实模型结果。 */
    @Test void artifactProvenanceMustMatchTheClaimedExecutionMode() {
        for(String mode:List.of("synthetic","live")) {
            var document=create();String first=publish(document,'a');var current=prototypes.get(version(document));
            document=workbench.get(session(document));var run=prototypes.submit(version(document),edit(document,current));
            var execution=prototypes.claim(run.id(),mode,"provenance-boundary-test");assertThat(execution).isNotNull();
            var verified=verified("b".repeat(64));var artifact=verified.artifact().deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode)artifact).put("mode","demo").put("generation","trusted-fixture");
            when(artifacts.verify("b".repeat(64))).thenReturn(new PrototypeArtifactStore.VerifiedArtifact(verified.objectKey(),verified.toolchainId(),verified.validation(),artifact));
            assertThatThrownBy(()->prototypes.complete(run.id(),execution.token(),completion(execution,'b'))).isInstanceOf(ProjectException.class);
            assertThat(repository.revisionForRun(run.id())).isNull();
            assertThat(prototypes.get(version(document)).current().id()).isEqualTo(first);
            assertThat(prototypes.fail(run.id(),execution.token(),"PROTOTYPE_ARTIFACT_UNAVAILABLE")).isTrue();
        }
    }

    /** 元数据改名只使自动应用锁过期，来源内容未变时允许明确应用候选。 */
    @Test void metadataConflictRetainsCandidateAndExplicitApplyIsIdempotent() {
        var document=create();var first=publish(document,'a');var before=prototypes.get(version(document));
        document=workbench.get(session(document));var run=prototypes.submit(version(document),edit(document,before));var execution=claim(run.id());
        var original=workspace.getVersion(version(document));
        workspace.saveVersion(original.id(),new EditVersion(original.revision(),"仅调整文档名称",original.goal(),original.content()));
        assertThat(prototypes.complete(run.id(),execution.token(),completion(execution,'b'))).isTrue();
        var candidate=repository.runDetail(run.id());var current=prototypes.get(version(document));
        assertThat(candidate.applicationStatus).isEqualTo("CONFLICT");assertThat(current.current().id()).isEqualTo(first);
        assertThat(prototypes.revision(version(document),candidate.resultRevisionId).freshness()).isEqualTo("CURRENT");
        document=workbench.get(session(document));var command=apply(document,current,run.id(),candidate.resultRevisionId);
        var applied=prototypes.apply(version(document),command);
        assertThat(applied.revisionId()).isEqualTo(candidate.resultRevisionId);
        assertThat(prototypes.get(version(document)).current().id()).isEqualTo(candidate.resultRevisionId);
        assertThat(repository.runDetail(run.id()).applicationStatus).isEqualTo("APPLIED");
        assertThat(prototypes.apply(version(document),command)).isEqualTo(applied);
        assertThat(prototypes.revisions(version(document),1).items()).hasSize(2);
    }

    /** PRD 正文已变的候选不能通过手工应用冒充最新来源。 */
    @Test void sourceChangeRequiresSyncAndCannotBeForcedByApply() {
        var document=create();var first=publish(document,'a');var before=prototypes.get(version(document));
        document=workbench.get(session(document));var run=prototypes.submit(version(document),edit(document,before));var execution=claim(run.id());
        document=save(document,PrdDocuments.patch(document.lifecycle().prd(),Map.of("rules","禁止重复工号"),null));
        assertThat(prototypes.complete(run.id(),execution.token(),completion(execution,'b'))).isTrue();
        var candidate=repository.runDetail(run.id());var current=prototypes.get(version(document));
        assertThat(candidate.applicationStatus).isEqualTo("CONFLICT");assertThat(current.current().id()).isEqualTo(first);
        var command=apply(document,current,run.id(),candidate.resultRevisionId);String versionId=version(document);
        assertThatThrownBy(()->prototypes.apply(versionId,command)).isInstanceOfSatisfying(ProjectException.class,
                error -> assertThat(error.code()).isEqualTo("PROTOTYPE_SOURCE_CHANGED"));
        assertThat(repository.command(current.id(),"APPLY",command.requestId())).isNull();
        assertThat(prototypes.get(versionId).current().id()).isEqualTo(first);
    }

    /** 无变化结果成功结束任务，但不制造空修订或清除旧稿。 */
    @Test void unchangedResultPreservesRevisionAndMarksNotApplicable() {
        var document=create();var first=publish(document,'a');var current=prototypes.get(version(document));
        document=workbench.get(session(document));var run=prototypes.submit(version(document),edit(document,current));var execution=claim(run.id());
        assertThat(prototypes.completeUnchanged(run.id(),execution.token())).isTrue();
        assertThat(workspace.getRun(run.id()).status()).isEqualTo("SUCCEEDED");
        assertThat(repository.runDetail(run.id()).applicationStatus).isEqualTo("NOT_APPLICABLE");
        assertThat(repository.runDetail(run.id()).resultRevisionId).isNull();
        assertThat(prototypes.get(version(document)).current().id()).isEqualTo(first);
        assertThat(prototypes.revisions(version(document),1).items()).hasSize(1);
    }

    /** 服务重启只终止任务，不自动派发；失效 Worker 令牌无法回写，显式新请求可继续。 */
    @Test void restartInvalidatesExecutionTokenAndReleasesActiveSlot() {
        var document=create();var command=generate(document);var run=prototypes.submit(version(document),command);var execution=claim(run.id());
        prototypes.interruptPreviousRuns();
        assertThat(workspace.getRun(run.id()).status()).isEqualTo("INTERRUPTED");assertThat(repository.runDetail(run.id()).executionToken).isNull();
        assertThat(prototypes.complete(run.id(),execution.token(),completion(execution,'a'))).isFalse();
        assertThat(prototypes.fail(run.id(),execution.token(),"PROTOTYPE_BUILD_FAILED")).isFalse();
        assertThat(prototypes.submit(version(document),command).id()).isEqualTo(run.id());verify(gateway,times(1)).dispatch(run.id());
        var retry=generate(workbench.get(session(document)));retry=new Submit(retry.sessionId(),retry.requestId(),retry.operation(),retry.expectedVersionRevision(),retry.expectedLifecycleRevision(),retry.expectedPrdRevision(),prototypes.get(version(document)).revision(),null,retry.templateId(),retry.templateRevision(),retry.source(),retry.scope(),retry.target(),retry.content(),run.id());
        var next=prototypes.submit(version(document),retry);
        assertThat(next.id()).isNotEqualTo(run.id());assertThat(repository.runDetail(next.id()).retryOfRunId).isEqualTo(run.id());
        assertThat(prototypes.get(version(document)).current()).isNull();
    }

    /** 同项目不同文档任务互斥，其他项目不共享执行资格或业务身份。 */
    @Test void sameProjectTasksAreSerializedAndCrossDocumentReadsAreRejected() {
        var project=workspace.createProject(new NewProject("共享项目","最初产品背景"));var first=create(project);var second=create(project);
        var run=prototypes.submit(version(first),generate(first));
        assertThatThrownBy(()->prototypes.submit(version(second),generate(second))).isInstanceOf(ProjectException.class);
        assertThat(repository.forVersion(version(second))).isNull();
        var execution=claim(run.id());assertThat(prototypes.complete(run.id(),execution.token(),completion(execution,'a'))).isTrue();
        String revisionId=prototypes.get(version(first)).current().id();
        assertThatThrownBy(()->prototypes.revision(version(second),revisionId)).isInstanceOf(ProjectException.class);
        var secondRun=prototypes.submit(version(second),generate(second));assertThat(secondRun.kind()).isEqualTo("PROTOTYPE");
    }

    /** 文档原型由多会话共享，删除其中一个会话不删除原型，已删除会话不可继续提交。 */
    @Test void sharedDocumentSurvivesSessionRecycleAndRejectsDeletedSessionWrites() {
        var document=create();String first=publish(document,'a');
        var other=workspace.createSession(document.workspace().project().id(),new NewSession("另一个会话",version(document)));var shared=workbench.open(other.id());
        assertThat(prototypes.get(version(shared)).current().id()).isEqualTo(first);
        var preview=workspace.deletionPreview(session(document));
        assertThat(preview.includesPrd()).isFalse();
        workspace.deleteSession(session(document),new SessionDeletion(preview.session().revision(),true,preview.versionRevision(),preview.documentRevision(),false));
        var stale=workbench.get(session(document));var current=prototypes.get(version(shared));
        assertThatThrownBy(()->prototypes.submit(version(stale),edit(stale,current))).isInstanceOf(ProjectException.class);
        assertThat(prototypes.get(version(shared)).current().id()).isEqualTo(first);
        var next=prototypes.submit(version(shared),edit(shared,current));assertThat(next.sessionId()).isEqualTo(other.id());
    }

    /** 文档回收只冻结访问资格；恢复后仍指向原封存修订及原来源。 */
    @Test void documentRecyclePreservesReferencesAndBlocksNewWrites() {
        var document=create();String first=publish(document,'a');var current=prototypes.get(version(document));
        assertThat(prototypes.previewReadable(version(document),first)).isTrue();
        assertThat(prototypes.previewReadable(version(create()),first)).isFalse();
        assertThat(prototypes.previewReadable("missing",first)).isFalse();
        var preview=workspace.versionDeletionPreview(version(document));
        var deleted=workspace.deleteVersion(version(document),new VersionDeletion(preview.version().revision(),true,preview.documentRevision(),preview.linkedSessions()));
        var unavailable=workbench.get(session(document));
        assertThatThrownBy(()->prototypes.submit(version(unavailable),edit(unavailable,current))).isInstanceOf(ProjectException.class);
        assertThat(repository.forVersion(version(document)).currentRevisionId).isEqualTo(first);assertThat(repository.revision(first)).isNotNull();
        assertThat(prototypes.previewReadable(version(document),first)).isFalse();
        workspace.deleteVersion(version(document),new VersionDeletion(deleted.revision(),false,null,null));
        assertThat(prototypes.get(version(document)).current().id()).isEqualTo(first);
        assertThat(prototypes.previewReadable(version(document),first)).isTrue();
    }

    /** 已冻结原型在项目/文档归档或取消后仍可读取，写入资格保持关闭。 */
    @Test void previewRemainsReadableAfterArchiveOrCancellation() {
        var document=create();String first=publish(document,'a');
        var version=workspace.getVersion(version(document));
        var canceledVersion=workspace.cancelVersion(version.id(),new CancelVersion(version.revision(),"归档前明确取消此测试交付"));
        workspace.archiveVersion(version.id(),new Archive(canceledVersion.revision(),true));
        assertThat(prototypes.previewReadable(version.id(),first)).isTrue();
        var project=workspace.getProject(version.projectId());
        workspace.archiveProject(project.id(),new Archive(project.revision(),true));
        assertThat(prototypes.previewReadable(version.id(),first)).isTrue();
        var canceled=create();String canceledRevision=publish(canceled,'b');
        var active=workspace.getVersion(version(canceled));
        workspace.cancelVersion(active.id(),new CancelVersion(active.revision(),"测试取消后保留可读证据"));
        assertThat(prototypes.previewReadable(active.id(),canceledRevision)).isTrue();
        var saved=workbench.get(session(canceled));
        assertThatThrownBy(()->prototypes.submit(active.id(),edit(saved,prototypes.get(active.id())))).isInstanceOf(ProjectException.class);
    }

    /** 项目背景参与固定输入，执行中修改背景必须保留为冲突候选而不是自动覆盖。 */
    @Test void backgroundChangeDuringExecutionPreservesPreviousPreview() {
        var document=create();String first=publish(document,'a');var before=prototypes.get(version(document));
        document=workbench.get(session(document));var run=prototypes.submit(version(document),edit(document,before));var execution=claim(run.id());
        var project=workspace.getProject(document.workspace().project().id());
        workspace.editProject(project.id(),new EditProject(project.revision(),project.name(),"修改了会影响人员数据的产品背景"));
        assertThat(prototypes.complete(run.id(),execution.token(),completion(execution,'b'))).isTrue();
        var detail=repository.runDetail(run.id());
        assertThat(detail.applicationStatus).isEqualTo("CONFLICT");assertThat(detail.resultRevisionId).isNotNull().isNotEqualTo(first);
        assertThat(prototypes.get(version(document)).current().id()).isEqualTo(first);
        assertThat(prototypes.revision(version(document),detail.resultRevisionId).freshness()).isEqualTo("STALE");
        assertThat(repository.source(detail.sourceId).contextJson).contains("最初产品背景").doesNotContain("修改了会影响人员数据的产品背景");
    }

    /** 成功回调自身也校验租约期限，不等待后台扫描才拒绝旧 Worker。 */
    @Test void expiredHeartbeatRejectsCompletionAndInvalidatesOldToken() {
        var document=create();String first=publish(document,'a');var current=prototypes.get(version(document));
        document=workbench.get(session(document));var run=prototypes.submit(version(document),edit(document,current));var execution=claim(run.id());
        var detail=repository.runDetail(run.id());detail.heartbeatAt=detail.heartbeatAt.minusMinutes(3);repository.update(detail);
        clearInvocations(artifacts);
        assertThat(prototypes.complete(run.id(),execution.token(),completion(execution,'b'))).isFalse();
        assertThat(workspace.getRun(run.id()).status()).isEqualTo("INTERRUPTED");
        assertThat(repository.runDetail(run.id()).executionToken).isNull();
        assertThat(repository.runDetail(run.id()).diagnosticJson).contains("PROTOTYPE_TIMEOUT");
        assertThat(prototypes.heartbeat(run.id(),execution.token(),"BUILDING",0,null)).isFalse();
        assertThat(prototypes.completeUnchanged(run.id(),execution.token())).isFalse();
        assertThat(prototypes.get(version(document)).current().id()).isEqualTo(first);
        verify(artifacts,never()).verify(anyString());
    }

    /** 主动回收既检查排队等待，也检查总预算；新鲜心跳不能延长十五分钟任务预算。 */
    @Test void expirySweepReleasesQueuedAndOverBudgetRunningTasks() {
        var queuedDocument=create();var queued=prototypes.submit(version(queuedDocument),generate(queuedDocument));
        var queuedRow=workspaces.run(queued.id());queuedRow.createdAt=queuedRow.createdAt.minusMinutes(3);queuedRow.updatedAt=queuedRow.updatedAt.minusMinutes(3);workspaces.update(queuedRow);
        var runningDocument=create();var running=prototypes.submit(version(runningDocument),generate(runningDocument));var execution=claim(running.id());
        var runningRow=workspaces.run(running.id());runningRow.createdAt=runningRow.createdAt.minusMinutes(16);workspaces.update(runningRow);
        var lateClaimDocument=create();var lateClaim=prototypes.submit(version(lateClaimDocument),generate(lateClaimDocument));
        var lateClaimRow=workspaces.run(lateClaim.id());lateClaimRow.createdAt=lateClaimRow.createdAt.minusMinutes(3);workspaces.update(lateClaimRow);
        assertThat(prototypes.claim(lateClaim.id(),"synthetic","late-claim-test")).isNull();
        assertThat(workspace.getRun(lateClaim.id()).status()).isEqualTo("INTERRUPTED");
        prototypes.recoverExpiredRuns();
        assertThat(workspace.getRun(queued.id()).status()).isEqualTo("INTERRUPTED");
        assertThat(workspace.getRun(running.id()).status()).isEqualTo("INTERRUPTED");
        assertThat(repository.runDetail(queued.id()).phase).isEqualTo("FINISHED");
        assertThat(repository.runDetail(running.id()).executionToken).isNull();
        assertThat(prototypes.complete(running.id(),execution.token(),completion(execution,'a'))).isFalse();
        assertThat(workspaces.running(queuedDocument.workspace().project().id(),null)).isFalse();
        assertThat(workspaces.running(runningDocument.workspace().project().id(),null)).isFalse();
        verify(gateway,times(1)).dispatch(queued.id());verify(gateway,times(1)).dispatch(running.id());
    }

    /** HTTP 公开入口遵循统一响应、202提交和503无副作用，并且不泄漏内部执行信息。 */
    @Test void httpEndpointsExposeSafeProgressAndUnavailableProviderHasNoSideEffects() throws Exception {
        var document=create();String endpoint="/api/alpha/workspace/versions/"+version(document)+"/prototype";
        mvc.perform(get(endpoint)).andExpect(status().isOk()).andExpect(jsonPath("$.data.exists").value(false));
        var command=generate(document);when(gateway.available()).thenReturn(false);
        mvc.perform(post(endpoint+"/runs").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(command)))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("PROTOTYPE_RUNNER_UNAVAILABLE"));
        assertThat(repository.forVersion(version(document))).isNull();assertThat(workspace.messages(session(document),null).items()).isEmpty();
        assertThat(workspace.getSession(session(document)).runs()).isEmpty();verify(gateway,never()).dispatch(anyString());
        when(gateway.available()).thenReturn(true);
        var accepted=mvc.perform(post(endpoint+"/runs").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(command)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.kind").value("PROTOTYPE"))
                .andExpect(jsonPath("$.data.prototype.operation").value("GENERATE")).andReturn().getResponse().getContentAsString();
        String runId=json.readTree(accepted).at("/data/id").asText();var execution=claim(runId);
        var progress=mvc.perform(get("/api/alpha/workspace/runs/{id}",runId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RUNNING")).andExpect(jsonPath("$.data.prototype.phase").value("PREPARING"))
                .andExpect(jsonPath("$.data.prototype.runId").value(runId)).andExpect(jsonPath("$.data.prototype.status").value("RUNNING"))
                .andReturn().getResponse().getContentAsString();
        assertThat(progress).doesNotContain(execution.token(),"executionToken","contextText","contextJson","最初产品背景","/Users/","/tmp/");
        assertThat(prototypes.complete(runId,execution.token(),completion(execution,'a'))).isTrue();
        mvc.perform(get(endpoint)).andExpect(status().isOk()).andExpect(jsonPath("$.data.exists").value(true))
                .andExpect(jsonPath("$.data.current.source.kind").value("DRAFT"))
                .andExpect(jsonPath("$.data.runs[0].runId").value(runId)).andExpect(jsonPath("$.data.runs[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.runs[0].mode").value("synthetic"));
        mvc.perform(get(endpoint+"/revisions")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));
        String revisionId=prototypes.get(version(document)).current().id();
        var revision=mvc.perform(get(endpoint+"/revisions/{id}",revisionId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(revisionId)).andReturn().getResponse().getContentAsString();
        assertThat(revision).doesNotContain(execution.token(),"executionToken","contextText","contextJson","/Users/","/tmp/");
    }

    /** 能力目录只公开固定能力及实时接收资格，查询不能派发任务或泄漏宿主配置。 */
    @Test void templateCatalogReportsSafeCapabilitiesAndRunnerAvailability() throws Exception {
        String body=mvc.perform(get("/api/alpha/workspace/prototype-templates"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value("zero-admin"))
                .andExpect(jsonPath("$.data[0].revision").value(2)).andExpect(jsonPath("$.data[0].maxPages").value(10))
                .andExpect(jsonPath("$.data[0].supportedPageTypes.length()").value(3))
                .andExpect(jsonPath("$.data[0].available").value(true)).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("/Users/","apiKey","artifactRoot","executionToken");
        when(gateway.available()).thenReturn(false);
        mvc.perform(get("/api/alpha/workspace/prototype-templates")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].available").value(false));
        verify(gateway,never()).dispatch(anyString());
    }

    /** 每个测试使用独立项目，背景参与冻结输入。 */
    private WorkbenchContracts.Detail create() { return create(workspace.createProject(new NewProject("原型事务测试","最初产品背景"))); }
    /** 在指定项目建立已保存的直接 PRD 文档，不进入真实模型运行。 */
    private WorkbenchContracts.Detail create(WorkspaceViews.Project project) {
        var version=workspace.createVersion(project.id(),new NewVersion("文档-"+UUID.randomUUID(),"业务目标","业务范围"));
        var document=workbench.open(workspace.createSession(project.id(),new NewSession("原型讨论",version.id())).id());
        var page=new PrdStructure.Page("list","人员列表","LIST",Map.of("overview","人员资料维护","fields","工号、姓名","search","按姓名查询","sorting","工号升序","actions","新增与编辑","states","空数据提示","acceptance","保存后列表同步"));
        var module=new PrdStructure.Module("people","人员管理",Map.of("overview","人员资料维护","rules","工号唯一","permissions","演示权限","acceptance","查询显示新增人员"),List.of(page));
        return save(document,new Prd("业务背景","业务目标","业务范围","业务流程","全局规则","异常说明","验收要求","非功能要求","排除内容",List.of(),List.of(),2,List.of(module)));
    }
    /** 同一正文保存走真实双修订入口。 */
    private WorkbenchContracts.Detail save(WorkbenchContracts.Detail document,Prd prd) {
        return workbench.save(session(document),new WorkbenchContracts.Draft(document.lifecycle().revision(),document.workspace().version().revision(),prd));
    }
    /** 构造首次完整生成命令，ALL 由业务服务展开稳定页面清单。 */
    private Submit generate(WorkbenchContracts.Detail document) {
        return new Submit(session(document),UUID.randomUUID().toString(),"GENERATE",document.workspace().version().revision(),document.lifecycle().revision(),document.lifecycle().prdRevision(),0,null,"zero-admin",2,new Source("DRAFT",null),new Scope("ALL",List.of()),new Target("PROTOTYPE",null,null),"生成管理端原型",null);
    }
    /** 编辑始终沿用当前稿的固定来源，不能偷换当前 PRD。 */
    private Submit edit(WorkbenchContracts.Detail document,PrototypeContracts.Detail current) {
        return new Submit(session(document),UUID.randomUUID().toString(),"EDIT",document.workspace().version().revision(),document.lifecycle().revision(),document.lifecycle().prdRevision(),current.revision(),current.current().id(),"zero-admin",2,new Source("EXISTING",current.current().source().id()),current.current().source().scope(),new Target("PROTOTYPE",null,null),"调整列表展示",null);
    }
    /** 保留同一幂等键，逐项更改原始语义来验证完整摘要。 */
    private Submit changed(Submit command,String content,Target target,Scope scope,long prdRevision) {
        return new Submit(command.sessionId(),command.requestId(),command.operation(),command.expectedVersionRevision(),command.expectedLifecycleRevision(),prdRevision,command.expectedPrototypeRevision(),command.baseRevisionId(),command.templateId(),command.templateRevision(),command.source(),scope,target,content,command.retryOfRunId());
    }
    /** 控制命令携带当前四种锁，旧请求重传必须返回原结果。 */
    private Apply apply(WorkbenchContracts.Detail document,PrototypeContracts.Detail current,String runId,String revisionId) {
        return new Apply(UUID.randomUUID().toString(),runId,revisionId,current.revision(),document.workspace().version().revision(),document.lifecycle().revision(),document.lifecycle().prdRevision());
    }
    /** 手工驱动受信合成执行边界，不创建任何外部模型请求。 */
    private Execution claim(String runId) { var execution=prototypes.claim(runId,"synthetic","p3-test");assertThat(execution).isNotNull();return execution; }
    /** 建立首个成功修订，供失败、冲突和生命周期测试复用。 */
    private String publish(WorkbenchContracts.Detail document,char object) {
        var run=prototypes.submit(version(document),generate(document));var execution=claim(run.id());
        assertThat(prototypes.complete(run.id(),execution.token(),completion(execution,object))).isTrue();return prototypes.get(version(document)).current().id();
    }
    /** 清单只引用冻结范围，文件摘要由模拟受信存储返回并由服务覆盖。 */
    private Completion completion(Execution execution,char object) {
        var scope=execution.source().scope();var page=scope.pageRefs().getFirst();
        JsonNode manifest=json.valueToTree(Map.of("schemaVersion",1,"scope",scope,
                "pages",List.of(Map.of("id","personnel-list","route","/personnel","entry","src/features/generated/list.vue","sourceRefs",List.of(page))),
                "coverage",scope.pageRefs().stream().map(ref->Map.of("moduleId",ref.moduleId(),"pageId",ref.pageId(),"status","IMPLEMENTED")).toList(),
                "assumptions",List.of(),"unsupported",List.of(),"files",List.of()));
        return new Completion(String.valueOf(object).repeat(64),manifest,"合成执行器校验通过；未调用模型");
    }
    /** 文件存储边界替身明确标记 synthetic，业务测试不将其当作真实浏览器证据。 */
    private PrototypeArtifactStore.VerifiedArtifact verified(String objectKey) {
        JsonNode validation=json.valueToTree(Map.of("status","PASSED","mode","synthetic","cases",List.of(Map.of("name","synthetic-transaction-fixture","status","PASSED"))));
        JsonNode artifact=json.valueToTree(Map.of("objectKey",objectKey,"mode","synthetic","generation","test-fixture","templateDigest","1".repeat(64),"sourceDigest","2".repeat(64),
                "sources",List.of(Map.of("objectKey",objectKey,"path","src/features/generated/list.vue","sha256","3".repeat(64),"bytes",24)),
                "files",List.of(Map.of("objectKey",objectKey,"path","index.html","sha256","4".repeat(64),"bytes",16))));
        return new PrototypeArtifactStore.VerifiedArtifact(objectKey,"sha256:"+"5".repeat(64),validation,artifact);
    }
    /** 文档身份与会话身份不能互换。 */
    private String version(WorkbenchContracts.Detail document) { return document.workspace().version().id(); }
    /** 当前提交命令的会话归属。 */
    private String session(WorkbenchContracts.Detail document) { return document.workspace().session().id(); }
}
