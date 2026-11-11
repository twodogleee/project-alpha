package com._36knight.alpha.agent;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com._36knight.alpha.dto.RequirementDraft;
import com._36knight.alpha.exception.AgentExecutionException;
import com._36knight.alpha.service.PrdRenderer;
import com._36knight.alpha.vo.RequirementDraftResponse;
import jakarta.validation.Validator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 使用真实 Spring AI Alibaba Graph 执行需求归一化、分析和 PRD 渲染。
 * 初始化阶段每次请求独立执行，不声称支持持久化访谈或暂停恢复。
 *
 * @author Andersen
 */
@Component
public class RequirementWorkflow {
    /**
     * 已接通的节点顺序，用于前端显示流程和交付来源。
     */
    public static final List<String> STAGES = List.of("normalize_input", "analyze_requirement", "render_prd");
    /**
     * 已编译的无会话工作流，运行数据只存于各自的 State。
     */
    private final CompiledGraph graph;
    /**
     * 每个请求开始时取得配置快照，分析与来源渲染使用同一副本。
     */
    private final AnalysisRuntimeProvider runtimeProvider;
    /**
     * 客户端留在请求私有注册表，Graph State 只携带随机标识与公开模式，结束后移除。
     */
    private final Map<String, AnalysisRuntime> activeRuns = new ConcurrentHashMap<>();

    /**
     * 装配三个节点；结构化结果通过校验后才能进入文档渲染。
     */
    public RequirementWorkflow(AnalysisRuntimeProvider runtimeProvider, PrdRenderer renderer,
                               Validator validator) throws GraphStateException {
        this.runtimeProvider = runtimeProvider;
        StateGraph workflow = new StateGraph("requirement_draft", () -> {
            Map<String, KeyStrategy> strategies = new HashMap<>();
            for (String key : List.of("content", "normalizedContent", "draft", "prdMarkdown", "runtimeId", "mode")) {
                strategies.put(key, new ReplaceStrategy());
            }
            return strategies;
        });
        workflow.addNode(STAGES.get(0), node_async(state -> Map.of("normalizedContent",
            state.<String>value("content").orElseThrow().strip())));
        workflow.addNode(STAGES.get(1), node_async(state -> {
            AnalysisRuntime runtime = activeRuns.get(state.<String>value("runtimeId").orElseThrow());
            if (runtime == null) throw new IllegalStateException("请求运行上下文已失效");
            RequirementDraft draft = runtime.analyzer().analyze(state.<String>value("normalizedContent").orElseThrow());
            if (draft == null || !validator.validate(draft).isEmpty()) {
                throw new IllegalStateException("需求分析结果未通过结构校验");
            }
            return Map.of("draft", draft);
        }));
        workflow.addNode(STAGES.get(2), node_async(state -> Map.of("prdMarkdown",
            renderer.render(state.<RequirementDraft>value("draft").orElseThrow(),
                "demo".equals(state.<String>value("mode").orElseThrow())))));
        workflow.addEdge(StateGraph.START, STAGES.get(0));
        workflow.addEdge(STAGES.get(0), STAGES.get(1));
        workflow.addEdge(STAGES.get(1), STAGES.get(2));
        workflow.addEdge(STAGES.get(2), StateGraph.END);
        this.graph = workflow.compile();
    }

    /**
     * 独立运行一次工作流，失败时不会用模板冒充成功的模型结果。
     */
    public RequirementDraftResponse execute(String content) {
        String runId = UUID.randomUUID().toString();
        try {
            AnalysisRuntime runtime = runtimeProvider.snapshot();
            activeRuns.put(runId, runtime);
            OverAllState state = graph.invoke(Map.of("content", content, "runtimeId", runId, "mode", runtime.mode())).orElseThrow();
            return new RequirementDraftResponse(runId, runtime.mode(),
                state.<RequirementDraft>value("draft").orElseThrow(),
                state.<String>value("prdMarkdown").orElseThrow(), STAGES);
        } catch (RuntimeException exception) {
            throw new AgentExecutionException("需求工作流执行失败", exception);
        } finally {
            activeRuns.remove(runId);
        }
    }
}
