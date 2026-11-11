package com._36knight.alpha;

import com._36knight.alpha.prototype.*;
import com._36knight.alpha.workspace.*;
import com._36knight.alpha.workspace.LifecycleContracts.Prd;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static com._36knight.alpha.prototype.PrototypeContracts.*;
import static com._36knight.alpha.workspace.WorkspaceRequests.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 Graph、异步执行与 H2 事务闭环；模型和容器边界显式使用 synthetic 替身。@author Andersen */
@SpringBootTest(properties = "spring.datasource.dynamic.datasource.master.url=jdbc:h2:mem:prototype-generation;DB_CLOSE_DELAY=-1")
class PrototypeGenerationIntegrationTest {
    /** 业务命令与读取接口。 */ @Autowired private PrototypeService prototypes;
    /** 测试建立隔离业务文档。 */ @Autowired private WorkspaceService workspace;
    /** 保存真实 PRD 并制造输入并发。 */ @Autowired private WorkbenchService workbench;
    /** 直接检查不可变修订及租约。 */ @Autowired private PrototypeRepository repository;
    /** 观察执行槽释放，避免异步测试之间竞争。 */ @Autowired private PrototypeGenerationRunner runner;
    /** 同一应用 JSON 契约。 */ @Autowired private ObjectMapper json;
    /** 明确合成模型；Graph 和输出校验不替换。 */ @MockitoBean private PrototypeModelProvider models;
    /** 构建失败/成功由测试控制，真实容器另行验收。 */ @MockitoBean private PrototypeBuildService builds;
    /** 文件引用替身不作为真实浏览器证据。 */ @MockitoBean private PrototypeArtifactStore artifacts;
    /** 合成响应会话，按模型调用次数消耗固定输出。 */ private final ArrayDeque<PrototypeModelProvider.Session> sessions = new ArrayDeque<>();
    /** 收集发给模型的上下文以验证固定来源及基准，不记录真实凭证。 */ private final List<String> prompts = new CopyOnWriteArrayList<>();
    /** 合成输出调用数。 */ private final AtomicInteger calls = new AtomicInteger();
    /** 模板范围内唯一文件。 */ private static final String FILE = "src/features/generated/People.vue";

    /** 重置外部边界，所有实际运行仍明确记录为 synthetic。 */
    @BeforeEach void setup() {
        sessions.clear(); prompts.clear(); calls.set(0);
        when(models.available()).thenReturn(true);
        when(models.snapshot()).thenAnswer(call -> sessions.removeFirst());
        when(builds.available()).thenReturn(true);
        when(builds.componentContract()).thenReturn("固定测试组件契约");
        when(builds.build(any(),eq("synthetic"))).thenReturn("a".repeat(64));
        when(artifacts.verify(anyString())).thenAnswer(call -> verified(call.getArgument(0)));
    }

    /** 首次生成及页面编辑基于同一来源和已封存源码，均经真实 Graph 后追加不可变修订。 */
    @Test void generateAndEditPersistFrozenSourceAndVerifiedRevision() throws Exception {
        var document = create();
        sessions.add(session(plan(), output("第一版")));
        var first = finish(prototypes.submit(version(document), command(document, "GENERATE")));
        assertThat(first.status()).isEqualTo("SUCCEEDED");
        var before = prototypes.get(version(document));
        assertThat(before.current().mode()).isEqualTo("synthetic");
        assertThat(before.current().manifest().path("plan").path("pages").size()).isEqualTo(1);
        when(artifacts.generatedFiles("a".repeat(64))).thenReturn(Map.of(FILE, source("第一版")));
        when(builds.build(any(),eq("synthetic"))).thenReturn("b".repeat(64));
        sessions.add(session(plan(), output("第二版")));
        var edited = finish(prototypes.submit(version(document), command(document, "EDIT")));
        assertThat(edited.status()).isEqualTo("SUCCEEDED");
        var current = prototypes.get(version(document)).current();
        assertThat(current.parentRevisionId()).isEqualTo(before.current().id());
        assertThat(current.source().id()).isEqualTo(before.current().source().id());
        assertThat(prototypes.revisions(version(document), 1).items()).hasSize(2);
        assertThat(prompts).anySatisfy(prompt -> assertThat(prompt).contains("第一版", "baseFiles", "精确冻结背景"));
        assertThat(repository.runDetail(edited.id()).executionToken).isNull();
        verify(builds,times(2)).build(any(),eq("synthetic"));
    }

