package com._36knight.alpha;

import com._36knight.alpha.project.ProjectException;
import com._36knight.alpha.prototype.PrototypeArtifactStore;
import com._36knight.alpha.prototype.PrototypeBuildException;
import com._36knight.alpha.prototype.PrototypeBuildService;
import com._36knight.alpha.prototype.PrototypeGenerationRunner;
import com._36knight.alpha.prototype.PrototypeModelException;
import com._36knight.alpha.prototype.PrototypeModelProvider;
import com._36knight.alpha.prototype.PrototypeRepository;
import com._36knight.alpha.prototype.PrototypeService;
import com._36knight.alpha.prototype.PrototypeExportService;
import com._36knight.alpha.workspace.LifecycleContracts.Prd;
import com._36knight.alpha.workspace.PrdStructure;
import com._36knight.alpha.workspace.WorkbenchContracts;
import com._36knight.alpha.workspace.WorkbenchService;
import com._36knight.alpha.workspace.WorkspaceService;
import com._36knight.alpha.workspace.WorkspaceViews;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static com._36knight.alpha.prototype.PrototypeContracts.*;
import static com._36knight.alpha.workspace.WorkspaceRequests.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 显式开启后使用真实模型与Docker，在随机PostgreSQL schema验证生成、编辑、恢复、确认和导出闭环。@author Andersen */
@Tag("prototype-live")
@EnabledIfEnvironmentVariable(named = "ALPHA_PROTOTYPE_LIVE_TEST", matches = "true")
@SpringBootTest(properties = {"alpha.prototype.generation.enabled=true", "logging.level.org.springframework.ai=OFF"})
@ActiveProfiles("postgres-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PrototypeLiveGenerationTest {
    /** 本次验收独有schema；只允许清理该确切随机身份。 */
    private static final String SCHEMA = "alpha_proto_live_" + UUID.randomUUID().toString().replace("-", "");
    /** 连接配置仅存在测试内存，报告和日志不输出值。 */
    private static final Properties LOCAL = localProperties();
    /** 安全证据不包含凭证、上游原文、接入地址或宿主绝对路径。 */
    private static final Path REPORT = Path.of("backend/build/reports/prototype-delivery-live.json");
    /** 真实、已绑定随机schema的数据源。 */
    @Autowired private DataSource dataSource;
    /** 实际异步执行器，不注入替身。 */
    @Autowired private PrototypeGenerationRunner runner;
    /** 实际模型配置与客户端适配器。 */
    @Autowired private PrototypeModelProvider models;
    /** 实际容器能力探测和Worker调用。 */
    @Autowired private PrototypeBuildService builds;
    /** 实际文件验证与源码读取。 */
    @Autowired private PrototypeArtifactStore artifacts;
    /** 实际原型业务事务入口。 */
    @Autowired private PrototypeService prototypes;
    /** 将指定真实修订导出为可独立复现的交付包。 */
    @Autowired private PrototypeExportService exports;
    /** 读取已持久化身份和安全错误分类。 */
    @Autowired private PrototypeRepository repository;
    /** 创建隔离项目、文档和会话。 */
    @Autowired private WorkspaceService workspace;
    /** 保存直接PRD，生成只使用这份正式业务快照。 */
    @Autowired private WorkbenchService workbench;
    /** 与业务相同的JSON格式。 */
    @Autowired private ObjectMapper json;
    /** 本次全部任务身份用于失败报告和停止后的隔离清理。 */
    private final List<String> runs = new ArrayList<>();
    /** 当前报告在每个关键节点写入，外部中断仍可留下已有安全证据。 */
    private ObjectNode report;

    /** 动态数据源和Flyway严格指向随机schema，原业务schema仅在显式只读连接中访问。 */
    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        String base = setting("ALPHA_DATABASE_URL", "jdbc:postgresql://localhost:5432/postgres");
        if (!base.startsWith("jdbc:postgresql:")) throw new IllegalStateException("真实原型验收需要PostgreSQL连接");
        String[] parts = base.split("\\?", 2);
        List<String> parameters = new ArrayList<>();
        if (parts.length == 2) for (String part : parts[1].split("&")) {
            if (!part.startsWith("currentSchema=") && !part.isBlank()) parameters.add(part);
        }
        parameters.add("currentSchema=" + SCHEMA);
        registry.add("spring.datasource.dynamic.datasource.master.url", () -> parts[0] + "?" + String.join("&", parameters));
        registry.add("spring.datasource.dynamic.datasource.master.username", () -> setting("ALPHA_DATABASE_USERNAME", "postgres"));
        registry.add("spring.datasource.dynamic.datasource.master.password", () -> setting("ALPHA_DATABASE_PASSWORD", ""));
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("alpha.model-config.encryption-key", () -> setting("ALPHA_MODEL_CONFIG_ENCRYPTION_KEY", ""));
        registry.add("alpha.model-config.key-file", () -> Path.of(setting("ALPHA_MODEL_CONFIG_KEY_FILE", "data/model-config.key")).toAbsolutePath().normalize().toString());
    }

    /** 先固定 PRD 基线，生成与编辑使用真实模型，再人工恢复、确认并导出确切修订。 */
    @Test void generatesEditsRestoresConfirmsAndExportsRealPrototype() throws Exception {
        report = json.createObjectNode().put("schemaVersion", 1).put("mode", "live").put("generation", "model")
            .put("startedAt", Instant.now().toString()).put("status", "RUNNING");
        report.set("prd", json.valueToTree(prd()));
        report.putArray("runs");
        writeReport();
        try {
            copySelectedModelReadOnly();
            if (!models.available()) throw new IllegalStateException("当前模型未启用、缺少凭证或原加密主密钥不可用");
            builds.refreshCapability();
            waitForRunner();
            var project = workspace.createProject(new NewProject("P6真实模型设备台账验收", "独立验收数据：只演示设备登记，不能连接真实业务服务。"));
            var version = workspace.createVersion(project.id(), new NewVersion("设备台账原型验收", "可运行设备台账列表及详情", "查询、编辑、取消、保存同步；字段为编号、名称、启用状态、容量。"));
            var session = workspace.createSession(project.id(), new NewSession("P6真实生成与局部编辑验收", version.id()));
            var document = workbench.open(session.id());
            document = workbench.save(session.id(), new WorkbenchContracts.Draft(document.lifecycle().revision(), document.workspace().version().revision(), prd()));
            document = workbench.confirmPrd(session.id(), new com._36knight.alpha.workspace.LifecycleContracts.Confirm(
                document.lifecycle().revision(), document.lifecycle().prdRevision(), id()));
            report.put("projectId", project.id()).put("versionId", version.id()).put("sessionId", session.id());
            var generate = new Submit(session.id(), id(), "GENERATE", document.workspace().version().revision(),
                document.lifecycle().revision(), document.lifecycle().prdRevision(), 0, null, "zero-admin", 2,
                new Source("BASELINE", document.lifecycle().prdBaseline().id()), new Scope("ALL", List.of()), new Target("PROTOTYPE", null, null),
                "依据已定稿PRD生成完整设备台账原型。必须有可用列表和详情路由，查询、编辑取消、保存后详情同步，并保留容量0和停用false。名称保存前trim，trim后为空拒绝；容量为0到999999的整数。提供真实可执行补充用例覆盖查询、取消不修改、保存0/false及详情同步。布局采用模板默认值，不把已明确规则再次列为待确认。", null);
            WorkspaceViews.Run first = submitAndWait(version.id(), generate);
            var firstRevision = prototypes.get(version.id()).current();
            assertThat(firstRevision).isNotNull();
            assertThat(firstRevision.sourceRunId()).isEqualTo(first.id());
            String sourceId = firstRevision.source().id();
            recordRevision(version.id(), first, "GENERATE");
            report.set("confirmationBlockers", json.valueToTree(prototypes.get(version.id()).confirmationBlockers()));
            writeReport();
            assertThat(prototypes.get(version.id()).confirmationBlockers()).as("初始原型必须没有未解决的确认阻塞，才能继续交付验收").isEmpty();

            waitForRunner();
            document = workbench.get(session.id());
            var current = prototypes.get(version.id());
            var edit = new Submit(session.id(), id(), "EDIT", document.workspace().version().revision(), document.lifecycle().revision(),
                document.lifecycle().prdRevision(), current.revision(), firstRevision.id(), "zero-admin", 2,
                new Source("EXISTING", sourceId), firstRevision.source().scope(), new Target("PAGE", new PageRef("assets", "list"), null),
                "只把设备列表页面主标题改为“设备台账（验收编辑）”，保持详情、全部查询编辑行为、模拟实体及已选范围不变。保留已有测试，增加检查列表新标题的assertText补充用例。输出完整生成目录文件，不省略未修改文件。", null);
            WorkspaceViews.Run second = submitAndWait(version.id(), edit);
            var secondRevision = prototypes.get(version.id()).current();
            assertThat(secondRevision).isNotNull();
            assertThat(secondRevision.id()).isNotEqualTo(firstRevision.id());
            assertThat(secondRevision.sourceRunId()).isEqualTo(second.id());
            assertThat(secondRevision.parentRevisionId()).isEqualTo(firstRevision.id());
            assertThat(secondRevision.source().id()).isEqualTo(sourceId);
            assertThat(prototypes.revisions(version.id(), 1).items()).hasSize(2);
            recordRevision(version.id(), second, "EDIT");
            String objectKey = json.readTree(repository.revision(secondRevision.id()).artifactJson).path("objectKey").asText();
            assertThat(artifacts.generatedFiles(objectKey).values().stream().anyMatch(content -> content.contains("设备台账（验收编辑）"))).isTrue();
            verifyDelivery(version.id(), session.id(), firstRevision, secondRevision);
            report.put("status", "PASSED").put("completedAt", Instant.now().toString());
            report.putArray("notVerified").add("真实业务数据与生产部署").add("任意PRD的全部业务语义");
            writeReport();
        } catch (Throwable failure) {
            report.put("status", "FAILED").put("completedAt", Instant.now().toString()).put("errorCode", safeCode(failure));
            captureRuns();
            writeReport();
            throw failure;
        }
    }

    /** 恢复保留生成来源并追加修订，确认和导出均绑定精确基线、范围及产物。 */
    private void verifyDelivery(String versionId, String sessionId, RevisionView first, RevisionView edited) throws Exception {
        var document = workbench.get(sessionId);
        var current = prototypes.get(versionId);
        report.set("confirmationBlockers", json.valueToTree(current.confirmationBlockers()));
        writeReport();
        assertThat(current.confirmationBlockers()).isEmpty();
        var firstCommand = new Confirm(id(), edited.id(), document.lifecycle().prdBaseline().id(), edited.source().scope(),
            current.revision(), document.workspace().version().revision(), document.lifecycle().revision(), document.lifecycle().prdRevision());
        var previousConfirmation = prototypes.confirm(versionId, firstCommand);
        assertThat(prototypes.confirm(versionId, firstCommand)).isEqualTo(previousConfirmation);
        assertThat(prototypes.get(versionId).status()).isEqualTo("CONFIRMED");
        current = prototypes.get(versionId);
        var restoration = new Restore(id(), first.id(), current.revision(), document.workspace().version().revision(),
            document.lifecycle().revision(), document.lifecycle().prdRevision());
        var restoredResult = prototypes.restore(versionId, restoration);
        assertThat(prototypes.restore(versionId, restoration)).isEqualTo(restoredResult);
        var restored = prototypes.revision(versionId, restoredResult.revisionId());
        assertThat(restored.restoredFromRevisionId()).isEqualTo(first.id());
        assertThat(restored.parentRevisionId()).isEqualTo(edited.id());
        assertThat(restored.sourceRunId()).isNull();
        assertThat(restored.source().id()).isEqualTo(first.source().id());
        assertThat(restored.mode()).isEqualTo("live");
        assertThat(prototypes.get(versionId).status()).isEqualTo("DRAFT");
        assertThat(prototypes.revisions(versionId, 1).items()).hasSize(3);
        assertThat(repository.revision(restored.id()).artifactJson).isEqualTo(repository.revision(first.id()).artifactJson);
        current = prototypes.get(versionId);
        var confirmationCommand = new Confirm(id(), restored.id(), document.lifecycle().prdBaseline().id(), restored.source().scope(),
            current.revision(), document.workspace().version().revision(), document.lifecycle().revision(), document.lifecycle().prdRevision());
        var confirmed = prototypes.confirm(versionId, confirmationCommand);
        assertThat(prototypes.confirm(versionId, confirmationCommand)).isEqualTo(confirmed);
        assertThat(prototypes.confirmations(versionId, 1).items()).hasSize(2);
        assertThat(prototypes.get(versionId).status()).isEqualTo("CONFIRMED");
        assertThat(workbench.get(sessionId).workspace().version().stage()).isEqualTo("PROTOTYPE");
        ObjectNode delivery = report.putObject("delivery").put("restoredRevisionId", restored.id())
            .put("restoredFromRevisionId", first.id()).put("confirmationId", confirmed.id())
            .put("prdBaselineId", confirmed.prdBaselineId()).put("mode", "live");
        delivery.set("scope", json.valueToTree(confirmed.scope()));
        var packages = delivery.putArray("exports");
        for (String format : List.of("source", "static")) {
            Path destination = REPORT.resolveSibling("prototype-delivery-live-" + format + ".zip");
            try (var archive = exports.open(versionId, restored.id(), format); var output = Files.newOutputStream(destination)) {
                archive.writeTo(output);
            }
            assertThat(Files.size(destination)).isPositive();
            packages.addObject().put("format", format).put("file", destination.getFileName().toString())
                .put("bytes", Files.size(destination)).put("sha256", java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(destination))));
        }
        assertThat(prototypes.get(versionId).current().id()).isEqualTo(restored.id());
        assertThat(prototypes.confirmations(versionId, 1).items()).hasSize(2);
        writeReport();
    }

    /** 业务连接只读当前启用的一组平台/模型及密文，目标写入只发生在隔离数据源。 */
    private void copySelectedModelReadOnly() {
        String sourceSchema = sourceSchema();
        String query = "SELECT p.protocol,p.base_url,m.model,m.api_key_ciphertext FROM " + sourceSchema + ".model_runtime_selection s "
            + "JOIN " + sourceSchema + ".model_profiles m ON m.id=s.active_model_id "
            + "JOIN " + sourceSchema + ".model_platforms p ON p.id=m.platform_id WHERE s.id=1 AND s.mode='ai'";
        try (var connection = DriverManager.getConnection(setting("ALPHA_DATABASE_URL", "jdbc:postgresql://localhost:5432/postgres"),
                setting("ALPHA_DATABASE_USERNAME", "postgres"), setting("ALPHA_DATABASE_PASSWORD", ""))) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement(query); var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("真实验收需要业务库中已启用的模型配置");
                String ciphertext = rows.getString("api_key_ciphertext");
                if (ciphertext == null || ciphertext.isBlank()) throw new IllegalStateException("已选模型没有已保存的凭证");
                String platformId = id(), modelId = id();
                var jdbc = new JdbcTemplate(dataSource);
                jdbc.update("INSERT INTO model_platforms(id,name,protocol,base_url,updated_at) VALUES (?,?,?,?,CURRENT_TIMESTAMP)", platformId,
                    "P6真实验收隔离平台", rows.getString("protocol"), rows.getString("base_url"));
                jdbc.update("INSERT INTO model_profiles(id,platform_id,name,model,api_key_ciphertext,updated_at) VALUES (?,?,?,?,?,CURRENT_TIMESTAMP)", modelId,
                    platformId, "P6真实验收隔离模型", rows.getString("model"), ciphertext);
                jdbc.update("UPDATE model_runtime_selection SET mode='ai',active_model_id=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=1", modelId);
                report.put("model", rows.getString("model"));
                if (rows.next()) throw new IllegalStateException("当前模型选择不唯一");
            } finally { connection.rollback(); }
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("无法只读取得当前模型配置或写入隔离配置");
        }
    }

    /** 等待前一任务释放单槽，配置或容器能力不可用必须失败而非跳过验收。 */
    private void waitForRunner() throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!runner.available() && System.nanoTime() < end) Thread.sleep(250);
        if (!runner.available()) throw new IllegalStateException("真实模型或隔离Worker能力不可用");
    }

    /** 实际提交命令并等待有界任务终态，日志只输出身份、阶段及修复计数。 */
    private WorkspaceViews.Run submitAndWait(String versionId, Submit command) throws Exception {
        WorkspaceViews.Run run = prototypes.submit(versionId, command);
        runs.add(run.id());
        captureRuns();
        writeReport();
        long end = System.nanoTime() + Duration.ofMinutes(15).toNanos();
        while (System.nanoTime() < end) {
            run = workspace.getRun(run.id());
            var progress = run.prototype();
            System.out.println("P6 live run=" + run.id() + " status=" + run.status() + " phase=" + progress.phase() + " repairs=" + progress.repairAttempts());
            if (Set.of("SUCCEEDED", "FAILED", "INTERRUPTED").contains(run.status())) {
                captureRuns();
                writeReport();
                if (!"SUCCEEDED".equals(run.status())) throw new IllegalStateException("真实原型任务未成功，安全错误分类见验收报告");
                assertThat(run.mode()).isEqualTo("live");
                assertThat(progress.applicationStatus()).isEqualTo("APPLIED");
                assertThat(progress.resultRevisionId()).isNotBlank();
                return run;
            }
            Thread.sleep(5000);
        }
        throw new IllegalStateException("真实原型任务未在十五分钟预算内结束");
    }

    /** 从已持久化修订和实际文件服务取证，不使用模型的成功声明。 */
    private void recordRevision(String versionId, WorkspaceViews.Run run, String operation) throws Exception {
        var detail = repository.runDetail(run.id());
        var revision = prototypes.revision(versionId, detail.resultRevisionId);
        var stored = repository.revision(revision.id());
        String objectKey = json.readTree(stored.artifactJson).path("objectKey").asText();
        var verified = artifacts.verify(objectKey);
        assertThat(revision.mode()).isEqualTo("live");
        assertThat(verified.artifact().path("mode").asText()).isEqualTo("live");
        assertThat(verified.artifact().path("generation").asText()).isEqualTo("model");
        assertThat(verified.validation().path("status").asText()).isEqualTo("PASSED");
        assertThat(verified.validation().path("cases").isArray()).isTrue();
        assertThat(verified.validation().path("cases").size()).isGreaterThanOrEqualTo(2);
        assertThat(revision.manifest().path("cases").size()).isGreaterThan(0);
        if (!report.has("revisions")) report.putArray("revisions");
        ObjectNode evidence = json.createObjectNode().put("operation", operation).put("runId", run.id()).put("revisionId", revision.id())
            .put("sourceId", revision.source().id()).put("objectKey", objectKey).put("templateId", "zero-admin").put("templateRevision", 2)
            .put("toolchainId", verified.toolchainId()).put("mode", revision.mode()).put("model", revision.model()).put("summary", revision.changeSummary());
        if (revision.parentRevisionId() != null) evidence.put("parentRevisionId", revision.parentRevisionId());
        evidence.set("plan", revision.manifest().path("plan"));
        evidence.set("cases", revision.manifest().path("cases"));
        evidence.set("validation", verified.validation());
        evidence.put("sourceSha256", revision.source().contentSha256());
        ((com.fasterxml.jackson.databind.node.ArrayNode) report.get("revisions")).add(evidence);
        writeReport();
    }

    /** 轮询证据仅保存公开状态和平台分类，不复制任务上下文、令牌或模型诊断原文。 */
    private void captureRuns() {
        var evidence = report.putArray("runs");
        for (String id : runs) {
            var run = workspace.getRun(id);
            var detail = repository.runDetail(id);
            ObjectNode item = evidence.addObject().put("id", id).put("status", run.status()).put("phase", detail.phase)
                .put("repairAttempts", detail.repairAttempts).put("applicationStatus", detail.applicationStatus);
            if (run.mode() != null) item.put("mode", run.mode());
            if (run.model() != null) item.put("model", run.model());
            if (detail.resultRevisionId != null) item.put("revisionId", detail.resultRevisionId);
            if (detail.diagnosticJson != null) {
                try { item.put("errorCode", json.readTree(detail.diagnosticJson).path("code").asText("PROTOTYPE_LIVE_FAILED")); }
                catch (java.io.IOException invalid) { item.put("errorCode", "PROTOTYPE_LIVE_FAILED"); }
            }
        }
    }

    /** 有界小PRD明确列出共享种子和交互，便于真实模型生成及浏览器取证。 */
    private Prd prd() {
        var list = new PrdStructure.Page("list", "设备台账", "LIST", Map.of(
            "overview", "页面标题为设备台账。列表路由 /assets，显示共享模拟设备。",
            "fields", "id字符串只读；name名称保存前裁剪首尾空白，裁剪后必须非空，不设额外长度或唯一性限制；enabled布尔值显示启用/停用；capacity必须为0到999999的整数。",
            "search", "按名称包含查询，查询文本先trim，空文本返回全部，区分大小写。点击查询应用条件，点击重置清空输入并恢复全部记录。",
            "sorting", "按id升序，数据少于一页即可。",
            "actions", "每行详情跳转 /assets/:id。编辑打开独立副本表单，取消不修改实体；保存先trim名称，拒绝空串或纯空白；容量须为0到999999的整数。校验成功写共享内存并关闭弹层，失败保留输入与实体原值。",
            "states", "没有结果显示空态；模拟编辑不会调用后端。",
            "acceptance", "查询打印机只显示asset-001；取消修改后原名称保持；保存名称为打印机已更新、capacity为0、enabled为false后详情同步显示停用和0。"));
        var detail = new PrdStructure.Page("detail", "设备详情", "DETAIL", Map.of(
            "overview", "只读设备详情路由 /assets/:id，测试入口 /assets/asset-001，menu:false。",
            "fields", "显示同一共享实体的编号id、名称name、启用状态enabled和容量capacity；0和false不能作为缺失。",
            "actions", "仅返回列表，不提供修改按钮。",
            "states", "不存在ID显示未找到和返回列表；不从真实服务获取数据。",
            "acceptance", "列表保存后详情显示同一实体新值，返回列表保留本次预览修改。"));
        var module = new PrdStructure.Module("assets", "设备台账", Map.of(
            "overview", "最小设备登记管理原型，两个页面共享一个模拟实体集合。",
            "rules", "固定种子asset-001/打印机/enabled:true/capacity:1；asset-002/扫描仪/enabled:false/capacity:0。刷新恢复种子，所有写入只在内存。",
            "permissions", "无需登录，只演示页面功能，不宣称真实权限保护。",
            "acceptance", "平台补充用例实际操作查询、取消、保存后跨页同步；示例用例要使用明确data-testid，不能自行执行脚本。"), List.of(list, detail));
        return new Prd("设备台账真实模型链路验收，数据全部为新建演示数据。", "生成可运行、可修改、可验证的设备台账原型。",
            "仅设备列表和只读详情；列表含编辑弹层。", "查询列表→编辑独立副本→取消或保存→打开详情核对共享实体。",
            "编号字符串且不可修改；名称先trim再校验非空，裁剪后存储，无额外长度和唯一性限制；容量为0到999999的整数，false与0是有效值。布局、提示措辞和组件外观使用固定模板默认值。", "空名称、纯空白名称、非整数或范围外容量保存失败且保留输入，不修改共享实体。",
            "真实模型源码通过隔离编译及浏览器验证，修订与来源入库；不代表人工业务确认。", "仅本次预览内存，无外部网络、认证和浏览器持久化。",
            "不实现删除、导入导出、真实数据库CRUD、角色权限和真实业务API。", List.of(), List.of(), 2, List.of(module));
    }

    /** 根据已配置连接推导业务schema，只允许单个普通标识符且不接受测试schema。 */
    private String sourceSchema() {
        String schema = setting("ALPHA_DATABASE_SCHEMA", "project_alpha_dev");
        String base = setting("ALPHA_DATABASE_URL", "");
        String[] parts = base.split("\\?", 2);
        if (parts.length == 2) for (String parameter : parts[1].split("&")) {
            if (parameter.startsWith("currentSchema=")) schema = java.net.URLDecoder.decode(parameter.substring(14), java.nio.charset.StandardCharsets.UTF_8);
        }
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]{0,62}") || schema.startsWith("alpha_proto_live_")) throw new IllegalStateException("业务schema配置不能安全识别");
        return "\"" + schema + "\"";
    }

    /** 报告只追加预定义安全证据，文件留在build目录供人工查看。 */
    private void writeReport() throws java.io.IOException {
        Files.createDirectories(REPORT.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(REPORT.toFile(), report);
    }

    /** 失败类别不包含上游异常正文，实际任务的更具体代码在runs数组中。 */
    private String safeCode(Throwable failure) {
        if (failure instanceof PrototypeModelException model) return model.code();
        if (failure instanceof PrototypeBuildException build) return build.code();
        if (failure instanceof ProjectException project) return project.code();
        return "PROTOTYPE_LIVE_FAILED";
    }

    /** 关闭本测试执行器后只删除本次随机schema，已验证文件对象保留供预览与复核。 */
    @AfterAll void cleanup() throws InterruptedException {
        if (runner != null) runner.close();
        if (dataSource == null) return;
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        var jdbc = new JdbcTemplate(dataSource);
        while (System.nanoTime() < deadline && jdbc.queryForObject("SELECT count(*) FROM workspace_runs WHERE kind='PROTOTYPE' AND status IN ('QUEUED','RUNNING')", Integer.class) > 0) Thread.sleep(250);
        if (!SCHEMA.matches("alpha_proto_live_[0-9a-f]{32}")) throw new IllegalStateException("验收schema身份不安全");
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    /** 私有本地连接配置只读取一次，不输出内容。 */
    private static Properties localProperties() {
        Properties properties = new Properties();
        Path file = Path.of(".env");
        if (Files.exists(file)) try (var input = Files.newInputStream(file)) { properties.load(input); }
        catch (java.io.IOException failure) { throw new IllegalStateException("无法读取后端本地配置"); }
        return properties;
    }

    /** 环境变量优先于本地私有配置，缺省值仅用于连接配置约定。 */
    private static String setting(String name, String fallback) { return System.getenv().getOrDefault(name, LOCAL.getProperty(name, fallback)); }

    /** 验收命令与数据均用全新业务身份，不碰已有业务对象。 */
    private String id() { return UUID.randomUUID().toString(); }
}
