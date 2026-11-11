package com._36knight.alpha;

import com._36knight.alpha.prototype.PrototypeRepository;
import com._36knight.alpha.prototype.persistence.PrototypeCommandEntity;
import com._36knight.alpha.prototype.persistence.PrototypeConfirmationEntity;
import com._36knight.alpha.prototype.persistence.PrototypeEntity;
import com._36knight.alpha.prototype.persistence.PrototypeRevisionEntity;
import com._36knight.alpha.prototype.persistence.PrototypeRunDetailEntity;
import com._36knight.alpha.prototype.persistence.PrototypeSourceEntity;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 原型存储的真实 Mapper、复合归属与唯一约束测试；全部输入均为合成记录。@author Andersen */
@SpringBootTest(properties="spring.datasource.dynamic.datasource.master.url=jdbc:h2:mem:prototype-storage;DB_CLOSE_DELAY=-1")
@Transactional
class PrototypeStorageConstraintsTest {
    /** 六表真实 MyBatis 持久化入口。 */ @Autowired private PrototypeRepository repository;
    /** 只用于构造父对象和直接验证数据库约束，不替代业务服务。 */ @Autowired private JdbcTemplate jdbc;

    /** 聚合、输入、任务、修订、确认和控制命令可读取；清空执行租约真实写入数据库。 */
    @Test void persistsSixKindsAndClearsNullableExecutionLease() {
        var owner=owner();var prototype=prototype(owner);var source=source(prototype);
        var detail=detail(owner,prototype,source,run(owner,"PROTOTYPE","SUCCEEDED"));repository.insert(detail);
        var revision=revision(prototype,source,1);revision.sourceRunId=detail.runId;repository.insert(revision);
        detail.resultRevisionId=revision.id;detail.applicationStatus="APPLIED";detail.executionToken=null;detail.heartbeatAt=null;repository.update(detail);
        var confirmation=new PrototypeConfirmationEntity();confirmation.id=id();confirmation.prototypeId=prototype.id;
        confirmation.versionId=owner.versionId();confirmation.revisionId=revision.id;confirmation.prdBaselineId=baseline(owner,"PRD");
        confirmation.scopeJson="{}";confirmation.requestId=id();confirmation.commandHash="c".repeat(64);confirmation.createdAt=OffsetDateTime.now();repository.insert(confirmation);
        prototype.currentRevisionId=revision.id;prototype.latestConfirmationId=confirmation.id;prototype.nextRevisionNumber=2L;repository.update(prototype);
        var command=new PrototypeCommandEntity();command.id=id();command.prototypeId=prototype.id;command.kind="APPLY";
        command.requestId=id();command.commandHash="d".repeat(64);command.targetRevisionId=revision.id;command.resultRevisionId=revision.id;
        command.resultJson="{\"applied\":true}";command.createdAt=OffsetDateTime.now();repository.insert(command);
        assertThat(repository.forVersion(owner.versionId()).currentRevisionId).isEqualTo(revision.id);
        assertThat(repository.source(source.id).prdJson).isEqualTo(source.prdJson);
        assertThat(repository.revisionForRun(detail.runId).id).isEqualTo(revision.id);
        assertThat(repository.runDetail(detail.runId).executionToken).isNull();
        assertThat(repository.runDetail(detail.runId).heartbeatAt).isNull();
        assertThat(repository.command(prototype.id,"APPLY",command.requestId).resultJson).isEqualTo(command.resultJson);
        assertThat(repository.confirmation(prototype.id,confirmation.requestId).id).isEqualTo(confirmation.id);
        assertThat(repository.revisions(prototype.id,1).getRecords()).hasSize(1);
        assertThat(repository.confirmations(prototype.id,1).getRecords()).hasSize(1);
        assertThat(repository.runDetails(prototype.id)).hasSize(1);
    }

