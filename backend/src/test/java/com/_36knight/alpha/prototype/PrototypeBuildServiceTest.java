package com._36knight.alpha.prototype;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** Java→Node 的真实本机管道测试；仅执行平台编写的临时脚本，不运行 Docker 或模型。@author Andersen */
class PrototypeBuildServiceTest {
    /** 每项测试拥有独立 Worker、产物根目录和协议捕获文件。 */
    @TempDir
    Path directory;
    /** 协议编码和子进程记录复用的测试映射器。 */
    private final ObjectMapper json = new ObjectMapper();
    /** 合成对象身份只检验桥接协议，文件验证由 ArtifactStore 测试负责。 */
    private static final String ARTIFACT = "a".repeat(64);
    /** Node 可继承的部署定位环境，其余变量（包含所有凭证配置）必须不存在；macOS JVM 可自行补充非敏感的用户文本编码标记。 */
    private static final Set<String> ALLOWED_ENVIRONMENT = Set.of("PATH", "HOME", "TMPDIR", "TEMP", "TMP", "SystemRoot", "USERPROFILE", "LOCALAPPDATA",
        "PLAYWRIGHT_BROWSERS_PATH", "XDG_CACHE_HOME", "PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH", "ALPHA_PROTOTYPE_TEMPLATE_DIR", "ALPHA_PROTOTYPE_ARTIFACT_ROOT", "__CF_USER_TEXT_ENCODING");

    /** 实际 stdin 保留全部文件及业务字段，来源标签和服务端产物路径不能被源码覆盖。 */
    @ParameterizedTest
    @ValueSource(strings = {"live", "synthetic"})
    void sendsCompleteProtocolWithoutInheritedCredentials(String mode) throws Exception {
        PrototypeBuildService service = worker("""
            import {writeFileSync} from 'node:fs';
            let raw = ''; for await (const chunk of process.stdin) raw += chunk;
            const payload = JSON.parse(raw);
            writeFileSync('received.json', JSON.stringify({payload,command:process.argv[2],
                envKeys:Object.keys(process.env),artifactRoot:process.env.ALPHA_PROTOTYPE_ARTIFACT_ROOT}));
            process.stdout.write(JSON.stringify({status:'PASSED',artifactId:'%s'}));
            """.formatted(ARTIFACT));
        PrototypeWorkflow.Output output = output();
        assertThat(service.build(output, mode)).isEqualTo(ARTIFACT);
        // 长任务完成本身构成新的能力证据，无需等待下一轮后台 doctor 才能接续编辑。
        assertThat(service.available()).isTrue();
        JsonNode captured = json.readTree(Files.readString(directory.resolve("received.json")));
        JsonNode payload = captured.path("payload");
        assertThat(captured.path("command").asText()).isEqualTo("build-generated");
        assertThat(payload.path("protocolVersion").asInt()).isEqualTo(1);
        assertThat(payload.path("mode").asText()).isEqualTo(mode);
        assertThat(payload.path("generation").asText()).isEqualTo("live".equals(mode) ? "model" : "test-fixture");
        assertThat(payload.path("files")).isEqualTo(json.valueToTree(output.files()));
        assertThat(payload.path("pages")).isEqualTo(output.plan().path("pages"));
        assertThat(payload.path("cases")).isEqualTo(output.cases());
        assertThat(payload.has("changeSummary")).isFalse();
        assertThat(captured.path("artifactRoot").asText()).isEqualTo(directory.resolve("objects").toAbsolutePath().toString());
        List<String> environment = new ArrayList<>();
        captured.path("envKeys").forEach(key -> environment.add(key.asText()));
        assertThat(environment).isNotEmpty().allMatch(ALLOWED_ENVIRONMENT::contains);
        assertThat(environment).doesNotContain("NODE_OPTIONS", "JAVA_TOOL_OPTIONS", "OPENAI_API_KEY",
            "DASHSCOPE_API_KEY", "DATABASE_PASSWORD", "MODEL_ENCRYPTION_KEY", "SPRING_APPLICATION_JSON");
        System.getenv().keySet().stream().filter(key -> !ALLOWED_ENVIRONMENT.contains(key))
            .forEach(key -> assertThat(environment).doesNotContain(key));
    }

    /** 联调样板模式不能通过生成桥接重新标记为真实产物。 */
    @Test
    void rejectsDemoBeforeStartingProcess() throws Exception {
        PrototypeBuildService service = worker("import {writeFileSync} from 'node:fs'; writeFileSync('started','yes');");
        assertThatThrownBy(() -> service.build(output(), "demo"))
            .isInstanceOf(PrototypeBuildException.class)
            .extracting(error -> ((PrototypeBuildException) error).code()).isEqualTo("PROTOTYPE_FILE_REJECTED");
        assertThat(directory.resolve("started")).doesNotExist();
    }

