package com._36knight.alpha.controller;

import com._36knight.alpha.vo.ApiResponse;
import com._36knight.alpha.workspace.PrdTemplates;
import com._36knight.alpha.template.PrdTemplateService;
import com._36knight.alpha.template.PrdTemplateContracts;
import jakarta.validation.Valid;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统配置的模板目录、有限草稿编辑及显式发布入口。@author Andersen
 */
@RestController
@RequestMapping("/api/alpha/config/prd-templates")
public class PrdTemplateController {
    /**
     * 发布目录与草稿业务由服务维护。
     */
    private final PrdTemplateService service;

    /**
     * 接口层只校验命令并返回业务真值。
     */
    public PrdTemplateController(PrdTemplateService service) {
        this.service = service;
    }

    /**
     * 返回全部已发布保留版本，草稿不参与 PRD 解析。
     */
    @GetMapping
    public ApiResponse<PrdTemplates.Catalog> get() {
        return ApiResponse.success(service.catalog());
    }

    /**
     * 管理目录仅列出可编辑的自定义聚合。
     */
    @GetMapping("/management")
    public ApiResponse<List<PrdTemplateContracts.Managed>> management() {
        return ApiResponse.success(service.management());
    }

    /**
     * 从明确已发布版本复制，重试使用相同请求标识。
     */
    @PostMapping("/copies")
    public ApiResponse<PrdTemplateContracts.Managed> copy(@Valid @RequestBody PrdTemplateContracts.Copy command) {
        return ApiResponse.success(service.copy(command));
    }

    /**
     * 已发布自定义模板打开下一版草稿。
     */
    @PostMapping("/{id}/draft")
    public ApiResponse<PrdTemplateContracts.Managed> openDraft(@PathVariable String id, @Valid @RequestBody PrdTemplateContracts.OpenDraft command) {
        return ApiResponse.success(service.openDraft(id, command));
    }

    /**
     * 保存受限配置，仅更新草稿，不直接影响任何 PRD。
     */
    @PutMapping("/{id}/draft")
    public ApiResponse<PrdTemplateContracts.Managed> save(@PathVariable String id, @Valid @RequestBody PrdTemplateContracts.Save command) {
        return ApiResponse.success(service.save(id, command));
    }

    /**
     * 发布已保存草稿，原发布版本保持可读。
     */
    @PostMapping("/{id}/publish")
    public ApiResponse<PrdTemplateContracts.Managed> publish(@PathVariable String id, @Valid @RequestBody PrdTemplateContracts.Publish command) {
        return ApiResponse.success(service.publish(id, command));
    }

    /**
     * 删除或恢复自定义模板，既有正文及已发布版本不受影响。
     */
    @PutMapping("/{id}/deletion")
    public ApiResponse<PrdTemplateContracts.Managed> deletion(@PathVariable String id, @Valid @RequestBody PrdTemplateContracts.Deletion command) {
        return ApiResponse.success(service.deletion(id, command));
    }
}
