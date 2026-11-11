package com._36knight.alpha.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 接入平台编辑，不携带模型密钥。
 * @param expectedRevision 页面载入的目录版本
 * @param name 全局唯一的显示名称
 * @param protocol 已实现的客户端协议
 * @param baseUrl 不含凭证的 API 基础地址
 * @param modelCatalogUrl 可选模型列表 GET 地址，与基础地址同源；空值表示清除
 * @author Andersen
 */
public record SaveModelPlatformRequest(@Min(1) long expectedRevision, @NotBlank @Size(max=120) String name,
        @NotBlank @Pattern(regexp="dashscope|openai-compatible") String protocol,
        @NotBlank @Size(max=500) String baseUrl, @Size(max=500) String modelCatalogUrl) {
    /** 兼容未提供模型列表地址的内部调用，默认不配置目录接口。 */
    public SaveModelPlatformRequest(long expectedRevision, String name, String protocol, String baseUrl) {
        this(expectedRevision, name, protocol, baseUrl, null);
    }
}
