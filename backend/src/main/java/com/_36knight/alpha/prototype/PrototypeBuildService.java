package com._36knight.alpha.prototype;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/** Java 与 P2 Worker 的固定 stdin 协议桥接；以本机编译器和 Chromium 验证受限生成文件。@author Andersen */
@Service
public class PrototypeBuildService {
    /** 配置只由后端管理。 */
    private final PrototypeGenerationProperties settings;
    /** Java 和 Node 必须引用同一封存根目录。 */
    private final PrototypeStorageProperties storage;
    /** stdin 与单响应协议编码器。 */
    private final ObjectMapper json;
    /** 能力检查结果最多保留九十秒，提交事务只读取缓存。 */
    private volatile long availableUntil;
    /** 从受信 Worker 接受的失败类别；未知错误保守归为环境不可用。 */
    private static final Set<String> CODES = Set.of("PROTOTYPE_FILE_REJECTED", "PROTOTYPE_BUILD_FAILED",
            "PROTOTYPE_PREVIEW_FAILED", "PROTOTYPE_TIMEOUT", "PROTOTYPE_ARTIFACT_UNAVAILABLE",
            "PROTOTYPE_RUNNER_UNAVAILABLE", "PROTOTYPE_RUNNER_BUSY", "PROTOTYPE_RUNNER_STALE_LOCK",
            "PROTOTYPE_TOOLCHAIN_NOT_PREPARED", "PROTOTYPE_TEMPLATE_CHANGED");

    /** 初始化桥接本身不创建文件、不启动浏览器或模型。 */
    public PrototypeBuildService(PrototypeGenerationProperties settings, PrototypeStorageProperties storage, ObjectMapper json) {
        this.settings = settings;
        this.storage = storage;
        this.json = json;
    }

    /** 提交期间只读取已经完成的环境探测，避免持有数据库锁等待子进程。 */
    public boolean available() { return settings.isEnabled() && System.nanoTime() < availableUntil; }

    /** 后台探测本机 Node、Vite 和 Chromium；失败关闭接收能力，绝不自动下载或安装依赖。 */
    public synchronized void refreshCapability() {
        if (!settings.isEnabled()) { availableUntil = 0; return; }
        try {
            JsonNode result = invoke("doctor", null, Duration.ofSeconds(65));
            availableUntil = result.path("available").asBoolean(false) ? System.nanoTime() + Duration.ofSeconds(90).toNanos() : 0;
        } catch (PrototypeBuildException failure) { availableUntil = 0; }
    }

