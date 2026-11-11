package com._36knight.alpha.prototype;

import com._36knight.alpha.project.ProjectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 通过真实 loopback HTTP 验证预览边界；合成内容不宣称经过模型或完整浏览器验收。@author Andersen */
class PrototypePreviewServiceTest {
    /** 只有业务归属检查被替换，HTTP 服务和静态响应均实际运行。 */
    private PrototypeService prototypes;
    /** 静态封存读取另有真实文件系统测试，本类聚焦会话与 HTTP。 */
    private PrototypeArtifactStore artifacts;
    /** 每例独立配置，避免占用固定端口。 */
    private PrototypePreviewProperties properties;
    /** 可推进时钟保证过期测试不依赖睡眠或调度时机。 */
    private MutableClock clock;
    /** 每例独立预览监听器。 */
    private PrototypePreviewService service;
    /** 禁用重定向，客户端不会掩盖预览服务的原始响应。 */
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();

    /** 明确标记的静态测试快照，不读取真实项目、数据库或开发者产物。 */
    @BeforeEach void setup() throws Exception {
        prototypes = mock(PrototypeService.class); artifacts = mock(PrototypeArtifactStore.class);
        properties = new PrototypePreviewProperties(); clock = new MutableClock();
        var json = new ObjectMapper();
        var manifest = json.readTree("{\"plan\":{\"pages\":[{\"path\":\"/items/list\"},{\"path\":\"/items/:id\",\"testPath\":\"/items/item-001\"}]}}");
        var verified = new PrototypeArtifactStore.VerifiedArtifact("a".repeat(64), "test", json.createObjectNode(), json.readTree("{\"files\":[{\"bytes\":200}]}"));
        var revision = mock(PrototypeContracts.RevisionView.class);
        when(revision.manifest()).thenReturn(manifest);
        when(prototypes.verifiedArtifact("v1", "r1")).thenReturn(verified);
        when(prototypes.revision("v1", "r1")).thenReturn(revision);
        when(prototypes.previewReadable("v1", "r1")).thenReturn(true);
        when(artifacts.staticSnapshot(verified)).thenReturn(new PrototypePreviewSnapshot(Map.of(
                "index.html", "<!doctype html><html><head></head><body>合成预览<script type=\"module\" src=\"./assets/app.js\"></script></body></html>".getBytes(StandardCharsets.UTF_8),
                "assets/app.js", "export const fixture = true;".getBytes(StandardCharsets.UTF_8),
                "assets/app.css", "body{color:black}".getBytes(StandardCharsets.UTF_8))));
        service = new PrototypePreviewService(prototypes, artifacts, properties, clock);
    }

    /** 关闭监听和线程，测试不会留下可继续访问的临时 URL。 */
    @AfterEach void cleanup() { service.close(); }

    /** 静态入口和资源实际返回，父对象检查逐请求执行，Cookie 不反射、不设置。 */
    @Test void servesOpaqueFrameAndStaticResourcesWithPerRequestChecks() throws Exception {
        var preview = open();
        assertThat(preview.sessionId()).matches("[a-f0-9]{48}");
        assertThat(preview.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));
        var frame = get(content(preview), "Sec-Fetch-Dest", "iframe");
        assertThat(frame.statusCode()).isEqualTo(200);
        assertThat(frame.body()).contains("合成预览", "/bridge/" + preview.sessionId() + "/bridge.js", "crossorigin=\"anonymous\"").doesNotContain("nonce=", "SELECT_PAGE");
        assertThat(frame.headers().firstValue("Content-Security-Policy").orElseThrow()).contains("script-src 'self';", "sandbox allow-scripts", "connect-src 'none'", "worker-src 'none'", "frame-ancestors http://127.0.0.1:18080").doesNotContain("allow-same-origin", "unsafe-eval", "nonce-");
        assertThat(frame.headers().firstValue("Access-Control-Allow-Origin")).contains("null");
        assertThat(frame.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(frame.headers().firstValue("Set-Cookie")).isEmpty();
        var bridge = get(bridge(preview), "Origin", "null");
        assertThat(bridge.statusCode()).isEqualTo(200);
        assertThat(bridge.body()).contains("SELECT_PAGE", "config.paths.includes(value.path)", "/items/item-001").doesNotContain("/items/:id");
        assertThat(bridge.headers().firstValue("Access-Control-Allow-Origin")).contains("null");
        assertThat(bridge.headers().firstValue("Content-Type")).contains("text/javascript; charset=utf-8");
        var resource = get(asset(preview), "Origin", "null", "Cookie", "alpha=secret");
        assertThat(resource.statusCode()).isEqualTo(200);
        assertThat(resource.body()).isEqualTo("export const fixture = true;");
        assertThat(resource.headers().firstValue("Set-Cookie")).isEmpty();
        verify(prototypes, times(4)).previewReadable("v1", "r1");
        verify(prototypes, times(1)).verifiedArtifact("v1", "r1");
    }

