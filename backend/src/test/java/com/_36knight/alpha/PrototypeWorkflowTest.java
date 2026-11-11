package com._36knight.alpha;

import com._36knight.alpha.prototype.PrototypeContracts;
import com._36knight.alpha.prototype.PrototypeBuildException;
import com._36knight.alpha.prototype.PrototypeGenerationException;
import com._36knight.alpha.prototype.PrototypeModelProvider;
import com._36knight.alpha.prototype.PrototypeOutputValidator;
import com._36knight.alpha.prototype.PrototypeWorkflow;
import com._36knight.alpha.workspace.LifecycleContracts.Prd;
import com._36knight.alpha.workspace.PrdDocuments;
import com._36knight.alpha.workspace.PrdStructure;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 使用显式合成模型响应校验Graph与不可信输出边界，不代表真实模型调用。@author Andersen */
class PrototypeWorkflowTest {
    /** 与实际业务相同的记录与时间编码模块。 */
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    /** 每个测试使用独立严格解析器。 */
    private final PrototypeOutputValidator validator = new PrototypeOutputValidator(json);

    /** Graph只有计划和文件两次调用，并且传递冻结数据而不泄漏任务令牌。 */
    @Test void plansThenGeneratesWithFrozenScopeAndNoExecutionToken() throws Exception {
        var session = mock(PrototypeModelProvider.Session.class);
        List<String> prompts = new ArrayList<>();
        when(session.generate(anyString(), anyString())).thenAnswer(invocation -> {
            prompts.add(invocation.getArgument(1));
            return prompts.size() == 1 ? plan() : output();
        });
        List<String> phases = new ArrayList<>();
        var workflow = new PrototypeWorkflow(json, validator);
        var result = workflow.execute(input(), session, phases::add);
        assertThat(result.files()).hasSize(2);
        assertThat(phases).containsExactly("PLANNING", "GENERATING");
        assertThat(prompts).allSatisfy(prompt -> {
            assertThat(prompt).contains("people", "orders", "原型背景", "当前模型任务需求");
            assertThat(prompt).doesNotContain("never-send-execution-token", "run-sensitive-id");
        });
        assertThat(result.plan().path("pages")).hasSize(2);
        assertThat(result.cases()).isEmpty();
    }

    /** 同名页面分属不同模块，不能省略一个身份或添加范围外页面。 */
    @Test void refusesMissingDuplicateAndForeignPageIdentities() throws Exception {
        var scope = input().execution().source().scope();
        var plan = json.readTree(plan());
        ((com.fasterxml.jackson.databind.node.ArrayNode) plan.path("pages")).remove(1);
        assertThatThrownBy(() -> validator.plan(plan.toString(), scope)).isInstanceOf(PrototypeGenerationException.class);
        assertThatThrownBy(() -> validator.plan(plan().replace("\"orders\"", "\"people\""), scope)).isInstanceOf(PrototypeGenerationException.class);
        assertThatThrownBy(() -> validator.plan(plan().replace("\"orders\"", "\"outside\""), scope)).isInstanceOf(PrototypeGenerationException.class);
    }

    /** 严格JSON解析不能从重复字段、尾随数据和自然语言中推断一份有效结果。 */
    @Test void rejectsDuplicateKeysTrailingDataAndMixedProse() {
        var scope = input().execution().source().scope();
        assertThatThrownBy(() -> validator.plan("{\"pages\":[],\"pages\":[]}", scope)).isInstanceOf(PrototypeGenerationException.class);
        assertThatThrownBy(() -> validator.plan(plan() + " {}", scope)).isInstanceOf(PrototypeGenerationException.class);
        assertThatThrownBy(() -> validator.plan("输出如下：" + plan(), scope)).isInstanceOf(PrototypeGenerationException.class);
        assertThat(validator.plan("```json\n" + plan() + "\n```", scope).path("pages")).hasSize(2);
    }

