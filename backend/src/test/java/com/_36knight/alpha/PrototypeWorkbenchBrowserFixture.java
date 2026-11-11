package com._36knight.alpha;

import com._36knight.alpha.prototype.PrototypeArtifactStore;
import com._36knight.alpha.prototype.PrototypeContracts.Completion;
import com._36knight.alpha.prototype.PrototypeContracts.Execution;
import com._36knight.alpha.prototype.PrototypeExecutionGateway;
import com._36knight.alpha.prototype.PrototypeService;
import com._36knight.alpha.workspace.LifecycleContracts.Prd;
import com._36knight.alpha.workspace.PrdStructure;
import com._36knight.alpha.workspace.WorkbenchContracts;
import com._36knight.alpha.workspace.WorkbenchService;
import com._36knight.alpha.workspace.WorkspaceRequests.NewProject;
import com._36knight.alpha.workspace.WorkspaceRequests.NewSession;
import com._36knight.alpha.workspace.WorkspaceRequests.NewVersion;
import com._36knight.alpha.workspace.WorkspaceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * P5/P6 浏览器验收专用的独立 H2 服务；使用已验证的合成 Worker 产物和真实业务事务、文件复核及预览。
 * 执行器仅模拟异步阶段和完成回调，不调用模型、不宣称本次生成或修改了静态页面，也不读取业务数据库。
 * @author Andersen
 */
