package com._36knight.alpha.agent;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com._36knight.alpha.model.ModelInvocationException;
import com._36knight.alpha.workspace.WorkbenchContracts.Context;
import com._36knight.alpha.workspace.WorkbenchContracts.Reply;
import com._36knight.alpha.workspace.PrdDocuments;
import com._36knight.alpha.template.PrdTemplateService;
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
 * 会话 Graph 固定编辑对象、生成结构化改动并校验；写入和确认由业务服务负责。@author Andersen
 */
@Component
public class WorkbenchWorkflow {
    /**
     * 只解析任务固定的已发布模板版本。
     */
    private final PrdTemplateService templates;
    /**
     * 可复用编排，状态不承担业务持久化。
     */
    private final CompiledGraph graph;
    /**
     * 客户端与密钥只留在调用期私有注册表。
     */
    private final Map<String, AnalysisRuntime> active = new ConcurrentHashMap<>();
    /**
     * 复杂泛型对象以 JSON 文本穿过 Graph State，避免状态复制丢失列表元素类型。
     */
    private final ObjectMapper json;

    /**
     * 构建有界上下文和结构化输出节点。
     */
    public WorkbenchWorkflow(ObjectMapper json, Validator validator, PrdTemplateService templates) throws GraphStateException {
        this.templates = templates;
        this.json = json;
        var workflow = new StateGraph("workbench_edit", () -> {
            Map<String, KeyStrategy> keys = new HashMap<>();
            for (String key : List.of("context", "runtimeId", "reply")) keys.put(key, new ReplaceStrategy());
            return keys;
        });
        workflow.addNode("edit_target", node_async(state -> {
            Context context = json.readValue(state.<String>value("context").orElseThrow(), Context.class);
            AnalysisRuntime runtime = active.get(state.<String>value("runtimeId").orElseThrow());
            Reply reply = runtime.isDemo() ? template(context) : runtime.analyzer().editWorkbench(modelContext(context));
            if (reply == null || !validator.validate(reply).isEmpty()) throw ModelInvocationException.invalidOutput();
            return Map.of("reply", json.writeValueAsString(reply));
        }));
        workflow.addEdge(StateGraph.START, "edit_target");
        workflow.addEdge("edit_target", StateGraph.END);
        graph = workflow.compile();
    }

    /**
     * 外部模型调用在业务事务外执行，运行完成即释放客户端。
     */
    public Reply execute(Context context, AnalysisRuntime runtime) {
        String id = UUID.randomUUID().toString();
        active.put(id, runtime);
        try {
            return json.readValue(graph.invoke(Map.of("context", json.writeValueAsString(context), "runtimeId", id)).orElseThrow().<String>value("reply").orElseThrow(), Reply.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("会话编辑状态无效", e);
        } finally {
            active.remove(id);
        }
    }

    /**
     * 模型与编辑器读取文档绑定的同一模板版本；模板不由模型选择或改写。
     */
    public String modelContext(Context context) throws com.fasterxml.jackson.core.JsonProcessingException {
        var value = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(context);
        value.set("templateDefinition", json.valueToTree(templates.forDocument(context.prd())));
        return json.writeValueAsString(value);
    }

    /**
     * 联调只将首段原文放入空文档，不伪造语义改写、业务规则或验收条件。
     */
    private Reply template(Context context) {
        if (!context.attachments().isEmpty() || !context.referenceAttachments().isEmpty())
            return new Reply("【本地模板联调，未调用大模型】附件原文已接收并作为参考资料保留。当前模式不分析附件，请启用真实模型后继续讨论。", null, null, null);
        if (PrdDocuments.sections(context.prd()).values().stream().allMatch(String::isBlank) && context.input().length() <= 8000)
            return new Reply("【本地模板联调，未调用大模型】已将你的原始描述放入 PRD 交付范围。其他章节仍为空，可直接编辑；启用真实模型后可通过会话逐步完善文档。", Map.of("scope", context.input()), null, null);
        return new Reply("【本地模板联调，未调用大模型】已记录讨论内容。此模式不执行语义改写，请直接编辑 PRD 正文，或在模型设置中启用真实模型。", null, null, null);
    }
}
