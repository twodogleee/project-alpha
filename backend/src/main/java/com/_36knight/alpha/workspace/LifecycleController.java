package com._36knight.alpha.workspace;

import com._36knight.alpha.vo.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import static com._36knight.alpha.workspace.LifecycleContracts.*;

/** 需求版本正式产物入口，只负责参数校验和响应。@author Andersen */
@RestController
@RequestMapping("/api/alpha/workspace/versions/{id}/lifecycle")
public class LifecycleController {
    /** 基线与产物应用服务。 */ private final LifecycleService service;
    /** 注入服务，不直接访问数据库。 */ public LifecycleController(LifecycleService service){this.service=service;}
    /** 当前评审、PRD、门槛和真实任务状态。 */ @GetMapping public ApiResponse<Detail> get(@PathVariable String id){return ApiResponse.success(service.get(id));}
    /** 保存评审不自动确认。 */ @PutMapping("/review") public ApiResponse<Detail> saveReview(@PathVariable String id,@Valid @RequestBody SaveReview r){return ApiResponse.success(service.saveReview(id,r));}
    /** 人工确认评审并进入 PRD。 */ @PostMapping("/review/confirm") public ApiResponse<Detail> confirmReview(@PathVariable String id,@Valid @RequestBody Confirm r){return ApiResponse.success(service.confirmReview(id,r));}
    /** 记录原因重新评审上游变更。 */ @PostMapping("/reopen") public ApiResponse<Detail> reopen(@PathVariable String id,@Valid @RequestBody Reopen r){return ApiResponse.success(service.reopen(id,r));}
    /** PRD 编辑与候选应用保存。 */ @PutMapping("/prd") public ApiResponse<Detail> savePrd(@PathVariable String id,@Valid @RequestBody SavePrd r){return ApiResponse.success(service.savePrd(id,r));}
    /** 人工确认 PRD，进入原型等待阶段。 */ @PostMapping("/prd/confirm") public ApiResponse<Detail> confirmPrd(@PathVariable String id,@Valid @RequestBody Confirm r){return ApiResponse.success(service.confirmPrd(id,r));}
    /** 显式后台生成候选。 */ @PostMapping("/prd/runs") @ResponseStatus(HttpStatus.ACCEPTED) public ApiResponse<Run> generate(@PathVariable String id,@Valid @RequestBody GeneratePrd r){return ApiResponse.success(service.generate(id,r));}
    /** 任务版本归属由应用服务校验。 */ @GetMapping("/prd/runs/{runId}") public ApiResponse<Run> run(@PathVariable String id,@PathVariable String runId){return ApiResponse.success(service.getRun(id,runId));}
    /** 不可变基线分页。 */ @GetMapping("/baselines") public ApiResponse<WorkspaceViews.Slice<Baseline>> baselines(@PathVariable String id,@RequestParam(defaultValue="1") long page){return ApiResponse.success(service.baselines(id,page));}
    /** 指定基线与范围快照只读。 */ @GetMapping("/baselines/{baselineId}") public ApiResponse<Baseline> baseline(@PathVariable String id,@PathVariable String baselineId){return ApiResponse.success(service.getBaseline(id,baselineId));}
    /** 不可变产物保存与重新评审原因。 */ @GetMapping("/history") public ApiResponse<WorkspaceViews.Slice<History>> history(@PathVariable String id,@RequestParam(defaultValue="REVIEW") String kind,@RequestParam(defaultValue="1") long page){return ApiResponse.success(service.history(id,kind,page));}
}
