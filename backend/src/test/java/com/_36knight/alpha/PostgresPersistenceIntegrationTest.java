package com._36knight.alpha;

import com._36knight.alpha.project.*;
import com._36knight.alpha.model.ModelCatalogRepository;
import com._36knight.alpha.model.ModelCatalogService;
import com._36knight.alpha.model.SaveModelPlatformRequest;
import com._36knight.alpha.model.SaveModelProfileRequest;
import com._36knight.alpha.model.SaveModelSelectionRequest;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.*;

/**
 * 显式 postgresTest 任务运行：使用真实 PostgreSQL 与随机隔离 schema，不读写开发项目。
 * @author Andersen
 */
@Tag("postgres")
@SpringBootTest(properties = {"alpha.ai.mode=demo", "alpha.ai.api-key="})
@ActiveProfiles("postgres-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresPersistenceIntegrationTest {
    /** 每次验证创建独立 schema，清理仅针对本次生成名称。 */
    private static final String SCHEMA = "alpha_it_" + UUID.randomUUID().toString().replace("-", "");
    /** 真实 Mapper 与事务边界。 */
    @Autowired private ProjectRepository repository;
    /** 用业务服务验证生成、编辑和版本来源。 */
    @Autowired private ProjectService service;
    /** SQL 仅用于验证类型及清理测试 schema，业务访问由 MyBatis Plus 实现。 */
    @Autowired private DataSource dataSource;
    /** 验证新增 V2 的配置业务入口。 */
    @Autowired private ModelCatalogService configurations;
    /** 重建服务时继续使用同一真实 PostgreSQL Mapper。 */
    @Autowired private ModelCatalogRepository configurationRepository;
    /** 正式生命周期业务规则与基线事务。 */
    @Autowired private com._36knight.alpha.workspace.LifecycleService lifecycle;
    /** 新工作空间业务，使用同一随机 PostgreSQL schema。 */
    @Autowired private com._36knight.alpha.workspace.WorkspaceService workspace;
    /** 统一工作空间在真实数据库上的任务与自动落稿。 */
    @Autowired private com._36knight.alpha.workspace.WorkbenchService workbench;

    /** 动态模板使用当前隔离 schema 的真实持久化和事务。 */
    @Autowired private com._36knight.alpha.template.PrdTemplateService prdTemplates;

    /** PostgreSQL 中发布历史不可变，旧发布幂等请求不会误发布后来草稿。 */
    @Test void dynamicPrdTemplatesPersistImmutableVersionsAndReplaySafely() {
        var command=new com._36knight.alpha.template.PrdTemplateContracts.Copy("mobile",1,"PG移动模板",UUID.randomUUID().toString());
        var item=prdTemplates.copy(command);assertThat(prdTemplates.copy(command).id()).isEqualTo(item.id());
        var publish=new com._36knight.alpha.template.PrdTemplateContracts.Publish(item.revision(),UUID.randomUUID().toString());
        item=prdTemplates.publish(item.id(),publish);var first=prdTemplates.resolve(item.id(),1);
        var draft=prdTemplates.openDraft(item.id(),new com._36knight.alpha.template.PrdTemplateContracts.OpenDraft(item.revision()));
        assertThat(prdTemplates.publish(item.id(),publish).draft()).isEqualTo(draft.draft());
        var d=draft.draft();var config=new com._36knight.alpha.template.PrdTemplateContracts.Configuration("PG移动模板第二版",d.description(),d.defaultPageType(),d.globalSections(),d.moduleSections(),d.pageSections(),d.pageTypes(),d.requiredGlobalSections(),d.requiredModuleSections());
        draft=prdTemplates.save(item.id(),new com._36knight.alpha.template.PrdTemplateContracts.Save(draft.revision(),config));
        prdTemplates.publish(item.id(),new com._36knight.alpha.template.PrdTemplateContracts.Publish(draft.revision(),UUID.randomUUID().toString()));
        assertThat(prdTemplates.resolve(item.id(),1)).isEqualTo(first);
        assertThat(prdTemplates.resolve(item.id(),2).name()).isEqualTo("PG移动模板第二版");
        var project=workspace.createProject(new com._36knight.alpha.workspace.WorkspaceRequests.NewProject("PG模板绑定","合成场景"));
        var session=workspace.createSession(project.id(),new com._36knight.alpha.workspace.WorkspaceRequests.NewSession("模板会话",null));
        var document=workbench.open(session.id());var prd=com._36knight.alpha.workspace.PrdDocuments.withTemplate(document.lifecycle().prd(),first);
        document=workbench.save(session.id(),new com._36knight.alpha.workspace.WorkbenchContracts.Draft(document.lifecycle().revision(),document.workspace().version().revision(),prd));
        assertThat(workbench.get(session.id()).lifecycle().prd().templateRevision()).isEqualTo(1);
        assertThat(prdTemplates.forDocument(workbench.get(session.id()).lifecycle().prd())).isEqualTo(first);
        var jdbc=new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='9' AND success",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM prd_template_versions WHERE template_id=?",Integer.class,item.id())).isEqualTo(2);
    }
    /** 复制超时后的并发重试仅创建一个模板，真实 PG 唯一约束冲突回滚后正确读取原结果。 */
    @Test void concurrentTemplateCopiesAreIdempotentOnPostgres() throws Exception {
        var command=new com._36knight.alpha.template.PrdTemplateContracts.Copy("general",1,"PG幂等复制",UUID.randomUUID().toString());
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var jobs=java.util.stream.IntStream.range(0,2).mapToObj(index->pool.submit(()->{ready.countDown();start.await(5,TimeUnit.SECONDS);return prdTemplates.copy(command);})).toList();
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(jobs.getFirst().get(10,TimeUnit.SECONDS).id()).isEqualTo(jobs.get(1).get(10,TimeUnit.SECONDS).id());
        }
        assertThat(new JdbcTemplate(dataSource).queryForObject("SELECT count(*) FROM prd_templates WHERE copy_request_id=?",Integer.class,command.requestId())).isEqualTo(1);
    }
    /** 版本脚本反复执行只补缺表，不改写既有模板定义及已发布历史。 */
    @Test void repeatableTemplateSqlPreservesPublishedRows() throws Exception {
        var item=prdTemplates.copy(new com._36knight.alpha.template.PrdTemplateContracts.Copy("management",1,"脚本保留验证",UUID.randomUUID().toString()));
        item=prdTemplates.publish(item.id(),new com._36knight.alpha.template.PrdTemplateContracts.Publish(item.revision(),UUID.randomUUID().toString()));
        var before=prdTemplates.resolve(item.id(),1);
        var script=new org.springframework.core.io.FileSystemResource("backend/script/v0.1.0/v0.1.0-prd-templates.sql");
        var populator=new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(script);populator.execute(dataSource);populator.execute(dataSource);
        assertThat(prdTemplates.resolve(item.id(),1)).isEqualTo(before);
    }
    /** 同一模板的真实 PostgreSQL 行锁保证并发保存只接受一个草稿修订。 */
    @Test void concurrentTemplateDraftSavesRejectStaleRevisionOnPostgres() throws Exception {
        var item=prdTemplates.copy(new com._36knight.alpha.template.PrdTemplateContracts.Copy("general",1,"PG并发保存",UUID.randomUUID().toString()));
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var jobs=List.of("甲版本","乙版本").stream().map(name->pool.submit(()->{
                var d=item.draft();var config=new com._36knight.alpha.template.PrdTemplateContracts.Configuration(name,d.description(),d.defaultPageType(),d.globalSections(),d.moduleSections(),d.pageSections(),d.pageTypes(),d.requiredGlobalSections(),d.requiredModuleSections());
                ready.countDown();start.await(5,TimeUnit.SECONDS);
                try{prdTemplates.save(item.id(),new com._36knight.alpha.template.PrdTemplateContracts.Save(item.revision(),config));return true;}
                catch(ProjectException exception){assertThat(exception.code()).isEqualTo("PRD_TEMPLATE_CONFLICT");return false;}
            })).toList();
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(List.of(jobs.getFirst().get(10,TimeUnit.SECONDS),jobs.get(1).get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
    }
    /** 从已有 V8 库升级 V9 仅增加模板表，旧业务 JSON 与修订完整保留，重跑迁移无额外修改。 */
    @Test void upgradesV8WithoutChangingExistingDocument() {
        String upgrade=SCHEMA+"_templates";var jdbc=new JdbcTemplate(dataSource);String id=UUID.randomUUID().toString();
        try{
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(upgrade).defaultSchema(upgrade).target("8").load().migrate();
            jdbc.update("INSERT INTO "+upgrade+".project_documents(id,name,revision,updated_at,document_json) VALUES (?,?,7,CURRENT_TIMESTAMP,CAST(? AS JSONB))",id,"旧业务资料","{\"templateId\":\"management\",\"templateRevision\":1,\"text\":\"旧正文\"}");
            var migration=org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(upgrade).defaultSchema(upgrade).load();
            migration.migrate();migration.migrate();
            assertThat(jdbc.queryForObject("SELECT document_json->>'text' FROM "+upgrade+".project_documents WHERE id=?",String.class,id)).isEqualTo("旧正文");
            assertThat(jdbc.queryForObject("SELECT revision FROM "+upgrade+".project_documents WHERE id=?",Long.class,id)).isEqualTo(7);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM "+upgrade+".prd_template_versions",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM "+upgrade+".flyway_schema_history WHERE version='9' AND success",Integer.class)).isEqualTo(1);
        }finally{jdbc.execute("DROP SCHEMA IF EXISTS "+upgrade+" CASCADE");}
    }
    /** V10 存量会话升级 V11 后默认可见，连续迁移保留原消息与草稿字段。 */
    @Test void upgradesV10SessionsAndRestoresNullableDeletion() {
        String upgrade=SCHEMA+"_sessions";var jdbc=new JdbcTemplate(dataSource);String project=UUID.randomUUID().toString(),session=UUID.randomUUID().toString();
        try {
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(upgrade).defaultSchema(upgrade).target("10").load().migrate();
            jdbc.update("INSERT INTO "+upgrade+".workspace_projects(id,name,background,revision,created_at,updated_at) VALUES (?,'旧项目','原背景',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",project);
            jdbc.update("INSERT INTO "+upgrade+".work_sessions(id,project_id,title,draft_text,draft_revision,revision,message_sequence,created_at,updated_at) VALUES (?,?,'旧会话','原始草稿',3,4,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",session,project);
            var flyway=org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(upgrade).defaultSchema(upgrade).load();flyway.migrate();flyway.migrate();
            assertThat(jdbc.queryForObject("SELECT draft_text FROM "+upgrade+".work_sessions WHERE id=?",String.class,session)).isEqualTo("原始草稿");
            assertThat(jdbc.queryForObject("SELECT deleted_at FROM "+upgrade+".work_sessions WHERE id=?",Object.class,session)).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM "+upgrade+".flyway_schema_history WHERE version='11' AND success",Integer.class)).isEqualTo(1);
        } finally { jdbc.execute("DROP SCHEMA IF EXISTS "+upgrade+" CASCADE"); }
        var p=workspace.createProject(new com._36knight.alpha.workspace.WorkspaceRequests.NewProject("PG回收站","隔离样本"));
        var d=workbench.open(workspace.createSession(p.id(),new com._36knight.alpha.workspace.WorkspaceRequests.NewSession("保留原文档",null)).id());
        String id=d.workspace().session().id(),v=d.workspace().version().id();var preview=workspace.deletionPreview(id);
        var removed=workspace.deleteSession(id,new com._36knight.alpha.workspace.WorkspaceRequests.SessionDeletion(preview.session().revision(),true,preview.versionRevision(),preview.documentRevision(),true));
        var populator=new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.FileSystemResource("backend/script/v0.1.0/v0.1.0-session-recycle-bin.sql"));populator.execute(dataSource);populator.execute(dataSource);
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM work_sessions WHERE id=?",Object.class,id)).isNotNull();
        workspace.deleteSession(id,new com._36knight.alpha.workspace.WorkspaceRequests.SessionDeletion(removed.revision(),false,null,null,false));
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM work_sessions WHERE id=?",Object.class,id)).isNull();
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM requirement_versions WHERE id=?",Object.class,v)).isNull();
        assertThat(workbench.open(id).lifecycle().prd()).isEqualTo(d.lifecycle().prd());
    }
    /** V11 删除记录升级后保留原状态；单独删除来源必须真实落库并支持清除和重复 SQL。 */
    @Test void upgradesV11AndPersistsIndependentDocumentDeletion() {
        String upgrade=SCHEMA+"_documents";var jdbc=new JdbcTemplate(dataSource);String project=UUID.randomUUID().toString(),version=UUID.randomUUID().toString();
        try {
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(upgrade).defaultSchema(upgrade).target("11").load().migrate();
            jdbc.update("INSERT INTO "+upgrade+".workspace_projects(id,name,background,revision,created_at,updated_at) VALUES (?,'迁移样本','原背景',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",project);
            jdbc.update("INSERT INTO "+upgrade+".requirement_versions(id,project_id,label,goal,content,revision,stage,lifecycle,created_at,updated_at,deleted_at) VALUES (?,?,'旧文档','旧目标','保留原文',3,'PRD','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",version,project);
            var flyway=org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(upgrade).defaultSchema(upgrade).load();flyway.migrate();flyway.migrate();
            assertThat(jdbc.queryForObject("SELECT independently_deleted FROM "+upgrade+".requirement_versions WHERE id=?",Boolean.class,version)).isFalse();
            assertThat(jdbc.queryForObject("SELECT deleted_at FROM "+upgrade+".requirement_versions WHERE id=?",Object.class,version)).isNotNull();
            assertThat(jdbc.queryForObject("SELECT content FROM "+upgrade+".requirement_versions WHERE id=?",String.class,version)).isEqualTo("保留原文");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM "+upgrade+".flyway_schema_history WHERE version='12' AND success",Integer.class)).isEqualTo(1);
        } finally { jdbc.execute("DROP SCHEMA IF EXISTS "+upgrade+" CASCADE"); }
        var p=workspace.createProject(new com._36knight.alpha.workspace.WorkspaceRequests.NewProject("PG文档回收","隔离样本"));
        var d=workbench.open(workspace.createSession(p.id(),new com._36knight.alpha.workspace.WorkspaceRequests.NewSession("保留会话",null)).id());
        String id=d.workspace().version().id();var preview=workspace.versionDeletionPreview(id);
        var removed=workspace.deleteVersion(id,new com._36knight.alpha.workspace.WorkspaceRequests.VersionDeletion(preview.version().revision(),true,preview.documentRevision(),preview.linkedSessions()));
        var populator=new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.FileSystemResource("backend/script/v0.1.0/v0.1.0-document-recycle-bin.sql"));populator.execute(dataSource);populator.execute(dataSource);
        assertThat(jdbc.queryForObject("SELECT independently_deleted FROM requirement_versions WHERE id=?",Boolean.class,id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM requirement_versions WHERE id=?",Object.class,id)).isNotNull();
        assertThat(workspace.getSession(d.workspace().session().id()).session().deletedAt()).isNull();
        workspace.deleteVersion(id,new com._36knight.alpha.workspace.WorkspaceRequests.VersionDeletion(removed.revision(),false,null,null));
        assertThat(jdbc.queryForObject("SELECT independently_deleted FROM requirement_versions WHERE id=?",Boolean.class,id)).isFalse();
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM requirement_versions WHERE id=?",Object.class,id)).isNull();
        assertThat(workbench.open(d.workspace().session().id()).lifecycle().prd()).isEqualTo(d.lifecycle().prd());
    }
    /** V9 存量模板升级 V10 默认可用，草稿和发布历史原文不变。 */
    @Test void upgradesV9TemplatesWithoutLosingDefinitions(){
        String upgrade=SCHEMA+"_deletion";var jdbc=new JdbcTemplate(dataSource);String id="custom_"+UUID.randomUUID();String request=UUID.randomUUID().toString();
        try{
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(upgrade).defaultSchema(upgrade).target("9").load().migrate();
            jdbc.update("INSERT INTO "+upgrade+".prd_templates(id,revision,latest_published_revision,draft_json,copy_request_id,copy_command_json,created_at,updated_at) VALUES (?,5,1,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id,"{\"name\":\"原草稿\"}",request,"{}");
            jdbc.update("INSERT INTO "+upgrade+".prd_template_versions(id,template_id,revision,definition_json,publish_request_id,source_revision,created_at) VALUES (?,?,1,?,?,3,CURRENT_TIMESTAMP)",UUID.randomUUID().toString(),id,"{\"name\":\"原发布定义\"}",UUID.randomUUID().toString());
            var flyway=org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(upgrade).defaultSchema(upgrade).load();flyway.migrate();flyway.migrate();
            assertThat(jdbc.queryForObject("SELECT deleted FROM "+upgrade+".prd_templates WHERE id=?",Boolean.class,id)).isFalse();
            assertThat(jdbc.queryForObject("SELECT revision FROM "+upgrade+".prd_templates WHERE id=?",Long.class,id)).isEqualTo(5);
            assertThat(jdbc.queryForObject("SELECT draft_json FROM "+upgrade+".prd_templates WHERE id=?",String.class,id)).isEqualTo("{\"name\":\"原草稿\"}");
            assertThat(jdbc.queryForObject("SELECT definition_json FROM "+upgrade+".prd_template_versions WHERE template_id=?",String.class,id)).isEqualTo("{\"name\":\"原发布定义\"}");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM "+upgrade+".flyway_schema_history WHERE version='10' AND success",Integer.class)).isEqualTo(1);
        }finally{jdbc.execute("DROP SCHEMA IF EXISTS "+upgrade+" CASCADE");}
    }
    /** 真实 PostgreSQL 删除后旧 PRD 可以继续保存，重复增量脚本不会恢复删除状态，显式恢复正确写入 false。 */
    @Test void deletedTemplateRetainsPrdAndExplicitRestorePersistsOnPostgres(){
        var item=prdTemplates.copy(new com._36knight.alpha.template.PrdTemplateContracts.Copy("mobile",1,"PG删除保留",UUID.randomUUID().toString()));
        item=prdTemplates.publish(item.id(),new com._36knight.alpha.template.PrdTemplateContracts.Publish(item.revision(),UUID.randomUUID().toString()));
        var definition=prdTemplates.resolve(item.id(),1);var p=workspace.createProject(new com._36knight.alpha.workspace.WorkspaceRequests.NewProject("PG旧引用","隔离验证"));
        var session=workspace.createSession(p.id(),new com._36knight.alpha.workspace.WorkspaceRequests.NewSession("旧文档",null));var d=workbench.open(session.id());
        var prd=com._36knight.alpha.workspace.PrdDocuments.withTemplate(d.lifecycle().prd(),definition);
        d=workbench.save(session.id(),new com._36knight.alpha.workspace.WorkbenchContracts.Draft(d.lifecycle().revision(),d.workspace().version().revision(),prd));
        var deleted=prdTemplates.deletion(item.id(),new com._36knight.alpha.template.PrdTemplateContracts.Deletion(item.revision(),true));
        var populator=new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.FileSystemResource("backend/script/v0.1.0/v0.1.0-prd-template-deletion.sql"));
        populator.execute(dataSource);populator.execute(dataSource);
        assertThat(new JdbcTemplate(dataSource).queryForObject("SELECT deleted FROM prd_templates WHERE id=?",Boolean.class,item.id())).isTrue();
        prd=com._36knight.alpha.workspace.PrdDocuments.patch(prd,java.util.Map.of("scope","旧稿继续完善"),null);
        d=workbench.save(session.id(),new com._36knight.alpha.workspace.WorkbenchContracts.Draft(d.lifecycle().revision(),d.workspace().version().revision(),prd));
        assertThat(workbench.get(session.id()).lifecycle().prd().scope()).isEqualTo("旧稿继续完善");assertThat(prdTemplates.forDocument(d.lifecycle().prd())).isEqualTo(definition);
        var restored=prdTemplates.deletion(item.id(),new com._36knight.alpha.template.PrdTemplateContracts.Deletion(deleted.revision(),false));
        assertThat(restored.deleted()).isFalse();assertThat(new JdbcTemplate(dataSource).queryForObject("SELECT deleted FROM prd_templates WHERE id=?",Boolean.class,item.id())).isFalse();
        assertThat(prdTemplates.resolve(item.id(),1)).isEqualTo(definition);
    }
    /** 删除与发布竞争同一修订时只接受一个操作，不出现删除后新增发布版本。 */
    @Test void deletingAndPublishingAreSerializedOnPostgres() throws Exception {
        var item=prdTemplates.copy(new com._36knight.alpha.template.PrdTemplateContracts.Copy("general",1,"PG删除发布竞争",UUID.randomUUID().toString()));
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var publish=pool.submit(()->{ready.countDown();start.await(5,TimeUnit.SECONDS);try{prdTemplates.publish(item.id(),new com._36knight.alpha.template.PrdTemplateContracts.Publish(item.revision(),UUID.randomUUID().toString()));return true;}catch(ProjectException exception){assertThat(exception.code()).isIn("PRD_TEMPLATE_DELETED","PRD_TEMPLATE_CONFLICT");return false;}});
            var deletion=pool.submit(()->{ready.countDown();start.await(5,TimeUnit.SECONDS);try{prdTemplates.deletion(item.id(),new com._36knight.alpha.template.PrdTemplateContracts.Deletion(item.revision(),true));return true;}catch(ProjectException exception){assertThat(exception.code()).isEqualTo("PRD_TEMPLATE_CONFLICT");return false;}});
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(List.of(publish.get(10,TimeUnit.SECONDS),deletion.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        var current=prdTemplates.management().stream().filter(value->value.id().equals(item.id())).findFirst().orElseThrow();
        assertThat(current.latestPublishedRevision()).isEqualTo(current.deleted()?0:1);
    }
    /** V7 扩展任务种类后，需求和自动生成 PRD 可在真实 PostgreSQL 中往返。 */
    @Test void unifiedWorkbenchPersistsTaskAndPrd() throws Exception {
        var p=workspace.createProject(new com._36knight.alpha.workspace.WorkspaceRequests.NewProject("统一空间 PG","隔离验证"));
        var session=workspace.createSession(p.id(),new com._36knight.alpha.workspace.WorkspaceRequests.NewSession("一期",null));
        var d=workbench.open(session.id());
        assertThat(d.lifecycle().directPrd()).isTrue();
        assertThat(d.lifecycle().reviewBaseline()).isNull();
        var run=workbench.submit(session.id(),new com._36knight.alpha.workspace.WorkbenchContracts.Message(UUID.randomUUID().toString(),d.lifecycle().revision(),d.workspace().version().revision(),"DOCUMENT","CSV 导入客户"));
        for(int i=0;i<200&&List.of("QUEUED","RUNNING").contains(workspace.getRun(run.id()).status());i++)Thread.sleep(25);
        assertThat(workspace.getRun(run.id()).status()).isEqualTo("SUCCEEDED");
        assertThat(workbench.get(session.id()).lifecycle().prd().scope()).isEqualTo("CSV 导入客户");
        assertThat(workbench.get(session.id()).workspace().version().stage()).isEqualTo("PRD");
        assertThat(new JdbcTemplate(dataSource).queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='8' AND success",Integer.class)).isEqualTo(1);
        assertThat(workspace.messages(session.id(),null).items()).hasSize(2);
        d=workbench.get(session.id());
        var page=new com._36knight.alpha.workspace.PrdStructure.Page("list","人员列表","LIST",java.util.Map.of("fields","姓名与部门","search","姓名模糊查询"));
        var module=new com._36knight.alpha.workspace.PrdStructure.Module("people","人员管理",java.util.Map.of("overview","维护人员"),List.of(page));
        var prd=com._36knight.alpha.workspace.PrdDocuments.withModules(d.lifecycle().prd(),List.of(module));
        prd=com._36knight.alpha.workspace.PrdDocuments.patch(prd,null,List.of("Q01 · 人员列表 · 默认排序待确认"));
        d=workbench.save(session.id(),new com._36knight.alpha.workspace.WorkbenchContracts.Draft(d.lifecycle().revision(),d.workspace().version().revision(),prd));
        assertThat(workbench.get(session.id()).lifecycle().prd()).isEqualTo(prd);
        assertThat(lifecycle.history(d.workspace().version().id(),"PRD",1).items().getFirst().contentJson()).contains("人员列表","默认排序待确认");
    }

    /** 隔离 currentSchema 与 Flyway schema，并沿用本机后端连接信息。 */
    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        Properties local = new Properties();
        Path file = Path.of(".env");
        if (Files.exists(file)) {
            try (var input = Files.newInputStream(file)) { local.load(input); }
            catch (java.io.IOException exception) { throw new IllegalStateException("无法读取本地测试连接配置"); }
        }
        String base = System.getenv().getOrDefault("ALPHA_DATABASE_URL",
                local.getProperty("ALPHA_DATABASE_URL", "jdbc:postgresql://localhost:5432/postgres"));
        if (!base.startsWith("jdbc:postgresql:")) throw new IllegalStateException("postgresTest 需要 PostgreSQL 地址");
        String[] parts = base.split("\\?", 2);
        var parameters = new java.util.ArrayList<String>();
        if (parts.length == 2) {
            for (String parameter : parts[1].split("&")) {
                if (!parameter.startsWith("currentSchema=") && !parameter.isBlank()) parameters.add(parameter);
            }
        }
        parameters.add("currentSchema=" + SCHEMA);
        registry.add("spring.datasource.dynamic.datasource.master.url", () -> parts[0] + "?" + String.join("&", parameters));
        registry.add("spring.datasource.dynamic.datasource.master.username", () -> System.getenv().getOrDefault(
                "ALPHA_DATABASE_USERNAME", local.getProperty("ALPHA_DATABASE_USERNAME", "postgres")));
        registry.add("spring.datasource.dynamic.datasource.master.password", () -> System.getenv().getOrDefault(
                "ALPHA_DATABASE_PASSWORD", local.getProperty("ALPHA_DATABASE_PASSWORD", "")));
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        // 隔离测试使用合成环境主密钥，绝不生成或读取开发主密钥文件。
        registry.add("alpha.model-config.encryption-key", () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
    }

    /** 验证真实迁移、JSONB 对象类型和时区字段，中文/特殊字符可往返。 */
    @Test
    void migratesAndPersistsJsonbWithTimezone() {
        var document = service.create(new CreateProjectRequest("PG 项目", "需求含中文、\"引号\"与换行\n第二行"));
        assertThat(repository.get(document.id())).isEqualTo(document);
        var jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT jsonb_typeof(document_json) FROM project_documents WHERE id = ?",
                String.class, document.id())).isEqualTo("object");
        assertThat(jdbc.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_schema = ? AND table_name = 'project_documents' AND column_name = 'updated_at'",
                String.class, SCHEMA)).isEqualTo("timestamp with time zone");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success", Integer.class)).isEqualTo(1);
        assertThat(repository.list()).extracting(ProjectSummary::id).contains(document.id());
    }

    /** 保存确认与回答后历史保持原样，通过 Mapper 重新读取结果一致。 */
    @Test
    void preservesConfirmationAndImmutableHistory() {
        var initial = service.create(new CreateProjectRequest("评审", "新增客户"));
        var edit = new SaveProjectRequest(1, initial.name(), initial.title(), initial.overview(),
                initial.requirements().stream().map(item -> new SaveProjectRequest.RequirementEdit(item.id(), item.text(),
                        ProjectDocument.RequirementStatus.CONFIRMED)).toList(),
                initial.questions().stream().map(item -> new SaveProjectRequest.QuestionEdit(item.id(), "用户回答")).toList(), List.of());
        var saved = service.save(initial.id(), edit);
        assertThat(saved.revision()).isEqualTo(2);
        assertThat(repository.get(initial.id())).isEqualTo(saved);
        assertThat(repository.version(initial.id(), 1)).isEqualTo(initial);
        assertThat(repository.versions(initial.id())).hasSize(2);
        assertThat(saved.prdMarkdown()).contains("已确认需求", "用户回答", "未调用大模型");
    }

    /** 两个真实 PostgreSQL 事务同时提交相同 revision，仅一个成功。 */
    @Test
    void concurrentWritersKeepOneWinner() throws Exception {
        var initial = service.create(new CreateProjectRequest("并发", "测试需求"));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> concurrentSave(initial, start, "窗口甲"));
            var second = executor.submit(() -> concurrentSave(initial, start, "窗口乙"));
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(repository.get(initial.id()).name()).isIn("窗口甲", "窗口乙");
        assertThat(repository.versions(initial.id())).hasSize(2);
        assertThat(repository.version(initial.id(), 1)).isEqualTo(initial);
    }

    /** 历史唯一约束冲突会回滚同事务内当前文档更新。 */
    @Test
    void versionInsertFailureRollsBackCurrentRow() {
        var initial = service.create(new CreateProjectRequest("回滚", "测试需求"));
        assertThatThrownBy(() -> repository.update(copy(initial, 1, "不应保存"), 1))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(repository.get(initial.id())).isEqualTo(initial);
        assertThat(repository.versions(initial.id())).hasSize(1);
    }

    /** PostgreSQL 分页插件限制项目摘要为 100 条，历史不被截断删除。 */
    @Test
    void paginatesSummariesWithoutLoadingSnapshots() {
        for (int index = 0; index < 101; index++) service.create(new CreateProjectRequest("分页 " + index, "分页测试"));
        assertThat(repository.list()).hasSize(100);
    }

    /** 各项目测试保持 demo，不受其他测试的启用选择影响。 */
    @BeforeEach
    void restoreDemoSelection() {
        configurations.select(new SaveModelSelectionRequest(configurations.get().revision(),"demo",null));
    }

    /** V3 真实存储、重建服务和目录并发修改均使用同一事务契约。 */
    @Test
    void persistsCatalogAndSerializesConcurrentWriters() throws Exception {
        var catalog=configurations.savePlatform(null,new SaveModelPlatformRequest(configurations.get().revision(),"PG 平台","openai-compatible","https://example.com/v1"));
        String platform=catalog.platforms().stream().filter(p -> p.name().equals("PG 平台")).findFirst().orElseThrow().id();
        catalog=configurations.saveProfile(null,new SaveModelProfileRequest(catalog.revision(),platform,"PG 模型","test-model","synthetic-pg-key",false));
        String model=catalog.models().stream().filter(m -> m.name().equals("PG 模型")).findFirst().orElseThrow().id();
        var jdbc=new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='3' AND success",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT api_key_ciphertext FROM model_profiles WHERE id=?",String.class,model)).startsWith("v2:").doesNotContain("synthetic-pg-key");
        configurations.select(new SaveModelSelectionRequest(catalog.revision(),"ai",model));
        var recreated=new ModelCatalogService(configurationRepository,new com._36knight.alpha.model.ModelSecretCipher(java.util.Base64.getEncoder().encodeToString(new byte[32]),"/unused"));
        assertThat(recreated.get()).isEqualTo(configurations.get());
        assertThat(recreated.activeSettings().apiKey()).isEqualTo("synthetic-pg-key");
        long revision=configurations.get().revision(); var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(() -> saveConfigurationTogether(start,revision));
            var second=executor.submit(() -> saveConfigurationTogether(start,revision)); start.countDown();
            assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(configurations.get().revision()).isEqualTo(revision+1);
    }

    /** 真实 PostgreSQL 的 V2 升级 V3 保留旧密文、地址与启用选择。 */
    @Test
    void upgradesExistingV2ConfigurationWithoutReencrypting() {
        String legacy=SCHEMA+"_legacy";
        var jdbc=new JdbcTemplate(dataSource);
        String key=java.util.Base64.getEncoder().encodeToString(new byte[32]);
        var cipher=new com._36knight.alpha.model.ModelSecretCipher(key,"/unused");
        String ciphertext=cipher.encrypt("synthetic-migration-key");
        try {
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(legacy).defaultSchema(legacy)
                    .target(org.flywaydb.core.api.MigrationVersion.fromVersion("2")).load().migrate();
            jdbc.update("INSERT INTO \""+legacy+"\".model_configuration (id,revision,mode,platform,model,base_url,api_key_ciphertext,updated_at) VALUES (1,7,'ai','custom','legacy-model','https://example.com/v1',?,CURRENT_TIMESTAMP)",ciphertext);
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(legacy).defaultSchema(legacy).load().migrate();
            String stored=jdbc.queryForObject("SELECT api_key_ciphertext FROM \""+legacy+"\".model_profiles WHERE id='model-migrated-v2'",String.class);
            assertThat(stored).isEqualTo(ciphertext); assertThat(cipher.decrypt(stored)).isEqualTo("synthetic-migration-key");
            assertThat(jdbc.queryForObject("SELECT active_model_id FROM \""+legacy+"\".model_runtime_selection",String.class)).isEqualTo("model-migrated-v2");
            assertThat(jdbc.queryForObject("SELECT mode FROM \""+legacy+"\".model_runtime_selection",String.class)).isEqualTo("ai");
            assertThat(jdbc.queryForObject("SELECT revision FROM \""+legacy+"\".model_runtime_selection",Long.class)).isEqualTo(7);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM \""+legacy+"\".model_configuration",Integer.class)).isEqualTo(1);
        } finally { jdbc.execute("DROP SCHEMA IF EXISTS \""+legacy+"\" CASCADE"); }
    }

    /** 无数据库密钥的 V2 真实模式安全转为 demo，真实外键阻止悬空引用。 */
    @Test
    void migratesMissingCredentialsAndEnforcesForeignKeys() {
        String legacy=SCHEMA+"_nokey"; var jdbc=new JdbcTemplate(dataSource);
        try {
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(legacy).defaultSchema(legacy)
                    .target(org.flywaydb.core.api.MigrationVersion.fromVersion("2")).load().migrate();
            jdbc.update("INSERT INTO \""+legacy+"\".model_configuration (id,revision,mode,platform,model,base_url,updated_at) VALUES (1,2,'ai','dashscope','qwen-plus','https://dashscope.aliyuncs.com',CURRENT_TIMESTAMP)");
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(legacy).defaultSchema(legacy).load().migrate();
            assertThat(jdbc.queryForObject("SELECT mode FROM \""+legacy+"\".model_runtime_selection",String.class)).isEqualTo("demo");
            assertThat(jdbc.queryForObject("SELECT api_key_ciphertext FROM \""+legacy+"\".model_profiles WHERE id='model-migrated-v2'",String.class)).isNull();
            assertThatThrownBy(() -> jdbc.update("DELETE FROM \""+legacy+"\".model_platforms WHERE id='platform-migrated-v2'"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("DELETE FROM \""+legacy+"\".model_profiles WHERE id='model-migrated-v2'"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally { jdbc.execute("DROP SCHEMA IF EXISTS \""+legacy+"\" CASCADE"); }
    }

    /** V6 补充可空目录地址，升级和再次迁移均保留平台、密文及当前启用选择。 */
    @Test
    void upgradesModelCatalogUrlWithoutRewritingExistingSettings() {
        String upgrade=SCHEMA+"_catalog_url"; var jdbc=new JdbcTemplate(dataSource);
        var cipher=new com._36knight.alpha.model.ModelSecretCipher(java.util.Base64.getEncoder().encodeToString(new byte[32]),"/unused");
        String ciphertext=cipher.encrypt("synthetic-catalog-upgrade-key");
        try {
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(upgrade).defaultSchema(upgrade).target("5").load().migrate();
            jdbc.update("INSERT INTO "+upgrade+".model_platforms(id,name,protocol,base_url,updated_at) VALUES('old-platform','原平台','openai-compatible','https://example.com/v1',now())");
            jdbc.update("INSERT INTO "+upgrade+".model_profiles(id,platform_id,name,model,api_key_ciphertext,updated_at) VALUES('old-model','old-platform','原模型','test-model',?,now())",ciphertext);
            jdbc.update("UPDATE "+upgrade+".model_runtime_selection SET revision=9,mode='ai',active_model_id='old-model'");
            var flyway=org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(upgrade).defaultSchema(upgrade).load();
            flyway.migrate();
            assertThat(jdbc.queryForObject("SELECT model_catalog_url FROM "+upgrade+".model_platforms WHERE id='old-platform'",String.class)).isNull();
            jdbc.update("UPDATE "+upgrade+".model_platforms SET model_catalog_url='https://example.com/v1/models' WHERE id='old-platform'");
            flyway.migrate();
            assertThat(jdbc.queryForObject("SELECT model_catalog_url FROM "+upgrade+".model_platforms WHERE id='old-platform'",String.class)).isEqualTo("https://example.com/v1/models");
            assertThat(jdbc.queryForObject("SELECT name FROM "+upgrade+".model_platforms WHERE id='old-platform'",String.class)).isEqualTo("原平台");
            assertThat(jdbc.queryForObject("SELECT api_key_ciphertext FROM "+upgrade+".model_profiles WHERE id='old-model'",String.class)).isEqualTo(ciphertext);
            assertThat(jdbc.queryForObject("SELECT mode FROM "+upgrade+".model_runtime_selection",String.class)).isEqualTo("ai");
            assertThat(jdbc.queryForObject("SELECT active_model_id FROM "+upgrade+".model_runtime_selection",String.class)).isEqualTo("old-model");
            assertThat(jdbc.queryForObject("SELECT revision FROM "+upgrade+".model_runtime_selection",Long.class)).isEqualTo(9);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM "+upgrade+".flyway_schema_history WHERE version='6' AND success",Integer.class)).isEqualTo(1);
        } finally { jdbc.execute("DROP SCHEMA IF EXISTS "+upgrade+" CASCADE"); }
    }

    /** 真实 Mapper 可保存与清空目录地址，服务重建后仍读取数据库。 */
    @Test
    void persistsAndClearsModelCatalogUrlInPostgres() {
        var saved=configurations.savePlatform(null,new SaveModelPlatformRequest(configurations.get().revision(),"PG 目录地址","openai-compatible",
                "https://example.com/v1","https://example.com/v1/models"));
        String id=saved.platforms().stream().filter(p->p.name().equals("PG 目录地址")).findFirst().orElseThrow().id();
        var recreated=new ModelCatalogService(configurationRepository,new com._36knight.alpha.model.ModelSecretCipher(java.util.Base64.getEncoder().encodeToString(new byte[32]),"/unused"));
        assertThat(recreated.get().platforms().stream().filter(p->p.id().equals(id)).findFirst().orElseThrow().modelCatalogUrl()).isEqualTo("https://example.com/v1/models");
        configurations.savePlatform(id,new SaveModelPlatformRequest(configurations.get().revision(),"PG 目录地址","openai-compatible","https://example.com/v1",""));
        assertThat(new JdbcTemplate(dataSource).queryForObject("SELECT model_catalog_url FROM model_platforms WHERE id=?",String.class,id)).isNull();
    }

    /** V4 迁移不自动导入旧历史，真实关系外键阻止跨项目会话关联。 */
    @Test
    void persistsWorkspaceRelationsAndRejectsCrossProjectForeignKey() {
        var p=workspace.createProject(new com._36knight.alpha.workspace.WorkspaceRequests.NewProject("产品甲","背景"));
        var q=workspace.createProject(new com._36knight.alpha.workspace.WorkspaceRequests.NewProject("产品乙",""));
        var v=workspace.createVersion(p.id(),new com._36knight.alpha.workspace.WorkspaceRequests.NewVersion("自定义一期","目标","范围"));
        var s=workspace.createSession(q.id(),new com._36knight.alpha.workspace.WorkspaceRequests.NewSession("探索",null));
        var jdbc=new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='4' AND success",Integer.class)).isEqualTo(1);
        assertThatThrownBy(()->jdbc.update("UPDATE work_sessions SET version_id=? WHERE id=?",v.id(),s.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(workspace.getSession(s.id()).session().versionId()).isNull();
        assertThat(workspace.getVersion(v.id()).content()).isEqualTo("范围");
        assertThatThrownBy(()->workspace.createVersion(p.id(),new com._36knight.alpha.workspace.WorkspaceRequests.NewVersion("自定义一期","重复",""))).isInstanceOf(ProjectException.class);
    }

    /** 真 PostgreSQL 根行锁下业务版本保存只有一个赢家，历史保持不可变。 */
    @Test
    void serializesWorkspaceVersionWriters() throws Exception {
        var p=workspace.createProject(new com._36knight.alpha.workspace.WorkspaceRequests.NewProject("新并发",""));
        var v=workspace.createVersion(p.id(),new com._36knight.alpha.workspace.WorkspaceRequests.NewVersion("并发一期","目标","原范围"));
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->workspaceWrite(start,v.id(),"甲"));
            var b=executor.submit(()->workspaceWrite(start,v.id(),"乙")); start.countDown();
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(workspace.revisions(v.id(),1).items()).hasSize(2);
        assertThat(workspace.savedRevision(v.id(),1).content()).isEqualTo("原范围");
    }

    /** 真持久化任务结果与消息重读、形成版本幂等均不依赖页面内存。 */
    @Test
    void savesWorkspaceConversationAndPromotion() throws Exception {
        var p=workspace.createProject(new com._36knight.alpha.workspace.WorkspaceRequests.NewProject("新对话","产品背景"));
        var s=workspace.createSession(p.id(),new com._36knight.alpha.workspace.WorkspaceRequests.NewSession("导入讨论",null));
        var command=new com._36knight.alpha.workspace.WorkspaceRequests.SubmitRun(UUID.randomUUID().toString(),"CHAT","测试需求");
        var run=workspace.submit(s.id(),command);
        for(int i=0;i<200&&java.util.Set.of("QUEUED","RUNNING").contains(workspace.getRun(run.id()).status());i++)Thread.sleep(20);
        assertThat(workspace.getRun(run.id()).status()).isEqualTo("SUCCEEDED");
        assertThat(workspace.submit(s.id(),command).id()).isEqualTo(run.id());
        assertThat(workspace.messages(s.id(),null).items()).hasSize(2);
        var saved=workspace.saveDraft(s.id(),new com._36knight.alpha.workspace.WorkspaceRequests.SaveDraft(1,"导入范围",null));
        var promote=new com._36knight.alpha.workspace.WorkspaceRequests.PromoteDraft(saved.revision(),saved.draftRevision(),"导入首期","目标");
        var v=workspace.promote(s.id(),promote);
        assertThat(workspace.promote(s.id(),promote).id()).isEqualTo(v.id());
        assertThat(workspace.versions(p.id(),1,false).items()).hasSize(1);
        assertThat(workspace.getSession(s.id()).session().versionId()).isEqualTo(v.id());
    }

    /** 并发工作空间写入只接受明确修订冲突。 */
    private boolean workspaceWrite(CountDownLatch start,String id,String content) throws Exception {
        if(!start.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("起跑超时");
        try {workspace.saveVersion(id,new com._36knight.alpha.workspace.WorkspaceRequests.EditVersion(1,"并发一期","目标",content));return true;}
        catch(ProjectException e){assertThat(e.code()).isEqualTo("WORKSPACE_REVISION_CONFLICT");return false;}
    }

    /** 相同根版本的竞争写入，仅允许一个成功。 */
    private boolean saveConfigurationTogether(CountDownLatch start,long revision) throws Exception {
        if (!start.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("等待目录并发测试超时");
        try { configurations.select(new SaveModelSelectionRequest(revision,"demo",null)); return true; }
        catch(com._36knight.alpha.model.ModelConfigurationException exception) { assertThat(exception.code()).isEqualTo("MODEL_CONFIG_CONFLICT"); return false; }
    }

    /** 等待同步起跑，仅将版本冲突视为预期失败。 */
    private boolean concurrentSave(ProjectDocument initial, CountDownLatch start, String name) throws Exception {
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("并发测试等待超时");
        try { repository.update(copy(initial, 2, name), 1); return true; }
        catch (ProjectException exception) {
            assertThat(exception.code()).isEqualTo("VERSION_CONFLICT");
            return false;
        }
    }

    /** 测试快照保留来源和稳定业务 ID。 */
    private ProjectDocument copy(ProjectDocument old, long revision, String name) {
        return new ProjectDocument(old.id(), name, old.originalContent(), old.mode(), old.runId(), revision,
                old.createdAt(), Instant.now(), old.title(), old.overview(), old.requirements(), old.questions(), old.suggestions(), old.prdMarkdown());
    }

    /** 只删除本测试随机创建的 schema，不操作 project_alpha_dev 或 Zero schema。 */
    @AfterAll
    void removeTestSchema() {
        if (!SCHEMA.matches("alpha_it_[0-9a-f]{32}")) throw new IllegalStateException("测试 schema 名称不安全");
        new JdbcTemplate(dataSource).execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE");
    }
    /** V4 存量工作空间升级 V5，范围和名称原样保留；只操作独立临时 schema。 */
    @Test void upgradesExistingWorkspaceWithoutRewritingScope() {
        String upgrade="alpha_upgrade_"+UUID.randomUUID().toString().replace("-","");
        var jdbc=new JdbcTemplate(dataSource);
        try {
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(upgrade).defaultSchema(upgrade).target("4").load().migrate();
            jdbc.update("INSERT INTO "+upgrade+".workspace_projects(id,name,background,revision,created_at,updated_at) VALUES('old-project','旧产品','原背景',1,now(),now())");
            jdbc.update("INSERT INTO "+upgrade+".requirement_versions(id,project_id,label,goal,content,revision,stage,lifecycle,created_at,updated_at) VALUES('old-version','old-project','一期','原目标','原范围',1,'REVIEW','ACTIVE',now(),now())");
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(upgrade).defaultSchema(upgrade).load().migrate();
            assertThat(jdbc.queryForObject("SELECT content FROM "+upgrade+".requirement_versions WHERE id='old-version'",String.class)).isEqualTo("原范围");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM "+upgrade+".flyway_schema_history WHERE version='5' AND success",Integer.class)).isEqualTo(1);
            jdbc.update("UPDATE "+upgrade+".requirement_versions SET stage='PRD' WHERE id='old-version'");
        } finally { jdbc.execute("DROP SCHEMA IF EXISTS "+upgrade+" CASCADE"); }
    }
    /** 真实 PostgreSQL 下确认事务仅有一个赢家，基线和阶段一致。 */
    @Test void lifecycleConfirmationHasOneWinnerAndImmutableBaseline() throws Exception {
        var p=workspace.createProject(new com._36knight.alpha.workspace.WorkspaceRequests.NewProject("生命周期产品","背景"));
        var v=workspace.createVersion(p.id(),new com._36knight.alpha.workspace.WorkspaceRequests.NewVersion("一期","导入","CSV 导入"));
        var d=lifecycle.get(v.id());var r=d.review().requirements().getFirst();
        var item=new com._36knight.alpha.workspace.LifecycleContracts.Requirement(r.id(),r.title(),r.description(),"合法 CSV 成功",false,null);
        d=lifecycle.saveReview(v.id(),new com._36knight.alpha.workspace.LifecycleContracts.SaveReview(d.revision(),new com._36knight.alpha.workspace.LifecycleContracts.Review(List.of(item),List.of(),List.of())));
        item=new com._36knight.alpha.workspace.LifecycleContracts.Requirement(r.id(),r.title(),r.description(),"合法 CSV 成功",true,null);
        d=lifecycle.saveReview(v.id(),new com._36knight.alpha.workspace.LifecycleContracts.SaveReview(d.revision(),new com._36knight.alpha.workspace.LifecycleContracts.Review(List.of(item),List.of(),List.of())));
        final var saved=d;var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->{start.await();try{lifecycle.confirmReview(v.id(),new com._36knight.alpha.workspace.LifecycleContracts.Confirm(saved.revision(),saved.reviewRevision(),UUID.randomUUID().toString()));return true;}catch(ProjectException e){return false;}});
            var b=executor.submit(()->{start.await();try{lifecycle.confirmReview(v.id(),new com._36knight.alpha.workspace.LifecycleContracts.Confirm(saved.revision(),saved.reviewRevision(),UUID.randomUUID().toString()));return true;}catch(ProjectException e){return false;}});
            start.countDown();assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(workspace.getVersion(v.id()).stage()).isEqualTo("PRD");assertThat(lifecycle.baselines(v.id(),1).items()).hasSize(1);
    }
    /** 数据库复合基线 FK 防止跨业务版本的 PRD 来源写入。 */
    @Test void lifecycleDatabaseRejectsCrossVersionBaseline() {
        var p=workspace.createProject(new com._36knight.alpha.workspace.WorkspaceRequests.NewProject("来源约束",""));
        var a=workspace.createVersion(p.id(),new com._36knight.alpha.workspace.WorkspaceRequests.NewVersion("甲","目标","范围"));
        var b=workspace.createVersion(p.id(),new com._36knight.alpha.workspace.WorkspaceRequests.NewVersion("乙","目标","范围"));
        var jdbc=new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO version_artifact_baselines(id,version_id,kind,source_revision,request_id,content_json,scope_fingerprint,label,goal,scope,mode,created_at) VALUES(?,?,'REVIEW',1,?,'{}','fingerprint','甲','目标','范围','manual',now())",UUID.randomUUID().toString(),a.id(),UUID.randomUUID().toString());
        String source=jdbc.queryForObject("SELECT id FROM version_artifact_baselines WHERE version_id=?",String.class,a.id());
        assertThatThrownBy(()->jdbc.update("INSERT INTO version_prd_runs(id,version_id,baseline_id,prd_revision,request_id,status,created_at,updated_at) VALUES(?,?,?,0,?,'QUEUED',now(),now())",UUID.randomUUID().toString(),b.id(),source,UUID.randomUUID().toString())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
