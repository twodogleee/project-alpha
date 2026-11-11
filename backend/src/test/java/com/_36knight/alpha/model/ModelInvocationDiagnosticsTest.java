package com._36knight.alpha.model;

import com._36knight.alpha.agent.RequirementAnalyzer;
import com._36knight.alpha.dto.RequirementDraft;
import com._36knight.alpha.exception.TaskFailureDiagnostics;
import com._36knight.alpha.workspace.LifecycleContracts.Prd;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证各模型调用边界、Graph 包装后的诊断关联及日志脱敏。@author Andersen */
@ExtendWith(OutputCaptureExtension.class)
class ModelInvocationDiagnosticsTest {
    /** 合成密钥仅用于证明错误回显不会泄漏。 */
    private static final String KEY = "synthetic-private-api-key";

    /** PRD、CHAT、DRAFT 均从同一个安全边界抛出可分类的异常。 */
    @Test void allModelOperationsRedactBeforeLeavingClient(CapturedOutput output) {
        var analyzer = new DiagnosticRequirementAnalyzer(new RequirementAnalyzer() {
            /** 模拟上游拒绝需求分析。 */
            public RequirementDraft analyze(String text) { throw rejected(); }
            /** 模拟上游拒绝会话请求。 */
            public String converse(String text) { throw rejected(); }
            /** 模拟上游拒绝 PRD。 */
            public Prd generatePrd(String text) { throw rejected(); }
        }, KEY);
        for (Supplier<?> call : java.util.List.<Supplier<?>>of(() -> analyzer.analyze("需求"),
                () -> analyzer.converse("会话"), () -> analyzer.generatePrd("基线"))) {
            var failure = assertThrows(ModelInvocationException.class, call::get);
            assertThat(failure.category()).isEqualTo("PERMISSION");
            assertThat(failure.upstreamStatus()).isEqualTo(403);
            assertThat(failure.getCause()).isNull();
            String message = TaskFailureDiagnostics.report(context(), System.nanoTime(), new IllegalStateException("unsafe-graph-wrapper", failure));
            assertThat(message).contains("HTTP 403", "模型未授权", "诊断编号").doesNotContain(KEY, "unsafe-graph-wrapper");
        }
        assertThat(output.getAll()).contains("runId=run-test", "versionId=version-test", "baselineId=baseline-test",
                "category=PERMISSION", "upstreamStatus=403", "upstreamCode=model_denied", "locations=")
                .doesNotContain(KEY, "unsafe-graph-wrapper", "\"error\":");
    }

    /** 未识别异常也记录可定位代码位置，不打印任意异常正文。 */
    @Test void unknownFailureKeepsLocationsWithoutRawMessages(CapturedOutput output) {
        var original = new IllegalArgumentException("private-user-input " + KEY);
        String message = TaskFailureDiagnostics.report(context(), System.nanoTime(), original);
        assertThat(message).contains("诊断编号").doesNotContain(KEY, "private-user-input");
        assertThat(output.getAll()).contains("category=UNKNOWN", "IllegalArgumentException", "ModelInvocationDiagnosticsTest.java")
                .doesNotContain(KEY, "private-user-input");
    }

    /** 非空但结构不合法的结果使用固定分类，不输出模型正文。 */
    @Test void invalidOutputUsesStructuredCategory(CapturedOutput output) {
        TaskFailureDiagnostics.report(context(), System.nanoTime(), ModelInvocationException.invalidOutput());
        assertThat(output.getAll()).contains("category=STRUCTURED_OUTPUT", "InvalidStructuredOutput");
    }

    /** 测试业务标识与实际输入无关。 */
    private TaskFailureDiagnostics.Context context() {
        return new TaskFailureDiagnostics.Context("PRD", "run-test", null, null, "version-test", "baseline-test");
    }
    /** 上游故意回显已知凭证和多行原因，脱敏后只能保留安全摘要。 */
    private NonTransientAiException rejected() {
        return new NonTransientAiException("403 - {\"error\":{\"code\":\"model_denied\",\"message\":\"模型未授权 " + KEY + "\"}}");
    }
}
