package com._36knight.alpha.agent;

import com._36knight.alpha.dto.RequirementDraft;

import java.util.List;

/**
 * 本地联调适配器：仅保留原始输入并提供固定澄清清单，不执行 AI 推理。
 *
 * @author Andersen
 */
public class LocalRequirementAnalyzer implements RequirementAnalyzer {
    /**
     * 本地联调只说明工作区操作，不模拟模型推理或自动确认需求。
     */
    @Override
    public String converse(String context) {
        return "【本地模板联调，未调用大模型】\n已保存本次输入与会话记录。你可以继续补充目标、使用者、业务流程和交付范围，再点击“整理需求草稿”。整理结果仍需人工编辑，并显式形成需求版本。";
    }

    /**
     * 生成便于验证前后端和 Graph 连通性的模板草稿。
     */
    @Override
    public RequirementDraft analyze(String content) {
        return new RequirementDraft("需求草稿", content, List.of(content), List.of(
            "谁使用这项功能，各角色分别有哪些权限？",
            "核心业务流程、成功条件和异常场景是什么？",
            "本期交付范围与明确排除的功能有哪些？"), List.of());
    }
}
