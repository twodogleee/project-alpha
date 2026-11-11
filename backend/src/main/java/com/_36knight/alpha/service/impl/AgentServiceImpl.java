package com._36knight.alpha.service.impl;

import com._36knight.alpha.agent.RequirementWorkflow;
import com._36knight.alpha.model.ModelCatalogService;
import com._36knight.alpha.service.AgentService;
import com._36knight.alpha.vo.RequirementDraftResponse;
import com._36knight.alpha.vo.RuntimeInfoResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 衔接工作台接口与需求 Graph 的应用服务。
 * @author Andersen
 */
@Service
public class AgentServiceImpl implements AgentService {
    /** 负责执行需求草稿流程。 */
    private final RequirementWorkflow workflow;
    /** 负责提供公开模式信息，不对外返回密钥。 */
    private final ModelCatalogService configurations;

    /** 对外运行信息沿用 Spring 配置的应用名。 */
    private final String applicationName;

    /** 注入工作流、模型配置和应用身份。 */
    public AgentServiceImpl(RequirementWorkflow workflow, ModelCatalogService configurations,
            @Value("${spring.application.name}") String applicationName) {
        this.workflow = workflow;
        this.configurations = configurations;
        this.applicationName = applicationName;
    }

    /** 返回当前运行模式与已实现的流程节点。 */
    @Override
    public RuntimeInfoResponse getRuntimeInfo() {
        var catalog = configurations.get();
        if ("demo".equals(catalog.runtime().mode())) return new RuntimeInfoResponse(applicationName, "demo", "local-template", RequirementWorkflow.STAGES);
        var model = catalog.models().stream().filter(m -> m.id().equals(catalog.runtime().activeModelId())).findFirst().orElseThrow();
        var platform = catalog.platforms().stream().filter(p -> p.id().equals(model.platformId())).findFirst().orElseThrow();
        String mode = "dashscope".equals(platform.protocol()) ? "dashscope" : "openai-compatible";
        return new RuntimeInfoResponse(applicationName, mode, model.model(), RequirementWorkflow.STAGES);
    }

    /** 调用 Graph 生成需求对象和对应的 PRD。 */
    @Override
    public RequirementDraftResponse analyzeRequirement(String content) {
        return workflow.execute(content);
    }
}