    /** 模型仅接触固定组件契约，不能提交自己的构建入口或组件适配器。 */
    public String componentContract() {
        Path path = settings.getTemplateDirectory().toAbsolutePath().normalize().resolve("component-contracts.md");
        try (InputStream input = Files.newInputStream(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            byte[] content = input.readNBytes(128 * 1024 + 1);
            if (content.length > 128 * 1024) throw new IOException("契约过大");
            return new String(content, StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new PrototypeBuildException("PROTOTYPE_RUNNER_UNAVAILABLE", "固定组件契约不可读取"); }
    }

    /** 所有生成区源码作为完整集合交给 Worker；页面与受限用例由平台执行器解释。 */
    public String build(PrototypeWorkflow.Output output, String mode) {
        if (!Set.of("live", "synthetic").contains(mode)) throw new PrototypeBuildException("PROTOTYPE_FILE_REJECTED", "不支持的生成来源");
        ObjectNode request = json.createObjectNode().put("protocolVersion", 1).put("mode", mode)
                .put("generation", "live".equals(mode) ? "model" : "test-fixture");
        request.set("files", json.valueToTree(output.files()));
        request.set("pages", output.plan().path("pages"));
        request.set("cases", output.cases());
        JsonNode response = invoke("build-generated", request, Duration.ofSeconds(270));
        String key = response.path("artifactId").asText();
        if (!"PASSED".equals(response.path("status").asText()) || !key.matches("[a-f0-9]{64}"))
            throw new PrototypeBuildException("PROTOTYPE_BUILD_FAILED", "Worker 未返回已验证的内容对象身份");
        // 成功任务已实际复核工具链；长任务结束后立即允许下一次编辑，不等待下一轮后台 doctor。
        availableUntil = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        return key;
    }

    /** 固定程序及参数，stdin/stdout/stderr 均有界；取消时 SIGTERM 给 Worker 清理其编译进程与临时浏览器。 */
    private JsonNode invoke(String command, JsonNode request, Duration timeout) {
        Process child = null;
        // 三条管道互相依赖，不能用低并发 commonPool 同时阻塞两个读端而饿死 stdin 写端。
        ExecutorService io = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Path worker = settings.getWorkerDirectory().toAbsolutePath().normalize();
            ProcessBuilder builder = new ProcessBuilder(settings.getNodeExecutable(), worker.resolve("src/cli.mjs").toString(), command);
            builder.directory(worker.toFile());
            Map<String, String> env = builder.environment();
            Map<String, String> inherited = Map.copyOf(env);
            env.clear();
            // Worker 只继承定位运行时和本机浏览器所需的环境；模型、数据库及应用私有配置不传给 Node。
            for (String key : List.of("PATH", "HOME", "TMPDIR", "TEMP", "TMP", "SystemRoot", "USERPROFILE", "LOCALAPPDATA", "PLAYWRIGHT_BROWSERS_PATH", "XDG_CACHE_HOME")) {
                if (inherited.containsKey(key)) env.put(key, inherited.get(key));
            }
            env.put("ALPHA_PROTOTYPE_ARTIFACT_ROOT", storage.getArtifactRoot().toAbsolutePath().normalize().toString());
            env.put("ALPHA_PROTOTYPE_TEMPLATE_DIR", settings.getTemplateDirectory().toAbsolutePath().normalize().toString());
            if (!settings.getChromiumExecutable().isBlank()) env.put("PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH", settings.getChromiumExecutable());
            child = builder.start();
            Process running = child;
            CompletableFuture<byte[]> stdout = readAsync(child.getInputStream(), 1024 * 1024, running, io);
            CompletableFuture<byte[]> stderr = readAsync(child.getErrorStream(), 128 * 1024, running, io);
            byte[] input = request == null ? new byte[0] : json.writeValueAsBytes(request);
            if (input.length > 8 * 1024 * 1024) throw new PrototypeBuildException("PROTOTYPE_FILE_REJECTED", "Worker 输入超过上限");
            CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> {
                try (var stream = running.getOutputStream()) { stream.write(input); }
                catch (IOException failure) { throw new PrototypeBuildException("PROTOTYPE_RUNNER_UNAVAILABLE", "Worker 输入管道关闭"); }
            }, io);
            if (!child.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) throw new PrototypeBuildException("PROTOTYPE_TIMEOUT", "Worker 执行超过时限");
            byte[] out = stdout.get(5, TimeUnit.SECONDS);
            byte[] err = stderr.get(5, TimeUnit.SECONDS);
            writer.get(5, TimeUnit.SECONDS);
            if (child.exitValue() != 0) {
                JsonNode failure;
                try { failure = read(err.length == 0 ? out : err); }
                catch (Exception invalid) { throw new PrototypeBuildException("PROTOTYPE_RUNNER_UNAVAILABLE", "Worker 未返回安全错误协议"); }
                String code = failure.path("code").asText();
                if (!CODES.contains(code)) code = "PROTOTYPE_RUNNER_UNAVAILABLE";
                throw new PrototypeBuildException(code, failure.path("message").asText("") + "\n" + failure.path("diagnostic").asText(""));
            }
            return read(out);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new PrototypeBuildException("PROTOTYPE_INTERRUPTED", "执行租约已取消");
        } catch (PrototypeBuildException failure) { throw failure; }
        catch (Exception failure) { throw new PrototypeBuildException("PROTOTYPE_RUNNER_UNAVAILABLE", "Worker 连接或输出协议不可用"); }
        finally { stop(child); io.shutdownNow(); }
    }

    /** 读取上限按实际字节执行，超量立即终止子进程，不能把截断响应当成功。 */
    private CompletableFuture<byte[]> readAsync(InputStream stream, int limit, Process child, ExecutorService io) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                byte[] bytes = stream.readNBytes(limit + 1);
                if (bytes.length > limit) { child.destroy(); throw new IOException("超量"); }
                return bytes;
            } catch (IOException failure) { throw new PrototypeBuildException("PROTOTYPE_RUNNER_UNAVAILABLE", "Worker 输出不可用或超过限额"); }
        }, io);
    }

    /** 严格解析单个响应，拒绝重复键和尾随日志。 */
    private JsonNode read(byte[] bytes) throws IOException {
        JsonNode value = json.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
        if (value == null || !value.isObject()) throw new IOException("无效 Worker 协议");
        return value;
    }

    /** 即使当前线程已中断，也为 Worker 留出有限清理时间，然后关闭剩余宿主子进程。 */
    private void stop(Process child) {
        if (child == null || !child.isAlive()) return;
        boolean interrupted = Thread.interrupted();
        try {
            child.destroy();
            if (!child.waitFor(12, TimeUnit.SECONDS)) {
                child.descendants().forEach(ProcessHandle::destroyForcibly);
                child.destroyForcibly();
            }
        } catch (InterruptedException again) { interrupted = true; child.destroyForcibly(); }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
