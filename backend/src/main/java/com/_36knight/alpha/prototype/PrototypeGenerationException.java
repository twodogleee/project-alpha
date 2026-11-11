package com._36knight.alpha.prototype;

/** 模型输出不符合平台契约时的安全诊断；只包含平台规则，不携带模型原文。@author Andersen */
public final class PrototypeGenerationException extends RuntimeException {
    /** 固定错误类型供执行器共享修复预算和记录安全终态。 */
    public static final String CODE = "PROTOTYPE_MODEL_OUTPUT_INVALID";

    /** 保存平台生成的有界规则提示，不附加上游异常。 */
    public PrototypeGenerationException(String diagnostic) {
        super(diagnostic == null ? "原型输出不符合生成契约" : diagnostic.substring(0, Math.min(2000, diagnostic.length())));
    }

    /** 返回稳定错误类型，避免把诊断作为错误码。 */
    public String code() { return CODE; }

    /** 提供可回馈给模型的纯文本校验规则。 */
    public String diagnostic() { return getMessage(); }
}
