package com._36knight.alpha.workspace;

import com._36knight.alpha.vo.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 统一工作空间接口仅处理参数及响应，模型编排与持久化交给应用服务。@author Andersen */
@RestController
@RequestMapping("/api/alpha/workspace")
public class WorkbenchController {
    /** 会话与产物的统一应用入口。 */ private final WorkbenchService service;
    /** 注入工作空间服务。 */ public WorkbenchController(WorkbenchService service){this.service=service;}
    /** 显式打开时为探索会话建立交付草稿。 */ @PostMapping("/sessions/{id}/workbench/open") public ApiResponse<WorkbenchContracts.Detail> open(@PathVariable String id){return ApiResponse.success(service.open(id));}
    /** 旧版本链接汇入相同工作空间。 */ @PostMapping("/versions/{id}/workbench/open") public ApiResponse<WorkbenchContracts.Detail> openVersion(@PathVariable String id){return ApiResponse.success(service.openVersion(id));}
    /** 读取已保存草稿和任务状态。 */ @GetMapping("/sessions/{id}/workbench") public ApiResponse<WorkbenchContracts.Detail> get(@PathVariable String id){return ApiResponse.success(service.get(id));}
    /** 自动保存与手工重试使用同一个有锁契约。 */ @PutMapping("/sessions/{id}/workbench") public ApiResponse<WorkbenchContracts.Detail> save(@PathVariable String id,@Valid @RequestBody WorkbenchContracts.Draft command){return ApiResponse.success(service.save(id,command));}
    /** 会话直接生成或编辑当前 PRD，后台处理并自动保存结果。 */ @PostMapping("/sessions/{id}/workbench/runs") @ResponseStatus(HttpStatus.ACCEPTED) public ApiResponse<WorkspaceViews.Run> submit(@PathVariable String id,@Valid @RequestBody WorkbenchContracts.Message command){return ApiResponse.success(service.submit(id,command));}
    /** 附件按消息所属会话读取，不暴露任务中的其他私有上下文。 */ @GetMapping("/sessions/{id}/workbench/runs/{runId}/attachments") public ApiResponse<java.util.List<WorkbenchContracts.Attachment>> attachments(@PathVariable String id,@PathVariable String runId){return ApiResponse.success(service.attachments(id,runId));}
    /** 已确认文档继续编辑。 */ @PostMapping("/sessions/{id}/workbench/prd/edit") public ApiResponse<WorkbenchContracts.Detail> editPrd(@PathVariable String id,@Valid @RequestBody LifecycleContracts.Reopen command){return ApiResponse.success(service.reopen(id,command));}
    /** 采纳或忽略独立设计建议。 */ @PostMapping("/sessions/{id}/workbench/suggestions") public ApiResponse<WorkbenchContracts.Detail> decide(@PathVariable String id,@Valid @RequestBody WorkbenchContracts.Decision command){return ApiResponse.success(service.decide(id,command));}
    /** 确认 PRD 基线；原型生成及人工确认仍由原型的独立命令完成。 */ @PostMapping("/sessions/{id}/workbench/prd/confirm") public ApiResponse<WorkbenchContracts.Detail> confirm(@PathVariable String id,@Valid @RequestBody LifecycleContracts.Confirm command){return ApiResponse.success(service.confirmPrd(id,command));}
}
