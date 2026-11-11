package com._36knight.alpha;

import com._36knight.alpha.agent.RequirementAnalyzer;
import com._36knight.alpha.agent.RequirementWorkflow;
import com._36knight.alpha.agent.AnalysisRuntime;
import com._36knight.alpha.dto.RequirementDraft;
import com._36knight.alpha.exception.AgentExecutionException;
import com._36knight.alpha.service.PrdRenderer;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证模型失败与不完整结构都不能被文档渲染掩盖。 */
class RequirementWorkflowTest {
    /** 模型异常必须保持失败，不生成伪成功的模板草稿。 */
    @Test
    void propagatesModelFailure() throws Exception {
        RequirementAnalyzer analyzer = content -> { throw new IllegalStateException("upstream failed"); };
        assertFailure(analyzer);
    }

    /** 无结构化结果时阻止后续 PRD 渲染。 */
    @Test
    void rejectsMissingModelResult() throws Exception {
        assertFailure(content -> null);
    }

    /** 缺少必填清单的模型结果不能进入交付。 */
    @Test
    void rejectsIncompleteModelResult() throws Exception {
        assertFailure(content -> new RequirementDraft("title", "overview", null, null, null));
    }

    /** 在真实 Graph 中运行指定分析器并断言失败边界。 */
    private void assertFailure(RequirementAnalyzer analyzer) throws Exception {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var workflow = new RequirementWorkflow(() -> new AnalysisRuntime("dashscope", "qwen-plus", analyzer),
                    new PrdRenderer(), factory.getValidator());
            assertThatThrownBy(() -> workflow.execute("客户管理"))
                    .isInstanceOf(AgentExecutionException.class);
        }
    }
}