    /** 复合外键禁止把另一文档的来源或另一聚合的修订设为当前稿。 */
    @Test void rejectsCrossDocumentSourcesAndCurrentRevisions() {
        var first=prototype(owner());var second=prototype(owner());var source=source(second);
        var foreign=revision(second,source,1);repository.insert(foreign);
        first.currentRevisionId=foreign.id;
        assertThatThrownBy(()->repository.update(first)).isInstanceOf(DataIntegrityViolationException.class);
        var crossed=new PrototypeSourceEntity();crossed.id=id();crossed.prototypeId=first.id;crossed.versionId=second.versionId;
        crossed.sourceKind="DRAFT";crossed.prdRevision=1L;crossed.lifecycleRevision=1L;crossed.versionRevision=1L;
        crossed.prdJson="{}";crossed.contextJson="{}";crossed.scopeJson="{}";crossed.fingerprintsJson="{}";
        crossed.contentSha256="a".repeat(64);crossed.createdAt=OffsetDateTime.now();
        assertThatThrownBy(()->repository.insert(crossed)).isInstanceOf(DataIntegrityViolationException.class);
    }

    /** 基线来源必须明确引用同文档 PRD 基线，REVIEW 基线和草稿伪装均被数据库拒绝。 */
    @Test void rejectsReviewBaselineAndInvalidSourceKindBinding() {
        var owner=owner();var prototype=prototype(owner);var source=source(prototype);
        var review=baseline(owner,"REVIEW");
        assertThatThrownBy(()->jdbc.update("UPDATE prototype_sources SET source_kind='BASELINE',prd_baseline_id=? WHERE id=?",review,source.id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE prototype_sources SET source_kind='BASELINE' WHERE id=?",source.id))
                .isInstanceOf(DataIntegrityViolationException.class);
        var prd=baseline(owner,"PRD");
        jdbc.update("UPDATE prototype_sources SET source_kind='BASELINE',prd_baseline_id=? WHERE id=?",prd,source.id);
        assertThat(repository.source(source.id).prdBaselineId).isEqualTo(prd);
    }

    /** 任务扩展只能连接同项目、同文档的 PROTOTYPE 通用任务。 */
    @Test void rejectsWrongRunKindAndCrossProjectRun() {
        var owner=owner();var prototype=prototype(owner);var source=source(prototype);
        var wrongKind=detail(owner,prototype,source,run(owner,"WORKBENCH","SUCCEEDED"));
        assertThatThrownBy(()->repository.insert(wrongKind)).isInstanceOf(DataIntegrityViolationException.class);
        var other=owner();var foreign=detail(owner,prototype,source,run(other,"PROTOTYPE","SUCCEEDED"));
        assertThatThrownBy(()->repository.insert(foreign)).isInstanceOf(DataIntegrityViolationException.class);
    }

    /** 同一任务只允许一个可运行修订；同聚合修订号也不得复用。 */
    @Test void rejectsDuplicateTaskResultsAndRevisionNumbers() {
        var owner=owner();var prototype=prototype(owner);var source=source(prototype);
        var detail=detail(owner,prototype,source,run(owner,"PROTOTYPE","SUCCEEDED"));repository.insert(detail);
        var first=revision(prototype,source,1);first.sourceRunId=detail.runId;repository.insert(first);
        var duplicate=revision(prototype,source,2);duplicate.sourceRunId=detail.runId;
        assertThatThrownBy(()->repository.insert(duplicate)).isInstanceOf(DataIntegrityViolationException.class);
        var reused=revision(prototype,source,1);
        assertThatThrownBy(()->repository.insert(reused)).isInstanceOf(DataIntegrityViolationException.class);
    }

    /** 即使绕过服务，工作空间任务也不能在同项目同时活动；结束后可提交新任务。 */
    @Test void restrictsOneActiveWorkspaceRunPerProject() {
        var owner=owner();var first=run(owner,"CHAT","QUEUED");
        assertThatThrownBy(()->run(owner,"PROTOTYPE","RUNNING")).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE workspace_runs SET status='INTERRUPTED' WHERE id=?",first);
        assertThat(run(owner,"PROTOTYPE","QUEUED")).isNotBlank();
    }

    /** 构造相互一致的项目、文档和关联会话，不调用模型。 */
    private Owner owner() {
        String project=id(),version=id(),session=id();
        jdbc.update("INSERT INTO workspace_projects(id,name,background,revision,created_at,updated_at) VALUES (?,'存储测试','合成背景',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",project);
        jdbc.update("INSERT INTO requirement_versions(id,project_id,label,goal,content,revision,stage,lifecycle,created_at,updated_at) VALUES (?,?,'存储一期','合成目标','合成范围',1,'PRD','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",version,project);
        jdbc.update("INSERT INTO work_sessions(id,project_id,version_id,title,draft_text,draft_revision,revision,message_sequence,created_at,updated_at) VALUES (?,?,?,'存储会话','',0,1,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",session,project,version);
        return new Owner(project,version,session);
    }
    /** 构造空聚合，序号分配器从 1 开始。 */
    private PrototypeEntity prototype(Owner owner) {
        var p=new PrototypeEntity();p.id=id();p.projectId=owner.projectId();p.versionId=owner.versionId();p.revision=1L;p.nextRevisionNumber=1L;
        p.templateId="zero-admin";p.templateRevision=1L;p.createdAt=OffsetDateTime.now();p.updatedAt=p.createdAt;repository.insert(p);return p;
    }
    /** 构造独立草稿快照，合成 JSON 不表示真实 PRD 语义验收。 */
    private PrototypeSourceEntity source(PrototypeEntity prototype) {
        var s=new PrototypeSourceEntity();s.id=id();s.prototypeId=prototype.id;s.versionId=prototype.versionId;s.sourceKind="DRAFT";
        s.prdRevision=1L;s.lifecycleRevision=1L;s.versionRevision=1L;s.prdJson="{\"title\":\"冻结样本\"}";
        s.contextJson="{}";s.scopeJson="{}";s.fingerprintsJson="{}";s.contentSha256="a".repeat(64);s.createdAt=OffsetDateTime.now();repository.insert(s);return s;
    }
    /** 构造同聚合可运行记录，文件校验属于上层 ArtifactStore 的测试责任。 */
    private PrototypeRevisionEntity revision(PrototypeEntity prototype,PrototypeSourceEntity source,long number) {
        var r=new PrototypeRevisionEntity();r.id=id();r.prototypeId=prototype.id;r.revisionNumber=number;r.sourceId=source.id;
        r.templateId=prototype.templateId;r.templateRevision=prototype.templateRevision;r.toolchainId="synthetic-toolchain";
        r.manifestJson="{}";r.validationJson="{}";r.artifactJson="{}";r.mode="demo";r.model="synthetic-storage";
        r.changeSummary="仅验证数据库关系";r.createdAt=OffsetDateTime.now();return r;
    }
    /** 构造通用任务，所有请求 ID 均独立。 */
    private String run(Owner owner,String kind,String status) {
        String run=id();jdbc.update("INSERT INTO workspace_runs(id,project_id,session_id,version_id,version_revision,request_id,kind,input_text,context_text,draft_revision,status,created_at,updated_at) VALUES (?,?,?,?,1,?,?, '合成命令','合成引用',1,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",run,owner.projectId(),owner.sessionId(),owner.versionId(),id(),kind,status);return run;
    }
    /** 构造提交后的任务扩展，并赋予待清空租约。 */
    private PrototypeRunDetailEntity detail(Owner owner,PrototypeEntity prototype,PrototypeSourceEntity source,String runId) {
        var d=new PrototypeRunDetailEntity();d.runId=runId;d.projectId=owner.projectId();d.versionId=owner.versionId();d.prototypeId=prototype.id;d.sourceId=source.id;
        d.operation="GENERATE";d.expectedPrototypeRevision=2L;d.expectedPrdRevision=1L;d.expectedLifecycleRevision=1L;d.expectedVersionRevision=1L;
        d.commandHash="b".repeat(64);d.targetJson="{}";d.phase="FINISHED";d.repairAttempts=0;d.executionToken=id();d.heartbeatAt=OffsetDateTime.now();d.applicationStatus="PENDING";return d;
    }
    /** 合成 PRD 或 REVIEW 基线，用于验证数据库种类约束。 */
    private String baseline(Owner owner,String kind) {
        String baseline=id();jdbc.update("INSERT INTO version_artifact_baselines(id,version_id,kind,source_revision,request_id,content_json,scope_fingerprint,label,goal,scope,mode,created_at) VALUES (?,?,?,1,?,'{}',?,'合成基线','合成目标','合成范围','demo',CURRENT_TIMESTAMP)",baseline,owner.versionId(),kind,id(),"e".repeat(64));return baseline;
    }
    /** 为每个合成业务对象产生独立身份。 */
    private String id() { return UUID.randomUUID().toString(); }
    /** 三个父对象的稳定关联，供存储约束样本复用。 */
    private record Owner(String projectId,String versionId,String sessionId) {}
}
