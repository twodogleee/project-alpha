package com._36knight.alpha;

import com._36knight.alpha.agent.AnalysisRuntime;
import com._36knight.alpha.agent.RequirementWorkflow;
import com._36knight.alpha.model.ModelConfigurationException;
import com._36knight.alpha.model.ModelCatalogRepository;
import com._36knight.alpha.model.ModelCatalogService;
import com._36knight.alpha.model.ModelRuntimeService;
import com._36knight.alpha.model.ModelSecretCipher;
import com._36knight.alpha.model.SaveModelPlatformRequest;
import com._36knight.alpha.model.SaveModelProfileRequest;
import com._36knight.alpha.model.SaveModelSelectionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真 HTTP 模拟平台验证动态客户端、结构化结果与密钥边界，不调用真实云平台。
 * @author Andersen
 */
@SpringBootTest(properties = {"alpha.ai.mode=demo", "alpha.ai.api-key=",
        "spring.datasource.dynamic.datasource.master.url=jdbc:h2:mem:model-config;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ModelConfigurationIntegrationTest {
    /** 测试私有主密钥文件独立于开发目录。 */
    private static final Path KEY_FILE = temporaryKeyFile();
    /** 模拟密钥仅用于本地协议断言。 */
    private static final String TEST_KEY = "synthetic-test-key-not-a-credential";
    /** 真实 HTTP Controller 验证入口。 */
    @Autowired private MockMvc mvc;
    /** 与应用相同的快照序列化器。 */
    @Autowired private ObjectMapper json;
    /** 数据库断言只针对当前测试库。 */
    @Autowired private DataSource dataSource;
    /** 配置的业务入口。 */
    @Autowired private ModelCatalogService configurations;
    /** 重建服务验证环境默认密钥的目标边界。 */
    @Autowired private ModelCatalogRepository repository;
    /** 单次运行上下文验证入口。 */
    @Autowired private ModelRuntimeService runtime;
    /** Graph 必须固定本次来源标记。 */
    @Autowired private RequirementWorkflow workflow;
    /** 本地模拟服务使用随机端口。 */
    private HttpServer server;
    /** 可切换为上游错误响应，验证安全错误边界。 */
    private volatile boolean fail;
    /** PRD 测试返回另一种结构，验证实际 ChatClient 转换而非只模拟适配器。 */
    private volatile boolean prdResponse;
    /** 模块型会话输出经过真实 JSON 转换与供应商客户端。 */
    private volatile boolean workbenchResponse;
    /** 上游成功但正文无法转换，验证结构化错误提示与日志安全。 */
    private volatile boolean invalidResponse;
    /** 可模拟网关 403 和结构化错误正文，不使用真实外部平台。 */
    private volatile int failureStatus;
    /** 仅供本机模拟平台返回的错误正文。 */
    private volatile String upstreamFailureBody;
    /** 请求到达时通知并发测试进行配置切换。 */
    private volatile CountDownLatch entered;
    /** 控制模拟调用完成，验证运行中切换不会污染来源。 */
    private volatile CountDownLatch release;

    /** 将密钥文件指向本次测试目录。 */
    @DynamicPropertySource
    static void privateKeyPath(DynamicPropertyRegistry registry) {
        registry.add("alpha.model-config.key-file", () -> KEY_FILE.toString());
        registry.add("alpha.model-config.encryption-key", () -> "");
    }
    /** 建立私有测试目录，失败不输出本地环境信息。 */
    private static Path temporaryKeyFile() {
        try { return Files.createTempDirectory("alpha-model-key-test").resolve("master.key"); }
        catch (java.io.IOException exception) { throw new IllegalStateException("无法建立隔离测试目录"); }
    }

    /** 每个测试从环境默认状态开始，协议响应保持明确的测试来源。 */
    @BeforeEach
    void startMockPlatform() throws Exception {
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.update("UPDATE model_runtime_selection SET revision=1,mode='demo',active_model_id=NULL");
        jdbc.execute("DELETE FROM model_profiles");
        jdbc.execute("DELETE FROM model_platforms");
        fail = false;
        prdResponse = false;
        workbenchResponse = false;
        invalidResponse = false;
        failureStatus = 401;
        upstreamFailureBody = null;
        entered = null;
        release = null;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer " + TEST_KEY);
            var request = json.readTree(exchange.getRequestBody());
            assertThat(request.path("model").asText()).startsWith("test-model");
            assertThat(request.path("messages").isArray()).isTrue();
            if (entered != null) entered.countDown();
            if (release != null) {
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Mock timeout"); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new java.io.IOException("Interrupted"); }
            }
            String content = json.writeValueAsString(Map.of("title", "模拟平台结果", "overview", "协议测试",
                    "requirements", List.of("允许新增记录"), "openQuestions", List.of(), "assumptions", List.of()));
            if(prdResponse) content=json.writeValueAsString(mockPrd());
            if(workbenchResponse) content=json.writeValueAsString(mockWorkbench());
            if(invalidResponse) content="unparseable upstream echoed " + TEST_KEY;
            byte[] body = fail ? (upstreamFailureBody == null ? "upstream echoed " + TEST_KEY : upstreamFailureBody).getBytes(StandardCharsets.UTF_8)
                    : json.writeValueAsBytes(Map.of("id", "synthetic-result", "object", "chat.completion", "created", 1,
                    "model", request.path("model").asText(), "choices", List.of(Map.of("index", 0,
                            "message", Map.of("role", "assistant", "content", content), "finish_reason", "stop")),
                    "usage", Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2)));
            exchange.getResponseHeaders().add("Content-Type", fail ? "text/plain" : "application/json");
            exchange.sendResponseHeaders(fail ? failureStatus : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/v1/services/aigc/text-generation/generation", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer " + TEST_KEY);
            var request = json.readTree(exchange.getRequestBody());
            assertThat(request.path("model").asText()).isEqualTo("test-model");
            assertThat(request.path("input").path("messages").isArray()).isTrue();
            String content = json.writeValueAsString(Map.of("title", "模拟百炼结果", "overview", "原生协议测试",
                    "requirements", List.of("允许新增记录"), "openQuestions", List.of(), "assumptions", List.of()));
            if(prdResponse) content=json.writeValueAsString(mockPrd());
            if(workbenchResponse) content=json.writeValueAsString(mockWorkbench());
            byte[] body = json.writeValueAsBytes(Map.of("request_id", "synthetic-dashscope-result",
                    "output", Map.of("choices", List.of(Map.of("index", 0, "finish_reason", "stop",
                            "message", Map.of("role", "assistant", "content", content)))),
                    "usage", Map.of("input_tokens", 1, "output_tokens", 1, "total_tokens", 2)));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    /** 释放等待并停止本地平台。 */
    @AfterEach
    void stopMockPlatform() {
        if (release != null) release.countDown();
        server.stop(0);
    }

    /** 目录保存与启用分离，真实客户端使用数据库目标，回切 demo 即时生效。 */
    @Test
    void crudAndActivationUseEncryptedDatabaseProfiles() throws Exception {
        var platform = addPlatform("模拟平台", "openai-compatible", url());
        var model = addModel(platform, "模拟模型", TEST_KEY);
        assertThat(configurations.get().runtime().mode()).isEqualTo("demo");
        String publicJson = mvc.perform(get("/api/alpha/model-config")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(publicJson).doesNotContain(TEST_KEY,"apiKeyCiphertext","v1:","v2:");
        String ciphertext = new JdbcTemplate(dataSource).queryForObject("SELECT api_key_ciphertext FROM model_profiles WHERE id=?", String.class, model);
        assertThat(ciphertext).startsWith("v2:").doesNotContain(TEST_KEY);
        assertThat(new ModelSecretCipher("", KEY_FILE.toString()).decrypt(ciphertext)).isEqualTo(TEST_KEY);
        configurations.select(new SaveModelSelectionRequest(revision(),"ai",model));
        mvc.perform(get("/api/alpha/runtime")).andExpect(jsonPath("$.data.aiMode").value("openai-compatible"));
        mvc.perform(post("/api/alpha/agent/drafts").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"新增记录\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.draft.title").value("模拟平台结果"));
        configurations.saveProfile(model, request(platform,"模拟模型改名","",false));
        assertThat(configurations.activeSettings().apiKey()).isEqualTo(TEST_KEY);
        configurations.select(new SaveModelSelectionRequest(revision(),"demo",model));
        configurations.deleteProfile(model,revision());
        assertThat(configurations.get().runtime().activeModelId()).isNull();
        configurations.deletePlatform(platform,revision());
        assertThat(configurations.get().platforms()).isEmpty();
    }

    /** HTTP CRUD 真实校验请求与白名单，创建不隐式启用。 */
    @Test
    void exposesPlatformAndModelCrudEndpoints() throws Exception {
        String response=mvc.perform(post("/api/alpha/model-config/platforms").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("expectedRevision",revision(),"name","接口平台","protocol","openai-compatible","baseUrl",url()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String platform=json.readTree(response).path("data").path("platforms").get(0).path("id").asText();
        response=mvc.perform(post("/api/alpha/model-config/models").contentType(MediaType.APPLICATION_JSON).content(body(platform,"接口模型",TEST_KEY,false)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String model=json.readTree(response).path("data").path("models").get(0).path("id").asText();
        mvc.perform(put("/api/alpha/model-config/models/"+model).contentType(MediaType.APPLICATION_JSON).content(body(platform,"编辑后模型","",false)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.models[0].name").value("编辑后模型"));
        mvc.perform(delete("/api/alpha/model-config/models/"+model).param("expectedRevision",Long.toString(revision()))).andExpect(status().isOk());
        mvc.perform(delete("/api/alpha/model-config/platforms/"+platform).param("expectedRevision",Long.toString(revision()))).andExpect(status().isOk());
    }

    /** 不完整模型能保存，但不能启用；关联平台和真实启用模型不能删除。 */
    @Test
    void enforcesReferencesAndActiveCredentialRequirements() {
        String platform=addPlatform("平台", "openai-compatible",url());
        String model=addModel(platform,"模型","");
        assertThatThrownBy(() -> configurations.select(new SaveModelSelectionRequest(revision(),"ai",model))).hasMessageContaining("API Key");
        assertThatThrownBy(() -> configurations.deletePlatform(platform,revision())).hasMessageContaining("关联模型");
        configurations.saveProfile(model,request(platform,"模型",TEST_KEY,false));
        configurations.select(new SaveModelSelectionRequest(revision(),"ai",model));
        assertThatThrownBy(() -> configurations.deleteProfile(model,revision())).hasMessageContaining("正在启用");
        assertThatThrownBy(() -> configurations.saveProfile(model,request(platform,"模型","",true))).hasMessageContaining("API Key");
        assertThat(configurations.activeSettings().apiKey()).isEqualTo(TEST_KEY);
    }

    /** 修改平台目标必须先清除关联密钥，移动模型不能沿用原密钥。 */
    @Test
    void confinesCredentialsToTheirPlatformDestination() {
        String platform=addPlatform("平台甲","openai-compatible",url());
        String other=addPlatform("平台乙","openai-compatible","https://example.com/v1");
        String model=addModel(platform,"模型",TEST_KEY);
        assertThatThrownBy(() -> configurations.savePlatform(platform,new SaveModelPlatformRequest(revision(),"平台甲","openai-compatible","https://example.com/v1")))
                .hasMessageContaining("先清除");
        assertThatThrownBy(() -> configurations.saveProfile(model,request(other,"模型","",false))).hasMessageContaining("新 API Key");
        configurations.saveProfile(model,request(other,"模型","",true));
        assertThat(configurations.get().models().get(0).apiKeyConfigured()).isFalse();
        configurations.savePlatform(platform,new SaveModelPlatformRequest(revision(),"平台甲改名","openai-compatible","https://example.com/v2"));
    }

    /** 测试未保存表单，不创建主密钥文件或模型；上游错误不回显凭证。 */
    @Test
    void testsUnsavedProfileWithoutPersistingAndSanitizesFailures(CapturedOutput output) throws Exception {
        String platform=addPlatform("测试平台","openai-compatible",url());
        long before=revision();
        mvc.perform(post("/api/alpha/model-config/test").contentType(MediaType.APPLICATION_JSON).content(body(platform,"未保存模型",TEST_KEY,false)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.connected").value(true));
        assertThat(revision()).isEqualTo(before); assertThat(configurations.get().models()).isEmpty();
        fail=true;
        String response=mvc.perform(post("/api/alpha/model-config/test").contentType(MediaType.APPLICATION_JSON).content(body(platform,"未保存模型",TEST_KEY,false)))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("MODEL_CONNECTION_FAILED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("HTTP 401")))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(TEST_KEY).contains("[REDACTED]", "诊断编号");
        assertThat(output.getAll()).contains("category=AUTHENTICATION", "upstreamStatus=401").doesNotContain(TEST_KEY);
    }

    /** 真实 SDK 的 403 错误原因进入页面及日志，敏感字段和密钥不能进入任何输出。 */
    @Test
    void explainsForbiddenGatewayReasonSafely(CapturedOutput output) throws Exception {
        String platform=addPlatform("网关平台","openai-compatible",url());
        fail=true;
        failureStatus=403;
        upstreamFailureBody=json.writeValueAsString(Map.of("error",Map.of("code","model_not_allowed",
                "message","Model access denied for " + TEST_KEY),"request",Map.of("api_key",TEST_KEY)));
        String response=mvc.perform(post("/api/alpha/model-config/test").contentType(MediaType.APPLICATION_JSON).content(body(platform,"未保存模型",TEST_KEY,false)))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("MODEL_CONNECTION_FAILED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Model access denied")))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).contains("HTTP 403", "[REDACTED]", "诊断编号").doesNotContain(TEST_KEY);
        assertThat(output.getAll()).contains("upstreamCode=model_not_allowed", "upstreamReason=Model access denied for [REDACTED]", "diagnosticId=").doesNotContain(TEST_KEY);
        assertThat(configurations.get().models()).isEmpty();
    }

    /** HTTP 成功但结构化正文无效时提供准确错误，并阻止 SDK 原文日志。 */
    @Test
    void explainsInvalidStructuredOutputWithoutLoggingBody(CapturedOutput output) throws Exception {
        String platform=addPlatform("测试平台","openai-compatible",url());
        invalidResponse=true;
        mvc.perform(post("/api/alpha/model-config/test").contentType(MediaType.APPLICATION_JSON).content(body(platform,"未保存模型",TEST_KEY,false)))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("MODEL_CONNECTION_FAILED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("结构化结果")));
        assertThat(output.getOut()).contains("category=STRUCTURED_OUTPUT").doesNotContain(TEST_KEY,"upstream echoed");
        assertThat(configurations.get().models()).isEmpty();
    }

    /** 百炼原生适配不依赖平台名称；同目录也可创建多个独立模型。 */
    @Test
    void supportsNativeDashscopeAndMultipleModels() throws Exception {
        String origin="http://127.0.0.1:"+server.getAddress().getPort();
        String platform=addPlatform("我的百炼账号","dashscope",origin);
        String model=addModel(platform,"模型一",TEST_KEY);
        configurations.saveProfile(null,new SaveModelProfileRequest(revision(),platform,"模型二","test-model-second","",false));
        assertThat(configurations.get().models()).hasSize(2);
        configurations.select(new SaveModelSelectionRequest(revision(),"ai",model));
        mvc.perform(post("/api/alpha/agent/drafts").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"新增记录\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.mode").value("dashscope"))
                .andExpect(jsonPath("$.data.draft.title").value("模拟百炼结果"));
    }

    /** 损坏凭证仍可读取公开目录、停用并提供新密钥恢复。 */
    @Test
    void allowsSafeRecoveryFromBrokenCiphertext() {
        String platform=addPlatform("恢复平台","openai-compatible",url()); String model=addModel(platform,"模型",TEST_KEY);
        configurations.select(new SaveModelSelectionRequest(revision(),"ai",model));
        new JdbcTemplate(dataSource).update("UPDATE model_profiles SET api_key_ciphertext='v2:broken' WHERE id=?",model);
        assertThat(configurations.get().models().get(0).apiKeyConfigured()).isTrue();
        assertThatThrownBy(configurations::activeSettings).isInstanceOf(ModelConfigurationException.class);
        configurations.select(new SaveModelSelectionRequest(revision(),"demo",model));
        assertThat(runtime.snapshot().isDemo()).isTrue();
        configurations.saveProfile(model,request(platform,"模型",TEST_KEY,false));
        configurations.select(new SaveModelSelectionRequest(revision(),"ai",model));
        assertThat(configurations.activeSettings().apiKey()).isEqualTo(TEST_KEY);
    }

    /** 陈旧目录写入、非法地址和重复名称必须失败且不推进版本。 */
    @Test
    void rejectsStaleVersionsAndInvalidDestinations() throws Exception {
        long old=revision(); addPlatform("唯一平台","openai-compatible",url());
        mvc.perform(post("/api/alpha/model-config/platforms").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("expectedRevision",old,"name","陈旧","protocol","openai-compatible","baseUrl",url()))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MODEL_CONFIG_CONFLICT"));
        long current=revision();
        for(String invalid:List.of("javascript:alert(1)","http://example.com/v1","https://user:password@example.com/v1","https://example.com/v1?key=bad","https://example.com/v1#key","https://example.com/v1/chat/completions")) {
            assertThatThrownBy(() -> addPlatform("无效平台","openai-compatible",invalid)).isInstanceOf(ModelConfigurationException.class);
        }
        assertThatThrownBy(() -> addPlatform("唯一平台","openai-compatible",url())).hasMessageContaining("名称已存在");
        assertThat(revision()).isEqualTo(current);
    }

    /** 请求执行期间目录切换不会污染已开始请求的模式和目标。 */
    @Test
    void preservesRunningRequestSnapshotAcrossSelectionChange() throws Exception {
        String platform=addPlatform("运行平台","openai-compatible",url()); String model=addModel(platform,"模型",TEST_KEY);
        configurations.select(new SaveModelSelectionRequest(revision(),"ai",model));
        AnalysisRuntime old=runtime.snapshot(); entered=new CountDownLatch(1); release=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var running=executor.submit(() -> workflow.execute("新增记录")); assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            configurations.select(new SaveModelSelectionRequest(revision(),"demo",model));
            assertThat(runtime.snapshot().isDemo()).isTrue(); assertThat(old.mode()).isEqualTo("openai-compatible");
            release.countDown(); var result=running.get(10,TimeUnit.SECONDS);
            assertThat(result.mode()).isEqualTo("openai-compatible"); assertThat(result.prdMarkdown()).contains("AI 生成草稿").doesNotContain("未调用大模型");
        }
    }
    /** 当前目录版本用于测试独立修改。 */
    private long revision() { return configurations.get().revision(); }
    /** 创建本机模拟平台并取回稳定标识。 */
    private String addPlatform(String name,String protocol,String url) {
        return configurations.savePlatform(null,new SaveModelPlatformRequest(revision(),name,protocol,url)).platforms().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow().id();
    }
    /** 创建指定平台模型，保存不隐式启用。 */
    private String addModel(String platform,String name,String key) {
        return configurations.saveProfile(null,request(platform,name,key,false)).models().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow().id();
    }
    /** 模型测试表单只使用模拟凭证。 */
    private SaveModelProfileRequest request(String platform,String name,String key,boolean clear) { return new SaveModelProfileRequest(revision(),platform,name,"test-model",key,clear); }
    /** WRITE_ONLY 密钥按真实请求结构构造。 */
    private String body(String platform,String name,String key,boolean clear) throws Exception { return json.writeValueAsString(Map.of("expectedRevision",revision(),"platformId",platform,"name",name,"model","test-model","apiKey",key,"clearApiKey",clear)); }
    /** 本机兼容 API 基础地址。 */
    private String url() { return "http://127.0.0.1:"+server.getAddress().getPort()+"/v1"; }
    /** 两种实际模型客户端均可解析完整 PRD 和需求映射，不调用云平台。 */
    @Test void bothProtocolsParsePrdDocumentWithBaselineCoverage() {
        prdResponse=true;
        for(String protocol:List.of("openai-compatible","dashscope")) {
            String address="dashscope".equals(protocol)?"http://127.0.0.1:"+server.getAddress().getPort():url();
            String platform=addPlatform("PRD "+protocol,protocol,address);
            String model=addModel(platform,"PRD "+protocol,TEST_KEY);
            configurations.select(new SaveModelSelectionRequest(revision(),"ai",model));
            var result=runtime.snapshot().analyzer().generatePrd("已确认需求基线，需求 ID=req-mock，允许新增记录");
            assertThat(result.requirementIds()).containsExactly("req-mock");assertThat(result.unresolvedIssues()).isEmpty();assertThat(result.workflows()).isEqualTo("录入并保存");
        }
    }
    /** 模块和页面结构经两种实际 ChatClient 协议转换，保留冒号路径与待确认事项。 */
    @Test void bothProtocolsParseModulePageEdits() {
        workbenchResponse=true;
        for(String protocol:List.of("openai-compatible","dashscope")) {
            String address="dashscope".equals(protocol)?"http://127.0.0.1:"+server.getAddress().getPort():url();
            String platform=addPlatform("模块 PRD "+protocol,protocol,address);
            String model=addModel(platform,"模块 PRD "+protocol,TEST_KEY);
            configurations.select(new SaveModelSelectionRequest(revision(),"ai",model));
            var result=runtime.snapshot().analyzer().editWorkbench("为当前空 PRD 增加人员管理及列表，排序待确认");
            assertThat(result.modules().getFirst().pages().getFirst().type()).isEqualTo("LIST");
            assertThat(result.sections()).containsEntry("page:people:list:fields","人员姓名");
            assertThat(result.unresolvedIssues()).containsExactly("Q01 · 人员列表 · 默认排序是什么？");
        }
    }
    /** 本机模拟响应仅验证协议，不代表真实模型输出质量。 */
    private com._36knight.alpha.workspace.WorkbenchContracts.Reply mockWorkbench() {
        return new com._36knight.alpha.workspace.WorkbenchContracts.Reply("已整理人员列表",Map.of("page:people:list:fields","人员姓名"),List.of("Q01 · 人员列表 · 默认排序是什么？"),null,
                List.of(new com._36knight.alpha.workspace.PrdStructure.ModuleChange("people","人员管理",false,List.of(new com._36knight.alpha.workspace.PrdStructure.PageChange("list","人员列表","LIST",false)))));
    }
    /** 合成 PRD 响应只用于本地协议验证。 */
    private com._36knight.alpha.workspace.LifecycleContracts.Prd mockPrd(){return new com._36knight.alpha.workspace.LifecycleContracts.Prd("背景","目标","范围","录入并保存","规则","异常","验收","边界","排除",List.of("req-mock"),List.of());}
}
