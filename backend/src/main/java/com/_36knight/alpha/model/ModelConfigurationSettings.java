package com._36knight.alpha.model;

/**
 * 单次客户端创建使用的私有配置，不作为接口返回对象。
 *
 * @param mode     demo 或 ai
 * @param platform 当前平台
 * @param model    平台模型名
 * @param baseUrl  平台接入地址
 * @param apiKey   仅在后端调用内存中使用的凭证
 * @author Andersen
 */
public record ModelConfigurationSettings(String mode, String platform, String model, String baseUrl, String apiKey) {
    /**
     * 旧 API 保留 dashscope 来源，并为兼容平台提供独立来源。
     */
    public String runtimeMode() {
        return "demo".equals(mode) ? "demo" : "dashscope".equals(platform) ? "dashscope" : "openai-compatible";
    }

    /**
     * 避免诊断输出意外泄漏凭证或用户地址。
     */
    @Override
    public String toString() {
        return "ModelConfigurationSettings[credentials=redacted]";
    }
}
