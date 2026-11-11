package com._36knight.alpha.prototype;

import com._36knight.alpha.project.ProjectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 精确修订的只读交付包；先完整核验有界快照，再流式压包，不调用模型或创建确认。@author Andersen */
@Service
public class PrototypeExportService {
    /** ZIP 的实际压缩输出上限，和前端下载限制保持一致。 */
    public static final long MAX_ARCHIVE_BYTES = 80L * 1024 * 1024;
    /** 一次导出最多持有 50 MiB 静态及 15 MiB 归属快照，避免慢下载并发堆积。 */
    private final Semaphore slot = new Semaphore(1);
    /** 业务层负责归属、回收资格与不可变修订读取。 */
    private final PrototypeService prototypes;
    /** 对象内容身份与逐文件摘要是导出的可信依据。 */
    private final PrototypeArtifactStore artifacts;
    /** 使用应用相同的日期和记录 JSON 契约。 */
    private final ObjectMapper json;

    /** 注入读取边界，不在导出过程中改变业务表或文件对象。 */
    public PrototypeExportService(PrototypeService prototypes, PrototypeArtifactStore artifacts, ObjectMapper json) {
        this.prototypes = prototypes; this.artifacts = artifacts; this.json = json;
    }

    /**
     * 打开下载前完成归属、文件和元数据检查，错误仍可由 Controller 以标准 JSON 返回。
     * 调用者必须关闭 Archive；关闭释放单槽并抛弃内存快照，成功下载不记录人工确认。
     */
    public Archive open(String versionId, String revisionId, String format) {
        if (!Set.of("source", "static").contains(format == null ? "" : format)) throw invalid();
        if (!slot.tryAcquire()) throw new ProjectException(HttpStatus.TOO_MANY_REQUESTS, "PROTOTYPE_EXPORT_BUSY", "另一个原型包正在下载，请稍后重试");
        try {
            var revision = prototypes.revision(versionId, revisionId);
            var verified = prototypes.verifiedArtifact(versionId, revisionId);
            if (!revision.mode().equals(verified.artifact().path("mode").asText())) throw unavailable();
            Map<String, byte[]> files = new TreeMap<>(artifacts.exportFiles(verified, format));
            files.putAll(artifacts.exportProvenance(verified));
            if (!files.containsKey("provenance/zero-source.json")) throw unavailable();
            if (format.equals("source") && (!files.containsKey("source/package.json") || !files.containsKey("source/package-lock.json")
                    || !files.containsKey("source/vite.config.js") || !files.containsKey("source/src/platform/routes.js")
                    || !files.containsKey("source/zero-source.json"))) throw unavailable();
            files.put("README.md", readme(format, revision, verified).getBytes(StandardCharsets.UTF_8));
            files.put("ATTRIBUTION.md", attribution().getBytes(StandardCharsets.UTF_8));
            files.put("serve.mjs", resource("serve.mjs"));
            if (format.equals("source")) files.put("rebuild.mjs", resource("rebuild.mjs"));
            files.put("frozen-prd.json", encode(revision.source().prd()));
            ObjectNode manifest = json.createObjectNode().put("schemaVersion", 1).put("format", format)
                    .put("versionId", versionId)
                    .put("revisionId", revision.id()).put("revisionNumber", revision.revisionNumber())
                    .put("mode", revision.mode()).put("model", revision.model()).put("freshness", revision.freshness())
                    .put("generation", verified.artifact().path("generation").asText())
                    .put("objectKey", verified.objectKey()).put("toolchainId", verified.toolchainId())
                    .put("templateDigest", verified.artifact().path("templateDigest").asText())
                    .put("sourceDigest", verified.artifact().path("sourceDigest").asText())
                    .put("notice", "导出的是模拟业务原型；生成、恢复及下载不代表人工确认或生产交付完成。")
                    .put("confirmationNotice", "historicalConfirmation 仅是本修订的历史确认记录，不表示当前 PRD 或交付状态仍有效。");
            manifest.put("parentRevisionId", revision.parentRevisionId());
            manifest.put("restoredFromRevisionId", revision.restoredFromRevisionId());
            manifest.set("historicalConfirmation", json.valueToTree(revision.latestConfirmation()));
            var source = manifest.putObject("source");
            source.put("id", revision.source().id()).put("kind", revision.source().kind()).put("prdRevision", revision.source().prdRevision())
                    .put("prdBaselineId", revision.source().prdBaselineId()).put("contentSha256", revision.source().contentSha256());
            source.set("scope", json.valueToTree(revision.source().scope()));
            manifest.set("validation", verified.validation());
            ObjectNode business = manifest.putObject("prototype");
            for (String key : Set.of("plan", "coverage", "cases", "notice")) if (revision.manifest().has(key)) business.set(key, revision.manifest().get(key));
            var entries = manifest.putArray("files");
            files.forEach((path, bytes) -> entries.addObject().put("path", path).put("bytes", bytes.length).put("sha256", hash(bytes)));
            files.put("export-manifest.json", encode(manifest));
            if (!prototypes.previewReadable(versionId, revisionId)) throw new ProjectException(HttpStatus.NOT_FOUND, "PROTOTYPE_NOT_FOUND", "原型或其来源文档已不可访问");
            String filename = "prototype-" + format + "-r" + revision.revisionNumber() + "-" + revision.id().replaceAll("[^A-Za-z0-9]", "").substring(0, Math.min(8, revision.id().replaceAll("[^A-Za-z0-9]", "").length())) + ".zip";
            return new Archive(filename, files, slot);
        } catch (RuntimeException failure) { slot.release(); throw failure; }
    }

