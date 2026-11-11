package com._36knight.alpha;

import com._36knight.alpha.agent.AnalysisRuntime;
import com._36knight.alpha.agent.LocalRequirementAnalyzer;
import com._36knight.alpha.agent.RequirementAnalyzer;
import com._36knight.alpha.dto.RequirementDraft;
import com._36knight.alpha.model.ModelRuntimeService;
import com._36knight.alpha.project.ProjectException;
import com._36knight.alpha.workspace.LifecycleService;
import com._36knight.alpha.workspace.LifecycleRepository;
import com._36knight.alpha.workspace.WorkspaceService;
import com._36knight.alpha.workspace.WorkspaceViews;
import com._36knight.alpha.workspace.persistence.PrdRunEntity;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import static com._36knight.alpha.workspace.LifecycleContracts.*;
import static com._36knight.alpha.workspace.WorkspaceRequests.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 生命周期真实事务/Mapper/HTTP 测试，云调用只用合成适配器。@author Andersen */
@SpringBootTest(properties="spring.datasource.dynamic.datasource.master.url=jdbc:h2:mem:lifecycle-tests;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class LifecycleIntegrationTest {
    /** 当前生命周期规则。 */ @Autowired private LifecycleService lifecycle;
    /** 模板删除仅限制新绑定，不影响已存基线正文。 */ @Autowired private com._36knight.alpha.template.PrdTemplateService templates;
    /** 第一阶段业务入口。 */ @Autowired private WorkspaceService workspace;
    /** 测试中断所需任务准备。 */ @Autowired private LifecycleRepository repository;
    /** HTTP 契约验证。 */ @Autowired private MockMvc mvc;
    /** 阻止测试付费调用。 */ @MockitoBean private ModelRuntimeService runtime;
    /** 每项测试固定本地来源。 */ @BeforeEach void setup(){when(runtime.snapshot()).thenReturn(new AnalysisRuntime("demo","local-template",new LocalRequirementAnalyzer()));}
    /** 新增和正文改动不能同次保存仍被确认，初始范围不自动推断验收。 */
    @Test void editsRevokeConfirmationAndPreserveHistory(){
        var v=create();var d=lifecycle.get(v.id());assertThat(d.review().requirements().getFirst().confirmed()).isFalse();
        d=ready(v.id());assertThat(d.reviewBlockers()).isEmpty();var r=d.review().requirements().getFirst();
        var changed=new Requirement(r.id(),r.title(),"修改范围",r.acceptance(),true,null);
        var saved=lifecycle.saveReview(v.id(),new SaveReview(d.revision(),new Review(List.of(changed),List.of(),List.of())));
        assertThat(saved.review().requirements().getFirst().confirmed()).isFalse();
        assertThat(lifecycle.history(v.id(),"REVIEW",1).items().getLast().contentJson()).doesNotContain("修改范围");
        assertThatThrownBy(()->lifecycle.saveReview(v.id(),new SaveReview(1,saved.review()))).isInstanceOf(ProjectException.class);
    }
    /** 阻塞、结论、延期和接受建议由服务器校验，接受不是确认需求。 */
    @Test void reviewGatesQuestionsAndSuggestions(){
        var v=create();var d=ready(v.id());
        var question=new Question(UUID.randomUUID().toString(),"失败如何处理",true,"OPEN","","");
        d=lifecycle.saveReview(v.id(),new SaveReview(d.revision(),new Review(d.review().requirements(),List.of(question),List.of())));
        assertThat(d.reviewBlockers()).anyMatch(s->s.contains("阻塞"));
        final var blocked=d;assertThatThrownBy(()->lifecycle.confirmReview(v.id(),confirmation(blocked, true))).isInstanceOf(ProjectException.class);
        var deferred=new Question(question.id(),question.text(),true,"DEFERRED","","下期处理");
        d=lifecycle.saveReview(v.id(),new SaveReview(d.revision(),new Review(d.review().requirements(),List.of(deferred),List.of())));assertThat(d.reviewBlockers()).isNotEmpty();
        var nonblocking=new Question(question.id(),question.text(),false,"DEFERRED","","明确首期不实现");
        var suggestion=new Suggestion(UUID.randomUUID().toString(),"提供失败记录下载","ACCEPTED","");
        d=lifecycle.saveReview(v.id(),new SaveReview(d.revision(),new Review(d.review().requirements(),List.of(nonblocking),List.of(suggestion))));
        assertThat(d.review().requirements()).hasSize(2);assertThat(d.review().requirements().getLast().confirmed()).isFalse();
        assertThat(d.review().requirements().getLast().sourceSuggestionId()).isEqualTo(suggestion.id());assertThat(d.reviewBlockers()).isNotEmpty();
    }
    /** 人工确认幂等，阶段与基线原子推进；同键不能用于另一次产物确认。 */
    @Test void confirmsImmutableReviewAndRejectsStageBypass(){
        var v=create();var d=ready(v.id());var request=confirmation(d,true);var confirmed=lifecycle.confirmReview(v.id(),request);
        assertThat(lifecycle.confirmReview(v.id(),request).reviewBaseline().id()).isEqualTo(confirmed.reviewBaseline().id());
        assertThat(workspace.getVersion(v.id()).stage()).isEqualTo("PRD");
        assertThatThrownBy(()->lifecycle.saveReview(v.id(),new SaveReview(confirmed.revision(),confirmed.review()))).isInstanceOf(ProjectException.class);
        assertThatThrownBy(()->lifecycle.confirmPrd(v.id(),request)).isInstanceOf(ProjectException.class);
        var current=workspace.getVersion(v.id());assertThatThrownBy(()->workspace.saveVersion(v.id(),new EditVersion(current.revision(),v.label(),v.goal(),"越过确认改范围"))).isInstanceOf(ProjectException.class);
        workspace.saveVersion(v.id(),new EditVersion(current.revision(),"名称变化",v.goal(),v.content()));
        assertThat(lifecycle.get(v.id()).reviewCurrent()).isTrue();
        assertThat(lifecycle.getBaseline(v.id(),confirmed.reviewBaseline().id()).label()).isEqualTo(v.label());
    }
    /** PRD 章节、待确认事项和需求覆盖是服务器确认门槛，确认不完成交付。 */
    @Test void prdConfirmationRequiresCompleteValidDocument(){
        var v=create();var d=confirmReview(v.id());var b=d.reviewBaseline();
        var pending=prd(b,"",List.of("异常路径未明确"));
        d=lifecycle.savePrd(v.id(),new SavePrd(d.revision(),b.id(),null,pending));
        var incomplete=d;assertThatThrownBy(()->lifecycle.confirmPrd(v.id(),confirmation(incomplete,false))).isInstanceOf(ProjectException.class);
        d=lifecycle.savePrd(v.id(),new SavePrd(d.revision(),b.id(),null,prd(b,"完整业务流程",List.of())));
        var request=confirmation(d,false);d=lifecycle.confirmPrd(v.id(),request);
        assertThat(d.prdBaseline().sourceBaselineId()).isEqualTo(b.id());assertThat(lifecycle.confirmPrd(v.id(),request).prdBaseline().id()).isEqualTo(d.prdBaseline().id());
        assertThat(workspace.getVersion(v.id()).stage()).isEqualTo("PROTOTYPE");assertThat(workspace.getVersion(v.id()).lifecycle()).isEqualTo("ACTIVE");
    }
    /** 未转换的旧流程继续编辑仍回到 PRD，保留评审来源和旧确认稿。 */
    @Test void legacyResumeStillReturnsToPrdStage(){
        var v=create();var d=confirmReview(v.id());var review=d.reviewBaseline();
        d=lifecycle.savePrd(v.id(),new SavePrd(d.revision(),review.id(),null,prd(review,"原始流程",List.of())));
        d=lifecycle.confirmPrd(v.id(),confirmation(d,false));var baseline=d.prdBaseline();
        d=lifecycle.resumePrd(v.id(),new Reopen(d.revision(),workspace.getVersion(v.id()).revision(),"完善旧流程"));
        assertThat(d.directPrd()).isFalse();assertThat(d.prdBaseline()).isNull();
        assertThat(workspace.getVersion(v.id()).stage()).isEqualTo("PRD");
        d=lifecycle.savePrd(v.id(),new SavePrd(d.revision(),review.id(),null,prd(review,"新流程",List.of())));
        assertThat(d.prd().workflows()).isEqualTo("新流程");
        assertThat(lifecycle.getBaseline(v.id(),baseline.id()).prd().workflows()).isEqualTo("原始流程");
    }
    /** 重新评审保留旧基线和 PRD，显式同步后才能确认下游。 */
    @Test void upstreamChangesInvalidatePrdWithoutRewritingBaselines(){
        var v=create();var d=confirmReview(v.id());var old=d.reviewBaseline();
        d=lifecycle.savePrd(v.id(),new SavePrd(d.revision(),old.id(),null,prd(old,"流程 A",List.of())));
        d=lifecycle.confirmPrd(v.id(),confirmation(d,false));var oldPrd=d.prdBaseline();
        var current=workspace.getVersion(v.id());d=lifecycle.reopen(v.id(),new Reopen(d.revision(),current.revision(),"增加异常路径"));
        assertThat(d.prdCurrent()).isFalse();assertThat(d.prd()).isNotNull();assertThat(d.reviewBaseline()).isNull();
        current=workspace.getVersion(v.id());workspace.saveVersion(v.id(),new EditVersion(current.revision(),current.label(),current.goal(),"新增失败重试范围"));
        d=lifecycle.saveReview(v.id(),new SaveReview(d.revision(),d.review()));assertThat(d.review().requirements().getFirst().confirmed()).isFalse();
        d=ready(v.id());d=lifecycle.confirmReview(v.id(),confirmation(d,true));assertThat(d.reviewBaseline().id()).isNotEqualTo(old.id());assertThat(d.prdCurrent()).isFalse();
        final var stale=d;assertThatThrownBy(()->lifecycle.confirmPrd(v.id(),confirmation(stale,false))).isInstanceOf(ProjectException.class);
        assertThat(lifecycle.getBaseline(v.id(),oldPrd.id()).prd().workflows()).isEqualTo("流程 A");
        assertThat(lifecycle.history(v.id(),"EVENT",1).items().getFirst().contentJson()).contains("增加异常路径");
    }
    /** 同版本复合基线关系和精确需求覆盖不能由客户端伪造。 */
    @Test void rejectsCrossVersionSourcesAndCoverage(){
        var a=create();var b=create();var da=confirmReview(a.id());var db=confirmReview(b.id());
        assertThatThrownBy(()->lifecycle.getBaseline(a.id(),db.reviewBaseline().id())).isInstanceOf(ProjectException.class);
        assertThatThrownBy(()->lifecycle.savePrd(a.id(),new SavePrd(da.revision(),db.reviewBaseline().id(),null,prd(db.reviewBaseline(),"流程",List.of())))).isInstanceOf(ProjectException.class);
        var good=prd(da.reviewBaseline(),"流程",List.of());var bad=new Prd(good.background(),good.objectives(),good.scope(),good.workflows(),good.rules(),good.exceptions(),good.acceptance(),good.nonfunctional(),good.exclusions(),List.of("other"),List.of());
        assertThatThrownBy(()->lifecycle.savePrd(a.id(),new SavePrd(da.revision(),da.reviewBaseline().id(),null,bad))).isInstanceOf(ProjectException.class);
    }
    /** 后台生成幂等，保存新 PRD 后旧候选不能覆盖；切换和刷新不影响执行。 */
    @Test void durableCandidateKeepsBaselineAndRejectsStaleApplication() throws Exception {
        var v=create();var d=confirmReview(v.id());var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();var expected=prd(d.reviewBaseline(),"合成流程",List.of());
        when(runtime.snapshot()).thenReturn(new AnalysisRuntime("openai-compatible","synthetic-prd",new RequirementAnalyzer(){
            /** 此适配器只测试 PRD。 */ public RequirementDraft analyze(String text){throw new UnsupportedOperationException();}
            /** 控制合成网络延迟并返回有明确需求映射的结构。 */ public Prd generatePrd(String context){calls.incrementAndGet();assertThat(context).contains("requirements",v.content());entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException();}return expected;}
        }));
        var request=new GeneratePrd(d.revision(),d.reviewBaseline().id(),UUID.randomUUID().toString());var run=lifecycle.generate(v.id(),request);
        try{assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();assertThat(lifecycle.generate(v.id(),request).id()).isEqualTo(run.id());
            final var current=d;assertThatThrownBy(()->lifecycle.reopen(v.id(),new Reopen(current.revision(),workspace.getVersion(v.id()).revision(),"运行时重开"))).isInstanceOf(ProjectException.class);
            d=lifecycle.savePrd(v.id(),new SavePrd(d.revision(),d.reviewBaseline().id(),null,expected));
        }finally{release.countDown();}
        var completed=await(run.id());assertThat(completed.status()).isEqualTo("SUCCEEDED");assertThat(calls).hasValue(1);assertThat(completed.model()).isEqualTo("synthetic-prd");
        final var current=d;assertThatThrownBy(()->lifecycle.savePrd(v.id(),new SavePrd(current.revision(),current.reviewBaseline().id(),run.id(),expected))).isInstanceOf(ProjectException.class);
        assertThat(lifecycle.get(v.id()).prdRevision()).isEqualTo(1);
    }
    /** 本地模板保留未调用模型来源和待确认事项，生成不写当前 PRD。 */
    @Test void templateIsOnlyCandidateAndCannotBeConfirmedUnresolved() throws Exception {
        var v=create();var d=confirmReview(v.id());var r=await(lifecycle.generate(v.id(),new GeneratePrd(d.revision(),d.reviewBaseline().id(),UUID.randomUUID().toString())).id());
        assertThat(r.status()).isEqualTo("SUCCEEDED");assertThat(r.result().background()).contains("未调用大模型");assertThat(lifecycle.get(v.id()).prd()).isNull();
        d=lifecycle.savePrd(v.id(),new SavePrd(d.revision(),d.reviewBaseline().id(),r.id(),r.result()));
        assertThat(d.prdBlockers()).anyMatch(s->s.contains("待确认"));
        var template=r.result();
        var clearedList=new Prd(template.background(),template.objectives(),template.scope(),template.workflows(),template.rules(),template.exceptions(),template.acceptance(),template.nonfunctional(),template.exclusions(),template.requirementIds(),List.of());
        d=lifecycle.savePrd(v.id(),new SavePrd(d.revision(),d.reviewBaseline().id(),null,clearedList));
        assertThat(d.prdBlockers()).anyMatch(s->s.contains("占位"));
        final var incomplete=d;assertThatThrownBy(()->lifecycle.confirmPrd(v.id(),confirmation(incomplete,false))).isInstanceOf(ProjectException.class);
    }
    /** 失败没有密钥/上游原文；启动中断不会自动重试。 */
    @Test void safeFailuresAndRestartInterruption() throws Exception {
        var v=create();var d=confirmReview(v.id());when(runtime.snapshot()).thenThrow(new IllegalStateException("synthetic-secret-must-not-leak"));
        var failed=await(lifecycle.generate(v.id(),new GeneratePrd(d.revision(),d.reviewBaseline().id(),UUID.randomUUID().toString())).id());
        assertThat(failed.status()).isEqualTo("FAILED");assertThat(failed.errorMessage()).doesNotContain("synthetic-secret");
        assertThat(failed.errorMessage()).contains("诊断编号", "明确重新生成");
        var stale=new PrdRunEntity();stale.id=UUID.randomUUID().toString();stale.versionId=v.id();stale.baselineId=d.reviewBaseline().id();stale.prdRevision=0L;stale.requestId=UUID.randomUUID().toString();stale.status="RUNNING";stale.createdAt=java.time.OffsetDateTime.now();stale.updatedAt=stale.createdAt;repository.insert(stale);
        lifecycle.interruptPreviousRuns();assertThat(lifecycle.getRun(stale.id).status()).isEqualTo("INTERRUPTED");
    }
    /** HTTP 有界输入与只读取消；无完成交付捷径。 */
    @Test void validatesHttpAndCanceledReadOnly() throws Exception {
        var v=create();mvc.perform(put("/api/alpha/workspace/versions/"+v.id()+"/lifecycle/review").contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":1,\"review\":{\"requirements\":null,\"questions\":[],\"suggestions\":[]}}" )).andExpect(status().isBadRequest());
        mvc.perform(put("/api/alpha/workspace/versions/"+v.id()+"/lifecycle/review").contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":1,\"review\":{\"requirements\":[],\"questions\":[{\"id\":\"q\",\"text\":\"是否阻塞\",\"status\":\"OPEN\",\"answer\":\"\",\"reason\":\"\"}],\"suggestions\":[]}}" )).andExpect(status().isBadRequest());
        mvc.perform(get("/api/alpha/workspace/versions/"+v.id()+"/lifecycle")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("contextText"))));
        workspace.cancelVersion(v.id(),new CancelVersion(1,"不实施"));var d=lifecycle.get(v.id());assertThatThrownBy(()->lifecycle.saveReview(v.id(),new SaveReview(d.revision(),d.review()))).isInstanceOf(ProjectException.class);
        mvc.perform(post("/api/alpha/workspace/versions/"+v.id()+"/lifecycle/complete")).andExpect(status().isNotFound());
    }
    /** 旧评审保存入口同样拒绝首次选用已删除模板，但已绑定文档仍可保存并确认。 */
    @Test void legacyPrdSaveRetainsDeletedBindingAndRejectsNewUse(){
        var item=templates.copy(new com._36knight.alpha.template.PrdTemplateContracts.Copy("management",1,"旧评审模板",UUID.randomUUID().toString()));
        item=templates.publish(item.id(),new com._36knight.alpha.template.PrdTemplateContracts.Publish(item.revision(),UUID.randomUUID().toString()));
        var template=templates.resolve(item.id(),1);var v=create();var d=confirmReview(v.id());var baseline=d.reviewBaseline();
        var original=com._36knight.alpha.workspace.PrdDocuments.withTemplate(prd(baseline,"完整流程",List.of()),template);
        d=lifecycle.savePrd(v.id(),new SavePrd(d.revision(),baseline.id(),null,original));
        templates.deletion(item.id(),new com._36knight.alpha.template.PrdTemplateContracts.Deletion(item.revision(),true));
        var edited=com._36knight.alpha.workspace.PrdDocuments.patch(original,java.util.Map.of("background","已删除模板仍保留旧文档"),null);
        d=lifecycle.savePrd(v.id(),new SavePrd(d.revision(),baseline.id(),null,edited));
        d=lifecycle.confirmPrd(v.id(),confirmation(d,false));assertThat(d.prdBaseline().prd().templateId()).isEqualTo(template.id());
        var other=create();var fresh=confirmReview(other.id());
        var candidate=com._36knight.alpha.workspace.PrdDocuments.withTemplate(prd(fresh.reviewBaseline(),"完整流程",List.of()),template);
        assertThatThrownBy(()->lifecycle.savePrd(other.id(),new SavePrd(fresh.revision(),fresh.reviewBaseline().id(),null,candidate)))
                .isInstanceOf(ProjectException.class).extracting("code").isEqualTo("PRD_TEMPLATE_DELETED");
        assertThat(lifecycle.get(other.id()).prd()).isNull();
    }
    /** 每个用例使用独立项目和业务版本。 */ private WorkspaceViews.Version create(){var p=workspace.createProject(new NewProject("测试产品", "内部运营"));return workspace.createVersion(p.id(),new NewVersion("一期","CSV 导入客户","只支持 CSV，重复手机号提示"));}
    /** 先保存明确验收，再在已保存正文上确认。 */
    private Detail ready(String id){var d=lifecycle.get(id);var r=d.review().requirements().getFirst();
        var withAcceptance=new Requirement(r.id(),r.title(),r.description(),"合法 CSV 可以导入，重复手机号提示",true,r.sourceSuggestionId());
        d=lifecycle.saveReview(id,new SaveReview(d.revision(),new Review(List.of(withAcceptance),List.of(),List.of())));r=d.review().requirements().getFirst();
        return lifecycle.saveReview(id,new SaveReview(d.revision(),new Review(List.of(new Requirement(r.id(),r.title(),r.description(),r.acceptance(),true,r.sourceSuggestionId())),List.of(),List.of())));
    }
    /** 提供完整已确认评审起点。 */ private Detail confirmReview(String id){var d=ready(id);return lifecycle.confirmReview(id,confirmation(d,true));}
    /** 独立幂等确认命令。 */ private Confirm confirmation(Detail d,boolean review){return new Confirm(d.revision(),review?d.reviewRevision():d.prdRevision(),UUID.randomUUID().toString());}
    /** 手工完整文档或显式待确认内容。 */ private Prd prd(Baseline b,String workflow,List<String> unresolved){return new Prd("运营需要导入客户",b.goal(),b.scope(),workflow,"手机号重复跳过","无效行返回失败原因","成功/失败条数可核对","内部使用，不定义未确认性能指标","本期不含分配规则",b.review().requirements().stream().map(Requirement::id).toList(),unresolved);}
    /** 等待真实服务终态，不假定固定生成时间。 */ private Run await(String id)throws Exception{for(int i=0;i<200;i++){var r=lifecycle.getRun(id);if(!List.of("QUEUED","RUNNING").contains(r.status()))return r;Thread.sleep(25);}throw new AssertionError("PRD run did not finish");}
}
