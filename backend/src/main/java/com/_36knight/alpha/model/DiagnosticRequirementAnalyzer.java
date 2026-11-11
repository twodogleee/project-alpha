package com._36knight.alpha.model;

import com._36knight.alpha.agent.RequirementAnalyzer;
import com._36knight.alpha.dto.RequirementDraft;
import com._36knight.alpha.workspace.LifecycleContracts.Prd;
import com._36knight.alpha.workspace.WorkbenchContracts.Reply;

import java.util.function.Supplier;

/**
 * 统一保护会话、需求草稿与 PRD 调用；密钥只存在于当次客户端内存。@author Andersen
 */
final class DiagnosticRequirementAnalyzer implements RequirementAnalyzer {
    /**
     * 已绑定平台的分析器，不参与序列化。
     */
    private final RequirementAnalyzer delegate;
    /**
     * 仅用于脱敏上游回显，不提供读取接口。
     */
    private final String apiKey;

    /**
     * 固定当次客户端和脱敏凭证，后续配置变化不影响正在执行的任务。
     */
    DiagnosticRequirementAnalyzer(RequirementAnalyzer delegate, String apiKey) {
        this.delegate = delegate;
        this.apiKey = apiKey;
    }

    /**
     * 草稿分析不回显原始异常。
     */
    @Override
    public RequirementDraft analyze(String content) {
        return invoke(() -> delegate.analyze(content));
    }

    /**
     * 文本会话使用相同诊断边界。
     */
    @Override
    public String converse(String context) {
        return invoke(() -> delegate.converse(context));
    }

    /**
     * PRD 调用在进入 Graph 异常链前完成脱敏。
     */
    @Override
    public Prd generatePrd(String context) {
        return invoke(() -> delegate.generatePrd(context));
    }

    /**
     * 会话编辑同一 PRD 也必须经过真实适配器和安全诊断，不能落入接口缺省实现。
     */
    @Override
    public Reply editWorkbench(String context) {
        return invoke(() -> delegate.editWorkbench(context));
    }

    /**
     * 原异常仅用于提取安全字段，不传给日志或上层框架。
     */
    private <T> T invoke(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (RuntimeException exception) {
            throw ModelInvocationException.from(exception, apiKey);
        }
    }
}
