package com._36knight.alpha.prototype;

import com._36knight.alpha.project.ProjectException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 只读取 P2 已封存对象，重新验证文件后才允许业务事务保存引用；不构建、不移动当前指针。
 * @author Andersen
 */
@Service
public class PrototypeArtifactStore {
    /** 产物 manifest 有界读取，不能让异常文件耗尽堆内存。 */
    private static final int MANIFEST_BYTES = 1024 * 1024;
    /** 与 P2 预览保持一致的静态文件扩展名，不接受 source map 或任意可执行文件。 */
    private static final Set<String> STATIC_TYPES = Set.of("html", "js", "css", "json", "svg", "png", "jpg", "jpeg", "webp", "gif", "ico", "woff", "woff2", "ttf");
    /** 不允许混入源码交付的本机配置与开发缓存目录。 */
    private static final Set<String> PRIVATE_PARTS = Set.of(".git", "node_modules", ".aws", ".codex");
    /** 样板、合成测试和真实模型的来源标签必须成对固定，不能将样板重新标记成模型结果。 */
    private static final Map<String, String> GENERATION_BY_MODE = Map.of("demo", "trusted-fixture", "synthetic", "test-fixture", "live", "model");
    /** 不可变对象只从此配置根目录读取，任何接口都不能覆盖它。 */
    private final Path root;
    /** 使用同一 JSON 配置构造安全业务引用。 */
    private final ObjectMapper json;
    /** 拒绝 manifest 的重复键和尾随内容，避免解析器产生不同解释。 */
    private final ObjectReader reader;