    /** 动态详情必须给真实示例路径，不能让自动跳转冒充详情验证成功。 */
    @Test void enforcesDynamicRouteSampleAndStaticMenu() {
        var scope = input().execution().source().scope();
        String dynamic = plan().replace("\"path\":\"/orders\"", "\"path\":\"/orders/:id\",\"menu\":false,\"testPath\":\"/orders/order-001\"");
        assertThat(validator.plan(dynamic, scope).path("pages")).hasSize(2);
        assertThatThrownBy(() -> validator.plan(dynamic.replace("\"/orders/order-001\"", "\"/other/one\""), scope)).isInstanceOf(PrototypeGenerationException.class);
        assertThatThrownBy(() -> validator.plan(dynamic.replace("\"menu\":false", "\"menu\":true"), scope)).isInstanceOf(PrototypeGenerationException.class);
        assertThatThrownBy(() -> validator.plan(plan().replace("\"/orders\"", "\"https://example.test\""), scope)).isInstanceOf(PrototypeGenerationException.class);
    }

    /** 文件白名单、大小写重复和缺失组件会在构建前被拒绝。 */
    @Test void rejectsHostPathsProtectedFilesDuplicateFilesAndMissingComponent() throws Exception {
        JsonNode plan = validator.plan(plan(), input().execution().source().scope());
        assertThatThrownBy(() -> validator.output(output().replace("src/features/generated/People.vue", "../../secret.vue"), plan)).isInstanceOf(PrototypeGenerationException.class);
        assertThatThrownBy(() -> validator.output(output().replace("src/features/generated/People.vue", "src/platform/routes.js"), plan)).isInstanceOf(PrototypeGenerationException.class);
        assertThatThrownBy(() -> validator.output(output().replace("src/features/generated/Orders.vue", "src/features/generated/people.vue"), plan)).isInstanceOf(PrototypeGenerationException.class);
        assertThatThrownBy(() -> validator.output(output().replace("src/features/generated/Orders.vue", "src/features/generated/Other.vue"), plan)).isInstanceOf(PrototypeGenerationException.class);
        var oversized = json.readTree(output());
        ((com.fasterxml.jackson.databind.node.ObjectNode) oversized.path("files").get(0)).put("content", "中".repeat(90000));
        assertThatThrownBy(() -> validator.output(oversized.toString(), plan)).isInstanceOf(PrototypeGenerationException.class);
    }

    /** 用例只能声明固定动作和data-testid，填空值仍然是有效操作。 */
    @Test void restrictsCasesToKnownRoutesAndActionsWhilePreservingEmptyFill() {
        JsonNode plan = validator.plan(plan(), input().execution().source().scope());
        String valid = output().replace("\"cases\":[]", "\"cases\":[{\"name\":\"清空\",\"path\":\"/people\",\"steps\":[{\"action\":\"fill\",\"testId\":\"query-name\",\"value\":\"\"}]}]");
        assertThat(validator.output(valid, plan).cases()).hasSize(1);
        assertThatThrownBy(() -> validator.output(valid.replace("\"fill\"", "\"evaluate\""), plan)).isInstanceOf(PrototypeGenerationException.class);
        assertThatThrownBy(() -> validator.output(valid.replace("\"query-name\"", "\"input > button\""), plan)).isInstanceOf(PrototypeGenerationException.class);
        assertThatThrownBy(() -> validator.output(valid.replace("\"path\":\"/people\"", "\"path\":\"/outside\""), plan)).isInstanceOf(PrototypeGenerationException.class);
        assertThatThrownBy(() -> validator.output(output().replace("\"cases\":[]", "\"cases\":[],\"command\":\"npm install\""), plan)).isInstanceOf(PrototypeGenerationException.class);
    }

    /** 修复沿用已校验计划和上一轮完整文件，修复函数自身不循环。 */
    @Test void repairKeepsPlanAndPassesOnlyBoundedDiagnostic() throws Exception {
        var session = mock(PrototypeModelProvider.Session.class);
        List<String> prompts = new ArrayList<>();
        when(session.generate(anyString(), anyString())).thenAnswer(invocation -> { prompts.add(invocation.getArgument(1)); return output(); });
        var workflow = new PrototypeWorkflow(json, validator);
        JsonNode plan = validator.plan(plan(), input().execution().source().scope());
        var previous = validator.output(output(), plan);
        var repaired = workflow.repair(input(), session, previous, "编译错误".repeat(4000));
        assertThat(repaired.plan()).isEqualTo(previous.plan());
        assertThat(prompts).hasSize(1);
        JsonNode prompt = json.readTree(prompts.getFirst());
        assertThat(prompt.path("platformDiagnostic").asText()).hasSize(12000);
        assertThat(prompt.path("baseFiles")).hasSize(2);
        verify(session).generate(anyString(), anyString());
    }

