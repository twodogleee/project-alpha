package com._36knight.alpha.controller;

import com._36knight.alpha.service.AgentService;
import com._36knight.alpha.vo.AnalyzeRequirementRequest;
import com._36knight.alpha.vo.ApiResponse;
import com._36knight.alpha.vo.RequirementDraftResponse;
import com._36knight.alpha.vo.RuntimeInfoResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alpha 工作台的运行信息与需求草稿接口。
 *
 * @author Andersen
 */
@RestController
@RequestMapping("/api/alpha")
public class AgentController {
    /**
     * 业务调用入口，不在控制器中编写分析或持久化逻辑。
     */
    private final AgentService agentService;

    /**
     * 注入 Agent 应用服务。
     */
    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    /**
     * 查询当前模式，前端据此显示真实来源。
     */
    @GetMapping("/runtime")
    public ApiResponse<RuntimeInfoResponse> getRuntimeInfo() {
        return ApiResponse.success(agentService.getRuntimeInfo());
    }

    /**
     * 校验需求输入并执行独立的草稿生成请求。
     */
    @PostMapping("/agent/drafts")
    public ApiResponse<RequirementDraftResponse> analyzeRequirement(@Valid @RequestBody AnalyzeRequirementRequest request) {
        return ApiResponse.success(agentService.analyzeRequirement(request.content()));
    }
}
