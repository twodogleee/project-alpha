package com._36knight.alpha.controller;

import com._36knight.alpha.model.ModelCatalogService;
import com._36knight.alpha.model.FetchPlatformModelsRequest;
import com._36knight.alpha.model.PlatformModelsView;
import com._36knight.alpha.model.PlatformModelCatalogService;
import com._36knight.alpha.model.ModelCatalogView;
import com._36knight.alpha.model.ModelConnectionResult;
import com._36knight.alpha.model.ModelRuntimeService;
import com._36knight.alpha.model.SaveModelPlatformRequest;
import com._36knight.alpha.model.SaveModelProfileRequest;
import com._36knight.alpha.model.SaveModelSelectionRequest;
import com._36knight.alpha.vo.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统设置平台与模型目录接口，不返回密钥。@author Andersen
 */
@RestController
@RequestMapping("/api/alpha/model-config")
public class ModelConfigurationController {
    /**
     * 接入平台、模型与选择业务。
     */
    private final ModelCatalogService catalog;
    /**
     * 显式模型连接测试。
     */
    private final ModelRuntimeService runtime;
    /**
     * 事务外显式获取上游模型目录。
     */
    private final PlatformModelCatalogService platformModels;

    /**
     * Controller 只接收校验和响应。
     */
    public ModelConfigurationController(ModelCatalogService catalog, ModelRuntimeService runtime, PlatformModelCatalogService platformModels) {
        this.catalog = catalog;
        this.runtime = runtime;
        this.platformModels = platformModels;
    }

    /**
     * 从已保存的平台目录地址获取模型；凭证单向提交，结果不自动保存或启用。
     */
    @PostMapping("/platforms/{id}/catalog")
    public ApiResponse<PlatformModelsView> fetchModels(@PathVariable String id, @Valid @RequestBody FetchPlatformModelsRequest request) {
        return ApiResponse.success(platformModels.fetch(id, request));
    }

    /**
     * 获取一致的公开目录与当前选择。
     */
    @GetMapping
    public ApiResponse<ModelCatalogView> get() {
        return ApiResponse.success(catalog.get());
    }

    /**
     * 新增平台，不自动创建模型或测试网络。
     */
    @PostMapping("/platforms")
    public ApiResponse<ModelCatalogView> createPlatform(@Valid @RequestBody SaveModelPlatformRequest request) {
        return ApiResponse.success(catalog.savePlatform(null, request));
    }

    /**
     * 编辑平台，目标变化执行凭证隔离规则。
     */
    @PutMapping("/platforms/{id}")
    public ApiResponse<ModelCatalogView> updatePlatform(@PathVariable String id, @Valid @RequestBody SaveModelPlatformRequest request) {
        return ApiResponse.success(catalog.savePlatform(id, request));
    }

    /**
     * 删除无引用平台，目录版本必须一致。
     */
    @DeleteMapping("/platforms/{id}")
    public ApiResponse<ModelCatalogView> deletePlatform(@PathVariable String id, @RequestParam long expectedRevision) {
        return ApiResponse.success(catalog.deletePlatform(id, expectedRevision));
    }

    /**
     * 新增模型，可先不填写密钥。
     */
    @PostMapping("/models")
    public ApiResponse<ModelCatalogView> createModel(@Valid @RequestBody SaveModelProfileRequest request) {
        return ApiResponse.success(catalog.saveProfile(null, request));
    }

    /**
     * 编辑指定模型，不自动启用。
     */
    @PutMapping("/models/{id}")
    public ApiResponse<ModelCatalogView> updateModel(@PathVariable String id, @Valid @RequestBody SaveModelProfileRequest request) {
        return ApiResponse.success(catalog.saveProfile(id, request));
    }

    /**
     * 删除未启用模型，保留引用和并发边界。
     */
    @DeleteMapping("/models/{id}")
    public ApiResponse<ModelCatalogView> deleteModel(@PathVariable String id, @RequestParam long expectedRevision) {
        return ApiResponse.success(catalog.deleteProfile(id, expectedRevision));
    }

    /**
     * 保存唯一运行选择，不自动发起模型请求。
     */
    @PutMapping("/runtime")
    public ApiResponse<ModelCatalogView> select(@Valid @RequestBody SaveModelSelectionRequest request) {
        return ApiResponse.success(catalog.select(request));
    }

    /**
     * 测试模型表单，新增时没有 modelId；不保存表单。
     */
    @PostMapping("/test")
    public ApiResponse<ModelConnectionResult> test(@RequestParam(required = false) String modelId, @Valid @RequestBody SaveModelProfileRequest request) {
        return ApiResponse.success(runtime.test(modelId, request));
    }
}
