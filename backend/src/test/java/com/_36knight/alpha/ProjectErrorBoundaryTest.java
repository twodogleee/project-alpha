package com._36knight.alpha;

import com._36knight.alpha.agent.RequirementWorkflow;
import com._36knight.alpha.exception.AgentExecutionException;
import com._36knight.alpha.exception.ApiExceptionHandler;
import com._36knight.alpha.project.*;
import com._36knight.alpha.service.PrdRenderer;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 验证模型失败不写入项目，以及 SQL/上游异常不进入 HTTP 响应。
 * @author Andersen
 */
class ProjectErrorBoundaryTest {
    /** 模型创建失败时不得保存一个没有实际结果的项目。 */
    @Test
    void failedAnalysisDoesNotWriteProject() {
        var repository = mock(ProjectRepository.class);
        var workflow = mock(RequirementWorkflow.class);
        when(workflow.execute("需求")).thenThrow(new AgentExecutionException("失败", new IllegalStateException("upstream-data")));
        var service = new ProjectService(repository, workflow, new PrdRenderer());
        assertThatThrownBy(() -> service.create(new CreateProjectRequest("项目", "需求")))
                .isInstanceOf(AgentExecutionException.class);
        verifyNoInteractions(repository);
    }

    /** HTTP 边界不能泄漏 SQL、凭证占位或上游正文。 */
    @Test
    void storageAndModelFailuresHaveSafeHttpResponses() throws Exception {
        var service = mock(ProjectService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ProjectController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        when(service.get("project-id")).thenThrow(new DataAccessResourceFailureException("private-sql-and-content"));
        String storage = mvc.perform(get("/api/alpha/projects/project-id")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE")).andReturn().getResponse().getContentAsString();
        assertThat(storage).doesNotContain("private-sql-and-content");
        when(service.create(any())).thenThrow(new AgentExecutionException("private-upstream", new IllegalStateException("credential-placeholder")));
        String model = mvc.perform(post("/api/alpha/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"项目\",\"content\":\"需求\"}")).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("AGENT_EXECUTION_FAILED")).andReturn().getResponse().getContentAsString();
        assertThat(model).doesNotContain("private-upstream", "credential-placeholder");
    }

    /** 未预期异常也有可检索编号，HTTP 不暴露任意异常正文。 */
    @Test void unexpectedFailuresHaveDiagnosticReference() throws Exception {
        var service = mock(ProjectService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ProjectController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        when(service.get("project-id")).thenThrow(new IllegalStateException("private-request-and-key"));
        String response = mvc.perform(get("/api/alpha/projects/project-id")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andReturn().getResponse().getContentAsString();
        assertThat(response).contains("诊断编号").doesNotContain("private-request-and-key");
    }
}
