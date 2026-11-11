package com._36knight.alpha;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证真实 Graph、HTTP 校验及无密钥启动的联调契约。 */
@SpringBootTest(properties = {"alpha.ai.mode=demo", "alpha.ai.api-key="})
@AutoConfigureMockMvc
class AgentApiIntegrationTest {
    /** 测试运行的 HTTP 接口。 */
    @Autowired
    private MockMvc mvc;
    /** 与应用一致的 JSON 序列化器。 */
    @Autowired
    private ObjectMapper objectMapper;

    /** 运行信息不泄漏密钥，且明确标注本地模板来源。 */
    @Test
    void exposesRuntimeWithoutCredentials() throws Exception {
        String response = mvc.perform(get("/api/alpha/runtime")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.aiMode").value("demo"))
                .andExpect(jsonPath("$.data.model").value("local-template"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("apiKey", "api-key", "DASHSCOPE_API_KEY");
    }

    /** 连续两次执行产生独立结果，原始输入不会跨请求泄漏。 */
    @Test
    void keepsIndependentGraphRunsAndUnconfirmedRules() throws Exception {
        String first = submit("客户模块需要分配销售");
        String second = submit("库存模块需要登记入库");
        assertThat(second).contains("库存模块需要登记入库").doesNotContain("客户模块需要分配销售");
        assertThat(objectMapper.readTree(first).at("/data/runId").asText())
                .isNotEqualTo(objectMapper.readTree(second).at("/data/runId").asText());
        assertThat(objectMapper.readTree(second).at("/data/prdMarkdown").asText())
                .contains("未调用大模型", "待确认业务规则后补充");
    }

    /** 空白、缺失和超限需求不能进入模型或工作流。 */
    @Test
    void rejectsInvalidRequirementBodies() throws Exception {
        for (String body : new String[]{"{}", "{\"content\":\"  \\n  \"}",
                objectMapper.writeValueAsString(Map.of("content", "需".repeat(12001)))}) {
            mvc.perform(post("/api/alpha/agent/drafts").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
    }

    /** 无效 JSON 返回契约化错误，不包含解析器或请求正文。 */
    @Test
    void rejectsMalformedJson() throws Exception {
        mvc.perform(post("/api/alpha/agent/drafts").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_JSON"));
    }

    /** 输入上限以内的长需求可以完成联调，不被内部结构校验误拒绝。 */
    @Test
    void acceptsMaximumLengthRequirement() throws Exception {
        submit("需".repeat(12000));
    }

    /** 提交需求并确认分析与渲染节点均已完成。 */
    private String submit(String content) throws Exception {
        return mvc.perform(post("/api/alpha/agent/drafts").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", content))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.completedStages.length()").value(3))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