    /** HTML 顶层直接打开、fetch 以及其他站点资源请求均不能执行生成页面。 */
    @Test void deniesTopLevelHtmlAndForeignOriginRequests() throws Exception {
        var preview = open();
        assertThat(get(content(preview)).statusCode()).isEqualTo(403);
        assertThat(get(content(preview), "Sec-Fetch-Dest", "document").statusCode()).isEqualTo(403);
        assertThat(get(asset(preview), "Sec-Fetch-Dest", "document").statusCode()).isEqualTo(403);
        assertThat(get(asset(preview), "Sec-Fetch-Dest", "iframe").statusCode()).isEqualTo(403);
        assertThat(get(asset(preview), "Origin", "https://untrusted.example").statusCode()).isEqualTo(403);
        assertThat(get(bridge(preview), "Origin", "https://untrusted.example").statusCode()).isEqualTo(403);
    }

    /** 独立窗口只包含受信平台包装，内部 iframe 保留同一隔离边界。 */
    @Test void windowUsesTrustedWrapperAndCannotBeFramed() throws Exception {
        var preview = open();
        var window = get(preview.windowUrl());
        assertThat(window.statusCode()).isEqualTo(200);
        assertThat(window.body()).contains("sandbox=\"allow-scripts\"", "referrerpolicy=\"no-referrer\"", "event.source !== frame.contentWindow", "event.origin !== 'null'").doesNotContain("合成预览");
        assertThat(window.headers().firstValue("Content-Security-Policy").orElseThrow()).contains("frame-ancestors 'none'", "frame-src http://127.0.0.1:");
        assertThat(window.headers().firstValue("X-Frame-Options")).contains("DENY");
        var embedded = get(preview.frameUrl(), "Sec-Fetch-Dest", "iframe");
        assertThat(embedded.statusCode()).isEqualTo(200);
        assertThat(embedded.body()).contains("header{display:none}", "/content/" + preview.sessionId()).doesNotContain("合成预览");
        assertThat(embedded.headers().firstValue("Content-Security-Policy").orElseThrow()).contains("frame-ancestors http://127.0.0.1:18080", "frame-src http://127.0.0.1:");
    }

    /** 到期时刻即时关闭页面与资源，清理后的配额可重新使用。 */
    @Test void expiresWithoutWaitingForBackgroundCleanup() throws Exception {
        var preview = open();
        clock.advance(Duration.ofMinutes(15));
        assertThat(get(preview.frameUrl(), "Sec-Fetch-Dest", "iframe").statusCode()).isEqualTo(410);
        assertThat(get(asset(preview)).statusCode()).isEqualTo(410);
        assertThat(get(bridge(preview)).statusCode()).isEqualTo(410);
        assertThat(open().sessionId()).isNotEqualTo(preview.sessionId());
    }

    /** 其他版本不能撤销本会话，正确版本撤销后独立窗口与资源都失效。 */
    @Test void revokesOnlyMatchingVersionAndDoesNotDeleteArtifacts() throws Exception {
        var preview = open();
        service.revoke("other-version", preview.sessionId());
        assertThat(get(asset(preview)).statusCode()).isEqualTo(200);
        service.revoke("v1", preview.sessionId());
        assertThat(get(preview.windowUrl()).statusCode()).isEqualTo(410);
        assertThat(get(asset(preview)).statusCode()).isEqualTo(410);
        assertThat(get(bridge(preview)).statusCode()).isEqualTo(410);
    }