    /** 构建、文件及浏览器失败保持可修复分类，诊断只供内部修复使用。 */
    @ParameterizedTest
    @ValueSource(strings = {"PROTOTYPE_FILE_REJECTED", "PROTOTYPE_BUILD_FAILED", "PROTOTYPE_PREVIEW_FAILED"})
    void preservesRepairableFailureClassification(String code) throws Exception {
        PrototypeBuildService service = worker("""
            for await (const chunk of process.stdin) {}
            process.stderr.write(JSON.stringify({code:'%s',message:'合成失败',diagnostic:'组件字段不匹配'}));
            process.exitCode = 1;
            """.formatted(code));
        assertThatThrownBy(() -> service.build(output(), "synthetic"))
            .isInstanceOf(PrototypeBuildException.class).hasMessage(code).hasNoCause()
            .satisfies(error -> {
                PrototypeBuildException failure = (PrototypeBuildException) error;
                assertThat(failure.repairable()).isTrue();
                assertThat(failure.diagnostic()).contains("组件字段不匹配");
            });
    }

    /** 未知分类统一转为环境故障，不能由子进程文本决定继续消耗模型修复预算。 */
    @Test
    void mapsUnknownFailureToNonRepairableEnvironmentError() throws Exception {
        PrototypeBuildService service = worker("""
            for await (const chunk of process.stdin) {}
            process.stderr.write(JSON.stringify({code:'untrusted-code',message:'synthetic'}));
            process.exitCode = 1;
            """);
        assertThatThrownBy(() -> service.build(output(), "synthetic"))
            .isInstanceOf(PrototypeBuildException.class)
            .satisfies(error -> {
                PrototypeBuildException failure = (PrototypeBuildException) error;
                assertThat(failure.code()).isEqualTo("PROTOTYPE_RUNNER_UNAVAILABLE");
                assertThat(failure.repairable()).isFalse();
            });
    }

    /** 单响应协议拒绝多余日志、重复键和非对象输出，不能宽松解析伪成功。 */
    @ParameterizedTest
    @ValueSource(strings = {"not json", "{} {}", "{\"status\":\"FAILED\",\"status\":\"PASSED\"}", "[]"})
    void rejectsInvalidStdoutProtocol(String payload) throws Exception {
        PrototypeBuildService service = worker("for await (const chunk of process.stdin) {}\nprocess.stdout.write(" + json.writeValueAsString(payload) + ");");
        assertThatThrownBy(() -> service.build(output(), "synthetic"))
            .isInstanceOf(PrototypeBuildException.class)
            .extracting(error -> ((PrototypeBuildException) error).code()).isEqualTo("PROTOTYPE_RUNNER_UNAVAILABLE");
    }

    /** 声称 PASSED 仍必须返回确切内容对象身份，不能传回绝对路径或 URL。 */
    @Test
    void rejectsInvalidArtifactIdentity() throws Exception {
        PrototypeBuildService service = worker("""
            for await (const chunk of process.stdin) {}
            process.stdout.write(JSON.stringify({status:'PASSED',artifactId:'../../private/path'}));
            """);
        assertThatThrownBy(() -> service.build(output(), "synthetic"))
            .extracting(error -> ((PrototypeBuildException) error).code()).isEqualTo("PROTOTYPE_BUILD_FAILED");
    }

    /** stdout 及 stderr 都按实际字节限制，子进程持续写入时也必须停止。 */
    @ParameterizedTest
    @ValueSource(strings = {"stdout", "stderr"})
    void stopsOversizedOutputWithoutAcceptingTruncation(String channel) throws Exception {
        PrototypeBuildService service = worker("""
            for await (const chunk of process.stdin) {}
            process.%s.write('x'.repeat(2 * 1024 * 1024));
            setInterval(() => {}, 1000);
            """.formatted(channel));
        assertTimeoutPreemptively(Duration.ofSeconds(8), () ->
            assertThatThrownBy(() -> service.build(output(), "synthetic"))
                .isInstanceOf(PrototypeBuildException.class)
                .extracting(error -> ((PrototypeBuildException) error).code()).isEqualTo("PROTOTYPE_RUNNER_UNAVAILABLE"));
    }

