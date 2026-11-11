package com._36knight.alpha;

import com._36knight.alpha.project.ProjectException;
import com._36knight.alpha.prototype.PrototypeArtifactStore;
import com._36knight.alpha.prototype.PrototypeStorageProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/** 使用明确的合成文件核验封存读取边界，不将其声明为浏览器或模型验收结果。@author Andersen */
class PrototypeArtifactStoreTest {
    /** 每个测试独占的文件根目录，不读取开发者真实产物。 */
    @TempDir Path directory;
    /** 构造与读取同一份 manifest JSON。 */
    private final ObjectMapper json = new ObjectMapper();
    /** 被测服务不依赖 Spring 容器或数据库。 */
    private PrototypeArtifactStore store;

    /** 使用临时目录替代默认产物根，避免污染 P2 current 指针。 */
    @BeforeEach void setup() {
        var properties = new PrototypeStorageProperties(); properties.setArtifactRoot(directory);
        store = new PrototypeArtifactStore(properties, json);
    }

    /** 完整封存对象转换为无宿主路径的业务引用，不回显未知验证字段。 */
    @Test void verifiesCompleteObjectAndExposesOnlyRelativeReferences() throws Exception {
        String id = fixture(manifest -> manifest.withObject("validation").put("privateDiagnostic", "/Users/private/task.log"));
        var result = store.verify(id);
        assertThat(result.objectKey()).isEqualTo(id);
        assertThat(result.toolchainId()).isEqualTo("sha256:" + "a".repeat(64));
        assertThat(result.validation().path("cases").size()).isEqualTo(1);
        assertThat(result.validation().has("privateDiagnostic")).isFalse();
        assertThat(result.artifact().path("mode").asText()).isEqualTo("synthetic");
        assertThat(result.artifact().path("generation").asText()).isEqualTo("test-fixture");
        assertThat(result.artifact().path("files").get(0).path("objectKey").asText()).isEqualTo("artifacts/" + id + "/dist/index.html");
        assertThat(result.artifact().toString()).doesNotContain(directory.toString(), "Users", "current.json");
        assertThat(Files.exists(directory.resolve("current.json"))).isFalse();
    }

    /** 宿主构建存储自己的指纹和验证版本，不能冒用历史镜像身份。 */
    @Test void acceptsLocalToolchainEvidence() throws Exception {
        String fingerprint = "host-sha256:" + "d".repeat(64);
        String id = fixture(manifest -> {
            manifest.remove("imageId");
            manifest.put("toolchainId", fingerprint);
            manifest.withObject("validation").withObject("toolchain").put("execution", "host").put("vite", "5.4.21");
        });
        var result = store.verify(id);
        assertThat(result.toolchainId()).isEqualTo(fingerprint);
        assertThat(result.validation().at("/toolchain/execution").asText()).isEqualTo("host");
    }

    /** 合成协议测试分别保留三种来源组合；live 分支只是格式验收，没有实际调用模型。 */
    @Test void preservesEachSupportedModeAndGenerationPair() throws Exception {
        for (var pair : Map.of("demo", "trusted-fixture", "synthetic", "test-fixture", "live", "model").entrySet()) {
            String id = fixture(manifest -> manifest.put("mode", pair.getKey()).put("generation", pair.getValue()));
            var result = store.verify(id);
            assertThat(result.artifact().path("mode").asText()).isEqualTo(pair.getKey());
            assertThat(result.artifact().path("generation").asText()).isEqualTo(pair.getValue());
        }
    }

    /** 来源未知、缺失或交叉标记时拒绝，避免可信样板以模型标签登记为业务修订。 */
    @Test void rejectsMissingUnknownAndMismatchedGenerationPairs() throws Exception {
        for (String[] pair : new String[][]{{"demo", "model"}, {"synthetic", "trusted-fixture"}, {"live", "trusted-fixture"}, {"live", "test-fixture"}, {"unknown", "model"}, {"demo", "unknown"}}) {
            String id = fixture(manifest -> manifest.put("mode", pair[0]).put("generation", pair[1]));
            rejected(() -> store.verify(id));
        }
        String missing = fixture(manifest -> manifest.remove("generation"));
        rejected(() -> store.verify(missing));
        String wrongType = fixture(manifest -> manifest.put("mode", 1));
        rejected(() -> store.verify(wrongType));
    }

