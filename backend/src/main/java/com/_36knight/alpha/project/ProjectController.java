package com._36knight.alpha.project;

import com._36knight.alpha.vo.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 项目草稿与只读历史接口；业务规则由应用服务执行。
 * @author Andersen
 */
@RestController
@RequestMapping("/api/alpha/projects")
public class ProjectController {
    /** 业务应用服务。 */
    private final ProjectService service;

    /** 注入项目能力，不在控制器内处理来源或版本。 */
    public ProjectController(ProjectService service) { this.service = service; }

    /** 返回最近项目列表。 */
    @GetMapping
    public ApiResponse<List<ProjectSummary>> list() { return ApiResponse.success(service.list()); }

    /** 分析并保存首版文档。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProjectDocument> create(@Valid @RequestBody CreateProjectRequest request) {
        return ApiResponse.success(service.create(request));
    }

    /** 读取已保存当前版本。 */
    @GetMapping("/{id}")
    public ApiResponse<ProjectDocument> get(@PathVariable String id) { return ApiResponse.success(service.get(id)); }

    /** 保存完整编辑，冲突时返回 409。 */
    @PutMapping("/{id}")
    public ApiResponse<ProjectDocument> save(@PathVariable String id, @Valid @RequestBody SaveProjectRequest request) {
        return ApiResponse.success(service.save(id, request));
    }

    /** 读取只读版本摘要。 */
    @GetMapping("/{id}/versions")
    public ApiResponse<List<VersionSummary>> versions(@PathVariable String id) {
        return ApiResponse.success(service.versions(id));
    }

    /** 读取指定历史快照，不提供隐式恢复接口。 */
    @GetMapping("/{id}/versions/{revision}")
    public ApiResponse<ProjectDocument> version(@PathVariable String id, @PathVariable long revision) {
        return ApiResponse.success(service.version(id, revision));
    }
}