    /** 编译失败修复最多两次，保留最后计划并记录真实使用轮数。 */
    @Test void repairsBuildFailuresWithinTwoRounds() throws Exception {
        var document = create();
        sessions.add(session(plan(), output("初稿"), output("修复一"), output("修复二")));
        when(builds.build(any(),eq("synthetic")))
                .thenThrow(new PrototypeBuildException("PROTOTYPE_BUILD_FAILED", "编译未通过"))
                .thenThrow(new PrototypeBuildException("PROTOTYPE_PREVIEW_FAILED", "查询按钮不存在"))
                .thenReturn("a".repeat(64));
        var run = finish(prototypes.submit(version(document), command(document,"GENERATE")));
        assertThat(run.status()).isEqualTo("SUCCEEDED");
        assertThat(run.prototype().repairAttempts()).isEqualTo(2);
        assertThat(calls.get()).isEqualTo(4);
        assertThat(prompts).anySatisfy(prompt -> assertThat(prompt).contains("platformDiagnostic", "查询按钮不存在"));
        assertThat(repository.runDetail(run.id()).planJson).contains("people");
    }

    /** 输出结构连续无效最终失败，既有当前稿不受影响且从未提交不合法文件给 Worker。 */
    @Test void invalidOutputCannotReplaceExistingRevision() throws Exception {
        var document = create();sessions.add(session(plan(),output("稳定版")));
        finish(prototypes.submit(version(document),command(document,"GENERATE")));
        String before = prototypes.get(version(document)).current().id();
        when(artifacts.generatedFiles(anyString())).thenReturn(Map.of(FILE,source("稳定版")));
        sessions.add(session("{}","{}","{}"));
        var run = finish(prototypes.submit(version(document),command(document,"EDIT")));
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(repository.runDetail(run.id()).diagnosticJson).contains("PROTOTYPE_MODEL_OUTPUT_INVALID");
        assertThat(run.prototype().repairAttempts()).isEqualTo(2);
        assertThat(prototypes.get(version(document)).current().id()).isEqualTo(before);
        assertThat(prototypes.revisions(version(document),1).items()).hasSize(1);
        verify(builds,times(1)).build(any(),eq("synthetic"));
    }

    /** EDIT 的全量文件和有效元数据相同就结束，不触发多余构建或制造新修订。 */
    @Test void unchangedEditDoesNotCreateRevision() throws Exception {
        var document = create();sessions.add(session(plan(),output("稳定版")));
        finish(prototypes.submit(version(document),command(document,"GENERATE")));
        when(artifacts.generatedFiles(anyString())).thenReturn(Map.of(FILE,source("稳定版")));
        sessions.add(session(plan(),output("稳定版")));
        var run = finish(prototypes.submit(version(document),command(document,"EDIT")));
        assertThat(run.status()).isEqualTo("SUCCEEDED");
        assertThat(run.prototype().resultRevisionId()).isNull();
        assertThat(run.prototype().applicationStatus()).isEqualTo("NOT_APPLICABLE");
        assertThat(prototypes.revisions(version(document),1).items()).hasSize(1);
        verify(builds,times(1)).build(any(),eq("synthetic"));
    }

    /** 领取前模型配置失效也必须结束已提交任务，不能永久 QUEUED。 */
    @Test void unavailableModelAfterSubmitEndsQueuedRun() throws Exception {
        var document=create();
        var failure=mock(PrototypeModelException.class);
        when(failure.code()).thenReturn("PROTOTYPE_MODEL_UNAVAILABLE");
        doThrow(failure).when(models).snapshot();
        var run=finish(prototypes.submit(version(document),command(document,"GENERATE")));
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(repository.runDetail(run.id()).diagnosticJson).contains("PROTOTYPE_MODEL_UNAVAILABLE");
        assertThat(prototypes.get(version(document)).current()).isNull();
        verify(builds,never()).build(any(),anyString());
    }

