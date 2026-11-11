package com._36knight.alpha;

import com._36knight.alpha.exception.ApiExceptionHandler;
import com._36knight.alpha.project.ProjectException;
import com._36knight.alpha.prototype.PrototypeArtifactStore;
import com._36knight.alpha.prototype.PrototypeContracts;
import com._36knight.alpha.prototype.PrototypeExportController;
import com._36knight.alpha.prototype.PrototypeExportService;
import com._36knight.alpha.prototype.PrototypeService;
import com._36knight.alpha.prototype.PrototypeStorageProperties;
import com._36knight.alpha.workspace.LifecycleContracts;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实临时封存文件的导出协议与下载边界测试；合成数据不表示模型或浏览器验收。@author Andersen */
class PrototypeExportServiceTest {
    /** 临时对象根目录，不读取或修改开发者产物。 */ @TempDir Path directory;
    /** 保留 Java 日期的同一序列化契约。 */ private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    /** 只模拟业务归属读取，文件核验和 ZIP 真实执行。 */ private PrototypeService business;
    /** 真正验证对象清单和每个文件。 */ private PrototypeArtifactStore store;
    /** 被测导出服务不启动 Spring 或数据库。 */ private PrototypeExportService exports;
    /** 已封存合成对象身份。 */ private String objectKey;
    /** 已冻结的待导出修订，故意设为历史且来源过期。 */ private PrototypeContracts.RevisionView revision;
    /** 原始字节用于逐文件精确断言。 */ private Map<String, byte[]> original;
    /** 稳定合法的历史修订 ID。 */ private static final String REVISION = "abcdef12-1111-2222-3333-444455556666";
    /** 稳定合法的版本 ID，不承载真实用户数据。 */ private static final String VERSION = "version-fixture";

    /** 合成文件保持真实 SHA-256 对象身份；业务状态单独通过只读 mock 提供。 */
    @BeforeEach void setup() throws Exception {
        var storage = new PrototypeStorageProperties(); storage.setArtifactRoot(directory);
        store = new PrototypeArtifactStore(storage, json); business = mock(PrototypeService.class);
        exports = new PrototypeExportService(business, store, json);
        objectKey = fixture(Map.of());
        var scope = new PrototypeContracts.Scope("SELECTED", List.of(new PrototypeContracts.PageRef("catalog", "items")));
        var prd = new LifecycleContracts.Prd("冻结背景 <script>纯文本</script>", "目标", "范围", "流程", "规则", "例外", "验收", "非功能", "排除", List.of("req-1"), List.of());
        var now = OffsetDateTime.parse("2026-10-29T10:00:00+08:00");
        var source = new PrototypeContracts.SourceView("source-fixture", "BASELINE", 4, "baseline-fixture", "d".repeat(64), scope, prd, now);
        var confirmation = new PrototypeContracts.ConfirmationView("confirmation-fixture", "prototype-fixture", REVISION, "baseline-fixture", scope, now);
        ObjectNode manifest = json.createObjectNode(); manifest.putObject("plan").put("label", "仅合成测试"); manifest.putObject("privateUnknown").put("path", directory.toString());
        revision = new PrototypeContracts.RevisionView(REVISION, 7, "parent-fixture", "run-fixture", "synthetic", "test-fixture", "合成测试", manifest, store.verify(objectKey).validation(), source, "STALE", false, now, "restored-fixture", confirmation);
        wire();
    }

    /** 业务读取精确限定版本与修订；只读资格模拟归档仍可读，不使用当前指针。 */
    private void wire() {
        when(business.revision(VERSION, REVISION)).thenReturn(revision);
        doAnswer(call -> store.verify(objectKey)).when(business).verifiedArtifact(VERSION, REVISION);
        when(business.previewReadable(VERSION, REVISION)).thenReturn(true);
    }

