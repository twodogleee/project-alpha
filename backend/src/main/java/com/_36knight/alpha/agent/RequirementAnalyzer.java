package com._36knight.alpha.agent;

import com._36knight.alpha.dto.RequirementDraft;

/**
 * 需求分析能力边界，隔离工作流与具体模型供应商。
 *
 * @author Andersen
 */
public interface RequirementAnalyzer {
    /**
     * 根据用户输入生成可校验的需求草稿。
     */
    RequirementDraft analyze(String content);

    /**
     * 对有界会话上下文生成纯文本回答；老分析适配器没有会话能力时明确拒绝。
     */
    default String converse(String context) {
        throw new UnsupportedOperationException("会话适配器尚未接入");
    }

    /**
     * 从已确认需求基线生成 PRD 候选；适配器不负责确认或写入正式产物。
     */
    default com._36knight.alpha.workspace.LifecycleContracts.Prd generatePrd(String context) {
        throw new UnsupportedOperationException("PRD 适配器尚未接入");
    }

    /**
     * 返回当前 PRD 的章节增量、待确认事项与新增建议，不能代替用户定稿或采纳建议。
     */
    default com._36knight.alpha.workspace.WorkbenchContracts.Reply editWorkbench(String context) {
        throw new UnsupportedOperationException("工作空间编辑适配器尚未接入");
    }
}
