package com._36knight.alpha;

import com._36knight.alpha.prototype.PrototypeArtifactStore;
import com._36knight.alpha.prototype.PrototypeExecutionGateway;
import com._36knight.alpha.prototype.PrototypeRepository;
import com._36knight.alpha.prototype.PrototypeService;
import com._36knight.alpha.workspace.LifecycleContracts.Prd;
import com._36knight.alpha.workspace.PrdStructure;
import com._36knight.alpha.workspace.WorkbenchContracts;
import com._36knight.alpha.workspace.WorkbenchService;
import com._36knight.alpha.workspace.WorkspaceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static com._36knight.alpha.prototype.PrototypeContracts.*;
import static com._36knight.alpha.workspace.WorkspaceRequests.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 在随机 PostgreSQL schema 验证原型正式迁移及重复升级；绝不操作业务 schema。@author Andersen */
@Tag("postgres")
@SpringBootTest(properties={"alpha.ai.mode=demo","alpha.ai.api-key="})
@ActiveProfiles("postgres-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PrototypePostgresMigrationTest {
    /** 只由测试产生的 schema，所有清理均限制在此前缀和随机身份。 */
    private static final String SCHEMA="alpha_proto_it_"+UUID.randomUUID().toString().replace("-","");
    /** 已被动态属性限定到本次随机 schema 的数据源。 */ @Autowired private DataSource dataSource;
    /** 真实原型应用服务在同一 PostgreSQL 隔离 schema 执行业务事务。 */ @Autowired private PrototypeService prototypes;
    /** 真实 Mapper 用于核对来源、修订和任务扩展没有被替身替换。 */ @Autowired private PrototypeRepository repository;
    /** 真实项目、文档、会话和通用任务入口。 */ @Autowired private WorkspaceService workspace;
    /** 已保存直接 PRD 的真实双修订事务入口。 */ @Autowired private WorkbenchService workbench;
    /** 只手动驱动合成任务，不派发模型或浏览器进程。 */ @MockitoBean private PrototypeExecutionGateway gateway;
    /** 文件验收使用明确 synthetic 替身；文件完整性由专门 ArtifactStore 测试覆盖。 */ @MockitoBean private PrototypeArtifactStore artifacts;
    /** 与服务相同的 JSON 记录编码。 */ @Autowired private ObjectMapper json;

    /** 复用后端本地测试连接配置；只在测试进程取值，不输出数据库凭证。 */
    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        Properties local=new Properties();Path file=Path.of(".env");
        if(Files.exists(file)) {
            try(var input=Files.newInputStream(file)){local.load(input);}
            catch(java.io.IOException exception){throw new IllegalStateException("无法读取本地测试连接配置");}
        }
        String base=System.getenv().getOrDefault("ALPHA_DATABASE_URL",local.getProperty("ALPHA_DATABASE_URL","jdbc:postgresql://localhost:5432/postgres"));
        if(!base.startsWith("jdbc:postgresql:")) throw new IllegalStateException("postgresTest 需要 PostgreSQL 地址");
        String[] parts=base.split("\\?",2);var parameters=new ArrayList<String>();
        if(parts.length==2) for(String parameter:parts[1].split("&")) {
            if(!parameter.startsWith("currentSchema=")&&!parameter.isBlank()) parameters.add(parameter);
        }
        parameters.add("currentSchema="+SCHEMA);
        registry.add("spring.datasource.dynamic.datasource.master.url",()->parts[0]+"?"+String.join("&",parameters));
        registry.add("spring.datasource.dynamic.datasource.master.username",()->System.getenv().getOrDefault("ALPHA_DATABASE_USERNAME",local.getProperty("ALPHA_DATABASE_USERNAME","postgres")));
        registry.add("spring.datasource.dynamic.datasource.master.password",()->System.getenv().getOrDefault("ALPHA_DATABASE_PASSWORD",local.getProperty("ALPHA_DATABASE_PASSWORD","")));
        registry.add("spring.flyway.schemas",()->SCHEMA);registry.add("spring.flyway.default-schema",()->SCHEMA);
        registry.add("alpha.model-config.encryption-key",()->java.util.Base64.getEncoder().encodeToString(new byte[32]));
    }

    /** 空 schema 的正式迁移创建完整六表和 PostgreSQL 部分唯一索引。 */
    @Test void createsSixTablesAndNativeActiveRunIndex() {
        var jdbc=new JdbcTemplate(dataSource);
        for(String table:List.of("prototypes","prototype_sources","prototype_revisions","prototype_run_details","prototype_confirmations","prototype_commands")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema=? AND table_name=?",Integer.class,SCHEMA,table)).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='13' AND success",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE schemaname=? AND indexname='uq_workspace_runs_active_project'",String.class,SCHEMA))
                .contains("UNIQUE INDEX","WHERE","QUEUED","RUNNING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema=? AND table_name='workspace_runs' AND column_name='active_project_id'",Integer.class,SCHEMA)).isZero();
    }

    /** V12 存量升级和手工增量/完整脚本的连续重放保留旧正文、任务及新原型快照。 */
    @Test void upgradesV12AndReplaysScriptsWithoutRewritingData() throws Exception {
        String upgrade=SCHEMA+"_v12";var jdbc=new JdbcTemplate(dataSource);
        String project=id(),version=id(),session=id(),run=id(),prototype=id(),source=id(),revision=id();
        try {
            Flyway.configure().dataSource(dataSource).schemas(upgrade).defaultSchema(upgrade).target("12").load().migrate();
            jdbc.update("INSERT INTO "+upgrade+".workspace_projects(id,name,background,revision,created_at,updated_at) VALUES (?,'原项目','保留背景',7,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",project);
            jdbc.update("INSERT INTO "+upgrade+".requirement_versions(id,project_id,label,goal,content,revision,stage,lifecycle,created_at,updated_at,deleted_at,independently_deleted) VALUES (?,?,'旧文档','旧目标','保留范围',3,'PROTOTYPE','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,TRUE)",version,project);
            jdbc.update("INSERT INTO "+upgrade+".work_sessions(id,project_id,version_id,title,draft_text,draft_revision,revision,message_sequence,created_at,updated_at) VALUES (?,?,?,'旧会话','保留草稿',4,5,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",session,project,version);
            jdbc.update("INSERT INTO "+upgrade+".workspace_runs(id,project_id,session_id,version_id,request_id,kind,input_text,context_text,draft_revision,status,result_text,created_at,updated_at) VALUES (?,?,?,?,?,'WORKBENCH','原问题','原上下文',4,'SUCCEEDED','原结果',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",run,project,session,version,id());
            var flyway=Flyway.configure().dataSource(dataSource).schemas(upgrade).defaultSchema(upgrade).load();flyway.migrate();flyway.migrate();
            jdbc.update("INSERT INTO "+upgrade+".prototypes(id,project_id,version_id,revision,next_revision_number,template_id,template_revision,created_at,updated_at) VALUES (?,?,?,2,2,'zero-admin',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",prototype,project,version);
            jdbc.update("INSERT INTO "+upgrade+".prototype_sources(id,prototype_id,version_id,source_kind,prd_revision,lifecycle_revision,version_revision,prd_json,context_json,scope_json,fingerprints_json,content_sha256,created_at) VALUES (?,?,?,'DRAFT',1,2,3,'{\"text\":\"保留来源\"}','{}','{}','{}',?,CURRENT_TIMESTAMP)",source,prototype,version,"a".repeat(64));
            jdbc.update("INSERT INTO "+upgrade+".prototype_revisions(id,prototype_id,revision_number,source_id,template_id,template_revision,toolchain_id,manifest_json,validation_json,artifact_json,mode,change_summary,created_at) VALUES (?,?,1,?,'zero-admin',1,'synthetic-migration','{}','{}','{}','demo','合成迁移记录',CURRENT_TIMESTAMP)",revision,prototype,source);
            jdbc.update("UPDATE "+upgrade+".prototypes SET current_revision_id=? WHERE id=?",revision,prototype);
            executeScript(upgrade,"backend/script/v0.1.0/v0.1.0-prototype-persistence.sql");
            executeScript(upgrade,"backend/script/v0.1.0/v0.1.0-prototype-persistence.sql");
            executeScript(upgrade,"backend/script/v0.1.0/v0.1.0-upgrade.sql");
            executeScript(upgrade,"backend/script/v0.1.0/v0.1.0-upgrade.sql");
            assertThat(jdbc.queryForObject("SELECT content FROM "+upgrade+".requirement_versions WHERE id=?",String.class,version)).isEqualTo("保留范围");
            assertThat(jdbc.queryForObject("SELECT independently_deleted FROM "+upgrade+".requirement_versions WHERE id=?",Boolean.class,version)).isTrue();
            assertThat(jdbc.queryForObject("SELECT deleted_at FROM "+upgrade+".requirement_versions WHERE id=?",Object.class,version)).isNotNull();
            assertThat(jdbc.queryForObject("SELECT draft_text FROM "+upgrade+".work_sessions WHERE id=?",String.class,session)).isEqualTo("保留草稿");
            assertThat(jdbc.queryForObject("SELECT result_text FROM "+upgrade+".workspace_runs WHERE id=?",String.class,run)).isEqualTo("原结果");
            assertThat(jdbc.queryForObject("SELECT current_revision_id FROM "+upgrade+".prototypes WHERE id=?",String.class,prototype)).isEqualTo(revision);
            assertThat(jdbc.queryForObject("SELECT prd_json FROM "+upgrade+".prototype_sources WHERE id=?",String.class,source)).isEqualTo("{\"text\":\"保留来源\"}");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM "+upgrade+".prototype_revisions",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM "+upgrade+".flyway_schema_history WHERE version='13' AND success",Integer.class)).isEqualTo(1);
        } finally { jdbc.execute("DROP SCHEMA IF EXISTS "+upgrade+" CASCADE"); }
    }

    /** PostgreSQL 原生部分索引阻止同项目跨种类活动任务，终结后立即释放该项目槽。 */
    @Test void nativeIndexRejectsParallelActiveWorkspaceRuns() {
        var jdbc=new JdbcTemplate(dataSource);String project=id(),session=id(),first=id();
        jdbc.update("INSERT INTO workspace_projects(id,name,background,revision,created_at,updated_at) VALUES (?,'PG并发样本','',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",project);
        jdbc.update("INSERT INTO work_sessions(id,project_id,title,draft_text,draft_revision,revision,message_sequence,created_at,updated_at) VALUES (?,?,'PG会话','',0,1,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",session,project);
        jdbc.update("INSERT INTO workspace_runs(id,project_id,session_id,request_id,kind,input_text,context_text,draft_revision,status,created_at,updated_at) VALUES (?,?,?,?,'CHAT','合成问题','',0,'RUNNING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",first,project,session,id());
        assertThatThrownBy(()->jdbc.update("INSERT INTO workspace_runs(id,project_id,session_id,request_id,kind,input_text,context_text,draft_revision,status,created_at,updated_at) VALUES (?,?,?,?,'PROTOTYPE','合成原型命令','',0,'QUEUED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id(),project,session,id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE workspace_runs SET status='INTERRUPTED' WHERE id=?",first);
        jdbc.update("INSERT INTO workspace_runs(id,project_id,session_id,request_id,kind,input_text,context_text,draft_revision,status,created_at,updated_at) VALUES (?,?,?,?,'PROTOTYPE','合成原型命令','',0,'QUEUED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id(),project,session,id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_runs WHERE project_id=? AND status IN ('QUEUED','RUNNING')",Integer.class,project)).isEqualTo(1);
        // 仅验证索引的合成行没有任务扩展；结束它以避免定时租约扫描误读不完整样本。
        jdbc.update("UPDATE workspace_runs SET status='INTERRUPTED' WHERE project_id=? AND status IN ('QUEUED','RUNNING')",project);
    }

    /** 真实 PG 的提交、领取、完成和候选应用均经过应用事务；仅执行器与文件边界为合成替身。 */
    @Test void persistsSyntheticBusinessTransactionsAndAppliesCandidateIdempotently() throws Exception {
        when(gateway.available()).thenReturn(true);
        when(artifacts.verify(anyString())).thenAnswer(call->syntheticArtifact(call.getArgument(0)));
        var project=workspace.createProject(new NewProject("PG原型事务","PG冻结背景"));
        var version=workspace.createVersion(project.id(),new NewVersion("PG原型文档","业务目标","业务范围"));
        var session=workspace.createSession(project.id(),new NewSession("PG原型讨论",version.id()));
        var document=workbench.open(session.id());
        var page=new PrdStructure.Page("list","人员列表","LIST",Map.of("overview","PG人员资料维护","fields","工号、姓名","search","按姓名查询","sorting","工号升序","actions","新增与编辑","states","空数据提示","acceptance","保存后列表同步"));
        var module=new PrdStructure.Module("people","人员管理",Map.of("overview","PG人员资料维护","rules","工号唯一","permissions","演示权限","acceptance","查询显示新增人员"),List.of(page));
        var prd=new Prd("业务背景","业务目标","业务范围","业务流程","全局规则","异常说明","验收要求","非功能要求","排除内容",List.of(),List.of(),2,List.of(module));
        document=workbench.save(session.id(),new WorkbenchContracts.Draft(document.lifecycle().revision(),document.workspace().version().revision(),prd));
        var generate=new Submit(session.id(),id(),"GENERATE",document.workspace().version().revision(),document.lifecycle().revision(),document.lifecycle().prdRevision(),0,null,"zero-admin",2,new Source("DRAFT",null),new Scope("ALL",List.of()),new Target("PROTOTYPE",null,null),"合成PG首次生成",null);
        var first=prototypes.submit(version.id(),generate);
        assertThat(prototypes.submit(version.id(),generate).id()).isEqualTo(first.id());
        verify(gateway,times(1)).dispatch(first.id());
        var firstExecution=prototypes.claim(first.id(),"synthetic","postgres-p3-synthetic");assertThat(firstExecution).isNotNull();
        var source=repository.source(repository.runDetail(first.id()).sourceId);
        String frozenPrd=source.prdJson,frozenContext=source.contextJson,frozenHash=source.contentSha256;
        assertThat(frozenPrd).contains("PG人员资料维护");assertThat(frozenContext).contains("PG冻结背景");
        assertThat(prototypes.complete(first.id(),firstExecution.token(),syntheticCompletion(firstExecution,'a'))).isTrue();
        var current=prototypes.get(version.id());String firstRevision=current.current().id();
        assertThat(repository.revisionForRun(first.id()).id).isEqualTo(firstRevision);
        assertThat(repository.revision(firstRevision).mode).isEqualTo("synthetic");
        assertThat(repository.runDetail(first.id()).applicationStatus).isEqualTo("APPLIED");
        assertThat(repository.runDetail(first.id()).executionToken).isNull();
        assertThat(workspace.getRun(first.id()).status()).isEqualTo("SUCCEEDED");
        assertThat(prototypes.complete(first.id(),firstExecution.token(),syntheticCompletion(firstExecution,'a'))).isFalse();

        document=workbench.get(session.id());
        var edit=new Submit(session.id(),id(),"EDIT",document.workspace().version().revision(),document.lifecycle().revision(),document.lifecycle().prdRevision(),current.revision(),firstRevision,"zero-admin",2,new Source("EXISTING",source.id),current.current().source().scope(),new Target("PROTOTYPE",null,null),"合成PG编辑列表",null);
        var second=prototypes.submit(version.id(),edit);
        var secondExecution=prototypes.claim(second.id(),"synthetic","postgres-p3-synthetic");assertThat(secondExecution).isNotNull();
        var beforeRename=workspace.getVersion(version.id());
        workspace.saveVersion(version.id(),new EditVersion(beforeRename.revision(),"PG仅调整文档名称",beforeRename.goal(),beforeRename.content()));
        assertThat(prototypes.complete(second.id(),secondExecution.token(),syntheticCompletion(secondExecution,'b'))).isTrue();
        var candidate=repository.runDetail(second.id());
        assertThat(candidate.applicationStatus).isEqualTo("CONFLICT");
        assertThat(prototypes.get(version.id()).current().id()).isEqualTo(firstRevision);
        assertThat(prototypes.revision(version.id(),candidate.resultRevisionId).freshness()).isEqualTo("CURRENT");
        assertThat(repository.revision(candidate.resultRevisionId).sourceId).isEqualTo(source.id);
        assertThat(repository.source(source.id).prdJson).isEqualTo(frozenPrd);
        assertThat(repository.source(source.id).contextJson).isEqualTo(frozenContext);
        assertThat(repository.source(source.id).contentSha256).isEqualTo(frozenHash);
        document=workbench.get(session.id());current=prototypes.get(version.id());
        var apply=new Apply(id(),second.id(),candidate.resultRevisionId,current.revision(),document.workspace().version().revision(),document.lifecycle().revision(),document.lifecycle().prdRevision());
        var applied=prototypes.apply(version.id(),apply);
        assertThat(prototypes.apply(version.id(),apply)).isEqualTo(applied);
        assertThat(repository.forVersion(version.id()).currentRevisionId).isEqualTo(candidate.resultRevisionId);
        assertThat(repository.runDetail(second.id()).applicationStatus).isEqualTo("APPLIED");
        assertThat(repository.command(current.id(),"APPLY",apply.requestId()).resultRevisionId).isEqualTo(candidate.resultRevisionId);
        assertThat(prototypes.revisions(version.id(),1).items()).hasSize(2);
        assertThat(workspace.messages(session.id(),null).items()).hasSize(4);
        assertThat(workspace.getRun(second.id()).status()).isEqualTo("SUCCEEDED");
        assertThat(json.writeValueAsString(workspace.getRun(second.id()))).doesNotContain(secondExecution.token(),"executionToken","contextText","PG冻结背景");
    }

    /** 合成完成清单引用冻结范围，不将替身标记为真实模型生成或浏览器验收。 */
    private Completion syntheticCompletion(Execution execution,char object) {
        var scope=execution.source().scope();var page=scope.pageRefs().getFirst();
        JsonNode manifest=json.valueToTree(Map.of("schemaVersion",1,"scope",scope,
                "pages",List.of(Map.of("id","personnel-list","route","/personnel","entry","src/features/generated/list.vue","sourceRefs",List.of(page))),
                "coverage",scope.pageRefs().stream().map(ref->Map.of("moduleId",ref.moduleId(),"pageId",ref.pageId(),"status","IMPLEMENTED")).toList(),
                "assumptions",List.of(),"unsupported",List.of(),"files",List.of()));
        return new Completion(String.valueOf(object).repeat(64),manifest,"合成 PostgreSQL 事务验证，未调用模型");
    }
    /** 文件边界替身包含明确 mode/generation，不提供宿主路径或伪装运行证据。 */
    private PrototypeArtifactStore.VerifiedArtifact syntheticArtifact(String objectKey) {
        JsonNode validation=json.valueToTree(Map.of("status","PASSED","mode","synthetic","cases",List.of(Map.of("name","synthetic-postgres-transaction-fixture","status","PASSED"))));
        JsonNode artifact=json.valueToTree(Map.of("objectKey",objectKey,"mode","synthetic","generation","test-fixture","templateDigest","1".repeat(64),"sourceDigest","2".repeat(64),
                "sources",List.of(Map.of("objectKey",objectKey,"path","src/features/generated/list.vue","sha256","3".repeat(64),"bytes",24)),
                "files",List.of(Map.of("objectKey",objectKey,"path","index.html","sha256","4".repeat(64),"bytes",16))));
        return new PrototypeArtifactStore.VerifiedArtifact(objectKey,"sha256:"+"5".repeat(64),validation,artifact);
    }

    /** JDBC 整体执行 PostgreSQL 脚本以正确保留 DO 块；事务失败回滚，连接 schema 使用后恢复。 */
    private void executeScript(String schema,String path) throws Exception {
        if(!schema.equals(SCHEMA+"_v12")) throw new IllegalArgumentException("非本测试升级 schema");
        try(var connection=dataSource.getConnection()) {
            String original=connection.getSchema();boolean autoCommit=connection.getAutoCommit();
            try {
                connection.setSchema(schema);connection.setAutoCommit(false);
                try(var statement=connection.createStatement()){statement.execute(Files.readString(Path.of(path)));}
                connection.commit();
            } catch(Exception exception) { connection.rollback();throw exception; }
            finally { connection.setAutoCommit(autoCommit);connection.setSchema(original); }
        }
    }
    /** 只清理本次生成的隔离 schema，开发和生产 schema 不在可操作范围。 */
    @AfterAll void cleanup() {
        if(!SCHEMA.matches("alpha_proto_it_[0-9a-f]{32}")) throw new IllegalStateException("测试 schema 名称不安全");
        new JdbcTemplate(dataSource).execute("DROP SCHEMA IF EXISTS "+SCHEMA+" CASCADE");
    }
    /** 合成迁移数据使用互不冲突的业务身份。 */
    private String id(){return UUID.randomUUID().toString();}
}