    /** 固定配置目录，不创建目录或访问 P2 的 current.json。 */
    public PrototypeArtifactStore(PrototypeStorageProperties properties, ObjectMapper json) {
        this.root = properties.getArtifactRoot().toAbsolutePath().normalize();
        this.json = json;
        this.reader = json.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /**
     * 复核一个不可变对象：身份必须是 manifest 的 SHA-256，三棵文件树必须与清单完全一致。
     * 模板与源树汇总摘要仅检查格式并随 manifest 固定记录，不在此重新计算 Worker 的树摘要。
     * 文件完整性依靠 manifest 内容身份及逐文件 SHA-256 复核，不据汇总标签推断 PRD 语义或模型来源。
     * @param objectKey P2 分配的 64 位小写十六进制内容身份，禁止路径和 URL
     * @return 相对对象引用、逐文件已验证摘要与受 manifest 保护的构建元数据，不含文件正文或宿主路径
     */
    public VerifiedArtifact verify(String objectKey) {
        try {
            requireHash(objectKey);
            requireDirectory(root);
            Path objects = root.resolve("artifacts");
            requireDirectory(objects);
            Path directory = objects.resolve(objectKey);
            requireDirectory(directory);
            byte[] content = readBounded(directory.resolve("artifact.json"), MANIFEST_BYTES);
            if (!hash(content).equals(objectKey)) throw unavailable();
            JsonNode manifest = reader.readTree(content);
            if (manifest == null || !manifest.isObject() || !manifest.path("protocolVersion").isIntegralNumber()
                    || !manifest.path("protocolVersion").canConvertToInt() || manifest.path("protocolVersion").intValue() != 1) throw unavailable();
            String mode = text(manifest, "mode"), generation = text(manifest, "generation");
            if (!generation.equals(GENERATION_BY_MODE.get(mode))) throw unavailable();
            // 新修订记录宿主工具链指纹；历史封存对象沿用其不可变镜像身份。
            boolean host = manifest.has("toolchainId");
            String toolchainId = text(manifest, host ? "toolchainId" : "imageId");
            if (!toolchainId.matches((host ? "host-sha256:" : "sha256:") + "[a-f0-9]{64}")) throw unavailable();
            requireHash(text(manifest, "templateDigest"));
            requireHash(text(manifest, "sourceDigest"));
            JsonNode validation = validation(manifest.path("validation"));
            ArrayNode files = verifyTree(directory, objectKey, "dist", manifest.path("files"), 1000, 50L * 1024 * 1024, true, false);
            ArrayNode sources = verifyTree(directory, objectKey, "source", manifest.path("sources"), 1500, 15L * 1024 * 1024, false, false);
            ArrayNode screenshots = verifyTree(directory, objectKey, "screenshots", manifest.path("screenshots"), 20, 20L * 1024 * 1024, true, true);
            if (files.isEmpty() || sources.isEmpty() || !contains(files, "index.html")) throw unavailable();
            ObjectNode artifact = json.createObjectNode();
            artifact.put("objectKey", objectKey);
            artifact.put("mode", mode).put("generation", generation);
            artifact.putObject("manifest").put("objectKey", "artifacts/" + objectKey + "/artifact.json").put("sha256", objectKey).put("bytes", content.length);
            // 汇总摘要由受信 Worker 记录；逐项文件校验不等于重新计算其树指纹算法。
            artifact.put("templateDigest", manifest.path("templateDigest").asText());
            artifact.put("sourceDigest", manifest.path("sourceDigest").asText());
            artifact.set("files", files); artifact.set("sources", sources); artifact.set("screenshots", screenshots);
            return new VerifiedArtifact(objectKey, toolchainId, validation, artifact);
        } catch (IOException | IllegalArgumentException | SecurityException exception) {
            // 文件系统异常可能包含宿主目录；只提供稳定业务错误，不向外附带原异常正文。
            throw unavailable();
        }
    }

    /**
     * 编辑只读取已经封存并重新核验的生成区文本；平台路由由 Worker 重建，不交给模型修改。
     * 返回文件内容仅用于内部模型上下文，不能加入任务轮询响应。
     */
    public Map<String, String> generatedFiles(String objectKey) {
        VerifiedArtifact verified = verify(objectKey);
        Map<String, String> result = new TreeMap<>();
        long total = 0;
        try {
            for (JsonNode entry : verified.artifact().path("sources")) {
                String name = entry.path("path").asText();
                if (!(name.startsWith("src/features/generated/") || name.startsWith("src/mock/generated/"))
                        || name.equals("src/features/generated/routes.js")) continue;
                if (result.size() >= 100 || !(name.endsWith(".vue") || name.endsWith(".js") || name.endsWith(".json"))) throw unavailable();
                Path sourceRoot = root.resolve("artifacts").resolve(objectKey).resolve("source");
                Path file = sourceRoot.resolve(name);
                // 在校验后再次检查祖先目录，拒绝读取期间替换为符号链接的路径。
                for (Path parent = file.getParent(); parent != null && parent.startsWith(sourceRoot); parent = parent.getParent()) requireDirectory(parent);
                byte[] bytes = readBounded(file, 256 * 1024);
                total += bytes.length;
                if (total > 5L * 1024 * 1024 || !hash(bytes).equals(entry.path("sha256").asText())) throw unavailable();
                String content = new String(bytes, StandardCharsets.UTF_8);
                if (!java.util.Arrays.equals(bytes, content.getBytes(StandardCharsets.UTF_8))) throw unavailable();
                result.put(name, content);
            }
            if (result.isEmpty()) throw unavailable();
            return Map.copyOf(result);
        } catch (IOException | SecurityException exception) { throw unavailable(); }
    }

    /**
     * 将已核验修订的静态文件读成有界快照；逐项复核摘要，关闭 verify 与实际读取之间的替换窗口。
     * 快照只在服务器内存使用，包含完整 dist，不包含源码、截图或宿主路径。
     */
    public PrototypePreviewSnapshot staticSnapshot(VerifiedArtifact verified) {
        requireHash(verified.objectKey());
        Map<String, byte[]> files = new TreeMap<>();
        long total = 0;
        try {
            requireDirectory(root);
            requireDirectory(root.resolve("artifacts"));
            Path object = root.resolve("artifacts").resolve(verified.objectKey());
            requireDirectory(object);
            Path dist = object.resolve("dist");
            requireDirectory(dist);
            for (JsonNode entry : verified.artifact().path("files")) {
                String name = text(entry, "path");
                relativePath(name, true, false);
                if (files.size() >= 1000 || files.containsKey(name)) throw unavailable();
                long expected = entry.path("bytes").asLong(-1);
                if (expected < 0 || expected > 50L * 1024 * 1024 || total + expected > 50L * 1024 * 1024) throw unavailable();
                Path file = dist.resolve(name);
                for (Path parent = file.getParent(); parent != null && parent.startsWith(dist); parent = parent.getParent()) requireDirectory(parent);
                byte[] content = readBounded(file, (int) expected);
                if (content.length != expected || !hash(content).equals(text(entry, "sha256"))) throw unavailable();
                total += content.length;
                files.put(name, content);
            }
            if (!files.containsKey("index.html")) throw unavailable();
            return new PrototypePreviewSnapshot(files);
        } catch (IOException | SecurityException exception) { throw unavailable(); }
    }

    /**
     * 导出精确封存树的独立副本；摘要校验后再次限长读取，避免校验与下载之间被替换。
     * 返回值只供服务端压包，数组不与任何缓存或其他请求共享；不接受调用方目录或文件名。
     */
    public Map<String, byte[]> exportFiles(VerifiedArtifact verified, String format) {
        if (!Set.of("source", "static").contains(format)) throw unavailable();
        return exportTree(verified, format.equals("source"), false);
    }

    /** 静态交付也保留同一封存源码的 Zero 归属和现有许可证，不扫描其他对象或宿主仓库。 */
    public Map<String, byte[]> exportProvenance(VerifiedArtifact verified) {
        return exportTree(verified, true, true);
    }

    /** 只读取清单中受控选择的树；归属材料复用源文件摘要与同样的反链接检查。 */
    private Map<String, byte[]> exportTree(VerifiedArtifact verified, boolean source, boolean provenance) {
        requireHash(verified.objectKey());
        String folder = source ? "source" : "dist";
        String prefix = provenance ? "provenance" : folder;
        long limit = (source ? 15L : 50L) * 1024 * 1024;
        Map<String, byte[]> files = new TreeMap<>();
        long total = 0;
        try {
            requireDirectory(root); requireDirectory(root.resolve("artifacts"));
            Path object = root.resolve("artifacts").resolve(verified.objectKey()); requireDirectory(object);
            Path tree = object.resolve(folder); requireDirectory(tree);
            for (JsonNode entry : verified.artifact().path(source ? "sources" : "files")) {
                String name = text(entry, "path"); relativePath(name, !source, false); exportPath(name);
                if (provenance && !provenancePath(name)) continue;
                if (files.size() >= (source ? 1500 : 1000) || files.containsKey(prefix + "/" + name)) throw unavailable();
                long expected = entry.path("bytes").asLong(-1);
                if (expected < 0 || expected > limit || total + expected > limit) throw unavailable();
                Path file = tree.resolve(name);
                for (Path parent = file.getParent(); parent != null && parent.startsWith(tree); parent = parent.getParent()) requireDirectory(parent);
                byte[] bytes = readBounded(file, (int) expected);
                if (bytes.length != expected || !hash(bytes).equals(text(entry, "sha256"))) throw unavailable();
                total += bytes.length; files.put(prefix + "/" + name, bytes);
            }
            if (files.isEmpty()) throw unavailable();
            return Map.copyOf(files);
        } catch (IOException | SecurityException exception) { throw unavailable(); }
    }

    /** 只选择固定 Zero 追溯文件与真实存在的 LICENSE/NOTICE，不虚构授权。 */
    private boolean provenancePath(String name) {
        if (Set.of("zero-source.json", "ZERO_BASELINE", "ZERO_OVERRIDES.yml").contains(name)) return true;
        String leaf = name.substring(name.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
        return leaf.matches("(license|licence|notice)(\\.[a-z0-9_-]+)?");
    }

    /** 可移植 ZIP 不夹带隐藏配置、私钥、凭证文件或开发依赖，大小写变体同样拒绝。 */
    private void exportPath(String name) {
        for (String part : name.split("/")) {
            String lower = part.toLowerCase(java.util.Locale.ROOT);
            if (part.startsWith(".") || PRIVATE_PARTS.contains(lower) || Set.of("credentials", "id_rsa", "id_ed25519", "desktop.ini", "thumbs.db").contains(lower)
                    || lower.matches(".*\\.(pem|key|p12|pfx|jks|keystore|map)$") || part.endsWith(".") || part.endsWith(" ")) throw unavailable();
        }
    }

    /** 逐项检查相对文件引用，并拒绝清单外内容、链接、特殊文件及超过总量的文件树。 */
    private ArrayNode verifyTree(Path directory, String objectKey, String folder, JsonNode entries,
            int maxFiles, long maxBytes, boolean staticFiles, boolean screenshots) throws IOException {
        if (!entries.isArray() || entries.size() > maxFiles) throw unavailable();
        Map<String, FileEntry> expected = new HashMap<>();
        long total = 0;
        ArrayNode references = json.createArrayNode();
        for (JsonNode entry : entries) {
            String name = text(entry, "path");
            relativePath(name, staticFiles, false);
            if (screenshots && !name.endsWith(".png")) throw unavailable();
            String digest = text(entry, "sha256"); requireHash(digest);
            JsonNode size = entry.path("bytes");
            if (!size.isIntegralNumber() || !size.canConvertToLong() || size.longValue() < 0 || size.longValue() > maxBytes) throw unavailable();
            total += size.longValue();
            if (total > maxBytes || expected.put(name, new FileEntry(digest, size.longValue())) != null) throw unavailable();
            references.addObject().put("path", name).put("objectKey", "artifacts/" + objectKey + "/" + folder + "/" + name)
                    .put("sha256", digest).put("bytes", size.longValue());
        }
        Path tree = directory.resolve(folder);
        requireDirectory(tree);
        Set<String> visited = new HashSet<>();
        Files.walkFileTree(tree, Set.of(), 33, new SimpleFileVisitor<>() {
            /** 空目录也计入节点上限，避免恶意目录树绕过文件数量限制。 */
            private int nodes;

            /** 只进入普通目录；静态路径继续采用 P2 的深度和字符限制。 */
            @Override public FileVisitResult preVisitDirectory(Path current, BasicFileAttributes attributes) {
                if (++nodes > 10000 || attributes.isSymbolicLink() || !attributes.isDirectory()) throw unavailable();
                if (!current.equals(tree)) relativePath(relativeName(tree, current), staticFiles, true);
                return FileVisitResult.CONTINUE;
            }

            /** 读取前后校验普通文件身份和字节数，数据流本身也受清单字节数约束。 */
            @Override public FileVisitResult visitFile(Path current, BasicFileAttributes attributes) throws IOException {
                if (++nodes > 10000 || !attributes.isRegularFile() || attributes.isSymbolicLink()) throw unavailable();
                String name = relativeName(tree, current);
                relativePath(name, staticFiles, false);
                FileEntry item = expected.get(name);
                if (item == null || !visited.add(name) || attributes.size() != item.bytes()) throw unavailable();
                if (!hashFile(current, item.bytes()).equals(item.sha256())) throw unavailable();
                BasicFileAttributes after = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!after.isRegularFile() || after.size() != attributes.size()
                        || (attributes.fileKey() != null && !attributes.fileKey().equals(after.fileKey()))) throw unavailable();
                return FileVisitResult.CONTINUE;
            }
        });
        if (visited.size() != expected.size()) throw unavailable();
        return references;
    }

    /** 只保留已定义的运行证据字段，未知诊断和文件路径不能透传至业务响应。 */
    private JsonNode validation(JsonNode original) {
        if (!original.isObject() || !"PASSED".equals(text(original, "status"))) throw unavailable();
        JsonNode cases = original.path("cases");
        if (!cases.isArray() || cases.isEmpty() || cases.size() > 1000) throw unavailable();
        ObjectNode result = json.createObjectNode().put("status", "PASSED");
        ArrayNode checked = result.putArray("cases");
        for (JsonNode item : cases) {
            String name = safeLabel(text(item, "name"), 300);
            if (!"PASSED".equals(text(item, "status"))) throw unavailable();
            checked.addObject().put("name", name).put("status", "PASSED");
        }
        for (String key : ListFields.RUNTIME_TEXT) {
            if (original.has(key)) result.put(key, safeLabel(text(original, key), 80));
        }
        if (original.has("notVerified")) {
            JsonNode values = original.path("notVerified");
            if (!values.isArray() || values.size() > 100) throw unavailable();
            ArrayNode notes = result.putArray("notVerified");
            for (JsonNode value : values) { if (!value.isTextual()) throw unavailable(); notes.add(safeLabel(value.asText(), 300)); }
        }
        JsonNode runtime = original.path("toolchain");
        if (runtime.isObject()) {
            ObjectNode recorded = result.putObject("toolchain");
            for (String key : ListFields.TOOLCHAIN_TEXT) if (runtime.has(key)) recorded.put(key, safeLabel(text(runtime, key), 80));
        }
        JsonNode isolation = original.path("isolation");
        if (isolation.isObject()) {
            ObjectNode recorded = result.putObject("isolation");
            for (String key : ListFields.ISOLATION_BOOLEAN) {
                if (isolation.has(key)) { if (!isolation.path(key).isBoolean() || !isolation.path(key).booleanValue()) throw unavailable(); recorded.put(key, true); }
            }
            if (isolation.has("uid")) { if (!isolation.path("uid").isIntegralNumber() || !isolation.path("uid").canConvertToInt() || isolation.path("uid").intValue() < 1) throw unavailable(); recorded.put("uid", isolation.path("uid").intValue()); }
            for (String key : ListFields.ISOLATION_TEXT) if (isolation.has(key)) recorded.put(key, safeLabel(text(isolation, key), 80));
            for (String key : ListFields.ISOLATION_ARRAY) {
                if (!isolation.has(key)) continue;
                JsonNode values = isolation.path(key);
                if (!values.isArray() || values.size() > 100) throw unavailable();
                ArrayNode names = recorded.putArray(key);
                for (JsonNode value : values) { if (!value.isTextual()) throw unavailable(); names.add(safeLabel(value.asText(), 80)); }
            }
        }
        return result;
    }

    /** 字段白名单集中维护，未知扩展不能自动成为公开运行证据。 */
    private static final class ListFields {
        /** 浏览器实现及执行器版本。 */ private static final Set<String> RUNTIME_TEXT = Set.of("browser", "playwright");
        /** 实际原型编译环境。 */ private static final Set<String> TOOLCHAIN_TEXT = Set.of("node", "platform", "architecture", "vite", "execution");
        /** 平台探针声明的只读、权限和凭证隔离结果。 */ private static final Set<String> ISOLATION_BOOLEAN = Set.of("readOnlyRoot", "readOnlyInput", "hostCredentialsAbsent", "noNewPrivileges");
        /** cgroup 与进程权限的实际读数。 */ private static final Set<String> ISOLATION_TEXT = Set.of("memoryMax", "pidsMax", "cpuMax", "effectiveCapabilities");
        /** 有地址及未启用的网络设备名。 */ private static final Set<String> ISOLATION_ARRAY = Set.of("networkInterfaces", "inactiveInterfaces");
        /** 字段表不构造业务对象。 */ private ListFields() { }
    }

    /** 所有路径先验证 POSIX 分量，绝不依赖 normalize 将恶意输入修正成合法路径。 */
    private void relativePath(String value, boolean staticFile, boolean directory) {
        if (value == null || value.isBlank() || value.length() > 1024 || value.startsWith("/")
                || value.indexOf('\\') >= 0 || value.indexOf(':') >= 0 || controlCharacters(value)) throw unavailable();
        String[] parts = value.split("/", -1);
        if (parts.length > (staticFile ? (directory ? 12 : 13) : 32)) throw unavailable();
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || PRIVATE_PARTS.contains(part) || part.equals(".env") || part.startsWith(".env.")) throw unavailable();
            if (staticFile && (part.startsWith(".") || part.matches(".*[\\x00-\\x20\\x7f%?#:].*"))) throw unavailable();
        }
        if (staticFile && !directory) {
            int point = value.lastIndexOf('.');
            if (point < 0 || !STATIC_TYPES.contains(value.substring(point + 1))) throw unavailable();
        }
    }

    /** 只将真实目录分隔符转为 POSIX，文件名中原有反斜杠必须保留并被拒绝。 */
    private String relativeName(Path tree, Path file) {
        return java.util.stream.StreamSupport.stream(tree.relativize(file).spliterator(), false)
                .map(Path::toString).collect(java.util.stream.Collectors.joining("/"));
    }

    /** 限定普通目录，不接受目录符号链接或尚未封存的对象。 */
    private void requireDirectory(Path directory) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) throw unavailable();
    }

    /** manifest 在读取前和读取过程中均限制大小，避免 stat 之后被替换为超大文件。 */
    private byte[] readBounded(Path file, int limit) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.size() > limit) throw unavailable();
        try (InputStream input = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit || bytes.length != attributes.size()) throw unavailable();
            return bytes;
        }
    }

    /** 文件摘要采用流式读取，文件增长时在首个超量块立即拒绝。 */
    private String hashFile(Path file, long expectedBytes) throws IOException {
        MessageDigest digest = digest();
        long size = 0;
        try (InputStream input = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                size += read;
                if (size > expectedBytes) throw unavailable();
                digest.update(buffer, 0, read);
            }
        }
        if (size != expectedBytes) throw unavailable();
        return HexFormat.of().formatHex(digest.digest());
    }

    /** 对 manifest 原始字节计算内容身份，包含其原有空白与换行。 */
    private String hash(byte[] bytes) { return HexFormat.of().formatHex(digest().digest(bytes)); }

    /** Java 标准 SHA-256 不依赖第三方库或本地命令。 */
    private MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("运行环境缺少 SHA-256"); }
    }

    /** 字符串字段不能由数值或对象隐式转换。 */
    private String text(JsonNode node, String field) { JsonNode value = node.path(field); if (!value.isTextual()) throw unavailable(); return value.asText(); }

    /** 仅接受内容寻址身份，非法身份不进入任何文件系统操作。 */
    private void requireHash(String value) { if (value == null || !value.matches("[a-f0-9]{64}")) throw unavailable(); }

    /** 运行证据只保留短纯文本，不允许夹带文件系统路径和控制字符。 */
    private String safeLabel(String value, int limit) {
        if (value.isBlank() || value.length() > limit || controlCharacters(value)
                || value.contains("\\") || value.matches(".*(?:^|\\s)/(?:Users|home|tmp|private|var|etc|opt|work|input)/.*") || value.matches(".*[A-Za-z]:/.*")) throw unavailable();
        return value;
    }

    /** 逐字符检查避免正则中的点号跨多行行为遗漏控制字符。 */
    private boolean controlCharacters(String value) { return value.chars().anyMatch(character -> character < 32 || character == 127); }

    /** 静态入口必须由 manifest 明确列出。 */
    private boolean contains(ArrayNode entries, String path) { for (JsonNode entry : entries) if (path.equals(entry.path("path").asText())) return true; return false; }

    /** 隐藏宿主位置和底层 I/O 细节的稳定失败。 */
    private ProjectException unavailable() { return new ProjectException(HttpStatus.CONFLICT, "PROTOTYPE_ARTIFACT_UNAVAILABLE", "原型产物缺失、损坏或未通过校验，请重新构建"); }

    /** 单个预期文件的不可变字节与内容指纹。 */
    private record FileEntry(String sha256, long bytes) { }

    /** 已验证对象与安全业务引用；不包含供客户端直接访问的永久路径或 URL。 */
    public record VerifiedArtifact(String objectKey, String toolchainId, JsonNode validation, JsonNode artifact) { }
}
