package com._36knight.alpha;

import com._36knight.alpha.project.ProjectException;
import com._36knight.alpha.workspace.WorkbenchContracts;
import com._36knight.alpha.workspace.WorkbenchService;
import com._36knight.alpha.workspace.WorkspaceService;
import com._36knight.alpha.workspace.WorkspaceRepository;
import com._36knight.alpha.workspace.LifecycleService;
import com._36knight.alpha.workspace.PrdDocuments;
import com._36knight.alpha.workspace.WorkspaceViews;
import com._36knight.alpha.workspace.persistence.WorkspaceRunEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import static com._36knight.alpha.workspace.WorkspaceRequests.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 回收站保真、共享关系与旧窗口写入边界；使用独立 H2，不调用模型。@author Andersen */
@SpringBootTest(properties="spring.datasource.dynamic.datasource.master.url=jdbc:h2:mem:session-deletion;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class SessionDeletionIntegrationTest {
    /** 会话事务边界。 */ @Autowired private WorkspaceService workspace;
    /** PRD 自动保存和打开入口。 */ @Autowired private WorkbenchService workbench;
    /** 读取历史与定稿。 */ @Autowired private LifecycleService lifecycle;
    /** 注入合成运行状态以确定性校验执行中保护。 */ @Autowired private WorkspaceRepository repository;
    /** HTTP 校验层。 */ @Autowired private MockMvc mvc;
    /** 合成 JSON 请求。 */ @Autowired private ObjectMapper json;
    /** 创建独立空 PRD。 */
    private WorkbenchContracts.Detail create() {
        var p=workspace.createProject(new NewProject("回收站测试","合成数据"));
        return workbench.open(workspace.createSession(p.id(),new NewSession("待整理 PRD",null)).id());
    }
    /** 根据确认快照构造精确删除命令。 */
    private SessionDeletion command(WorkspaceViews.DeletionPreview p) {
        return new SessionDeletion(p.session().revision(),true,p.versionRevision(),p.documentRevision(),p.includesPrd());
    }
    /** 删除后普通目录与版本入口不泄漏，恢复同身份、正文、问题和保存历史。 */
    @Test void preservesDocumentAndRejectsAllStaleWrites() {
        var d=create();String id=d.workspace().session().id(),v=d.workspace().version().id(),project=d.workspace().project().id();
        var prd=PrdDocuments.patch(d.lifecycle().prd(),Map.of("scope","保留用户正文"),List.of("权限待确认"));
        d=workbench.save(id,new WorkbenchContracts.Draft(d.lifecycle().revision(),d.workspace().version().revision(),prd));
        var before=d;var history=lifecycle.history(v,"PRD",1);var preview=workspace.deletionPreview(id);
        var removed=workspace.deleteSession(id,command(preview));
        assertThat(removed.deletedAt()).isNotNull();assertThat(workspace.sessions(project,null,1,false).items()).isEmpty();
        assertThat(workspace.versions(project,1,false).items()).isEmpty();assertThat(workspace.deletedSessions(1).items()).extracting(WorkspaceViews.Session::id).contains(id);
        assertThat(workbench.open(id).lifecycle().prd()).isEqualTo(prd);
        assertThat(workbench.openVersion(v).workspace().session()).isNull();
        assertThat(workbench.openVersion(v).lifecycle().prd()).isEqualTo(prd);
        assertThatThrownBy(()->workbench.save(id,new WorkbenchContracts.Draft(before.lifecycle().revision(),before.workspace().version().revision(),prd))).isInstanceOf(ProjectException.class);
        assertThatThrownBy(()->workspace.renameSession(id,new RenameSession(removed.revision(),"不允许"))).isInstanceOf(ProjectException.class);
        assertThatThrownBy(()->workspace.archiveSession(id,new Archive(removed.revision(),false))).isInstanceOf(ProjectException.class);
        assertThatThrownBy(()->workspace.createSession(project,new NewSession("不能新关联",v))).isInstanceOf(ProjectException.class);
        var restored=workspace.deleteSession(id,new SessionDeletion(removed.revision(),false,null,null,false));
        assertThat(restored.deletedAt()).isNull();assertThat(restored.id()).isEqualTo(id);
        assertThat(workspace.getVersion(v).deletedAt()).isNull();assertThat(lifecycle.history(v,"PRD",1)).isEqualTo(history);
        assertThat(workbench.open(id).lifecycle().prd()).isEqualTo(prd);
        assertThatThrownBy(()->workspace.deleteSession(id,command(preview))).isInstanceOf(ProjectException.class);
        var current=workbench.get(id);
        assertThat(workbench.save(id,new WorkbenchContracts.Draft(current.lifecycle().revision(),current.workspace().version().revision(),prd)).lifecycle().prd()).isEqualTo(prd);
    }
    /** 共享文档只在最后保留会话被删除后回收，恢复任一会话即恢复原文档。 */
    @Test void sharedAndArchivedSessionsProtectTheDocument() {
        var d=create();String id=d.workspace().session().id(),v=d.workspace().version().id(),project=d.workspace().project().id();
        var second=workspace.createSession(project,new NewSession("第二会话",v));
        second=workspace.archiveSession(second.id(),new Archive(second.revision(),true));
        var preview=workspace.deletionPreview(id);assertThat(preview.includesPrd()).isFalse();assertThat(preview.otherSessions()).isEqualTo(1);
        var firstRemoved=workspace.deleteSession(id,command(preview));assertThat(workspace.getVersion(v).deletedAt()).isNull();
        var secondRemoved=workspace.deleteSession(second.id(),command(workspace.deletionPreview(second.id())));
        assertThat(workspace.getVersion(v).deletedAt()).isNotNull();
        workspace.deleteSession(id,new SessionDeletion(firstRemoved.revision(),false,null,null,false));
        assertThat(workspace.getVersion(v).deletedAt()).isNull();
        var secondRestored=workspace.deleteSession(second.id(),new SessionDeletion(secondRemoved.revision(),false,null,null,false));
        assertThat(secondRestored.archivedAt()).isEqualTo(second.archivedAt());
    }
    /** 确认弹窗期间发生正文保存或新共享关系时，必须重新确认。 */
    @Test void rejectsChangedDocumentAndSharingScope() {
        var d=create();String id=d.workspace().session().id();var preview=workspace.deletionPreview(id);
        workbench.save(id,new WorkbenchContracts.Draft(d.lifecycle().revision(),d.workspace().version().revision(),PrdDocuments.patch(d.lifecycle().prd(),Map.of("scope","另一窗口已修改"),null)));
        assertThatThrownBy(()->workspace.deleteSession(id,command(preview))).isInstanceOf(ProjectException.class);
        var next=workspace.deletionPreview(id);
        workspace.createSession(d.workspace().project().id(),new NewSession("另一个会话",d.workspace().version().id()));
        assertThatThrownBy(()->workspace.deleteSession(id,command(next))).isInstanceOf(ProjectException.class);
        assertThat(workspace.getSession(id).session().deletedAt()).isNull();
    }
    /** 已定稿会话恢复后仍使用同一确认快照，不把定稿变回草稿。 */
    @Test void finalizedBaselineSurvivesDeletion() {
        var d=create();String id=d.workspace().session().id();
        var page=new com._36knight.alpha.workspace.PrdStructure.Page("list","人员列表","LIST",Map.of("overview","查看人员","fields","姓名","search","姓名查询","sorting","编码升序","actions","查看","states","空数据提示","acceptance","正确返回人员"));
        var module=new com._36knight.alpha.workspace.PrdStructure.Module("people","人员管理",Map.of("overview","维护人员","rules","编码唯一","permissions","管理员维护","acceptance","可查询"),List.of(page));
        var prd=new com._36knight.alpha.workspace.LifecycleContracts.Prd("背景","目标","范围","流程","规则","异常","验收","边界","排除",List.of(),List.of(),2,List.of(module));
        d=workbench.save(id,new WorkbenchContracts.Draft(d.lifecycle().revision(),d.workspace().version().revision(),prd));
        d=workbench.confirmPrd(id,new com._36knight.alpha.workspace.LifecycleContracts.Confirm(d.lifecycle().revision(),d.lifecycle().prdRevision(),UUID.randomUUID().toString()));
        var baseline=d.lifecycle().prdBaseline();
        var preview=workspace.versionDeletionPreview(d.workspace().version().id());
        var removedDocument=workspace.deleteVersion(preview.version().id(),new VersionDeletion(preview.version().revision(),true,preview.documentRevision(),preview.linkedSessions()));
        workspace.deleteVersion(removedDocument.id(),new VersionDeletion(removedDocument.revision(),false,null,null));
        assertThat(workbench.get(id).lifecycle().prdBaseline()).isEqualTo(baseline);
        var deleted=workspace.deleteSession(id,command(workspace.deletionPreview(id)));
        workspace.deleteSession(id,new SessionDeletion(deleted.revision(),false,null,null,false));
        assertThat(workbench.open(id).lifecycle().prdBaseline()).isEqualTo(baseline);
        assertThat(workbench.get(id).workspace().version().stage()).isEqualTo("PROTOTYPE");
    }
    /** 服务端根据真实任务状态阻止删除，客户端禁用不是唯一保护。 */
    @Test void rejectsRunningTasksAndInvalidHttpCommands() throws Exception {
        var d=create();String id=d.workspace().session().id();
        var run=new WorkspaceRunEntity();run.id=UUID.randomUUID().toString();run.projectId=d.workspace().project().id();run.sessionId=id;run.versionId=d.workspace().version().id();run.requestId=UUID.randomUUID().toString();run.kind="WORKBENCH";run.status="QUEUED";run.inputText="合成任务";run.contextText="{}";run.mode="demo";run.model="local";run.draftRevision=0L;run.createdAt=OffsetDateTime.now();run.updatedAt=run.createdAt;
        repository.insert(run);
        assertThatThrownBy(()->workspace.deleteSession(id,command(workspace.deletionPreview(id)))).isInstanceOf(ProjectException.class);
        var documentPreview=workspace.versionDeletionPreview(d.workspace().version().id());
        assertThatThrownBy(()->workspace.deleteVersion(documentPreview.version().id(),new VersionDeletion(documentPreview.version().revision(),true,documentPreview.documentRevision(),documentPreview.linkedSessions()))).isInstanceOf(ProjectException.class);
        run.status="INTERRUPTED";repository.update(run);
        mvc.perform(put("/api/alpha/workspace/sessions/"+id+"/deletion").contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":1,\"includesPrd\":true}" )).andExpect(status().isBadRequest());
        mvc.perform(put("/api/alpha/workspace/sessions/"+id+"/deletion").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(command(workspace.deletionPreview(id))))).andExpect(status().isOk()).andExpect(jsonPath("$.data.deletedAt").isNotEmpty());
    }
    /** 文档独立删除保留所有会话和 PRD；会话删除恢复不能撤销文档删除。 */
    @Test void documentDeletionPreservesSessionsAndRequiresExplicitDocumentRestore() {
        var d=create();String id=d.workspace().session().id(),v=d.workspace().version().id(),project=d.workspace().project().id();
        var prd=PrdDocuments.patch(d.lifecycle().prd(),Map.of("scope","独立文档正文"),List.of("字段规则待确认"));
        d=workbench.save(id,new WorkbenchContracts.Draft(d.lifecycle().revision(),d.workspace().version().revision(),prd));
        var before=d;var history=lifecycle.history(v,"PRD",1);
        var second=workspace.createSession(project,new NewSession("共享会话",v));
        var preview=workspace.versionDeletionPreview(v);
        assertThat(preview.linkedSessions()).isEqualTo(2);
        var removed=workspace.deleteVersion(v,new VersionDeletion(preview.version().revision(),true,preview.documentRevision(),preview.linkedSessions()));
        assertThat(workspace.versions(project,1,false).items()).isEmpty();
        assertThat(workspace.sessions(project,null,1,false).items()).hasSize(2);
        assertThat(workspace.deletedVersions(1).items()).extracting(WorkspaceViews.Version::id).contains(v);
        assertThat(workbench.openVersion(v).workspace().session()).isNull();
        assertThat(workbench.openVersion(v).lifecycle().prd()).isEqualTo(prd);
        assertThat(workbench.open(id).lifecycle().prd()).isEqualTo(prd);
        assertThatThrownBy(()->workbench.save(id,new WorkbenchContracts.Draft(before.lifecycle().revision(),before.workspace().version().revision(),prd))).isInstanceOf(ProjectException.class);
        assertThatThrownBy(()->workspace.createSession(project,new NewSession("旧链接不能新关联",v))).isInstanceOf(ProjectException.class);
        var first=workspace.deleteSession(id,command(workspace.deletionPreview(id)));
        var last=workspace.deleteSession(second.id(),command(workspace.deletionPreview(second.id())));
        workspace.deleteSession(id,new SessionDeletion(first.revision(),false,null,null,false));
        workspace.deleteSession(second.id(),new SessionDeletion(last.revision(),false,null,null,false));
        assertThat(workspace.getVersion(v).deletedAt()).isEqualTo(removed.deletedAt());
        var restored=workspace.deleteVersion(v,new VersionDeletion(removed.revision(),false,null,null));
        assertThat(restored.id()).isEqualTo(v);assertThat(restored.deletedAt()).isNull();
        assertThat(lifecycle.history(v,"PRD",1)).isEqualTo(history);
        assertThat(workbench.open(id).lifecycle().prd()).isEqualTo(prd);
        var current=workbench.get(id);
        assertThat(workbench.save(id,new WorkbenchContracts.Draft(current.lifecycle().revision(),current.workspace().version().revision(),prd)).lifecycle().prd()).isEqualTo(prd);
        assertThatThrownBy(()->workspace.deleteVersion(v,new VersionDeletion(removed.revision(),false,null,null))).isInstanceOf(ProjectException.class);
    }
    /** 正文和共享范围在确认期间变化时拒绝，防止用户按旧范围删除。 */
    @Test void documentDeletionRejectsStaleContentAndRelations() {
        var d=create();String id=d.workspace().session().id(),v=d.workspace().version().id();
        var preview=workspace.versionDeletionPreview(v);
        workbench.save(id,new WorkbenchContracts.Draft(d.lifecycle().revision(),d.workspace().version().revision(),PrdDocuments.patch(d.lifecycle().prd(),Map.of("scope","新正文"),null)));
        assertThatThrownBy(()->workspace.deleteVersion(v,new VersionDeletion(preview.version().revision(),true,preview.documentRevision(),preview.linkedSessions()))).isInstanceOf(ProjectException.class);
        var current=workspace.versionDeletionPreview(v);
        workspace.createSession(d.workspace().project().id(),new NewSession("新共享",v));
        assertThatThrownBy(()->workspace.deleteVersion(v,new VersionDeletion(current.version().revision(),true,current.documentRevision(),current.linkedSessions()))).isInstanceOf(ProjectException.class);
        assertThat(workspace.getVersion(v).deletedAt()).isNull();
    }
    /** 无会话、无 PRD 产物的历史文档也能回收查看，不能隐式创建会话或初始化产物。 */
    @Test void standaloneDocumentCanBeDeletedAndViewedWithoutInitialization() throws Exception {
        var p=workspace.createProject(new NewProject("旧文档","测试"));
        var v=workspace.createVersion(p.id(),new NewVersion("旧版本","交付目标","原始范围"));
        var preview=workspace.versionDeletionPreview(v.id());assertThat(preview.documentRevision()).isNull();
        mvc.perform(put("/api/alpha/workspace/versions/"+v.id()+"/deletion").contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/alpha/workspace/versions/"+v.id()+"/deletion").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new VersionDeletion(v.revision(),true,null,0L))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.deletedAt").isNotEmpty());
        assertThat(workbench.openVersion(v.id()).lifecycle()).isNull();
        assertThat(workbench.openVersion(v.id()).workspace().version().content()).isEqualTo("原始范围");
        assertThat(workspace.sessions(p.id(),null,1,false).items()).isEmpty();
        var removed=workspace.getVersion(v.id());
        var archived=workspace.archiveProject(p.id(),new Archive(p.revision(),true));
        assertThatThrownBy(()->workspace.deleteVersion(v.id(),new VersionDeletion(removed.revision(),false,null,null))).isInstanceOf(ProjectException.class);
        workspace.archiveProject(p.id(),new Archive(archived.revision(),false));
        workspace.deleteVersion(v.id(),new VersionDeletion(removed.revision(),false,null,null));
        assertThat(workspace.getVersion(v.id()).content()).isEqualTo("原始范围");
    }

}