    /** 回收或权限消失时后续资源立即拒绝，并撤销会话，恢复后需要重新申请。 */
    @Test void revokesSessionWhenParentBecomesUnreadable() throws Exception {
        var preview = open();
        when(prototypes.previewReadable("v1", "r1")).thenReturn(false);
        assertThat(get(asset(preview)).statusCode()).isEqualTo(410);
        when(prototypes.previewReadable("v1", "r1")).thenReturn(true);
        assertThat(get(preview.windowUrl()).statusCode()).isEqualTo(410);
        assertThat(get(open().windowUrl()).statusCode()).isEqualTo(200);
    }

    /** 业务服务允许只读归档/取消时仍可访问；浏览器预览不会借机写入业务状态。 */
    @Test void preservesReadOnlyAccessWhenBusinessParentRemainsReadable() throws Exception {
        var preview = open();
        when(prototypes.previewReadable("v1", "r1")).thenReturn(true);
        assertThat(get(preview.windowUrl()).statusCode()).isEqualTo(200);
        assertThat(get(asset(preview)).statusCode()).isEqualTo(200);
    }

    /** 数据库或资格检查失败时不能回退为读取内存缓存。 */
    @Test void failsClosedWhenBusinessCheckThrows() throws Exception {
        var preview = open();
        when(prototypes.previewReadable("v1", "r1")).thenThrow(new IllegalStateException("/Users/private/secret"));
        var response = get(asset(preview));
        assertThat(response.statusCode()).isEqualTo(410);
        assertThat(response.body()).doesNotContain("private", "secret");
    }

    /** Host 校验防止 DNS 重绑定，路径拒绝必须发生在任何隐式规范化之前。 */
    @Test void rejectsWrongHostAndAmbiguousPaths() throws Exception {
        var preview = open();
        assertThat(raw(preview, "GET", URI.create(preview.frameUrl()).getRawPath(), "attacker.example")).startsWith("HTTP/1.1 403");
        String base = "/content/" + preview.sessionId() + "/";
        for (String suffix : List.of("../index.html", "%2e%2e/index.html", "assets%2fapp.js", "assets//app.js", ".env", "index.html?extra=1")) {
            assertThat(raw(preview, "GET", base + suffix, URI.create(preview.frameUrl()).getRawAuthority())).startsWith("HTTP/1.1 400");
        }
    }

