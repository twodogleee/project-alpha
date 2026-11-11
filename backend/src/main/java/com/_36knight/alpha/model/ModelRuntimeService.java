package com._36knight.alpha.model;

import com._36knight.alpha.agent.AnalysisRuntime;
import com._36knight.alpha.agent.AnalysisRuntimeProvider;
import com._36knight.alpha.dto.RequirementDraft;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 创建不可变运行上下文，并提供不会保存项目的显式连接测试。@author Andersen */
@Service
public class ModelRuntimeService implements AnalysisRuntimeProvider {
    /** 只记录安全摘要，不将原异常或上游正文传给日志框架。 */
    private static final Logger LOG = LoggerFactory.getLogger(ModelRuntimeService.class);
    /** 当前持久化模型配置。 */
    private final ModelCatalogService configurations;
    /** 平台客户端适配能力。 */
    private final ModelAnalyzerFactory factory;
    /** 连接测试也验证工作流需要的结构化输出能力。 */
    private final Validator validator;

    /** 配置获取、客户端创建及结构校验分别保持职责。 */
    public ModelRuntimeService(ModelCatalogService configurations, ModelAnalyzerFactory factory, Validator validator) {
        this.configurations = configurations;
        this.factory = factory;
        this.validator = validator;
    }

    /** 开始生成时固定配置，下一次请求再读取新保存配置。 */
    @Override public AnalysisRuntime snapshot() {
        ModelConfigurationSettings settings = configurations.activeSettings();
        return new AnalysisRuntime(settings.runtimeMode(), "demo".equals(settings.mode()) ? "local-template" : settings.model(),
                new DiagnosticRequirementAnalyzer(factory.create(settings), settings.apiKey()));
    }

    /** 测试表单不保存；上游失败只打印或返回脱敏后的错误字段。 */
    public ModelConnectionResult test(String id, SaveModelProfileRequest request) {
        ModelConfigurationSettings settings = configurations.testSettings(id, request);
        RequirementDraft result;
        try {
            result = factory.create(settings).analyze("连接测试：用户可以新增一条测试记录。请输出简洁需求草稿。");
        } catch (RuntimeException exception) {
            throw failed(ModelConnectionFailure.from(exception, settings.apiKey()));
        }
        if (result == null || !validator.validate(result).isEmpty()) throw failed(ModelConnectionFailure.invalidOutput());
        return new ModelConnectionResult(true, "ai", settings.model(), "模型连接与结构化输出测试通过；表单尚未自动保存");
    }

    /** 使用诊断编号关联页面与日志，保留上游脱敏原因，不记录原异常和凭证。 */
    private ModelConfigurationException failed(ModelConnectionFailure failure) {
        String diagnosticId = java.util.UUID.randomUUID().toString();
        ModelErrorDetail detail = failure.upstreamDetail();
        LOG.warn("模型连接测试失败：diagnosticId={}, category={}, upstreamStatus={}, exceptionType={}, upstreamCode={}, upstreamReason={}",
                diagnosticId, failure.category(), failure.upstreamStatus(), failure.exceptionType(),
                detail == null ? "" : detail.code(), detail == null ? "" : detail.reason());
        return new ModelConfigurationException(HttpStatus.BAD_GATEWAY, "MODEL_CONNECTION_FAILED",
                failure.message() + "（诊断编号：" + diagnosticId + "）");
    }
}
