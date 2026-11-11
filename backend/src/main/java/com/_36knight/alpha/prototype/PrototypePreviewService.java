package com._36knight.alpha.prototype;

import com._36knight.alpha.project.ProjectException;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 按修订申请短期独立预览；静态资源留在有界内存，每个请求重新检查业务父对象读取资格。
 * HttpServer 仅监听 127.0.0.1，不承载 Alpha API、Cookie、模型调用或文件系统浏览。
 * @author Andersen
 */
@Service
public class PrototypePreviewService {
    /** MIME 白名单与 ArtifactStore 的 P2 静态类型保持一致。 */
    private static final Map<String, String> MIME = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"), Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"), Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"), Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"), Map.entry("webp", "image/webp"), Map.entry("gif", "image/gif"),
            Map.entry("ico", "image/x-icon"), Map.entry("woff", "font/woff"), Map.entry("woff2", "font/woff2"), Map.entry("ttf", "font/ttf"));
    /** 业务服务负责归属与回收状态，预览不会复制其权限逻辑。 */
    private final PrototypeService prototypes;
    /** 只读封存对象的摘要校验与内存快照提供方。 */
    private final PrototypeArtifactStore artifacts;
    /** 时钟可替换用于真实 HTTP 的过期验收，不依赖测试睡眠。 */
    private final Clock clock;
    /** 创建时固定配置白名单，拒绝反射请求头中的任意来源。 */
    private final List<String> allowedOrigins;
    /** 会话创建时固定的有效时长。 */
    private final Duration ttl;
    /** 本进程保留数量上限。 */
    private final int maxSessions;
    /** 本进程保留快照的字节上限。 */
    private final long maxBytes;
    /** 独立监听端口，零表示随机。 */
    private final int port;
    /** 会话身份与 nonce 不能预测或由用户指定。 */
    private final SecureRandom random = new SecureRandom();
    /** 所有增删和配额操作由当前实例监视器保护。 */
    private final Map<String, Session> sessions = new HashMap<>();
    /** 首次请求预览时才启动本机 HTTP，不影响普通 Spring 单元测试。 */
    private HttpServer server;
    /** 独立服务的精确 origin，用于 Host 校验与 CSP。 */
    private String origin;
    /** 有界并发和排队，静态 HTTP 不使用无限线程或无限任务队列。 */
    private ThreadPoolExecutor executor;
    /** 定期释放未再访问的过期会话内存。 */
    private ScheduledExecutorService cleanup;
    /** 实际持有的静态快照字节数。 */
    private long retainedBytes;
    /** 应用关闭后不允许重新启动预览监听。 */
    private boolean closed;

    /** Spring 装配采用 UTC 系统时钟，不在构造期间占用端口。 */
    @Autowired
    public PrototypePreviewService(PrototypeService prototypes, PrototypeArtifactStore artifacts, PrototypePreviewProperties properties) {
        this(prototypes, artifacts, properties, Clock.systemUTC());
    }

    /** 同包测试可使用可推进时钟，其他约束与生产初始化一致。 */
    PrototypePreviewService(PrototypeService prototypes, PrototypeArtifactStore artifacts, PrototypePreviewProperties properties, Clock clock) {
        this.prototypes = prototypes; this.artifacts = artifacts; this.clock = clock;
        allowedOrigins = List.copyOf(properties.getAllowedOrigins());
        ttl = properties.getTtl(); maxSessions = properties.getMaxSessions(); maxBytes = properties.getMaxBytes(); port = properties.getPort();
        if (allowedOrigins.isEmpty() || allowedOrigins.size() > 16 || allowedOrigins.stream().anyMatch(value -> !validOrigin(value))
                || ttl == null || ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofHours(1)) > 0
                || maxSessions < 1 || maxSessions > 64 || maxBytes < 1 || maxBytes > 512L * 1024 * 1024 || port < 0 || port > 65535) {
            throw new IllegalArgumentException("原型预览配置不合法");
        }
    }

    /**
     * 串行创建快照，避免并发申请在配额之外分配大型文件；首次申请核验真实产物，后续资源只读快照。
     * parentOrigin 只能选择配置白名单中的 Alpha 页面，未提供时使用第一项。
     */
    public synchronized View create(String versionId, String revisionId, OpenCommand command) {
        String parentOrigin = command == null || command.parentOrigin() == null ? allowedOrigins.getFirst() : command.parentOrigin();
        if (!allowedOrigins.contains(parentOrigin)) throw failure(HttpStatus.BAD_REQUEST, "PROTOTYPE_PREVIEW_ORIGIN", "当前工作台来源未配置为允许的预览来源");
        expire();
        if (closed) throw unavailable();
        if (sessions.size() >= maxSessions) throw capacity();
        var verified = prototypes.verifiedArtifact(versionId, revisionId);
        long expectedBytes = 0;
        for (JsonNode file : verified.artifact().path("files")) expectedBytes += file.path("bytes").asLong();
        if (expectedBytes < 0 || expectedBytes > maxBytes - retainedBytes) throw capacity();
        var revision = prototypes.revision(versionId, revisionId);
        Set<String> paths = new HashSet<>(); paths.add("/");
        for (JsonNode page : revision.manifest().path("plan").path("pages")) {
            String path = page.path("path").asText();
            if (validPagePath(path)) paths.add(path);
            // 参数路由使用冻结的实际验证地址定位，不能将 :id 作为待执行模板交给浏览器。
            String testPath = page.path("testPath").asText();
            if (validPagePath(testPath)) paths.add(testPath);
        }
        PrototypePreviewSnapshot snapshot = artifacts.staticSnapshot(verified);
        if (snapshot.bytes() > maxBytes - retainedBytes) throw capacity();
        if (!readable(versionId, revisionId)) throw failure(HttpStatus.NOT_FOUND, "PROTOTYPE_PREVIEW_UNAVAILABLE", "原型或其来源文档已不可访问");
        startServer();
        if (origin.equals(parentOrigin)) throw unavailable();
        String id = randomHex(), nonce = randomNonce();
        Instant expiresAt = clock.instant().plus(ttl);
        Session session = new Session(id, versionId, revisionId, parentOrigin, nonce, expiresAt, snapshot, Set.copyOf(paths));
        sessions.put(id, session); retainedBytes += snapshot.bytes();
        return new View(id, revisionId, frameUrl(id), origin + "/window/" + id, expiresAt);
    }

    /** 撤销仅释放匹配版本的临时会话；父文档已回收时仍允许主动清理，不修改封存产物。 */
    public synchronized void revoke(String versionId, String sessionId) {
        Session session = sessions.get(sessionId);
        if (session != null && session.versionId().equals(versionId)) remove(sessionId);
    }

    /** 应用停止立即关闭监听、任务线程和会话，重启后旧 URL 不再有效。 */
    @PreDestroy
    public synchronized void close() {
        closed = true;
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
        if (cleanup != null) cleanup.shutdownNow();
        sessions.clear(); retainedBytes = 0;
    }

    /** 仅监听 IPv4 loopback，并为静态服务设置有限的排队和清理任务。 */
    private void startServer() {
        if (server != null) return;
        try {
            HttpServer candidate = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 32);
            executor = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64),
                    runnable -> Thread.ofPlatform().daemon().name("prototype-preview-http").unstarted(runnable), new ThreadPoolExecutor.AbortPolicy());
            candidate.setExecutor(executor); candidate.createContext("/", this::handle);
            origin = "http://127.0.0.1:" + candidate.getAddress().getPort();
            candidate.start(); server = candidate;
            cleanup = Executors.newSingleThreadScheduledExecutor(runnable -> Thread.ofPlatform().daemon().name("prototype-preview-expiry").unstarted(runnable));
            cleanup.scheduleWithFixedDelay(this::expire, 30, 30, TimeUnit.SECONDS);
        } catch (IOException exception) { throw unavailable(); }
    }

    /** 请求入口只接受准确 Host、GET/HEAD、单义路径和有效会话；失败响应不携带内部异常。 */
    private void handle(HttpExchange exchange) throws IOException {
        try {
            commonHeaders(exchange);
            List<String> hosts = exchange.getRequestHeaders().get("Host");
            if (hosts == null || hosts.size() != 1 || !URI.create(origin).getRawAuthority().equals(hosts.getFirst())) { respond(exchange, 403, "预览来源不匹配"); return; }
            if (!Set.of("GET", "HEAD").contains(exchange.getRequestMethod())) { exchange.getResponseHeaders().set("Allow", "GET, HEAD"); respond(exchange, 405, "预览仅允许只读请求"); return; }
            String path = exchange.getRequestURI().getRawPath();
            if (!safeRequestPath(path) || exchange.getRequestURI().isAbsolute() || exchange.getRequestURI().getRawQuery() != null || exchange.getRequestURI().getRawFragment() != null) { respond(exchange, 400, "预览路径不合法"); return; }
            String[] parts = path.split("/", 4);
            if (parts.length < 3 || !parts[2].matches("[a-f0-9]{48}")) { respond(exchange, 404, "预览不存在"); return; }
            Session session = session(parts[2]);
            if (session == null) { respond(exchange, 410, "预览已到期，请回工作台重新打开"); return; }
            // 每个页面和资源请求都检查当前业务状态，不能因已经缓存静态内容而跳过回收限制。
            if (!readable(session.versionId(), session.revisionId())) { revoke(session.versionId(), session.id()); respond(exchange, 410, "原型或其来源文档已不可访问"); return; }
            if ("window".equals(parts[1]) && parts.length == 3) { serveWrapper(exchange, session, false); return; }
            if ("frame".equals(parts[1]) && parts.length == 4 && parts[3].equals("index.html")) { serveWrapper(exchange, session, true); return; }
            if ("bridge".equals(parts[1]) && parts.length == 4 && parts[3].equals("bridge.js")) { serveBridge(exchange, session); return; }
            if (!"content".equals(parts[1]) || parts.length != 4 || parts[3].isEmpty()) { respond(exchange, 404, "预览资源不存在"); return; }
            serveFile(exchange, session, parts[3]);
        } catch (RuntimeException exception) {
            respond(exchange, 503, "预览暂时不可访问，请回工作台重试");
        } finally { exchange.close(); }
    }

    /** 受信包装页的 frame-src 约束内层自身导航；独立窗口不允许嵌套，工作台模式只允许配置来源。 */
    private void serveWrapper(HttpExchange exchange, Session session, boolean embedded) throws IOException {
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; script-src 'nonce-" + session.nonce()
                + "'; style-src 'nonce-" + session.nonce() + "'; frame-src " + origin
                + "; connect-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors "
                + (embedded ? session.parentOrigin() : "'none'") + "; worker-src 'none'");
        if (!embedded) exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        byte[] body = PrototypePreviewDocuments.wrapper(origin + "/content/" + session.id() + "/index.html", session.id(), session.revisionId(), session.nonce(), session.parentOrigin(), session.paths(), embedded).getBytes(StandardCharsets.UTF_8);
        send(exchange, 200, "text/html; charset=utf-8", body);
    }

    /** 与静态资源相同的会话和业务资格入口，平台桥接不能通过永久无资格脚本地址绕过撤销。 */
    private void serveBridge(HttpExchange exchange, Session session) throws IOException {
        if (!resourceOriginAllowed(exchange, session)) { respond(exchange, 403, "预览来源不匹配"); return; }
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "null");
        exchange.getResponseHeaders().set("Cross-Origin-Resource-Policy", "cross-origin");
        byte[] body = PrototypePreviewDocuments.bridge(session.id(), session.revisionId(), origin, session.paths()).getBytes(StandardCharsets.UTF_8);
        send(exchange, 200, "text/javascript; charset=utf-8", body);
    }

    /** 资源从内存提供；HTML 只能在 iframe 加载，所有文档均强制 opaque sandbox。 */
    private void serveFile(HttpExchange exchange, Session session, String path) throws IOException {
        long size = session.snapshot().size(path);
        int dot = path.lastIndexOf('.');
        String mime = dot < 0 ? null : MIME.get(path.substring(dot + 1));
        if (size < 0 || mime == null) { respond(exchange, 404, "预览资源不存在"); return; }
        String destination = exchange.getRequestHeaders().getFirst("Sec-Fetch-Dest");
        // SVG 等资源不能被改作活动文档执行；所有可执行页面都必须经 HTML 受限入口。
        if (!path.endsWith(".html") && destination != null && Set.of("document", "iframe", "frame", "object", "embed").contains(destination)) {
            respond(exchange, 403, "静态资源不能作为预览页面打开"); return;
        }
        if (!resourceOriginAllowed(exchange, session)) { respond(exchange, 403, "预览来源不匹配"); return; }
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "null");
        exchange.getResponseHeaders().set("Cross-Origin-Resource-Policy", "cross-origin");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; script-src 'self'"
                + "; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-src 'none'; frame-ancestors "
                + session.parentOrigin() + " " + origin + "; worker-src 'none'; sandbox allow-scripts");
        if (path.endsWith(".html")) {
            if (!"iframe".equals(destination)) { respond(exchange, 403, "请通过工作台或独立预览窗口打开"); return; }
            String html = PrototypePreviewDocuments.frame(session.snapshot().text(path), session.id(), origin);
            send(exchange, 200, mime, html.getBytes(StandardCharsets.UTF_8));
        } else {
            exchange.getResponseHeaders().set("Content-Type", mime);
            exchange.getResponseHeaders().set("Content-Length", Long.toString(size));
            exchange.sendResponseHeaders(200, "HEAD".equals(exchange.getRequestMethod()) || size == 0 ? -1 : size);
            if (!"HEAD".equals(exchange.getRequestMethod()) && size > 0) session.snapshot().write(path, exchange.getResponseBody());
        }
    }

    /** 所有响应禁止缓存和引用泄漏，并禁止浏览器相机、麦克风等能力。 */
    private void commonHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=(), serial=(), clipboard-read=(), clipboard-write=()");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; sandbox");
    }

    /** 稳定中文错误不包含宿主位置、SQL 或底层异常。 */
    private void respond(HttpExchange exchange, int status, String message) throws IOException { send(exchange, status, "text/plain; charset=utf-8", message.getBytes(StandardCharsets.UTF_8)); }

    /** HEAD 只发送响应元数据，正文长度与 GET 保持一致。 */
    private void send(HttpExchange exchange, int status, String contentType, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length));
        exchange.sendResponseHeaders(status, "HEAD".equals(exchange.getRequestMethod()) ? -1 : bytes.length);
        if (!"HEAD".equals(exchange.getRequestMethod())) exchange.getResponseBody().write(bytes);
    }

    /** 失败关闭；临时数据库故障不应允许读取缓存内容。 */
    private boolean readable(String versionId, String revisionId) {
        try { return prototypes.previewReadable(versionId, revisionId); }
        catch (RuntimeException exception) { return false; }
    }

    /** opaque 子页使用 Origin:null；无 Origin 的被动资源及固定平台来源也可读，不反射任意网站。 */
    private boolean resourceOriginAllowed(HttpExchange exchange, Session session) {
        List<String> origins = exchange.getRequestHeaders().get("Origin");
        return origins == null || origins.size() == 1 && Set.of("null", origin, session.parentOrigin()).contains(origins.getFirst());
    }

    /** 在检查期限后取快照引用，请求期间不会再次访问磁盘。 */
    private synchronized Session session(String id) { expire(); return sessions.get(id); }

    /** 会话无访问也会由清理线程释放，截止时刻按不再有效处理。 */
    private synchronized void expire() {
        Instant now = clock.instant();
        for (String id : sessions.values().stream().filter(session -> !now.isBefore(session.expiresAt())).map(Session::id).toList()) remove(id);
    }

    /** 配额与会话删除在同一个锁内更新。 */
    private void remove(String id) { Session removed = sessions.remove(id); if (removed != null) retainedBytes -= removed.snapshot().bytes(); }

    /** 原始 URL 路径不经解码或 normalize；百分号、控制字符和点分量全部拒绝。 */
    private boolean safeRequestPath(String path) {
        if (path == null || path.length() > 1200 || !path.startsWith("/") || path.contains("//") || path.matches(".*[\\x00-\\x20\\x7f%?#:\\\\].*")) return false;
        for (String part : path.substring(1).split("/", -1)) if (part.isEmpty() || part.startsWith(".")) return false;
        return true;
    }

    /** 页面定位只支持 Worker 已定义的有界 hash 路由，不接受 URL、查询串或脚本协议。 */
    private boolean validPagePath(String path) { return path != null && path.length() <= 220 && path.matches("/[A-Za-z0-9_./-]*") && !path.contains(".."); }

    /** 只接受无凭证、路径、参数和片段的准确 HTTP(S) origin。 */
    private boolean validOrigin(String value) {
        try {
            URI uri = URI.create(value);
            return Set.of("http", "https").contains(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getRawQuery() == null && uri.getRawFragment() == null && uri.getRawPath().isEmpty()
                    && value.equals(uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority());
        } catch (RuntimeException exception) { return false; }
    }

    /** token 仅对应单个短期修订，不使用磁盘对象键作为公开地址。 */
    private String randomHex() { byte[] bytes = new byte[24]; random.nextBytes(bytes); return HexFormat.of().formatHex(bytes); }

    /** CSP nonce 单独生成，与可见的会话身份独立。 */
    private String randomNonce() { byte[] bytes = new byte[24]; random.nextBytes(bytes); return Base64.getEncoder().withoutPadding().encodeToString(bytes); }

    /** 由服务器固定资源前缀构造 URL，客户端不能传入文件系统路径。 */
    private String frameUrl(String id) { return origin + "/frame/" + id + "/index.html"; }

    /** 超配额时保留现有会话，调用方可关闭旧预览或等待到期后重试。 */
    private ProjectException capacity() { return failure(HttpStatus.TOO_MANY_REQUESTS, "PROTOTYPE_PREVIEW_CAPACITY", "临时预览数量或大小已达上限，请关闭旧预览后重试"); }

    /** 不将监听失败的系统地址或底层栈回显到 API。 */
    private ProjectException unavailable() { return failure(HttpStatus.SERVICE_UNAVAILABLE, "PROTOTYPE_PREVIEW_UNAVAILABLE", "本机独立预览服务不可用"); }

    /** 沿用现有业务响应错误契约。 */
    private ProjectException failure(HttpStatus status, String code, String message) { return new ProjectException(status, code, message); }

    /** 选择固定白名单中的 Alpha 来源；可省略以采用配置第一项。 */
    public record OpenCommand(String parentOrigin) { }

    /** 短期读取能力，不包含产物目录、模型源码或永久静态 URL。 */
    public record View(String sessionId, String revisionId, String frameUrl, String windowUrl, Instant expiresAt) { }

    /** 仅服务端持有的固定修订与内容；业务资格在每次读取时另行检查。 */
    private record Session(String id, String versionId, String revisionId, String parentOrigin, String nonce,
            Instant expiresAt, PrototypePreviewSnapshot snapshot, Set<String> paths) { }
}
