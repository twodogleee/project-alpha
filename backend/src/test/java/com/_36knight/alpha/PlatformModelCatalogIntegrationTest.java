package com._36knight.alpha;

import com._36knight.alpha.model.FetchPlatformModelsRequest;
import com._36knight.alpha.model.ModelCatalogService;
import com._36knight.alpha.model.SaveModelPlatformRequest;
import com._36knight.alpha.model.SaveModelProfileRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 本机 HTTP 模型目录契约、凭证隔离和错误边界验证，不调用真实平台。@author Andersen */
@SpringBootTest(properties = {"alpha.ai.mode=demo", "alpha.ai.api-key=",
        "alpha.model-config.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "spring.datasource.dynamic.datasource.master.url=jdbc:h2:mem:platform-model-catalog;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class PlatformModelCatalogIntegrationTest {
    /** 只用于本机鉴权与日志断言的合成凭证。 */
    private static final String KEY = "synthetic-catalog-credential";
    /** Controller 公开契约测试入口。 */
    @Autowired private MockMvc mvc;
    /** 公共 JSON 序列化器。 */
    @Autowired private ObjectMapper json;
    /** 目录持久化与快照业务。 */
    @Autowired private ModelCatalogService catalog;
    /** 仅访问隔离 H2 测试库。 */
    @Autowired private DataSource dataSource;
    /** 随机本机端口平台。 */
    private HttpServer server;
    /** 模拟错误状态。 */
    private volatile int upstreamStatus;
    /** 模拟响应，不包含真实凭证。 */
    private volatile String upstreamBody;
    /** 本次到达平台的认证头，不进入应用输出。 */
    private volatile String authorization;
    /** 确认错误或陈旧请求不发出网络请求。 */
    private final AtomicInteger calls = new AtomicInteger();
    /** 确认目录获取不调用推理接口，也不跟随跳转。 */
    private final AtomicInteger otherCalls = new AtomicInteger();

    /** 初始化目录根和本机平台，不接触开发数据。 */
    @BeforeEach void start() throws Exception {
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.update("UPDATE model_runtime_selection SET revision=1,mode='demo',active_model_id=NULL");
        jdbc.execute("DELETE FROM model_profiles"); jdbc.execute("DELETE FROM model_platforms");
        calls.set(0); otherCalls.set(0); authorization = null; upstreamStatus = 200;
        upstreamBody = "{\"data\":[{\"id\":\"test-model-z\"},{\"id\":\"gpt-6-astra\"},{\"id\":\"gpt-6-astra\"}]}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            calls.incrementAndGet(); authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (!"GET".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); exchange.close(); return; }
            byte[] bytes = upstreamBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (upstreamStatus == 302) exchange.getResponseHeaders().set("Location", url() + "/redirected");
            exchange.sendResponseHeaders(upstreamStatus, bytes.length);
            try { exchange.getResponseBody().write(bytes); } catch (java.io.IOException ignored) { /* 大响应主动中断读取是预期行为。 */ }
            finally { exchange.close(); }
        });
        server.createContext("/v1/redirected", exchange -> { otherCalls.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.createContext("/v1/chat/completions", exchange -> { otherCalls.incrementAndGet(); exchange.sendResponseHeaders(500, -1); exchange.close(); });
        server.start();
    }
    /** 清理本次模拟 HTTP 平台。 */
    @AfterEach void stop() { server.stop(0); }

    /** 地址可动态保存与清空，不修改关联模型密文或当前模式。 */
    @Test void persistsAndClearsCatalogUrlWithoutChangingKeys() throws Exception {
        String platform = platform("目录平台");
        catalog.saveProfile(null, new SaveModelProfileRequest(revision(), platform, "已有模型", "test-model", KEY, false));
        String ciphertext = new JdbcTemplate(dataSource).queryForObject("SELECT api_key_ciphertext FROM model_profiles", String.class);
        mvc.perform(get("/api/alpha/model-config")).andExpect(jsonPath("$.data.platforms[0].modelCatalogUrl").value(url() + "/models"));
        mvc.perform(put("/api/alpha/model-config/platforms/" + platform).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new SaveModelPlatformRequest(revision(), "目录平台", "openai-compatible", url(), ""))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.platforms[0].modelCatalogUrl").doesNotExist());
        assertThat(new JdbcTemplate(dataSource).queryForObject("SELECT model_catalog_url FROM model_platforms", String.class)).isNull();
        assertThat(new JdbcTemplate(dataSource).queryForObject("SELECT api_key_ciphertext FROM model_profiles", String.class)).isEqualTo(ciphertext);
        assertThat(catalog.get().runtime().mode()).isEqualTo("demo"); assertThat(calls.get()).isZero();
    }

    /** 尚未填写模型名称也可获取，名单去重排序，不创建模型或改变目录版本。 */
    @Test void fetchesWithInputKeyWithoutSavingOrInference() throws Exception {
        String platform = platform("目录平台"); long revision = revision();
        upstreamBody = json.writeValueAsString(Map.of("data", List.of(Map.of("id", "test-model-z"), Map.of("id", "gpt-6-astra"),
                Map.of("id", "gpt-6-astra"), Map.of("id", KEY), Map.of("id", "invalid name"), Map.of("id", 123))));
        var response = fetch(platform, new FetchPlatformModelsRequest(revision, null, KEY, false))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.models.length()").value(2))
                .andExpect(jsonPath("$.data.models[0]").value("gpt-6-astra"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(KEY, "Authorization"); assertThat(authorization).isEqualTo("Bearer " + KEY);
        assertThat(catalog.get().revision()).isEqualTo(revision); assertThat(catalog.get().models()).isEmpty();
        assertThat(catalog.get().runtime().mode()).isEqualTo("demo"); assertThat(otherCalls.get()).isZero();
    }

    /** 原密钥只属于当前模型当前平台；新输入优先、清除命令匿名、跨平台不借用。 */
    @Test void reusesOnlyCurrentModelsSamePlatformKey() throws Exception {
        String first = platform("平台一");
        var saved = catalog.saveProfile(null, new SaveModelProfileRequest(revision(), first, "已有模型", "test-model", KEY, false));
        String model = saved.models().getFirst().id(); String second = platform("平台二");
        fetch(first, new FetchPlatformModelsRequest(revision(), model, "", false)).andExpect(status().isOk());
        assertThat(authorization).isEqualTo("Bearer " + KEY);
        fetch(first, new FetchPlatformModelsRequest(revision(), model, "synthetic-replacement", false)).andExpect(status().isOk());
        assertThat(authorization).isEqualTo("Bearer synthetic-replacement");
        fetch(first, new FetchPlatformModelsRequest(revision(), model, "", true)).andExpect(status().isOk()); assertThat(authorization).isNull();
        fetch(second, new FetchPlatformModelsRequest(revision(), model, "", false)).andExpect(status().isOk()); assertThat(authorization).isNull();
        fetch(first, new FetchPlatformModelsRequest(revision(), null, "", false)).andExpect(status().isOk()); assertThat(authorization).isNull();
        assertThat(catalog.get().models()).hasSize(1);
    }

    /** HTTP 403 原因可诊断，页面和日志不携带上游回显的密钥。 */
    @Test void reportsSanitizedUpstreamError(CapturedOutput output) throws Exception {
        String platform = platform("目录平台"); upstreamStatus = 403;
        upstreamBody = json.writeValueAsString(Map.of("error", Map.of("code", "catalog_forbidden", "message", "目录权限不足，回显 " + KEY)));
        String response = fetch(platform, new FetchPlatformModelsRequest(revision(), null, KEY, false))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("MODEL_CATALOG_FETCH_FAILED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).contains("403", "目录权限不足", "诊断编号").doesNotContain(KEY);
        assertThat(output.getAll()).contains("模型列表获取失败", "upstreamStatus=403", "upstreamCode=catalog_forbidden", "目录权限不足").doesNotContain(KEY);
    }

    /** 格式错误不输出原文；空目录为可用响应，用户可以继续手工输入。 */
    @Test void rejectsInvalidResponsesAndAcceptsEmptyCatalog() throws Exception {
        String platform = platform("目录平台");
        for (String body : List.of("<html>" + KEY + "</html>", "{\"models\":[]}", "[]")) {
            upstreamBody = body;
            String response = fetch(platform, new FetchPlatformModelsRequest(revision(), null, KEY, false))
                    .andExpect(status().isBadGateway()).andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain(KEY);
        }
        upstreamBody = "{\"data\":[]}";
        fetch(platform, new FetchPlatformModelsRequest(revision(), null, "", false)).andExpect(status().isOk()).andExpect(jsonPath("$.data.models").isEmpty());
    }

    /** HTTP 重定向绝不跟随，不把认证头交给下一跳。 */
    @Test void neverFollowsRedirect() throws Exception {
        String platform = platform("目录平台"); upstreamStatus = 302;
        fetch(platform, new FetchPlatformModelsRequest(revision(), null, KEY, false)).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("未跟随跳转")));
        assertThat(otherCalls.get()).isZero();
    }

    /** 正文和名单限制在真实 HTTP 流上生效，失败不截取或保存部分目录。 */
    @Test void boundsBodyAndCatalogSize() throws Exception {
        String platform = platform("目录平台"); upstreamBody = "x".repeat(1024 * 1024 + 1);
        fetch(platform, new FetchPlatformModelsRequest(revision(), null, "", false)).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("过大")));
        var models = new ArrayList<Map<String, String>>();
        for (int index = 0; index < 1001; index++) models.add(Map.of("id", "model-" + index));
        upstreamBody = json.writeValueAsString(Map.of("data", models));
        fetch(platform, new FetchPlatformModelsRequest(revision(), null, "", false)).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("1000")));
        assertThat(catalog.get().models()).isEmpty();
    }

    /** 整体超时覆盖响应头之后的正文等待，不留下无限读取请求。 */
    @Test void timesOutWhenHeadersArriveButBodyStalls() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/v1/models/slow", exchange -> {
            exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().flush();
            try { release.await(20, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        String platform = catalog.savePlatform(null, new SaveModelPlatformRequest(revision(), "慢平台", "openai-compatible", url(), url() + "/models/slow"))
                .platforms().getFirst().id();
        long started = System.nanoTime();
        try {
            fetch(platform, new FetchPlatformModelsRequest(revision(), null, "", false)).andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("超时")));
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started).toSeconds()).isBetween(14L, 19L);
        } finally { release.countDown(); }
    }

    /** 陈旧版本、无配置地址和冲突凭证策略均在发出网络前拒绝。 */
    @Test void rejectsInvalidRequestBeforeNetwork() throws Exception {
        String platform = platform("目录平台");
        fetch(platform, new FetchPlatformModelsRequest(revision() - 1, null, KEY, false)).andExpect(status().isConflict());
        fetch(platform, new FetchPlatformModelsRequest(revision(), null, KEY, true)).andExpect(status().isBadRequest());
        catalog.savePlatform(platform, new SaveModelPlatformRequest(revision(), "目录平台", "openai-compatible", url(), null));
        fetch(platform, new FetchPlatformModelsRequest(revision(), null, KEY, false)).andExpect(status().isBadRequest());
        assertThat(calls.get()).isZero();
    }

    /** 不接受跨域、不同端口、URL 凭证、查询参数或危险协议。 */
    @Test void rejectsUnsafeCatalogUrls() throws Exception {
        for (String invalid : List.of("https://other.example/v1/models", "http://127.0.0.1:1/v1/models",
                url().replace("127.0.0.1", "user:password@127.0.0.1") + "/models", url() + "/models?key=value",
                url() + "/models#value", "file:///tmp/models", "/v1/models")) {
            mvc.perform(post("/api/alpha/model-config/platforms").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(new SaveModelPlatformRequest(revision(), "目录平台", "openai-compatible", url(), invalid))))
                    .andExpect(status().isBadRequest());
        }
        assertThat(catalog.get().platforms()).isEmpty(); assertThat(calls.get()).isZero();
    }

    /** 本机回环基础 URL，支持既有测试协议边界。 */
    private String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }
    /** 获取当前测试目录版本。 */
    private long revision() { return catalog.get().revision(); }
    /** 新建配置了目录 URL 的模拟平台，不调用网络。 */
    private String platform(String name) {
        return catalog.savePlatform(null, new SaveModelPlatformRequest(revision(), name, "openai-compatible", url(), url() + "/models"))
                .platforms().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow().id();
    }
    /** 调用公开接口，以便覆盖请求校验和统一响应。 */
    private org.springframework.test.web.servlet.ResultActions fetch(String platform, FetchPlatformModelsRequest request) throws Exception {
        return mvc.perform(post("/api/alpha/model-config/platforms/" + platform + "/catalog").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("expectedRevision", request.expectedRevision(), "modelId", request.modelId() == null ? "" : request.modelId(),
                        "apiKey", request.apiKey() == null ? "" : request.apiKey(), "clearApiKey", request.clearApiKey()))));
    }
}