    /** 对象身份只接受固定摘要，绝对路径和目录穿越均在磁盘读取前拒绝。 */
    @Test void rejectsPathsAndMissingObjects() {
        for (String id : new String[]{"../private", "/etc/passwd", "C:\\private", "a".repeat(63), "A".repeat(64), "a".repeat(64)}) rejected(() -> store.verify(id));
        rejected(() -> store.verify(null));
    }

    /** manifest 本身被改动后，目录名不能继续为内容身份。 */
    @Test void rejectsChangedManifest() throws Exception {
        String id = fixture(manifest -> { });
        Files.writeString(object(id).resolve("artifact.json"), "{}\n");
        rejected(() -> store.verify(id));
    }

    /** 相同大小的字节改动仍必须被 SHA-256 检出。 */
    @Test void rejectsSameLengthContentTampering() throws Exception {
        String id = fixture(manifest -> { });
        Files.writeString(object(id).resolve("dist/index.html"), "<p>changed</p>");
        rejected(() -> store.verify(id));
    }

    /** 每个清单文件都必须存在，树内额外内容不能逃过摘要校验。 */
    @Test void rejectsMissingAndUnlistedFiles() throws Exception {
        String missing = fixture(manifest -> { });
        Files.delete(object(missing).resolve("source/main.js"));
        rejected(() -> store.verify(missing));
        String extra = fixture(manifest -> manifest.put("notice", "另一组合成数据"));
        Files.writeString(object(extra).resolve("dist/extra.js"), "extra");
        rejected(() -> store.verify(extra));
    }

    /** 文件和子目录链接都不能让产物读取越出固定对象。 */
    @Test void rejectsFileAndDirectorySymlinks() throws Exception {
        String id = fixture(manifest -> { });
        Path original = object(id).resolve("dist/index.html");
        Path target = directory.resolve("outside.html"); Files.move(original, target); Files.createSymbolicLink(original, target);
        rejected(() -> store.verify(id));
        Files.delete(original); Files.move(target, original);
        Path source = object(id).resolve("source"), moved = directory.resolve("source-copy"); Files.move(source, moved); Files.createSymbolicLink(source, moved);
        rejected(() -> store.verify(id));
    }

    /** artifacts 根和具体对象链接同样禁止，不能仅校验最终文件。 */
    @Test void rejectsObjectAndArtifactsDirectorySymlinks() throws Exception {
        String id = fixture(manifest -> { });
        Path original = object(id), moved = directory.resolve("moved-object"); Files.move(original, moved); Files.createSymbolicLink(original, moved);
        rejected(() -> store.verify(id));
        Files.delete(original); Files.move(moved, original);
        Path artifacts = directory.resolve("artifacts"), renamed = directory.resolve("moved-artifacts"); Files.move(artifacts, renamed); Files.createSymbolicLink(artifacts, renamed);
        rejected(() -> store.verify(id));
    }

    /** 合成的失败或未执行检查不能成为已验证业务修订。 */
    @Test void rejectsFailedMissingAndEmptyVerificationCases() throws Exception {
        String failed = fixture(manifest -> ((ObjectNode) manifest.path("validation").path("cases").get(0)).put("status", "FAILED"));
        rejected(() -> store.verify(failed));
        String empty = fixture(manifest -> manifest.withObject("validation").putArray("cases"));
        rejected(() -> store.verify(empty));
        String pending = fixture(manifest -> manifest.withObject("validation").put("status", "NOT_RUN"));
        rejected(() -> store.verify(pending));
    }

