package com._36knight.alpha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 验证创建、人工评审、来源隔离、历史和冲突的 HTTP 契约。
 * @author Andersen
 */
@SpringBootTest(properties = {"alpha.ai.mode=demo", "alpha.ai.api-key=", "spring.datasource.dynamic.datasource.master.url=jdbc:h2:mem:project-api;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class ProjectApiIntegrationTest {
    /** 在真实 Spring 上下文中运行接口，不调用付费模型。 */
    @Autowired private MockMvc mvc;
    /** 保持与应用一致的快照解析方式。 */
    @Autowired private ObjectMapper mapper;

    /** 保存确认与澄清回答后重开，历史版本保持未评审内容。 */
    @Test
    void createsReviewsReopensAndExportsImmutableVersions() throws Exception {
        JsonNode created = create("客户管理", "运营可以新增客户");
        String id = created.path("id").asText();
        String requirementId = created.at("/requirements/0/id").asText();
        assertThat(created.path("revision").asInt()).isEqualTo(1);
        assertThat(created.at("/requirements/0/status").asText()).isEqualTo("PENDING");
        assertThat(created.path("prdMarkdown").asText()).contains("未调用大模型", "待确认需求");
        ObjectNode edit = payload(created);
        ((ObjectNode) edit.at("/requirements/0")).put("status", "CONFIRMED");
        ((ObjectNode) edit.at("/questions/0")).put("answer", "仅运营可以新增客户");
        edit.withArray("requirements").addObject().putNull("id").put("text", "销售可以登记跟进")
                .put("status", "PENDING").put("source", "GENERATED"); // 客户端来源不得生效。
        JsonNode saved = save(id, edit);
        assertThat(saved.path("revision").asInt()).isEqualTo(2);
        assertThat(saved.at("/requirements/0/id").asText()).isEqualTo(requirementId);
        assertThat(saved.at("/requirements/1/source").asText()).isEqualTo("USER");
        assertThat(saved.path("prdMarkdown").asText()).contains("已确认需求", "仅运营可以新增客户", "人工新增");
        assertThat(data(mvc.perform(get("/api/alpha/projects/{id}", id)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString())).isEqualTo(saved);
        JsonNode historical = data(mvc.perform(get("/api/alpha/projects/{id}/versions/1", id)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(historical).isEqualTo(created);
        mvc.perform(get("/api/alpha/projects/{id}/versions", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].revision").value(2)).andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get("/api/alpha/projects")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == '" + id + "')].name").value("客户管理"));
    }

    /** 改写已确认正文不能同时沿用确认，后续明确确认才生效。 */
    @Test
    void changingConfirmedTextRequiresAnotherConfirmation() throws Exception {
        JsonNode created = create("库存", "登记入库");
        String id = created.path("id").asText();
        ObjectNode edit = payload(created);
        ((ObjectNode) edit.at("/requirements/0")).put("status", "CONFIRMED");
        JsonNode confirmed = save(id, edit);
        edit = payload(confirmed);
        ((ObjectNode) edit.at("/requirements/0")).put("text", "登记入库并记录操作人");
        JsonNode changed = save(id, edit);
        assertThat(changed.at("/requirements/0/status").asText()).isEqualTo("PENDING");
        edit = payload(changed);
        ((ObjectNode) edit.at("/requirements/0")).put("status", "CONFIRMED");
        assertThat(save(id, edit).at("/requirements/0/status").asText()).isEqualTo("CONFIRMED");
    }

    /** 旧 revision 返回 409，当前结果和版本总数不改变。 */
    @Test
    void rejectsStaleSaveWithoutOverwritingOrAppendingHistory() throws Exception {
        JsonNode created = create("审批", "提交审批");
        String id = created.path("id").asText();
        ObjectNode first = payload(created);
        first.put("name", "新名称");
        JsonNode saved = save(id, first);
        ObjectNode stale = payload(created);
        stale.put("name", "旧窗口名称");
        mvc.perform(put("/api/alpha/projects/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(stale))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT")).andExpect(jsonPath("$.data").isEmpty());
        assertThat(data(mvc.perform(get("/api/alpha/projects/{id}", id)).andReturn().getResponse().getContentAsString())).isEqualTo(saved);
        mvc.perform(get("/api/alpha/projects/{id}/versions", id)).andExpect(jsonPath("$.data.length()").value(2));
    }

    /** 跨项目 ID、重复项和移除澄清问题均不进入存储。 */
    @Test
    void rejectsForgedDuplicateAndMissingIds() throws Exception {
        JsonNode first = create("项目甲", "功能甲");
        JsonNode second = create("项目乙", "功能乙");
        ObjectNode edit = payload(first);
        ((ObjectNode) edit.at("/requirements/0")).put("id", second.at("/requirements/0/id").asText());
        invalid(first, edit);
        edit = payload(first);
        edit.withArray("requirements").add(edit.at("/requirements/0").deepCopy());
        invalid(first, edit);
        edit = payload(first);
        edit.withArray("questions").remove(0);
        invalid(first, edit);
        mvc.perform(get("/api/alpha/projects/{id}/versions", first.path("id").asText()))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    /** 普通需求可全部删除，文档仍明确区分空清单与未回答问题。 */
    @Test
    void supportsRemovingOrdinaryRequirements() throws Exception {
        JsonNode created = create("范围收缩", "一个功能");
        ObjectNode edit = payload(created);
        edit.withArray("requirements").removeAll();
        JsonNode saved = save(created.path("id").asText(), edit);
        assertThat(saved.path("requirements").isEmpty()).isTrue();
        assertThat(saved.path("prdMarkdown").asText()).contains("暂无。", "待回答");
    }

    /** 校验字段与枚举，未知项目/版本均使用安全错误。 */
    @Test
    void exposesValidationNotFoundAndJsonErrorContracts() throws Exception {
        mvc.perform(post("/api/alpha/projects").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" \",\"content\":\"需求\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(get("/api/alpha/projects/missing")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
        JsonNode created = create("边界", "需求");
        String id = created.path("id").asText();
        mvc.perform(get("/api/alpha/projects/{id}/versions/99", id)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VERSION_NOT_FOUND"));
        mvc.perform(get("/api/alpha/projects/{id}/versions/not-a-number", id)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        ObjectNode edit = payload(created);
        ((ObjectNode) edit.at("/requirements/0")).put("status", "UNKNOWN");
        mvc.perform(put("/api/alpha/projects/{id}", id).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(edit)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_JSON"));
        edit = payload(created);
        ((ObjectNode) edit.at("/requirements/0")).put("status", 1);
        mvc.perform(put("/api/alpha/projects/{id}", id).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(edit)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_JSON"));
        edit = payload(created);
        ((ObjectNode) edit.at("/requirements/0")).putNull("text");
        invalid(created, edit);
    }

    /** 创建模板项目，读取接口的数据部分。 */
    private JsonNode create(String name, String content) throws Exception {
        ObjectNode request = mapper.createObjectNode().put("name", name).put("content", content);
        return data(mvc.perform(post("/api/alpha/projects").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    /** 按公共编辑契约构造请求，不写来源字段。 */
    private ObjectNode payload(JsonNode document) {
        ObjectNode edit = mapper.createObjectNode().put("expectedRevision", document.path("revision").asLong());
        for (String field : new String[]{"name", "title", "overview", "requirements", "suggestions"}) edit.set(field, document.path(field).deepCopy());
        var questions = edit.putArray("questions");
        document.path("questions").forEach(item -> questions.addObject().put("id", item.path("id").asText()).put("answer", item.path("answer").asText()));
        return edit;
    }

    /** 保存成功时返回完整业务版本。 */
    private JsonNode save(String id, ObjectNode edit) throws Exception {
        return data(mvc.perform(put("/api/alpha/projects/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(edit))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    /** 验证不合法请求被拒绝。 */
    private void invalid(JsonNode document, ObjectNode edit) throws Exception {
        mvc.perform(put("/api/alpha/projects/{id}", document.path("id").asText()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(edit))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    /** 解包统一响应。 */
    private JsonNode data(String response) throws Exception { return mapper.readTree(response).path("data"); }
}