    /** 源码包包含完整生成文件、固定平台、锁文件和归属，来源正文与历史确认保持原值。 */
    @Test void sourcePreservesExactFilesScopeAndHistoricalConfirmation() throws Exception {
        Map<String, byte[]> zip = export("source");
        for (var entry : original.entrySet()) if (entry.getKey().startsWith("source/")) assertThat(zip.get(entry.getKey())).containsExactly(entry.getValue());
        assertThat(zip).containsKeys("README.md", "ATTRIBUTION.md", "rebuild.mjs", "serve.mjs", "frozen-prd.json", "export-manifest.json", "provenance/zero-source.json", "provenance/ZERO_BASELINE", "provenance/LICENSE");
        assertThat(zip.keySet()).noneMatch(path -> path.startsWith("dist/") || path.startsWith("screenshots/"));
        var manifest = json.readTree(zip.get("export-manifest.json"));
        assertThat(manifest.path("revisionId").asText()).isEqualTo(REVISION);
        assertThat(manifest.path("mode").asText()).isEqualTo("synthetic");
        assertThat(manifest.path("freshness").asText()).isEqualTo("STALE");
        assertThat(manifest.path("restoredFromRevisionId").asText()).isEqualTo("restored-fixture");
        assertThat(manifest.path("historicalConfirmation").path("revisionId").asText()).isEqualTo(REVISION);
        assertThat(manifest.path("source").path("scope").path("pageRefs").get(0).path("moduleId").asText()).isEqualTo("catalog");
        assertThat(manifest.path("prototype").has("privateUnknown")).isFalse();
        assertThat(new String(zip.get("export-manifest.json"), StandardCharsets.UTF_8)).doesNotContain(directory.toString());
        assertThat(json.readTree(zip.get("frozen-prd.json")).path("background").asText()).isEqualTo(revision.source().prd().background());
        assertThat(new String(zip.get("README.md"), StandardCharsets.UTF_8)).contains("未调用模型", "node rebuild.mjs", "npm run build", "不表示生产交付完成").doesNotContain("npm test");
        verify(business).revision(VERSION, REVISION); verify(business).verifiedArtifact(VERSION, REVISION); verify(business).previewReadable(VERSION, REVISION); verifyNoMoreInteractions(business);
        assertHashes(zip);
    }

    /** 静态 ZIP 保留封存 dist 的精确字节，并携带独立归属说明和可运行预览入口。 */
    @Test void staticPreservesDistAndIndependentAttributionWithoutSourceOrScreenshots() throws Exception {
        Map<String, byte[]> zip = export("static");
        for (var entry : original.entrySet()) if (entry.getKey().startsWith("dist/")) assertThat(zip.get(entry.getKey())).containsExactly(entry.getValue());
        assertThat(zip.keySet()).noneMatch(path -> path.startsWith("source/") || path.startsWith("screenshots/") || path.contains("node_modules") || path.startsWith("/"));
        assertThat(zip).containsKeys("provenance/zero-source.json", "provenance/ZERO_BASELINE", "provenance/ZERO_OVERRIDES.yml", "provenance/LICENSE", "serve.mjs").doesNotContainKey("rebuild.mjs");
        assertThat(new String(zip.get("ATTRIBUTION.md"), StandardCharsets.UTF_8)).contains("provenance/", "不会额外授予上游软件许可");
        assertThat(new String(zip.get("README.md"), StandardCharsets.UTF_8)).contains("node serve.mjs dist", "不要直接以 file://");
        assertHashes(zip);
    }

    /** 文件变更在输出头之前失败，且失败不会占用后续下载槽。 */
    @Test void rejectsTamperedFilesAndReleasesFailedPreparationSlot() throws Exception {
        Files.writeString(object().resolve("dist/index.html"), "changed");
        assertThatThrownBy(() -> exports.open(VERSION, REVISION, "static")).isInstanceOf(ProjectException.class);
        Files.write(object().resolve("dist/index.html"), original.get("dist/index.html"));
        assertThat(export("static")).containsKey("dist/index.html");
    }

    /** 已校验对象到读取之间的替换也重新核对字节，而非只信任之前的 verify。 */
    @Test void rejectsReplacementAfterVerifiedArtifactReturns() throws Exception {
        var verified = store.verify(objectKey);
        when(business.verifiedArtifact(VERSION, REVISION)).thenReturn(verified);
        Files.writeString(object().resolve("source/src/generated/Items.vue"), "replacement");
        assertThatThrownBy(() -> exports.open(VERSION, REVISION, "source")).isInstanceOf(ProjectException.class);
    }

    /** 即使封存摘要正确，也不得将私有包管理配置或密钥扩展名加入交付。 */
    @Test void rejectsPrivateConfigAndKeysEvenWithValidManifest() throws Exception {
        for (String name : List.of(".npmrc", "private.pem", "config/credentials", "NODE_MODULES/index.js")) {
            objectKey = fixture(Map.of("source/" + name, "private fixture".getBytes(StandardCharsets.UTF_8))); wire();
            assertThatThrownBy(() -> exports.open(VERSION, REVISION, "source")).isInstanceOf(ProjectException.class);
            assertThatThrownBy(() -> exports.open(VERSION, REVISION, "static")).isInstanceOf(ProjectException.class);
        }
    }

