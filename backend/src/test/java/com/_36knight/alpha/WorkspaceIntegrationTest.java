package com._36knight.alpha;

import com._36knight.alpha.agent.*;
import com._36knight.alpha.dto.RequirementDraft;
import com._36knight.alpha.model.ModelRuntimeService;
import com._36knight.alpha.project.ProjectException;
import com._36knight.alpha.workspace.*;
import com._36knight.alpha.workspace.persistence.WorkspaceRunEntity;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static com._36knight.alpha.workspace.WorkspaceRequests.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 工作空间真实服务、Mapper 与 HTTP 关系测试，模型只用本地模板或合成适配器。@author Andersen */
@SpringBootTest(properties="spring.datasource.dynamic.datasource.master.url=jdbc:h2:mem:workspace-tests;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class WorkspaceIntegrationTest {
    /** 实际事务业务入口。 */ @Autowired private WorkspaceService service;
    /** 模拟运行快照，禁止测试请求付费平台。 */ @MockitoBean private ModelRuntimeService runtime;
    /** 少量持久化断言和中断状态准备。 */ @Autowired private WorkspaceRepository repository;
    /** HTTP 契约验证。 */ @Autowired private MockMvc mvc;
    /** 每个测试固定独立模型来源。 */ @BeforeEach void setup(){when(runtime.snapshot()).thenReturn(new AnalysisRuntime("demo","local-template",new LocalRequirementAnalyzer()));}
    /** 一个产品的多个会话和业务版本独立，保存次数不制造新业务版本。 */
    @Test void separatesProjectsSessionsAndVersionRevisions(){
        var p=service.createProject(new NewProject("客户系统","内部客户管理"));
        var a=service.createSession(p.id(),new NewSession("导入讨论",null));
        var b=service.createSession(p.id(),new NewSession("分配讨论",null));
        var saved=service.saveDraft(a.id(),new SaveDraft(a.revision(),"只做 CSV 导入",null));
        var v=service.promote(a.id(),new PromoteDraft(saved.revision(),saved.draftRevision(),"一期导入","支持批量建档"));
        assertThat(service.promote(a.id(),new PromoteDraft(saved.revision(),saved.draftRevision(),"一期导入","支持批量建档")).id()).isEqualTo(v.id());
        var edited=service.saveVersion(v.id(),new EditVersion(v.revision(),v.label(),v.goal(),"只做 CSV 导入，校验重复项"));
        assertThat(edited.revision()).isEqualTo(2);assertThat(edited.stage()).isEqualTo("REVIEW");
        assertThat(service.versions(p.id(),1,false).items()).hasSize(1);
        assertThat(service.savedRevision(v.id(),1).content()).isEqualTo("只做 CSV 导入");
        assertThat(service.getSession(b.id()).session().draftText()).isEmpty();
        assertThat(service.getSession(a.id()).session().versionId()).isEqualTo(v.id());
        assertThatThrownBy(()->service.saveVersion(v.id(),new EditVersion(1,v.label(),v.goal(),"覆盖"))).isInstanceOf(ProjectException.class);
    }
    /** 归属和命名受服务与数据库约束，探索会话不能绑定两次。 */
    @Test void rejectsCrossProjectAndDuplicateLabels(){
        var p=service.createProject(new NewProject("甲",""));var q=service.createProject(new NewProject("乙",""));
        var v=service.createVersion(p.id(),new NewVersion("v1.0","导入",""));var s=service.createSession(q.id(),new NewSession("讨论",null));
        assertThatThrownBy(()->service.linkVersion(s.id(),new LinkVersion(s.revision(),v.id()))).isInstanceOf(ProjectException.class);
        assertThatThrownBy(()->service.createSession(q.id(),new NewSession("错归属",v.id()))).isInstanceOf(ProjectException.class);
        assertThatThrownBy(()->service.createVersion(p.id(),new NewVersion(" v1.0 ","重复",""))).isInstanceOf(ProjectException.class);
        var own=service.createSession(p.id(),new NewSession("本项目",null));
        var linked=service.linkVersion(own.id(),new LinkVersion(own.revision(),v.id()));
        assertThatThrownBy(()->service.linkVersion(own.id(),new LinkVersion(linked.revision(),v.id()))).isInstanceOf(ProjectException.class);
    }
    /** 归档会话不结束版本，活动版本不能直接归档，恢复仍保持取消状态。 */
    @Test void archivesIndependentlyAndNeverPretendsCompletion(){
        var p=service.createProject(new NewProject("归档",""));var v=service.createVersion(p.id(),new NewVersion("一期","测试","范围"));
        var s=service.createSession(p.id(),new NewSession("会话",v.id()));
        var archived=service.archiveSession(s.id(),new Archive(s.revision(),true));
        assertThat(service.getVersion(v.id()).lifecycle()).isEqualTo("ACTIVE");
        assertThat(service.archiveSession(s.id(),new Archive(archived.revision(),false)).archivedAt()).isNull();
        assertThatThrownBy(()->service.archiveVersion(v.id(),new Archive(v.revision(),true))).isInstanceOf(ProjectException.class);
        assertThatThrownBy(()->service.archiveProject(p.id(),new Archive(p.revision(),true))).isInstanceOf(ProjectException.class);
        var canceled=service.cancelVersion(v.id(),new CancelVersion(v.revision(),"范围调整，下期再做"));
        assertThatThrownBy(()->service.saveDraft(s.id(),new SaveDraft(service.getSession(s.id()).session().revision(),"继续修改已取消范围",null))).isInstanceOf(ProjectException.class);
        var av=service.archiveVersion(v.id(),new Archive(canceled.revision(),true));
        var restored=service.archiveVersion(v.id(),new Archive(av.revision(),false));
        assertThat(restored.archivedAt()).isNull();assertThat(restored.lifecycle()).isEqualTo("CANCELED");
        var ap=service.archiveProject(p.id(),new Archive(p.revision(),true));
        assertThatThrownBy(()->service.createSession(p.id(),new NewSession("不可创建",null))).isInstanceOf(ProjectException.class);
        assertThat(service.archiveProject(p.id(),new Archive(ap.revision(),false)).archivedAt()).isNull();
    }
    /** 同源草稿同步显式产生范围快照，旧内容与来源可追溯。 */
    @Test void syncRequiresBothSavedRevisions(){
        var p=service.createProject(new NewProject("同步",""));var v=service.createVersion(p.id(),new NewVersion("首版","目标","旧范围"));
        var s=service.createSession(p.id(),new NewSession("变更",v.id()));var d=service.saveDraft(s.id(),new SaveDraft(1,"新范围",null));
        assertThat(service.getVersion(v.id()).content()).isEqualTo("旧范围");
        var updated=service.syncDraft(s.id(),new SyncDraft(1,d.draftRevision()));
        assertThat(updated.content()).isEqualTo("新范围");assertThat(service.savedRevision(v.id(),2).sourceSessionId()).isEqualTo(s.id());
        assertThatThrownBy(()->service.syncDraft(s.id(),new SyncDraft(1,d.draftRevision()))).isInstanceOf(ProjectException.class);
        assertThat(service.savedRevision(v.id(),1).content()).isEqualTo("旧范围");
    }
    /** 用户消息先持久化；相同幂等请求只执行一次，期间允许读取其他会话。 */
    @Test void durableRunsAreIdempotentAndKeepConversationContext() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();var captured=new AtomicReference<String>();
        when(runtime.snapshot()).thenReturn(new AnalysisRuntime("openai-compatible","synthetic-chat",new RequirementAnalyzer(){
            /** 此测试不使用结构化草稿。 */ public RequirementDraft analyze(String text){throw new UnsupportedOperationException();}
            /** 捕获有界上下文，模拟耗时响应。 */ public String converse(String text){calls.incrementAndGet();captured.set(text);entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException();}return "合成回答";}
        }));
        var p=service.createProject(new NewProject("上下文项目","产品约束"));var s=service.createSession(p.id(),new NewSession("持续讨论",null));
        var request=new SubmitRun(UUID.randomUUID().toString(),"CHAT","运营需要导入客户");var r=service.submit(s.id(),request);
        try{
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(service.messages(s.id(),null).items()).extracting(WorkspaceViews.Message::content).containsExactly("运营需要导入客户");
            assertThat(service.submit(s.id(),request).id()).isEqualTo(r.id());
            assertThatThrownBy(()->service.archiveSession(s.id(),new Archive(1,true))).isInstanceOf(ProjectException.class);
            assertThatThrownBy(()->service.submit(s.id(),new SubmitRun(UUID.randomUUID().toString(),"CHAT","重复发送"))).isInstanceOf(ProjectException.class);
        }finally{release.countDown();}
        await(r.id());assertThat(calls).hasValue(1);assertThat(captured.get()).contains("产品约束","运营需要导入客户");
        var follow=service.submit(s.id(),new SubmitRun(UUID.randomUUID().toString(),"CHAT","补充重复项规则"));await(follow.id());
        assertThat(captured.get()).contains("合成回答","补充重复项规则","运营需要导入客户");
        assertThat(service.messages(s.id(),null).items()).hasSize(4);
        assertThat(service.getRun(follow.id()).model()).isEqualTo("synthetic-chat");
    }
    /** 整理候选不保存草稿；旧候选不能覆盖新的人工修订。 */
    @Test void draftCandidateRequiresExplicitSaveAndMatchingBaseline() throws Exception {
        var p=service.createProject(new NewProject("草稿",""));var s=service.createSession(p.id(),new NewSession("草稿讨论",null));
        await(service.submit(s.id(),new SubmitRun(UUID.randomUUID().toString(),"CHAT","增加导入能力")).id());
        var r=await(service.submit(s.id(),new SubmitRun(UUID.randomUUID().toString(),"DRAFT","")).id());
        assertThat(service.getSession(s.id()).session().draftRevision()).isZero();
        var saved=service.saveDraft(s.id(),new SaveDraft(1,r.resultText(),r.id()));assertThat(saved.draftRevision()).isEqualTo(1);
        assertThatThrownBy(()->service.saveDraft(s.id(),new SaveDraft(saved.revision(),r.resultText(),r.id()))).isInstanceOf(ProjectException.class);
        assertThat(service.versions(p.id(),1,false).items()).isEmpty();
    }
    /** 版本范围变化后，基于旧版本生成的候选不能覆盖当前草稿。 */
    @Test void versionChangeInvalidatesGeneratedCandidate() throws Exception {
        var p=service.createProject(new NewProject("基准",""));var v=service.createVersion(p.id(),new NewVersion("一期","目标","旧范围"));
        var s=service.createSession(p.id(),new NewSession("围绕版本",v.id()));
        var r=await(service.submit(s.id(),new SubmitRun(UUID.randomUUID().toString(),"DRAFT","")).id());
        assertThat(r.versionRevision()).isEqualTo(1);
        service.saveVersion(v.id(),new EditVersion(1,v.label(),v.goal(),"修改后的范围"));
        assertThatThrownBy(()->service.saveDraft(s.id(),new SaveDraft(1,r.resultText(),r.id()))).isInstanceOf(ProjectException.class);
        assertThat(service.getSession(s.id()).session().draftRevision()).isZero();
    }
    /** 上游错误只保存安全失败，消息不丢失且不自动请求模板。 */
    @Test void failureAndRestartRequireExplicitRetry() throws Exception {
        when(runtime.snapshot()).thenThrow(new IllegalStateException("private-credential-upstream"));
        var p=service.createProject(new NewProject("失败",""));var s=service.createSession(p.id(),new NewSession("讨论",null));
        var failed=await(service.submit(s.id(),new SubmitRun(UUID.randomUUID().toString(),"CHAT","保留输入")).id());
        assertThat(failed.status()).isEqualTo("FAILED");assertThat(failed.errorMessage()).doesNotContain("private-credential-upstream");
        assertThat(service.messages(s.id(),null).items()).hasSize(1);
        var row=new WorkspaceRunEntity();row.id=UUID.randomUUID().toString();row.projectId=p.id();row.sessionId=s.id();row.requestId=UUID.randomUUID().toString();row.kind="DRAFT";row.status="RUNNING";row.inputText="";row.contextText="合成上下文";row.draftRevision=0L;row.createdAt=java.time.OffsetDateTime.now();row.updatedAt=row.createdAt;repository.insert(row);
        service.interruptPreviousRuns();assertThat(service.getRun(row.id).status()).isEqualTo("INTERRUPTED");
        assertThat(service.getRun(row.id).errorMessage()).doesNotContain("合成上下文");
    }
    /** 两窗口保存相同基准只有一个成功，历史没有幽灵快照。 */
    @Test void concurrentVersionWritersHaveOneWinner() throws Exception {
        var p=service.createProject(new NewProject("并发",""));var v=service.createVersion(p.id(),new NewVersion("一期","目标","初稿"));
        var start=new CountDownLatch(1);try(var executor=Executors.newFixedThreadPool(2)){
            var a=executor.submit(()->write(v.id(),start,"甲"));var b=executor.submit(()->write(v.id(),start,"乙"));start.countDown();
            assertThat(List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(service.revisions(v.id(),1).items()).hasSize(2);
    }
    /** HTTP 使用新对象契约，拒绝越过首期阶段能力和错误分页。 */
    @Test void validatesHttpCommandsAndKeepsPublicResponsesSafe() throws Exception {
        mvc.perform(post("/api/alpha/workspace/projects").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\",\"background\":\"\"}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/alpha/workspace/projects?page=0")).andExpect(status().isBadRequest());
        var p=service.createProject(new NewProject("HTTP",""));var s=service.createSession(p.id(),new NewSession("会话",null));
        mvc.perform(post("/api/alpha/workspace/sessions/{id}/runs",s.id()).contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"1234567890123456\",\"kind\":\"UNKNOWN\",\"content\":\"测试\"}")).andExpect(status().isBadRequest());
        var r=await(service.submit(s.id(),new SubmitRun(UUID.randomUUID().toString(),"CHAT","合成资料")).id());
        String response=mvc.perform(get("/api/alpha/workspace/runs/{id}",r.id())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("contextText","apiKey");
        mvc.perform(post("/api/alpha/workspace/versions/any/complete")).andExpect(status().isNotFound());
    }
    /** 短轮询等待真实终态，不使用假进度。 */
    private WorkspaceViews.Run await(String id) throws InterruptedException {for(int i=0;i<200;i++){var r=service.getRun(id);if(!Set.of("QUEUED","RUNNING").contains(r.status()))return r;Thread.sleep(20);}throw new AssertionError("任务未终结");}
    /** 并发提交范围快照。 */
    private boolean write(String id,CountDownLatch start,String content) throws InterruptedException {start.await();try{service.saveVersion(id,new EditVersion(1,"一期","目标",content));return true;}catch(ProjectException e){return false;}}
}
