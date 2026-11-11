package com._36knight.alpha.prototype;

import com._36knight.alpha.vo.ApiResponse;
import com._36knight.alpha.workspace.WorkspaceViews;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import static com._36knight.alpha.prototype.PrototypeContracts.*;

/** 原型业务与受控预览接口；执行回调仅为内部 Java 契约，不开放客户端写产物或执行令牌。@author Andersen */
@RestController
@RequestMapping("/api/alpha/workspace/versions/{versionId}/prototype")
public class PrototypeController {
    /** 所有归属、锁和状态判断集中在应用服务。 */ private final PrototypeService service;
    /** 短期静态预览的申请与撤销不进入业务修订写事务。 */ private final PrototypePreviewService previews;
    /** 注入业务服务及独立预览边界。 */ public PrototypeController(PrototypeService service,PrototypePreviewService previews){this.service=service;this.previews=previews;}
    /** 不存在时返回 EMPTY，不隐式创建聚合。 */
    @GetMapping public ApiResponse<Detail> get(@PathVariable String versionId){return ApiResponse.success(service.get(versionId));}
    /** 任务提交仅在执行提供方可用时接受，模型或隔离 Worker 不可用时返回 503。 */
    @PostMapping("/runs") @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<WorkspaceViews.Run> submit(@PathVariable String versionId,@Valid @RequestBody Submit command){return ApiResponse.success(service.submit(versionId,command));}
    /** 历史包含当前和冲突候选，不支持客户端文件系统路径。 */
    @GetMapping("/revisions") public ApiResponse<WorkspaceViews.Slice<RevisionView>> revisions(@PathVariable String versionId,@RequestParam(defaultValue="1") long page){return ApiResponse.success(service.revisions(versionId,page));}
    /** 查看固定修订及其冻结来源。 */
    @GetMapping("/revisions/{revisionId}") public ApiResponse<RevisionView> revision(@PathVariable String versionId,@PathVariable String revisionId){return ApiResponse.success(service.revision(versionId,revisionId));}
    /** 候选应用记录独立幂等命令，来源过期时要求同步或重新生成。 */
    @PostMapping("/apply") public ApiResponse<Applied> apply(@PathVariable String versionId,@Valid @RequestBody Apply command){return ApiResponse.success(service.apply(versionId,command));}
    /** 恢复追加新修订，保留历史来源及文件，不调用生成执行器。 */
    @PostMapping("/restore") public ApiResponse<Applied> restore(@PathVariable String versionId,@Valid @RequestBody Restore command){return ApiResponse.success(service.restore(versionId,command));}
    /** 人工确认由服务端再次核对有效定稿、完整范围、验证证据和并发状态。 */
    @PostMapping("/confirm") public ApiResponse<ConfirmationView> confirm(@PathVariable String versionId,@Valid @RequestBody Confirm command){return ApiResponse.success(service.confirm(versionId,command));}
    /** 确认历史保留当时的精确范围和基线，不自动恢复其有效状态。 */
    @GetMapping("/confirmations") public ApiResponse<WorkspaceViews.Slice<ConfirmationView>> confirmations(@PathVariable String versionId,@RequestParam(defaultValue="1") long page){return ApiResponse.success(service.confirmations(versionId,page));}
    /** 为指定修订申请受控预览；只接受服务器配置中允许的工作台来源。 */
    @PostMapping("/revisions/{revisionId}/preview")
    public ApiResponse<PrototypePreviewService.View> preview(@PathVariable String versionId,@PathVariable String revisionId,@RequestBody(required=false) PrototypePreviewService.OpenCommand command){return ApiResponse.success(previews.create(versionId,revisionId,command));}
    /** 离开预览或切换修订时释放临时会话，不删除历史产物。 */
    @DeleteMapping("/preview/{sessionId}")
    public ApiResponse<Void> revokePreview(@PathVariable String versionId,@PathVariable String sessionId){previews.revoke(versionId,sessionId);return ApiResponse.success(null);}
}