    /** 交付说明不借用模板中的人员样板说明，命令均针对当前导出目录。 */
    private String readme(String format, PrototypeContracts.RevisionView revision, PrototypeArtifactStore.VerifiedArtifact verified) {
        String mode = switch (revision.mode()) { case "live" -> "真实模型生成"; case "synthetic" -> "合成联调结果，未调用模型"; default -> "模板联调结果，未调用模型"; };
        String commands = format.equals("source") ? """
                ## 使用固定工具链重建

                安装 Node.js 22+ 与 Docker，确保本机已经保留清单中 toolchainId 对应的镜像，然后在本目录执行：

                ```sh
                node rebuild.mjs
                tar -xzf rebuilt-static.tar.gz
                node serve.mjs dist
                ```

                rebuild.mjs 仅使用指定镜像，网络关闭、非特权、输入只读；镜像内已有锁定依赖，不安装额外依赖。
                镜像并不打包进 ZIP；换机需由工具链维护者提供相同镜像。不会自动下载替代镜像或降级到宿主执行。
                开发者在自己的隔离开发环境中也可进入 source/，使用锁文件执行 npm ci --ignore-scripts 和 npm run build。
                后者不等于复现固定工具链或重新通过 Alpha 浏览器验证。source/test/ 是模板校准材料，不能作为本次业务验收依据。
                source/README.md 是冻结模板的原始说明；本文件才是本次独立交付包的入口。
                """ : """
                ## 打开已验证静态包

                安装 Node.js 22+，在本目录执行：

                ```sh
                node serve.mjs dist
                ```

                打开终端打印的本机地址。不要直接以 file:// 打开 index.html。
                dist/ 每个文件都与指定修订封存的静态产物一致；export-manifest.json 记录逐文件摘要。
                """;
        if (format.equals("source") && verified.toolchainId().startsWith("host-sha256:")) {
            commands = """
                    ## 在本机重建

                    安装 Node.js 22+、npm 和 tar，在解压目录执行以下命令。首次安装依赖需要 npm 缓存或网络。

                    ```sh
                    npm --prefix source ci --ignore-scripts
                    node rebuild.mjs
                    tar -xzf rebuilt-static.tar.gz
                    node serve.mjs dist
                    ```

                    rebuild.mjs 复核源码摘要，在临时目录使用锁定的 Vite 编译。清单记录原修订通过浏览器验证时的工具链指纹。
                    重建只生成静态文件。原修订的浏览器检查见 export-manifest.json 中的 validation。
                    """;
        }
        return "# Alpha 原型交付包\n\n修订 r" + revision.revisionNumber() + "（" + revision.id() + "）\n\n生成方式：" + mode
                + "\n\n来源：" + revision.source().kind() + " PRD r" + revision.source().prdRevision() + "；范围以 export-manifest.json 的 source.scope 为准。"
                + "\n\n工具链：`" + verified.toolchainId() + "`\n\n" + commands
                + "\n## 使用边界\n\n所有业务动作使用模拟数据，不连接真实业务后端。预览数据仅驻留内存，刷新恢复种子。serve.mjs 使用独立本机来源和受限 iframe。"
                + "\n\n历史确认记录只说明当时确认过该修订和范围，不保证当前 PRD 仍有效；下载不创建确认、恢复不继承确认，也不表示生产交付完成。"
                + "\n\n完整冻结 PRD 位于 frozen-prd.json；源码包保留原始模板、生成文件、依赖锁和 Zero 来源声明。业务 JSON 按纯文本查看，不执行其中的外部文字。\n";
    }