    /** 路径歧义、重复项、源码映射和与 P2 不一致的文件类型都失败关闭。 */
    @Test void rejectsUnsafeDuplicateAndUnsupportedManifestPaths() throws Exception {
        for (String path : new String[]{"../index.html", "/index.html", "a//index.html", "a%2findex.html", ".hidden/index.html", "a/index.js.map", "a/index.exe", "a\nb\nc.html", "a\\index.html"}) {
            String id = fixture(manifest -> ((ObjectNode) manifest.path("files").get(0)).put("path", path));
            rejected(() -> store.verify(id));
        }
        String duplicate = fixture(manifest -> ((ArrayNode) manifest.path("files")).add(manifest.path("files").get(0).deepCopy()));
        rejected(() -> store.verify(duplicate));
    }

    /** 协议版本不作字符串强制转换，失败的隔离证据不能挂在 PASSED 结果下入库。 */
    @Test void rejectsWrongProtocolTypeAndContradictoryIsolationEvidence() throws Exception {
        String version = fixture(manifest -> manifest.put("protocolVersion", "1"));
        rejected(() -> store.verify(version));
        String writable = fixture(manifest -> manifest.withObject("validation").putObject("isolation").put("readOnlyRoot", false));
        rejected(() -> store.verify(writable));
        String root = fixture(manifest -> manifest.withObject("validation").putObject("isolation").put("uid", 0));
        rejected(() -> store.verify(root));
    }

    /** 在打开内容之前拒绝超限数量和虚报的巨大字节数，不分配对应内存。 */
    @Test void rejectsOverLimitMetadataAndOversizedManifest() throws Exception {
        String bytes = fixture(manifest -> ((ObjectNode) manifest.path("files").get(0)).put("bytes", 50L * 1024 * 1024 + 1));
        rejected(() -> store.verify(bytes));
        String count = fixture(manifest -> { ArrayNode files = (ArrayNode) manifest.path("files"); for (int i = 0; i < 1000; i++) files.add(files.get(0).deepCopy()); });
        rejected(() -> store.verify(count));
        String oversized = fixture(manifest -> manifest.put("extra", "x".repeat(1024 * 1024)));
        rejected(() -> store.verify(oversized));
    }

    /** 所有文件均完整也不能通过缺少 HTML 入口或夹带私有环境文件的清单。 */
    @Test void rejectsMissingEntryAndPrivateSourceNames() throws Exception {
        String missing = fixture(manifest -> ((ObjectNode) manifest.path("files").get(0)).put("path", "other.html"));
        Files.move(object(missing).resolve("dist/index.html"), object(missing).resolve("dist/other.html"));
        rejected(() -> store.verify(missing));
        String privateSource = fixture(manifest -> ((ObjectNode) manifest.path("sources").get(0)).put("path", ".env"));
        Files.move(object(privateSource).resolve("source/main.js"), object(privateSource).resolve("source/.env"));
        rejected(() -> store.verify(privateSource));
    }

