package com._36knight.alpha.model;

/** 模型调用的安全异常：只携带脱敏诊断，不保留包含上游原文的 cause。@author Andersen */
public final class ModelInvocationException extends RuntimeException {
    /** 经当前客户端凭证脱敏后的诊断字段。 */
    private final ModelConnectionFailure failure;

    /** 复制代码位置用于排障，不复制原异常消息、原因链或 suppressed。 */
    private ModelInvocationException(ModelConnectionFailure failure, RuntimeException original) {
        super(failure.message());
        this.failure = failure;
        if (original != null) setStackTrace(original.getStackTrace());
    }

    /** 必须在持有当次凭证的客户端边界提取并脱敏。 */
    static ModelInvocationException from(RuntimeException original, String apiKey) {
        return new ModelInvocationException(ModelConnectionFailure.from(original, apiKey), original);
    }

    /** 结构和业务校验失败只输出固定说明，不回显模型结果。 */
    public static ModelInvocationException invalidOutput() {
        return new ModelInvocationException(ModelConnectionFailure.invalidOutput(), null);
    }

    /** 稳定分类用于日志检索。 */
    public String category() { return failure.category(); }
    /** 上游可识别的 HTTP 状态码。 */
    public Integer upstreamStatus() { return failure.upstreamStatus(); }
    /** 原异常类型，不含原异常消息。 */
    public String exceptionType() { return failure.exceptionType(); }
    /** 平台返回的脱敏错误代码。 */
    public String upstreamCode() { return failure.upstreamDetail() == null ? "" : failure.upstreamDetail().code(); }
}