    /** 超大输入不进入子进程 stdin，也不会等待编译或浏览器执行。 */
    @Test
    void rejectsOversizedRequest() throws Exception {
        PrototypeBuildService service = worker("for await (const chunk of process.stdin) {};");
        PrototypeWorkflow.Output normal = output();
        PrototypeWorkflow.Output large = new PrototypeWorkflow.Output(normal.plan(),
            List.of(new PrototypeWorkflow.SourceFile("src/features/generated/large.js", "x".repeat(8 * 1024 * 1024))),
            normal.cases(), "合成超限输入");
        assertThatThrownBy(() -> service.build(large, "synthetic"))
            .extracting(error -> ((PrototypeBuildException) error).code()).isEqualTo("PROTOTYPE_FILE_REJECTED");
    }

    /** Java 租约取消向实际 Node 发送 SIGTERM，留出专属资源清理时间并保留中断标记。 */
    @Test
    void interruptionAllowsWorkerSigtermCleanup() throws Exception {
        PrototypeBuildService service = worker("""
            import {writeFileSync} from 'node:fs';
            process.on('SIGTERM', () => { writeFileSync('terminated','cleaned'); process.exit(0); });
            for await (const chunk of process.stdin) {}
            writeFileSync('ready','ready');
            setInterval(() => {}, 1000);
            """);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread execution = Thread.ofPlatform().start(() -> {
            try { service.build(output(), "synthetic"); }
            catch (Throwable error) { failure.set(error); }
            finally { interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        try {
            awaitFile(directory.resolve("ready"));
            execution.interrupt();
            execution.join(5000);
            assertThat(execution.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(PrototypeBuildException.class);
            assertThat(((PrototypeBuildException) failure.get()).code()).isEqualTo("PROTOTYPE_INTERRUPTED");
            assertThat(interrupted).isTrue();
            assertThat(Files.readString(directory.resolve("terminated"))).isEqualTo("cleaned");
        } finally {
            execution.interrupt();
            execution.join(5000);
        }
    }

    /** doctor 的结果仅更新能力缓存，后续探测失败会关闭已有可用缓存。 */
    @Test
    void doctorRefreshControlsCachedAvailability() throws Exception {
        PrototypeBuildService service = worker("""
            for await (const chunk of process.stdin) {}
            process.stdout.write(JSON.stringify({available:process.argv[2] === 'doctor'}));
            """);
        assertThat(service.available()).isFalse();
        service.refreshCapability();
        assertThat(service.available()).isTrue();
        Files.writeString(directory.resolve("src/cli.mjs"), "process.stdout.write('{\"available\":false}');");
        service.refreshCapability();
        assertThat(service.available()).isFalse();
    }

    /** 脚本和目录仅由测试创建；没有通过业务请求或模型指定可执行内容。 */
    private PrototypeBuildService worker(String script) throws Exception {
        Files.createDirectories(directory.resolve("src"));
        Files.writeString(directory.resolve("src/cli.mjs"), script, StandardCharsets.UTF_8);
        PrototypeGenerationProperties properties = new PrototypeGenerationProperties();
        properties.setWorkerDirectory(directory);
        PrototypeStorageProperties storage = new PrototypeStorageProperties();
        storage.setArtifactRoot(directory.resolve("objects"));
        return new PrototypeBuildService(properties, storage, json);
    }

    /** 构造包含两份不同源码、路由与补充操作的完整合成候选。 */
    private PrototypeWorkflow.Output output() {
        var pages = json.createArrayNode();
        pages.addObject().put("moduleId", "items").put("pageId", "list").put("path", "/items")
            .put("component", "src/features/generated/items/List.vue").put("menu", true);
        JsonNode plan = json.createObjectNode().set("pages", pages);
        var cases = json.createArrayNode();
        cases.addObject().put("name", "查看列表").put("path", "/items")
            .putArray("steps").addObject().put("action", "assertText").put("text", "列表");
        return new PrototypeWorkflow.Output(plan, List.of(
            new PrototypeWorkflow.SourceFile("src/features/generated/items/List.vue", "<template><main>中文\n列表</main></template>"),
            new PrototypeWorkflow.SourceFile("src/mock/generated/items.js", "export const items = [{id:'0',count:0,enabled:false,name:''}];")),
            cases, "协议合成测试，不代表模型或容器验证");
    }

    /** 等待真实进程就绪而不假定 Node 启动耗时，超时给出明确失败。 */
    private void awaitFile(Path file) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!Files.exists(file) && System.nanoTime() < end) Thread.sleep(10);
        assertThat(file).exists();
    }
}
