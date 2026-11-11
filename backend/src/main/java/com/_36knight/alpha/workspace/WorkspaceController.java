package com._36knight.alpha.workspace;

import com._36knight.alpha.vo.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import static com._36knight.alpha.workspace.WorkspaceRequests.*;
import static com._36knight.alpha.workspace.WorkspaceViews.*;

/** 单人工作空间接口；只接收校验参数，关联和阶段规则由服务负责。@author Andersen */
@RestController
@RequestMapping("/api/alpha/workspace")
public class WorkspaceController {
    /** 工作空间应用服务。 */ private final WorkspaceService service;
    /** 注入业务服务。 */ public WorkspaceController(WorkspaceService service) { this.service=service; }
    /** 项目目录分页。 */ @GetMapping("/projects") public ApiResponse<Slice<Project>> projects(@RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="false") boolean archived) { return ApiResponse.success(service.projects(page,archived)); }
    /** 新建产品容器，不生成需求。 */ @PostMapping("/projects") @ResponseStatus(HttpStatus.CREATED) public ApiResponse<Project> createProject(@Valid @RequestBody NewProject r) { return ApiResponse.success(service.createProject(r)); }
    /** 项目背景。 */ @GetMapping("/projects/{id}") public ApiResponse<Project> project(@PathVariable String id) { return ApiResponse.success(service.getProject(id)); }
    /** 项目元数据更新。 */ @PutMapping("/projects/{id}") public ApiResponse<Project> editProject(@PathVariable String id,@Valid @RequestBody EditProject r) { return ApiResponse.success(service.editProject(id,r)); }
    /** 项目归档或恢复。 */ @PutMapping("/projects/{id}/archive") public ApiResponse<Project> archiveProject(@PathVariable String id,@Valid @RequestBody Archive r) { return ApiResponse.success(service.archiveProject(id,r)); }
    /** 项目内会话分页。 */ @GetMapping("/projects/{id}/sessions") public ApiResponse<Slice<Session>> sessions(@PathVariable String id,@RequestParam(required=false) String versionId,@RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="false") boolean archived) { return ApiResponse.success(service.sessions(id,versionId,page,archived)); }
    /** 在项目内创建会话。 */ @PostMapping("/projects/{id}/sessions") @ResponseStatus(HttpStatus.CREATED) public ApiResponse<Session> createSession(@PathVariable String id,@Valid @RequestBody NewSession r) { return ApiResponse.success(service.createSession(id,r)); }
    /** 会话详情。 */ @GetMapping("/sessions/{id}") public ApiResponse<SessionDetail> session(@PathVariable String id) { return ApiResponse.success(service.getSession(id)); }
    /** 消息按序号向前分页。 */ @GetMapping("/sessions/{id}/messages") public ApiResponse<Slice<Message>> messages(@PathVariable String id,@RequestParam(required=false) Long before) { return ApiResponse.success(service.messages(id,before)); }
    /** 改名。 */ @PutMapping("/sessions/{id}/title") public ApiResponse<Session> rename(@PathVariable String id,@Valid @RequestBody RenameSession r) { return ApiResponse.success(service.renameSession(id,r)); }
    /** 会话归档与恢复。 */ @PutMapping("/sessions/{id}/archive") public ApiResponse<Session> archiveSession(@PathVariable String id,@Valid @RequestBody Archive r) { return ApiResponse.success(service.archiveSession(id,r)); }
    /** 读取删除范围，不触发数据创建。 */ @GetMapping("/sessions/{id}/deletion-preview") public ApiResponse<DeletionPreview> deletionPreview(@PathVariable String id) { return ApiResponse.success(service.deletionPreview(id)); }
    /** 可恢复删除及恢复。 */ @PutMapping("/sessions/{id}/deletion") public ApiResponse<Session> deletion(@PathVariable String id,@Valid @RequestBody SessionDeletion r) { return ApiResponse.success(service.deleteSession(id,r)); }
    /** 回收站会话分页。 */ @GetMapping("/trash/sessions") public ApiResponse<Slice<Session>> trash(@RequestParam(defaultValue="1") long page) { return ApiResponse.success(service.deletedSessions(page)); }
    /** 关联版本，不修改范围。 */ @PutMapping("/sessions/{id}/version") public ApiResponse<Session> link(@PathVariable String id,@Valid @RequestBody LinkVersion r) { return ApiResponse.success(service.linkVersion(id,r)); }
    /** 提交持久化任务，返回 202；浏览器通过任务状态获取终态。 */ @PostMapping("/sessions/{id}/runs") @ResponseStatus(HttpStatus.ACCEPTED) public ApiResponse<Run> submit(@PathVariable String id,@Valid @RequestBody SubmitRun r) { return ApiResponse.success(service.submit(id,r)); }
    /** 安全的任务轮询。 */ @GetMapping("/runs/{id}") public ApiResponse<Run> run(@PathVariable String id) { return ApiResponse.success(service.getRun(id)); }
    /** 保存候选草稿。 */ @PutMapping("/sessions/{id}/draft") public ApiResponse<Session> draft(@PathVariable String id,@Valid @RequestBody SaveDraft r) { return ApiResponse.success(service.saveDraft(id,r)); }
    /** 草稿沉淀为自定义业务版本。 */ @PostMapping("/sessions/{id}/promote") @ResponseStatus(HttpStatus.CREATED) public ApiResponse<Version> promote(@PathVariable String id,@Valid @RequestBody PromoteDraft r) { return ApiResponse.success(service.promote(id,r)); }
    /** 显式同步草稿至关联版本。 */ @PostMapping("/sessions/{id}/sync") public ApiResponse<Version> sync(@PathVariable String id,@Valid @RequestBody SyncDraft r) { return ApiResponse.success(service.syncDraft(id,r)); }
    /** 项目版本目录。 */ @GetMapping("/projects/{id}/versions") public ApiResponse<Slice<Version>> versions(@PathVariable String id,@RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="false") boolean archived) { return ApiResponse.success(service.versions(id,page,archived)); }
    /** 手工定义交付版本。 */ @PostMapping("/projects/{id}/versions") @ResponseStatus(HttpStatus.CREATED) public ApiResponse<Version> createVersion(@PathVariable String id,@Valid @RequestBody NewVersion r) { return ApiResponse.success(service.createVersion(id,r)); }
    /** 版本详情。 */ @GetMapping("/versions/{id}") public ApiResponse<Version> version(@PathVariable String id) { return ApiResponse.success(service.getVersion(id)); }
    /** 文档删除的正文与关联范围快照。 */ @GetMapping("/versions/{id}/deletion-preview") public ApiResponse<VersionDeletionPreview> versionDeletionPreview(@PathVariable String id) { return ApiResponse.success(service.versionDeletionPreview(id)); }
    /** 文档单独回收或恢复，关联会话保留。 */ @PutMapping("/versions/{id}/deletion") public ApiResponse<Version> deleteVersion(@PathVariable String id,@Valid @RequestBody VersionDeletion r) { return ApiResponse.success(service.deleteVersion(id,r)); }
    /** 已回收文档目录。 */ @GetMapping("/trash/versions") public ApiResponse<Slice<Version>> deletedVersions(@RequestParam(defaultValue="1") long page) { return ApiResponse.success(service.deletedVersions(page)); }
    /** 保存范围，不推进阶段。 */ @PutMapping("/versions/{id}") public ApiResponse<Version> saveVersion(@PathVariable String id,@Valid @RequestBody EditVersion r) { return ApiResponse.success(service.saveVersion(id,r)); }
    /** 显式取消交付并记录原因。 */ @PostMapping("/versions/{id}/cancel") public ApiResponse<Version> cancel(@PathVariable String id,@Valid @RequestBody CancelVersion r) { return ApiResponse.success(service.cancelVersion(id,r)); }
    /** 版本归档与恢复。 */ @PutMapping("/versions/{id}/archive") public ApiResponse<Version> archiveVersion(@PathVariable String id,@Valid @RequestBody Archive r) { return ApiResponse.success(service.archiveVersion(id,r)); }
    /** 保存记录分页。 */ @GetMapping("/versions/{id}/revisions") public ApiResponse<Slice<SavedRevision>> revisions(@PathVariable String id,@RequestParam(defaultValue="1") long page) { return ApiResponse.success(service.revisions(id,page)); }
    /** 不可变历史正文。 */ @GetMapping("/versions/{id}/revisions/{revision}") public ApiResponse<SavedRevision> saved(@PathVariable String id,@PathVariable long revision) { return ApiResponse.success(service.savedRevision(id,revision)); }
    /** 归档记录分页。 */ @GetMapping("/archives") public ApiResponse<Archives> archives(@RequestParam(defaultValue="1") long page) { return ApiResponse.success(service.archives(page)); }
}