    /** 编辑读回真实封存的生成区文本，保留中文与值语义，平台路由和其他模板文件不交给模型。 */
    @Test void readsOnlyVerifiedGeneratedTextsForEditing() throws Exception {
        String page = "<template><main>中文列表</main></template>";
        String mock = "export const rows = [{id:'0',count:0,enabled:false,name:''}];";
        String id = generatedFixture(Map.of(
            "src/features/generated/items/List.vue", page.getBytes(StandardCharsets.UTF_8),
            "src/mock/generated/items.js", mock.getBytes(StandardCharsets.UTF_8),
            "src/features/generated/routes.js", "// 平台独占路由".getBytes(StandardCharsets.UTF_8),
            "src/platform/components.js", "// 平台独占组件".getBytes(StandardCharsets.UTF_8)));
        Map<String, String> texts = store.generatedFiles(id);
        assertThat(texts).containsExactlyInAnyOrderEntriesOf(Map.of(
            "src/features/generated/items/List.vue", page, "src/mock/generated/items.js", mock));
        assertThatThrownBy(() -> texts.put("unexpected", "mutation")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(Files.exists(directory.resolve("current.json"))).isFalse();
    }

    /** 生成源码被篡改时不能进入编辑上下文，即使篡改保持相同文件长度。 */
    @Test void rejectsGeneratedContentDigestMismatch() throws Exception {
        String id = generatedFixture(Map.of("src/mock/generated/items.js", "export const count = 0;".getBytes(StandardCharsets.UTF_8)));
        Files.writeString(object(id).resolve("source/src/mock/generated/items.js"), "export const count = 1;");
        rejected(() -> store.generatedFiles(id));
    }

    /** 已建立预览快照不再读取磁盘；目录发生变化只影响下次申请，不替换当前修订内容。 */
    @Test void staticPreviewSnapshotRemainsFixedAfterDiskChanges() throws Exception {
        String id = fixture(manifest -> { });
        var snapshot = store.staticSnapshot(store.verify(id));
        assertThat(snapshot.text("index.html")).isEqualTo("<p>fixture</p>");
        assertThat(snapshot.bytes()).isEqualTo("<p>fixture</p>".getBytes(StandardCharsets.UTF_8).length);
        assertThat(snapshot.size("source/main.js")).isEqualTo(-1);
        snapshot.write("index.html", new java.io.OutputStream() {
            /** 字节写入只用于验证输出流不能持有快照的内部数组。 */
            @Override public void write(int value) { }
            /** 即使接收方主动修改传入缓冲区，也不能破坏后续预览请求。 */
            @Override public void write(byte[] bytes, int offset, int length) { java.util.Arrays.fill(bytes, (byte) 0); }
        });
        assertThat(snapshot.text("index.html")).isEqualTo("<p>fixture</p>");
        Files.writeString(object(id).resolve("dist/index.html"), "<p>changed</p>");
        assertThat(snapshot.text("index.html")).isEqualTo("<p>fixture</p>");
        rejected(() -> store.staticSnapshot(store.verify(id)));
    }

    /** 验证和快照读取之间修改相同长度内容，静态摘要必须再次发现，而非信任早先结果。 */
    @Test void staticPreviewSnapshotRechecksBytesAfterVerification() throws Exception {
        String id = fixture(manifest -> { });
        var verified = store.verify(id);
        Files.writeString(object(id).resolve("dist/index.html"), "<p>changed</p>");
        rejected(() -> store.staticSnapshot(verified));
    }

    /** 快照读取前替换祖先为链接也不能从外部目录读取。 */
    @Test void staticPreviewSnapshotRejectsDirectoryReplacement() throws Exception {
        String id = fixture(manifest -> { });
        var verified = store.verify(id);
        Path original = object(id).resolve("dist"), moved = directory.resolve("outside-dist");
        Files.move(original, moved); Files.createSymbolicLink(original, moved);
        rejected(() -> store.staticSnapshot(verified));
    }

    /** 在完整对象验证后替换内容，第二次文本摘要比较仍必须拒绝。 */
    @Test void rechecksGeneratedDigestAfterArtifactVerification() throws Exception {
        String id = generatedFixture(Map.of("src/mock/generated/items.js", "export const count = 0;".getBytes(StandardCharsets.UTF_8)));
        PrototypeArtifactStore checked = spy(store);
        doAnswer(invocation -> {
            Object verified = invocation.callRealMethod();
            Files.writeString(object(id).resolve("source/src/mock/generated/items.js"), "export const count = 1;");
            return verified;
        }).when(checked).verify(id);
        rejected(() -> checked.generatedFiles(id));
    }

    /** 生成区最终文件或祖先目录为链接时均拒绝，不读取对象之外的文本。 */
    @Test void rejectsGeneratedFileAndDirectorySymlinks() throws Exception {
        String id = generatedFixture(Map.of("src/mock/generated/items.js", "export const count = 0;".getBytes(StandardCharsets.UTF_8)));
        Path file = object(id).resolve("source/src/mock/generated/items.js");
        Path outside = directory.resolve("outside-generated.js");
        Files.move(file, outside);
        Files.createSymbolicLink(file, outside);
        rejected(() -> store.generatedFiles(id));
        Files.delete(file);
        Files.move(outside, file);
        Path folder = file.getParent();
        Path moved = directory.resolve("outside-generated-directory");
        Files.move(folder, moved);
        Files.createSymbolicLink(folder, moved);
        rejected(() -> store.generatedFiles(id));
    }

    /** 摘要合法也不能把非法 UTF-8、超大单文件或空生成区交给模型。 */
    @Test void rejectsInvalidTextSizeAndMissingGeneratedSources() throws Exception {
        String malformed = generatedFixture(Map.of("src/mock/generated/items.js", new byte[]{(byte) 0xc3, (byte) 0x28}));
        rejected(() -> store.generatedFiles(malformed));
        String oversized = generatedFixture(Map.of("src/mock/generated/items.js", "x".repeat(256 * 1024 + 1).getBytes(StandardCharsets.UTF_8)));
        rejected(() -> store.generatedFiles(oversized));
        String noGenerated = fixture(manifest -> { });
        rejected(() -> store.generatedFiles(noGenerated));
    }

    /** 诊断错误不能附带临时目录、原始 IOException 或文件内容。 */
    private void rejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ProjectException.class, failure -> {
            assertThat(failure.code()).isEqualTo("PROTOTYPE_ARTIFACT_UNAVAILABLE");
            assertThat(failure.getMessage()).doesNotContain(directory.toString(), "outside.html", "task.log");
            assertThat(failure.getCause()).isNull();
        });
    }

    /** 只生成验证协议的合成对象，未运行构建、浏览器或模型。 */
    private String fixture(Consumer<ObjectNode> customize) throws Exception {
        Map<String, byte[]> files = Map.of("dist/index.html", "<p>fixture</p>".getBytes(StandardCharsets.UTF_8), "source/main.js", "// 合成测试文件".getBytes(StandardCharsets.UTF_8), "screenshots/example.png", new byte[]{1, 2, 3});
        ObjectNode manifest = json.createObjectNode().put("protocolVersion", 1).put("mode", "synthetic").put("generation", "test-fixture")
                .put("imageId", "sha256:" + "a".repeat(64)).put("templateDigest", "b".repeat(64)).put("sourceDigest", "c".repeat(64));
        manifest.putObject("validation").put("status", "PASSED").putArray("cases").addObject().put("name", "合成协议检查，未运行浏览器").put("status", "PASSED");
        entries(manifest.putArray("files"), "index.html", files.get("dist/index.html"));
        entries(manifest.putArray("sources"), "main.js", files.get("source/main.js"));
        entries(manifest.putArray("screenshots"), "example.png", files.get("screenshots/example.png"));
        customize.accept(manifest);
        byte[] content = json.writeValueAsBytes(manifest);
        String id = hash(content);
        Path object = object(id); Files.createDirectories(object);
        Files.write(object.resolve("artifact.json"), content);
        for (var file : files.entrySet()) { Path target = object.resolve(file.getKey()); Files.createDirectories(target.getParent()); Files.write(target, file.getValue()); }
        return id;
    }

    /** 将真实生成区文件追加到合成对象，再按新 manifest 字节摘要重新封存，避免伪造文件服务返回值。 */
    private String generatedFixture(Map<String, byte[]> sources) throws Exception {
        String originalId = fixture(manifest -> { });
        Path original = object(originalId);
        ObjectNode manifest = (ObjectNode) json.readTree(Files.readAllBytes(original.resolve("artifact.json")));
        for (var source : sources.entrySet()) {
            entries((ArrayNode) manifest.path("sources"), source.getKey(), source.getValue());
            Path file = original.resolve("source").resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, source.getValue());
        }
        byte[] content = json.writeValueAsBytes(manifest);
        String id = hash(content);
        Files.write(original.resolve("artifact.json"), content);
        Files.move(original, object(id));
        return id;
    }

    /** 生成与文件实际字节一致的单项清单。 */
    private void entries(ArrayNode list, String path, byte[] bytes) throws Exception { list.addObject().put("path", path).put("sha256", hash(bytes)).put("bytes", bytes.length); }

    /** 测试与平台采用相同 SHA-256 内容身份。 */
    private String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }

    /** 返回本测试唯一根内的对象目录，不接受外部输入。 */
    private Path object(String id) { return directory.resolve("artifacts").resolve(id); }
}
