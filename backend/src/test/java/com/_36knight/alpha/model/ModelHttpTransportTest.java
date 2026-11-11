package com._36knight.alpha.model;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 通过本机合成 HTTP 服务验证响应边界，不连接真实平台。@author Andersen */
class ModelHttpTransportTest {
    /** 每个测试独占随机本机端口，不依赖外部网络。 */
    private HttpServer server;
    /** 与生产使用相同请求工厂和字节限制的客户端。 */
    private RestClient client;

    /** 初始化本机服务与实际 JDK HTTP 传输。 */
    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        client = ModelHttpTransport.restClient().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
    }

    /** 每次测试结束释放本机监听端口。 */
    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /** 正常响应通过原始字节转换，中文内容不被限制器破坏。 */
    @Test
    void acceptsBoundedResponse() {
        server.createContext("/normal", exchange -> reply(exchange, "模型合成响应".getBytes(StandardCharsets.UTF_8), 200));
        assertThat(client.get().uri("/normal").retrieve().body(String.class)).isEqualTo("模型合成响应");
    }

    /** 声明超大长度时，在转换器读取正文前拒绝。 */
    @Test
    void rejectsOversizedContentLength() {
        server.createContext("/large", exchange -> {
            exchange.sendResponseHeaders(200, 17L * 1024 * 1024);
            exchange.close();
        });
        assertThatThrownBy(() -> client.get().uri("/large").retrieve().body(String.class))
            .hasMessageContaining("模型响应超过传输上限");
    }

    /** 缺失长度的分块流同样强制上限，不能无限累计到内存。 */
    @Test
    void rejectsOversizedChunkedResponse() {
        server.createContext("/chunked", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                byte[] block = new byte[64 * 1024];
                for (int index = 0; index < 257; index++) output.write(block);
            } catch (IOException disconnected) {
                // 客户端达到上限会关闭连接，合成服务器允许写入被取消。
            }
        });
        assertThatThrownBy(() -> client.get().uri("/chunked").retrieve().body(String.class))
            .hasRootCauseMessage("模型响应超过传输上限");
    }

    /** 网关重定向不能让携带凭证的模型请求跟随到另一个地址。 */
    @Test
    void refusesCredentialRedirect() {
        AtomicInteger redirected = new AtomicInteger();
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/target");
            reply(exchange, new byte[0], 302);
        });
        server.createContext("/target", exchange -> {
            redirected.incrementAndGet();
            reply(exchange, "unexpected".getBytes(StandardCharsets.UTF_8), 200);
        });
        var response = client.get().uri("/redirect").header("Authorization", "Bearer synthetic").retrieve().toBodilessEntity();
        assertThat(response.getStatusCode().value()).isEqualTo(302);
        assertThat(redirected).hasValue(0);
    }

    /** 服务端仅发送受控的合成响应，不记录请求中的凭证头。 */
    private void reply(HttpExchange exchange, byte[] body, int status) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/plain;charset=UTF-8");
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