@Tag("prototype-browser")
@EnabledIfEnvironmentVariable(named = "ALPHA_PROTOTYPE_BROWSER_TEST", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
    "server.address=127.0.0.1", "server.port=18081", "alpha.prototype.generation.enabled=false",
    "alpha.prototype.preview.allowed-origins=http://127.0.0.1:5179,http://127.0.0.1:18081",
    "logging.level.org.springframework.ai=OFF"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PrototypeWorkbenchBrowserFixture {
    /** 已由真实 Worker 构建并验证的两页合成产物；通过环境变量可替换成同契约的新产物。 */
    private static final String DEFAULT_OBJECT = "f82e7f151f534d5ce511e9f9d626074af05b56c65dcfdacf72ad8ae6336e17d1";
    /** 本次服务私有目录，绝不修改生产产物和 current.json。 */
    private static final Path ISOLATED_ROOT = temporaryRoot();
    /** 固定报告只记录测试业务身份和本机路由，无凭证或模型输入。 */
    private static final Path REPORT = Path.of("backend/build/reports/prototype-browser-fixture.json");
    /** 浏览器脚本结束后写此标志，由 JUnit 正常关闭服务。 */
    private static final Path STOP = Path.of("backend/build/reports/prototype-browser.stop");
    /** 实际事务接口用于创建隔离 PRD 并接收浏览器请求。 */
    @Autowired private WorkspaceService workspace;
    /** 已保存 PRD 与自动保存使用相同业务接口。 */
    @Autowired private WorkbenchService workbench;
    /** 实际原型任务租约、来源快照及修订完成事务。 */
    @Autowired private PrototypeService prototypes;
    /** 实际 SHA-256、目录结构与文件引用复核，不能使用存储替身。 */
    @Autowired private PrototypeArtifactStore artifacts;
    /** 报告与业务清单沿用应用 JSON 配置。 */
    @Autowired private ObjectMapper json;
    /** 唯一合成替身是执行调度入口，明确不触发真实模型。 */
    @MockitoBean private PrototypeExecutionGateway gateway;
    /** 测试任务按顺序推进，阶段间隔让浏览器观察真实轮询与刷新恢复。 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    /** 单次验收的安全状态和已观察任务命令。 */
    private ObjectNode report;
    /** 独立复制后的内容身份，生成和编辑都使用同一明确合成样例。 */
    private String objectKey;
    /** 固定生成样例的两页计划，业务清单与真实静态路由一致。 */
    private JsonNode plan;
    /** 与实际 Worker 验证报告对应的声明式交互用例，不伪造额外通过证据。 */
    private JsonNode cases;

    /** 独立内存库和产物目录隔离所有写操作，模型加密文件也不会落入业务路径。 */
    @DynamicPropertySource
    static void isolate(DynamicPropertyRegistry registry) {
        String database = "prototype-browser-" + UUID.randomUUID();
        registry.add("spring.datasource.dynamic.datasource.master.url", () -> "jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1");
        registry.add("alpha.prototype.artifact-root", ISOLATED_ROOT::toString);
        registry.add("alpha.model-config.key-file", () -> ISOLATED_ROOT.resolve("model-config.key").toString());
    }

    /** 有界启动测试服务，供浏览器通过真正的 Controller 和前端操作进行端到端验收。 */
    @Test
    void serveIsolatedWorkbenchUntilBrowserFinishes() throws Exception {
        Files.createDirectories(REPORT.getParent());
        Files.deleteIfExists(STOP);
        prepareArtifact();
        when(gateway.available()).thenReturn(true);
        doAnswer(call -> { String runId = call.getArgument(0); executor.submit(() -> executeSynthetic(runId)); return null; })
            .when(gateway).dispatch(anyString());
        var project = workspace.createProject(new NewProject("P5合成工作台验收", "独立内存库和合成演示数据；未调用真实模型。"));
        var version = workspace.createVersion(project.id(), new NewVersion("项目管理原型", "验证工作台原型交互", "项目列表与项目详情"));
        var session = workspace.createSession(project.id(), new NewSession("P5合成原型讨论", version.id()));
        var document = workbench.open(session.id());
        workbench.save(session.id(), new WorkbenchContracts.Draft(document.lifecycle().revision(), document.workspace().version().revision(), prd()));
        report = json.createObjectNode().put("schemaVersion", 1).put("mode", "synthetic").put("generation", "test-fixture")
            .put("notice", "P5合成工作台验收，未调用模型；生成与页面修改回调均沿用同一已验证静态样例")
            .put("status", "READY").put("startedAt", Instant.now().toString()).put("projectId", project.id())
            .put("versionId", version.id()).put("sessionId", session.id()).put("objectKey", objectKey)
            .put("backendUrl", "http://127.0.0.1:18081")
            .put("url", "http://127.0.0.1:5179/#/projects/" + project.id() + "/sessions/" + session.id());
        report.putArray("runs");
        writeReport();
        try {
            long deadline = System.nanoTime() + Duration.ofMinutes(20).toNanos();
            while (!Files.exists(STOP) && System.nanoTime() < deadline) Thread.sleep(500);
            assertThat(Files.exists(STOP)).as("浏览器应在20分钟内写入停止标志").isTrue();
        } finally {
            executor.shutdownNow();
            synchronized (this) { report.put("status", "STOPPED"); writeReport(); }
        }
    }

    /** 复制已有 synthetic 对象而不改变任何清单或校验证据，并实际复核文件内容身份。 */
    private void prepareArtifact() throws IOException {
        objectKey = System.getenv().getOrDefault("ALPHA_PROTOTYPE_BROWSER_OBJECT", DEFAULT_OBJECT);
        assertThat(objectKey).matches("[a-f0-9]{64}");
        Path original = Path.of("data/prototypes/artifacts").resolve(objectKey);
        assertThat(original).as("需先用 generated-request.json 构建 synthetic Worker 样例").isDirectory();
        Path destination = ISOLATED_ROOT.resolve("artifacts").resolve(objectKey);
        try (var paths = Files.walk(original)) {
            for (Path path : paths.toList()) {
                Path target = destination.resolve(original.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(target); else Files.copy(path, target);
            }
        }
        var verified = artifacts.verify(objectKey);
        assertThat(verified.artifact().path("mode").asText()).isEqualTo("synthetic");
        assertThat(verified.artifact().path("generation").asText()).isEqualTo("test-fixture");
        JsonNode request = json.readTree(Path.of("prototype-worker/test/fixtures/generated-request.json").toFile());
        ObjectNode fixedPlan = json.createObjectNode();
        fixedPlan.set("pages", request.path("pages"));
        fixedPlan.putArray("entities"); fixedPlan.putArray("impact"); fixedPlan.putArray("gaps");
        plan = fixedPlan;
        cases = request.path("cases").deepCopy();
    }

    /** 模拟执行器只调用真实领取、心跳、完成事务；失败只写安全分类并使任务结束。 */
    private void executeSynthetic(String runId) {
        Execution execution = null;
        try {
            execution = prototypes.claim(runId, "synthetic", "p5-browser-fixture");
            assertThat(execution).isNotNull();
            recordCommand(execution);
            for (String phase : List.of("PLANNING", "GENERATING", "BUILDING", "VERIFYING", "PERSISTING")) {
                assertThat(prototypes.heartbeat(runId, execution.token(), phase, 0, plan)).isTrue();
                Thread.sleep(1300);
            }
            ObjectNode manifest = json.createObjectNode().put("schemaVersion", 1);
            manifest.set("scope", json.valueToTree(execution.source().scope())); manifest.set("plan", plan);
            manifest.set("cases", cases);
            var coverage = manifest.putArray("coverage");
            execution.source().scope().pageRefs().forEach(page -> coverage.addObject().put("moduleId", page.moduleId())
                .put("pageId", page.pageId()).put("status", "IMPLEMENTED"));
            manifest.put("notice", "仅用于P5合成工作台验收，沿用既有Worker验证样例，未调用模型或执行页面代码生成。");
            String summary = "EDIT".equals(execution.operation()) ? "合成联调页面修改回调；静态样例沿用" : "合成联调生成回调；载入已验证的两页静态样例";
            assertThat(prototypes.complete(runId, execution.token(), new Completion(objectKey, manifest, summary))).isTrue();
        } catch (Throwable failure) {
            if (execution != null) prototypes.fail(runId, execution.token(), "PROTOTYPE_EXECUTION_FAILED");
            synchronized (this) {
                report.put("executionFailure", failure.getClass().getSimpleName());
                try { writeReport(); } catch (IOException ignored) { /* 测试进程终止时报告不可写不影响业务终态。 */ }
            }
        }
    }

    /** 报告目标与来源身份，浏览器可校对请求，不存储执行令牌或模型上下文。 */
    private synchronized void recordCommand(Execution execution) throws IOException {
        ObjectNode event = report.withArray("runs").addObject().put("runId", execution.runId()).put("operation", execution.operation())
            .put("sourceId", execution.source().id()).put("content", execution.content());
        event.set("target", json.valueToTree(execution.target())); event.set("scope", json.valueToTree(execution.source().scope()));
        writeReport();
    }

    /** 原子替换报告避免浏览器在 JSON 写入中途读取半份状态。 */
    private synchronized void writeReport() throws IOException {
        Path staging = REPORT.resolveSibling(REPORT.getFileName() + ".tmp");
        json.writerWithDefaultPrettyPrinter().writeValue(staging.toFile(), report);
        Files.move(staging, REPORT, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** 与两页 synthetic 样例相同的稳定模块、页面身份和最小完整 PRD。 */
    private Prd prd() {
        var list = new PrdStructure.Page("list", "项目管理", "LIST", Map.of("overview", "维护项目名称", "fields", "编号、名称",
            "search", "本期无需查询", "sorting", "编号升序", "actions", "修改名称、保存、查看详情", "states", "本地内存数据", "acceptance", "保存后详情同步"));
        var detail = new PrdStructure.Page("detail", "项目详情", "DETAIL", Map.of("overview", "查看同一项目", "fields", "编号、名称",
            "actions", "返回列表", "states", "内存演示", "acceptance", "展示列表保存后的名称"));
        var module = new PrdStructure.Module("items", "项目管理", Map.of("overview", "两页项目管理", "rules", "修改仅在演示内存生效",
            "permissions", "本地验收", "acceptance", "详情与列表同步"), List.of(list, detail));
        return new Prd("P5合成验收背景", "验证工作台原型交互", "项目列表和详情", "列表修改后进入详情", "共享模拟数据", "重载恢复初始项目",
            "页面切换与修订可追溯", "不请求外部服务", "真实业务数据和模型生成", List.of(), List.of(), 2, List.of(module));
    }

    /** 临时目录初始化失败即中止测试，不能退回生产目录。 */
    private static Path temporaryRoot() {
        try { return Files.createTempDirectory("alpha-p5-browser-"); }
        catch (IOException failure) { throw new IllegalStateException("无法建立隔离原型验收目录", failure); }
    }
}