    /** 容器构建期间 PRD 变化时，已通过检查的结果落候选而非覆盖当前稿。 */
    @Test void changedPrdDuringBuildProducesCandidate() throws Exception {
        var document=create();sessions.add(session(plan(),output("候选版")));
        when(builds.build(any(),eq("synthetic"))).thenAnswer(call -> {
            var latest=workbench.get(document.workspace().session().id());
            workbench.save(latest.workspace().session().id(),new WorkbenchContracts.Draft(latest.lifecycle().revision(),latest.workspace().version().revision(),latest.lifecycle().prd()));
            return "a".repeat(64);
        });
        var run=finish(prototypes.submit(version(document),command(document,"GENERATE")));
        assertThat(run.status()).isEqualTo("SUCCEEDED");
        assertThat(run.prototype().applicationStatus()).isEqualTo("CONFLICT");
        assertThat(prototypes.get(version(document)).current()).isNull();
        assertThat(prototypes.revisions(version(document),1).items()).hasSize(1);
    }

    /** 同步 PRD 固定新来源、复用原文件，并验证整个选择范围后才追加修订。 */
    @Test void syncPrdUsesNewSnapshotAndExistingSourceFiles() throws Exception {
        var document=create();sessions.add(session(plan(),output("旧来源")));
        finish(prototypes.submit(version(document),command(document,"GENERATE")));
        var before=prototypes.get(version(document));
        when(artifacts.generatedFiles(anyString())).thenReturn(Map.of(FILE,source("旧来源")));
        var latest=workbench.get(document.workspace().session().id());
        workbench.save(latest.workspace().session().id(),new WorkbenchContracts.Draft(latest.lifecycle().revision(),latest.workspace().version().revision(),latest.lifecycle().prd()));
        sessions.add(session(plan(),output("新来源")));
        var run=finish(prototypes.submit(version(document),command(document,"SYNC_PRD")));
        assertThat(run.status()).isEqualTo("SUCCEEDED");
        var after=prototypes.get(version(document)).current();
        assertThat(after.source().id()).isNotEqualTo(before.current().source().id());
        assertThat(after.parentRevisionId()).isEqualTo(before.current().id());
        assertThat(prompts).anySatisfy(prompt->assertThat(prompt).contains("SYNC_PRD","旧来源"));
    }

    /** 运行时中断保留上一修订，单独记录 INTERRUPTED 而非模型失败。 */
    @Test void cancelledWorkerCannotPublishRevision() throws Exception {
        var document=create();sessions.add(session(plan(),output("被取消")));
        when(builds.build(any(),eq("synthetic"))).thenThrow(new PrototypeBuildException("PROTOTYPE_INTERRUPTED","执行已取消"));
        var run=finish(prototypes.submit(version(document),command(document,"GENERATE")));
        assertThat(run.status()).isEqualTo("INTERRUPTED");
        assertThat(run.prototype().resultRevisionId()).isNull();
        assertThat(prototypes.get(version(document)).current()).isNull();
        assertThat(repository.runDetail(run.id()).executionToken).isNull();
        verify(builds,times(1)).build(any(),eq("synthetic"));
    }

