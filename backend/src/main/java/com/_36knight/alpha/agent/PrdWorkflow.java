package com._36knight.alpha.agent;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com._36knight.alpha.workspace.LifecycleContracts.Baseline;
import com._36knight.alpha.workspace.LifecycleContracts.Prd;
import com._36knight.alpha.workspace.LifecycleContracts.Requirement;
import com._36knight.alpha.model.ModelInvocationException;
import com._36knight.alpha.workspace.PrdDocuments;
import com._36knight.alpha.workspace.PrdTemplates;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * PRD 阶段 Graph：固定确认输入、生成候选、结构校验；凭证不进入 Graph State。@author Andersen
 */
@Component
public class PrdWorkflow {
    /**
     * 编译后的无状态节点编排。
     */
    private final CompiledGraph graph;
    /**
     * 当次客户端只保留于执行私有注册表，结束即清理。
     */
    private final Map<String, AnalysisRuntime> active = new ConcurrentHashMap<>();

    /**
     * 装配基线输入、供应商调用和结构校验节点。
     */
    public PrdWorkflow(ObjectMapper json, Validator validator) throws GraphStateException {
        var workflow = new StateGraph("confirmed_prd", () -> {
            Map<String, KeyStrategy> keys = new HashMap<>();
            for (String k : List.of("baseline", "context", "runtimeId", "prd")) keys.put(k, new ReplaceStrategy());
            return keys;
        });
        workflow.addNode("prepare_baseline", node_async(state -> {
            Baseline b = state.<Baseline>value("baseline").orElseThrow();
            var context = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(b);
            context.set("templateDefinition", json.valueToTree(PrdTemplates.resolve(PrdTemplates.LEGACY_ID, PrdTemplates.LEGACY_REVISION)));
            return Map.of("context", json.writeValueAsString(context));
        }));
        workflow.addNode("generate_prd", node_async(state -> {
            var runtime = active.get(state.<String>value("runtimeId").orElseThrow());
            if (runtime == null) throw new IllegalStateException("运行配置已失效");
            Baseline b = state.<Baseline>value("baseline").orElseThrow();
            return Map.of("prd", runtime.isDemo() ? template(b) : runtime.analyzer().generatePrd(state.<String>value("context").orElseThrow()));
        }));
        workflow.addNode("validate_prd", node_async(state -> {
            Prd p = state.<Prd>value("prd").orElseThrow();
            // 旧基线生成入口固定兼容模板，模型无权选择其他模板或未发布版本。
            p = PrdDocuments.withTemplate(p, PrdTemplates.LEGACY_ID, PrdTemplates.LEGACY_REVISION);
            if (!validator.validate(p).isEmpty()) throw ModelInvocationException.invalidOutput();
            try {
                PrdDocuments.validateStructure(p);
            } catch (IllegalArgumentException exception) {
                throw ModelInvocationException.invalidOutput();
            }
            return Map.of("prd", p);
        }));
        workflow.addEdge(StateGraph.START, "prepare_baseline");
        workflow.addEdge("prepare_baseline", "generate_prd");
        workflow.addEdge("generate_prd", "validate_prd");
        workflow.addEdge("validate_prd", StateGraph.END);
        graph = workflow.compile();
    }

    /**
     * 执行独立候选生成；模型调用期间不持有数据库事务。
     */
    public Prd execute(Baseline baseline, AnalysisRuntime runtime) {
        String id = UUID.randomUUID().toString();
        active.put(id, runtime);
        try {
            return graph.invoke(Map.of("baseline", baseline, "runtimeId", id)).orElseThrow().<Prd>value("prd").orElseThrow();
        } finally {
            active.remove(id);
        }
    }

    /**
     * 联调模板仅复制已确认事实，流程和边界明确需人工补充。
     */
    private Prd template(Baseline b) {
        String requirements = b.review().requirements().stream().map(r -> r.title() + "：" + r.description()).reduce("", (a, c) -> a + c + "\n");
        String acceptance = b.review().requirements().stream().map(r -> r.title() + "：" + r.acceptance()).reduce("", (a, c) -> a + c + "\n");
        return new Prd("【本地模板联调，未调用大模型】\n" + b.scope(), b.goal(), requirements,
            "待人工补充并核实业务流程。", requirements, "待人工补充异常路径，不推断错误处理规则。", acceptance,
            "待人工明确性能、安全及其他非功能边界。", "仅包含已确认范围；其他能力需另行评审。",
            b.review().requirements().stream().map(Requirement::id).toList(), List.of("明确业务流程", "明确异常处理路径", "明确非功能边界与排除范围"));
    }
}
