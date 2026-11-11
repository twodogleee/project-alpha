package com._36knight.alpha.service;

import com._36knight.alpha.vo.RequirementDraftResponse;
import com._36knight.alpha.vo.RuntimeInfoResponse;

/**
 * 工作台调用的 Agent 应用服务，隐藏编排框架细节。
 * @author Andersen
 */
public interface AgentService {
    /** 查询非敏感的模型模式和工作流阶段。 */
    RuntimeInfoResponse getRuntimeInfo();

    /** 整理一份需求输入并生成 PRD 草稿。 */
    RequirementDraftResponse analyzeRequirement(String content);
}