    /** 创建单页已保存 PRD，不调用既有需求模型。 */
    private WorkbenchContracts.Detail create() {
        var project=workspace.createProject(new NewProject("P4合成事务","精确冻结背景"));
        var version=workspace.createVersion(project.id(),new NewVersion("生成文档","人员查询","单页列表"));
        var document=workbench.open(workspace.createSession(project.id(),new NewSession("原型生成",version.id())).id());
        var page=new PrdStructure.Page("list","人员列表","LIST",Map.of("overview","人员查询","fields","姓名","search","按姓名查询","sorting","姓名","actions","查询","states","空态","acceptance","正确筛选"));
        var module=new PrdStructure.Module("people","人员",Map.of("overview","人员管理","rules","ID唯一","permissions","模拟","acceptance","筛选"),List.of(page));
        var prd=new Prd("背景","目标","范围","流程","规则","异常","验收","非功能","排除",List.of(),List.of(),2,List.of(module));
        return workbench.save(document.workspace().session().id(),new WorkbenchContracts.Draft(document.lifecycle().revision(),document.workspace().version().revision(),prd));
    }

    /** 使用每次读取到的准确锁；编辑保留已冻结范围和来源。 */
    private Submit command(WorkbenchContracts.Detail original,String operation) {
        var document=workbench.get(original.workspace().session().id());
        var prototype=prototypes.get(version(document));
        boolean edit="EDIT".equals(operation);
        return new Submit(document.workspace().session().id(),UUID.randomUUID().toString(),operation,
                document.workspace().version().revision(),document.lifecycle().revision(),document.lifecycle().prdRevision(),prototype.revision(),
                prototype.current()==null?null:prototype.current().id(),"zero-admin",2,edit?new Source("EXISTING",prototype.current().source().id()):new Source("DRAFT",null),
                edit?prototype.current().source().scope():new Scope("ALL",List.of()),new Target("PROTOTYPE",null,null),"生成完整原型",null);
    }

    /** 等待真实异步执行及槽释放，超时使测试明确失败。 */
    private WorkspaceViews.Run finish(WorkspaceViews.Run initial) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
        WorkspaceViews.Run run;
        do {
            run=prototypes.run(initial.id());
            if (!List.of("QUEUED","RUNNING").contains(run.status()) && runner.available()) return run;
            Thread.sleep(20);
        } while(System.nanoTime()<deadline);
        throw new AssertionError("原型任务未按时结束："+run.status());
    }

    /** 构造明确合成的串行文本会话，记录真实 Graph 传入的用户上下文。 */
    private PrototypeModelProvider.Session session(String... responses) {
        var queue=new ArrayDeque<>(List.of(responses));
        return new PrototypeModelProvider.Session() {
            /** 合成测试不能伪装真实模型。 */ public String mode(){return "synthetic";}
            /** 固定可追溯测试模型名。 */ public String model(){return "p4-synthetic";}
            /** 每次节点调用消耗一条完整输出。 */ public String generate(String system,String user){calls.incrementAndGet();prompts.add(user);return queue.removeFirst();}
            /** 合成会话没有外部资源。 */ public void close(){ }
        };
    }
    /** 每份计划精确引用测试 PRD 页面。 */
    private String plan(){return "{\"pages\":[{\"moduleId\":\"people\",\"pageId\":\"list\",\"title\":\"人员\",\"path\":\"/people\",\"component\":\""+FILE+"\",\"menu\":true}],\"entities\":[],\"impact\":[],\"gaps\":[]}";}
    /** 真实合法文件结构供 Graph 解析，未将其当作实际浏览器证据。 */
    private String output(String text)throws Exception{return json.writeValueAsString(Map.of("files",List.of(Map.of("path",FILE,"content",source(text))),"cases",List.of(),"changeSummary",text));}
    /** 不含网络或宿主依赖的合成页面。 */
    private String source(String text){return "<template><section>"+text+"</section></template>";}
    /** 构造明确 synthetic 文件服务替身。 */
    private PrototypeArtifactStore.VerifiedArtifact verified(String key){return new PrototypeArtifactStore.VerifiedArtifact(key,"sha256:"+"5".repeat(64),json.valueToTree(Map.of("status","PASSED","cases",List.of(Map.of("name","合成边界","status","PASSED")))) ,json.valueToTree(Map.of("objectKey",key,"mode","synthetic","generation","test-fixture","sources",List.of())));}
    /** 文档与会话身份不可互换。 */
    private String version(WorkbenchContracts.Detail document){return document.workspace().version().id();}
}