    /** 替换为链接不能把清单摘要对应的宿主文件带出对象根。 */
    @Test void rejectsSymlinkAfterVerification() throws Exception {
        var verified = store.verify(objectKey); when(business.verifiedArtifact(VERSION, REVISION)).thenReturn(verified);
        Path file = object().resolve("dist/index.html"), outside = directory.resolve("outside.html");
        Files.move(file, outside); Files.createSymbolicLink(file, outside);
        assertThatThrownBy(() -> exports.open(VERSION, REVISION, "static")).isInstanceOf(ProjectException.class);
    }

    /** 回收或来源撤销若发生在快照期间，最后一次资格检查仍拒绝下载。 */
    @Test void rechecksReadabilityImmediatelyBeforeArchiveReturn() {
        when(business.previewReadable(VERSION, REVISION)).thenReturn(false);
        assertThatThrownBy(() -> exports.open(VERSION, REVISION, "static")).isInstanceOfSatisfying(ProjectException.class, failure -> assertThat(failure.code()).isEqualTo("PROTOTYPE_NOT_FOUND"));
    }

    /** 未知格式不会执行任何业务查询，避免进入默认静态分支。 */
    @Test void rejectsUnknownOrMissingFormatWithoutBusinessReads() {
        clearInvocations(business);
        for (String format : new String[]{"SOURCE", "../source", "", null}) assertThatThrownBy(() -> exports.open(VERSION, REVISION, format)).isInstanceOfSatisfying(ProjectException.class, failure -> assertThat(failure.code()).isEqualTo("INVALID_INPUT"));
        verifyNoInteractions(business);
    }

    /** 并发内存受到单槽约束，关闭幂等不会放出多余许可。 */
    @Test void holdsBoundedSlotUntilArchiveCloseAndCloseIsIdempotent() throws Exception {
        var first = exports.open(VERSION, REVISION, "static");
        assertThatThrownBy(() -> exports.open(VERSION, REVISION, "static")).isInstanceOfSatisfying(ProjectException.class, failure -> assertThat(failure.code()).isEqualTo("PROTOTYPE_EXPORT_BUSY"));
        first.close(); first.close();
        try (var second = exports.open(VERSION, REVISION, "source")) {
            assertThatThrownBy(() -> exports.open(VERSION, REVISION, "static")).isInstanceOf(ProjectException.class);
            second.writeTo(new ByteArrayOutputStream());
            assertThatThrownBy(() -> second.writeTo(new ByteArrayOutputStream())).isInstanceOf(IOException.class);
        }
    }

    /** 客户端中断经过同一 try-close 边界释放内存槽，不影响下一次下载。 */
    @Test void disconnectedOutputCanBeClosedAndNextDownloadSucceeds() throws Exception {
        try (var archive = exports.open(VERSION, REVISION, "source")) {
            assertThatThrownBy(() -> archive.writeTo(new OutputStream() {
                /** 模拟响应流在首字节写入时断开。 */ @Override public void write(int value) throws IOException { throw new IOException("fixture disconnect"); }
            })).isInstanceOf(IOException.class);
        }
        assertThat(export("static")).containsKey("dist/index.html");
    }