    /** 无有效候选的格式错误仍可按同一外部预算重新计划，错误类型不被Graph包装吞掉。 */
    @Test void invalidFirstOutputRemainsRepairableWithoutHiddenRetries() throws Exception {
        var session = mock(PrototypeModelProvider.Session.class);
        when(session.generate(anyString(), anyString())).thenReturn("invalid", plan(), output());
        var workflow = new PrototypeWorkflow(json, validator);
        assertThatThrownBy(() -> workflow.execute(input(), session, phase -> { })).isInstanceOf(PrototypeGenerationException.class);
        var result = workflow.repair(input(), session, null, "模型必须返回合法 JSON");
        assertThat(result.files()).hasSize(2);
    }

    /** 阶段推进丢失租约时，Graph 包装后仍返回原中断分类，不继续发起后续模型调用。 */
    @ParameterizedTest
    @ValueSource(strings = {"PLANNING", "GENERATING"})
    void preservesInterruptedBuildExceptionFromPhaseCallback(String interruptedPhase) throws Exception {
        var session = mock(PrototypeModelProvider.Session.class);
        when(session.generate(anyString(), anyString())).thenReturn(plan(), output());
        var workflow = new PrototypeWorkflow(json, validator);
        var interrupted = new PrototypeBuildException("PROTOTYPE_INTERRUPTED", "合成租约已失效");
        assertThatThrownBy(() -> workflow.execute(input(), session, phase -> {
            if (interruptedPhase.equals(phase)) throw interrupted;
        })).isSameAs(interrupted);
        verify(session, times("PLANNING".equals(interruptedPhase) ? 0 : 1)).generate(anyString(), anyString());
    }

    /** 两模块故意复用list，用真实已有PRD结构验证复合页面身份。 */
    private PrototypeWorkflow.Input input() {
        Prd prd = PrdDocuments.withModules(new Prd("原型背景", "目标", "范围", "流程", "规则", "异常", "验收", "质量", "排除", List.of(), List.of()), List.of(
            new PrdStructure.Module("people", "人员", Map.of(), List.of(new PrdStructure.Page("list", "列表", "LIST", Map.of("fields", "姓名")))),
            new PrdStructure.Module("orders", "订单", Map.of(), List.of(new PrdStructure.Page("list", "列表", "LIST", Map.of("fields", "编号"))))));
        var scope = new PrototypeContracts.Scope("ALL", List.of(new PrototypeContracts.PageRef("people", "list"), new PrototypeContracts.PageRef("orders", "list")));
        var source = new PrototypeContracts.SourceView("source", "DRAFT", 1, null, "a".repeat(64), scope, prd, OffsetDateTime.now());
        var execution = new PrototypeContracts.Execution("run-sensitive-id", "never-send-execution-token", source, "{\"background\":\"固定背景\"}", null, new PrototypeContracts.Target("PROTOTYPE", null, null), "当前模型任务需求");
        return new PrototypeWorkflow.Input(execution, "GENERATE", List.of(), null, "固定组件约定");
    }

    /** 合成计划只用于结构校验，不声称页面已执行。 */
    private String plan() {
        return """
            {"pages":[{"moduleId":"people","pageId":"list","title":"人员","path":"/people","component":"src/features/generated/People.vue"},{"moduleId":"orders","pageId":"list","title":"订单","path":"/orders","component":"src/features/generated/Orders.vue"}],"entities":[],"impact":[],"gaps":[]}
            """;
    }

    /** 合成源码不执行，用于核验全量文件和修复传参。 */
    private String output() {
        return """
            {"files":[{"path":"src/features/generated/People.vue","content":"<template><div>人员</div></template>"},{"path":"src/features/generated/Orders.vue","content":"<template><div>订单</div></template>"}],"cases":[],"changeSummary":"建立两个页面"}
            """;
    }
}