    /** 静态服务不接受写方法，HEAD 不发送正文，清单外文件不回退到入口页面。 */
    @Test void supportsReadOnlyMethodsAndDoesNotUseSpaFallback() throws Exception {
        var preview = open();
        String asset = asset(preview);
        var head = client.send(HttpRequest.newBuilder(URI.create(asset)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(head.statusCode()).isEqualTo(200); assertThat(head.body()).isEmpty();
        assertThat(head.headers().firstValue("Content-Length")).contains(Integer.toString("export const fixture = true;".getBytes(StandardCharsets.UTF_8).length));
        var post = client.send(HttpRequest.newBuilder(URI.create(asset)).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(post.statusCode()).isEqualTo(405);
        assertThat(get(asset.replace("app.js", "missing.js")).statusCode()).isEqualTo(404);
    }

    /** 工作台来源只从配置白名单选择，不接受任意网站、脚本地址或带路径来源。 */
    @Test void rejectsUnlistedOriginAndInvalidConfiguration() {
        for (String origin : List.of("https://attacker.example", "http://127.0.0.1:18080/", "null", "javascript:alert(1)")) {
            assertThatThrownBy(() -> service.create("v1", "r1", new PrototypePreviewService.OpenCommand(origin)))
                    .isInstanceOfSatisfying(ProjectException.class, failure -> assertThat(failure.code()).isEqualTo("PROTOTYPE_PREVIEW_ORIGIN"));
        }
        properties.setAllowedOrigins(List.of("http://user:secret@127.0.0.1:18080"));
        assertThatThrownBy(() -> new PrototypePreviewService(prototypes, artifacts, properties, clock)).isInstanceOf(IllegalArgumentException.class);
    }

    /** 达到数量上限不驱逐旧预览；显式撤销后才允许建立替代会话。 */
    @Test void enforcesSessionCapacityAndReclaimsOnRevoke() {
        service.close(); properties.setMaxSessions(1); service = new PrototypePreviewService(prototypes, artifacts, properties, clock);
        var first = open();
        assertThatThrownBy(this::open).isInstanceOfSatisfying(ProjectException.class, failure -> assertThat(failure.code()).isEqualTo("PROTOTYPE_PREVIEW_CAPACITY"));
        service.revoke("v1", first.sessionId());
        assertThat(open().sessionId()).isNotEqualTo(first.sessionId());
    }

    /** 大小限额在分配快照之前使用已验证清单预检。 */
    @Test void rejectsOversizedSnapshotBeforeAllocation() {
        service.close(); properties.setMaxBytes(100); service = new PrototypePreviewService(prototypes, artifacts, properties, clock);
        reset(artifacts);
        assertThatThrownBy(this::open).isInstanceOfSatisfying(ProjectException.class, failure -> assertThat(failure.code()).isEqualTo("PROTOTYPE_PREVIEW_CAPACITY"));
        org.mockito.Mockito.verifyNoInteractions(artifacts);
    }

    /** 业务路由只返回短期地址与时效，删除路由与前端约定一致。 */
    @Test void exposesCreateAndRevokeControllerContract() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new PrototypeController(prototypes, service)).build();
        String body = mvc.perform(post("/api/alpha/workspace/versions/v1/prototype/revisions/r1/preview")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"parentOrigin\":\"http://127.0.0.1:5178\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revisionId").value("r1"))
                .andExpect(jsonPath("$.data.frameUrl").isString()).andExpect(jsonPath("$.data.windowUrl").isString())
                .andExpect(jsonPath("$.data.expiresAt").exists()).andReturn().getResponse().getContentAsString();
        String id = new ObjectMapper().readTree(body).path("data").path("sessionId").asText();
        mvc.perform(delete("/api/alpha/workspace/versions/v1/prototype/preview/" + id)).andExpect(status().isOk());
    }

    /** 无参数时选用默认受信工作台来源。 */
    private PrototypePreviewService.View open() { return service.create("v1", "r1", null); }

    /** 按服务器返回的 token 前缀请求静态资源。 */
    private String asset(PrototypePreviewService.View preview) { return content(preview).replace("index.html", "assets/app.js"); }

    /** 真正生成页面只出现在平台 wrapper 的内部 iframe 中。 */
    private String content(PrototypePreviewService.View preview) { return preview.frameUrl().replace("/frame/", "/content/"); }

    /** 平台桥接位于同一短期会话下，不存在脱离读取资格的全局 JS URL。 */
    private String bridge(PrototypePreviewService.View preview) { return preview.frameUrl().replace("/frame/", "/bridge/").replace("index.html", "bridge.js"); }

    /** 实际发送 GET；成对 headers 可以模拟浏览器 Fetch Metadata 和 opaque Origin。 */
    private HttpResponse<String> get(String url, String... headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3));
        if (headers.length > 0) request.headers(headers);
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 原始请求保留点分量和 Host，避免 HTTP 客户端先规范化恶意测试路径。 */
    private String raw(PrototypePreviewService.View preview, String method, String path, String host) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", URI.create(preview.frameUrl()).getPort())) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write((method + " " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 固定时区的测试时钟只允许正向推进。 */
    private static final class MutableClock extends Clock {
        /** 所有测试从明确的固定时刻开始。 */
        private Instant now = Instant.parse("2026-10-29T10:00:00Z");
        /** 推进服务时间而不阻塞线程。 */
        void advance(Duration duration) { now = now.plus(duration); }
        /** 会话时间统一使用 UTC。 */
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        /** 测试只使用 UTC，不创建额外时钟对象。 */
        @Override public Clock withZone(ZoneId zone) { return this; }
        /** 返回当前测试时刻。 */
        @Override public Instant instant() { return now; }
    }
}