    /** HTTP 成功是标准安全文件名 ZIP，失败仍为可解析的统一 JSON。 */
    @Test void controllerReturnsBinaryZipAndJsonErrorsBeforeHeaders() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new PrototypeExportController(exports)).setControllerAdvice(new ApiExceptionHandler()).build();
        var response = mvc.perform(get("/api/alpha/workspace/versions/{v}/prototype/revisions/{r}/export", VERSION, REVISION).param("format", "static"))
                .andExpect(status().isOk()).andExpect(content().contentType("application/zip"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"prototype-static-r7-abcdef12.zip\""))
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse();
        assertThat(unzip(response.getContentAsByteArray())).containsKey("dist/index.html");
        mvc.perform(get("/api/alpha/workspace/versions/{v}/prototype/revisions/{r}/export", VERSION, REVISION).param("format", "unknown"))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/json")).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(get("/api/alpha/workspace/versions/{v}/prototype/revisions/{r}/export", VERSION, REVISION))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/json")).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    /** 输出 ZIP 并真正解压，不用字段断言冒充交付包可读性检查。 */
    private Map<String, byte[]> export(String format) throws Exception {
        try (var archive = exports.open(VERSION, REVISION, format); var output = new ByteArrayOutputStream()) { archive.writeTo(output); return unzip(output.toByteArray()); }
    }
    /** 检查每个 ZIP 条目为唯一普通相对文件，不允许目录穿越或重复覆盖。 */
    private Map<String, byte[]> unzip(byte[] bytes) throws Exception {
        Map<String, byte[]> result = new TreeMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                assertThat(entry.isDirectory()).isFalse(); assertThat(entry.getName()).doesNotStartWith("/").doesNotContain("..", "\\", ":");
                assertThat(result.put(entry.getName(), zip.readAllBytes())).isNull();
            }
        }
        return result;
    }
    /** 包内逐文件摘要覆盖所有交付文件，清单自身不采用循环自摘要。 */
    private void assertHashes(Map<String, byte[]> zip) throws Exception {
        var manifest = json.readTree(zip.get("export-manifest.json"));
        assertThat(manifest.path("files").size()).isEqualTo(zip.size() - 1);
        for (var item : manifest.path("files")) {
            byte[] bytes = zip.get(item.path("path").asText()); assertThat(bytes).isNotNull();
            assertThat(bytes.length).isEqualTo(item.path("bytes").asInt()); assertThat(hash(bytes)).isEqualTo(item.path("sha256").asText());
        }
    }
    /** 合成源码保持构建入口、平台路由、锁文件和实际许可证，仅用于导出协议测试。 */
    private String fixture(Map<String, byte[]> extra) throws Exception {
        Map<String, byte[]> files = new TreeMap<>();
        Map.ofEntries(Map.entry("dist/index.html", "<html><body>合成测试</body></html>"), Map.entry("dist/assets/main.js", "document.body.dataset.fixture='true';"),
                Map.entry("source/package.json", "{\"name\":\"export-fixture\"}"), Map.entry("source/package-lock.json", "{\"lockfileVersion\":3}"), Map.entry("source/vite.config.js", "export default {};"),
                Map.entry("source/src/platform/routes.js", "export default [];"), Map.entry("source/src/generated/Items.vue", "<template><p>合成列表</p></template>"), Map.entry("source/zero-source.json", "{\"source\":\"synthetic attribution fixture\"}"),
                Map.entry("source/ZERO_BASELINE", "source_commit: synthetic-fixture\n"), Map.entry("source/ZERO_OVERRIDES.yml", "overrides: []\n"), Map.entry("source/LICENSE", "Synthetic test license text; no upstream authorization claim."))
                .forEach((name, text) -> files.put(name, text.getBytes(StandardCharsets.UTF_8)));
        files.put("screenshots/example.png", new byte[]{1,2,3}); files.putAll(extra); original = files;
        ObjectNode manifest = json.createObjectNode().put("protocolVersion", 1).put("mode", "synthetic").put("generation", "test-fixture")
                .put("imageId", "sha256:" + "a".repeat(64)).put("templateDigest", "b".repeat(64)).put("sourceDigest", "c".repeat(64));
        manifest.putObject("validation").put("status", "PASSED").putArray("cases").addObject().put("name", "合成协议检查，未运行浏览器").put("status", "PASSED");
        ArrayNode dist = manifest.putArray("files"), source = manifest.putArray("sources"), screenshots = manifest.putArray("screenshots");
        for (var file : files.entrySet()) {
            String name = file.getKey(), folder = name.substring(0, name.indexOf('/')), relative = name.substring(name.indexOf('/') + 1);
            (folder.equals("dist") ? dist : folder.equals("source") ? source : screenshots).addObject().put("path", relative).put("bytes", file.getValue().length).put("sha256", hash(file.getValue()));
        }
        byte[] encoded = json.writeValueAsBytes(manifest); String key = hash(encoded); Path object = directory.resolve("artifacts").resolve(key); Files.createDirectories(object);
        Files.write(object.resolve("artifact.json"), encoded);
        for (var file : files.entrySet()) { Path target = object.resolve(file.getKey()); Files.createDirectories(target.getParent()); Files.write(target, file.getValue()); }
        return key;
    }
    /** 当前测试封存对象目录。 */ private Path object() { return directory.resolve("artifacts").resolve(objectKey); }
    /** 与正式对象身份相同的摘要算法。 */ private String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
