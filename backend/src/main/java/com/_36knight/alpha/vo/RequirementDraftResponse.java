package com._36knight.alpha.vo;

import com._36knight.alpha.dto.RequirementDraft;
import java.util.List;

/**
 * 工作流的交付结果，包含明确的来源与执行步骤。
 * @param runId 本次运行标识，当前不作为持久化会话 ID
 * @param mode demo、dashscope 或 openai-compatible，保留本次生成来源
 * @param draft 待评审的结构化需求
 * @param prdMarkdown 由同一需求结果渲染的 PRD 草稿
 * @param completedStages 本次已完成的工作流阶段标识
 * @author Andersen
 */
public record RequirementDraftResponse(String runId, String mode, RequirementDraft draft,
        String prdMarkdown, List<String> completedStages) {
}