    /** 尊重现有归属和许可证，不为没有发布许可证的上游虚构授权。 */
    private String attribution() {
        return "# 来源与第三方说明\n\n管理端采用 Zero 的固定版本。两种交付包均在 provenance/ 保留该修订封存的 zero-source.json、ZERO_BASELINE、ZERO_OVERRIDES.yml 以及实际存在的 LICENSE/NOTICE；上游版本、提交、文件归属和摘要以这些文件为准。"
                + "\n\n导出保留封存源码中的既有许可证、NOTICE 和作者注释（如存在），不会额外授予上游软件许可。未附独立许可证的部分请按权利人的实际授权使用。"
                + "\n\n第三方依赖版本固定在 source/package-lock.json；安装依赖后应一并保留各包随附的 LICENSE/NOTICE。静态包不包含 node_modules 或私有配置。\n";
    }

    /** 只加载随应用发布的平台脚本，不读取用户路径或模板指定脚本。 */
    private byte[] resource(String name) {
        try (var stream = PrototypeExportService.class.getResourceAsStream("/prototype-export/" + name)) {
            if (stream == null) throw unavailable();
            byte[] bytes = stream.readNBytes(64 * 1024 + 1);
            if (bytes.length > 64 * 1024) throw unavailable();
            return bytes;
        } catch (IOException failure) { throw unavailable(); }
    }

    /** 单份导出元数据仍有明确上限，不截断冻结正文。 */
    private byte[] encode(Object value) {
        try { byte[] bytes = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(value); if (bytes.length > 4 * 1024 * 1024) throw unavailable(); return bytes; }
        catch (IOException failure) { throw unavailable(); }
    }

    /** ZIP 清单仅记录自身相对文件名和内容摘要。 */
    private String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("运行环境缺少 SHA-256"); }
    }

    /** 格式错误不触发业务读取和磁盘扫描。 */
    private ProjectException invalid() { return new ProjectException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "导出格式仅支持 source 或 static"); }
    /** 不回显文件路径、底层 I/O 或模板源码。 */
    private ProjectException unavailable() { return new ProjectException(HttpStatus.CONFLICT, "PROTOTYPE_EXPORT_UNAVAILABLE", "该修订暂时无法导出，请检查产物完整性后重试"); }

    /** 有界快照和下载槽的生命周期；Controller 必须在响应完成或断开时关闭。 */
    public static final class Archive implements AutoCloseable {
        /** ASCII 下载文件名，不使用任意业务标题。 */ private final String filename;
        /** 本次下载独占的文件数组，关闭后释放引用。 */ private Map<String, byte[]> files;
        /** 持有到下载结束，防止慢客户端堆积大型快照。 */ private final Semaphore slot;
        /** close 幂等，异常和成功分支不会重复释放。 */ private final AtomicBoolean closed = new AtomicBoolean();
        /** 同一 Archive 不能被多次并发写到不同响应。 */ private final AtomicBoolean written = new AtomicBoolean();
        /** 只有应用服务可构造已经过校验的交付内容。 */
        private Archive(String filename, Map<String, byte[]> files, Semaphore slot) { this.filename = filename; this.files = files; this.slot = slot; }
        /** 用于标准 Content-Disposition attachment。 */ public String filename() { return filename; }
        /** 逐项写 ZIP，相对路径来自已核验清单；不复制整个压缩包到内存。 */
        public void writeTo(OutputStream output) throws IOException {
            if (closed.get() || !written.compareAndSet(false, true)) throw new IOException("导出会话已结束");
            try (var zip = new ZipOutputStream(new LimitedOutputStream(output))) {
                for (var file : files.entrySet()) {
                    ZipEntry entry = new ZipEntry(file.getKey()); entry.setTime(315532800000L);
                    zip.putNextEntry(entry); zip.write(file.getValue()); zip.closeEntry();
                }
            }
        }
        /** 释放快照与单槽，不改变任何业务文件或确认记录。 */
        @Override public void close() { if (closed.compareAndSet(false, true)) { files = Map.of(); slot.release(); } }
    }

    /** 压缩流只允许写入有界字节，异常和客户端断开均由 Controller 的资源块清理。 */
    private static final class LimitedOutputStream extends FilterOutputStream {
        /** 已实际发送的压缩字节数。 */ private long bytes;
        /** 包装响应流，不自行创建临时文件。 */ private LimitedOutputStream(OutputStream output) { super(output); }
        /** 单字节写入与数组写入共用同一限制。 */
        @Override public void write(int value) throws IOException { require(1); out.write(value); }
        /** 不调用 FilterOutputStream 的逐字节默认实现，避免重复计数。 */
        @Override public void write(byte[] value, int offset, int length) throws IOException { require(length); out.write(value, offset, length); }
        /** 上限严格按输出计数，不把 ZIP 元数据开销遗漏。 */
        private void require(int count) throws IOException { if (count > MAX_ARCHIVE_BYTES - bytes) throw new IOException("原型导出超过大小限制"); bytes += count; }
    }
}
