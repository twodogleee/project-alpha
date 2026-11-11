package com._36knight.alpha.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 显式获取模型目录，不保存凭证或创建模型。
 *
 * @param expectedRevision 页面载入的目录版本
 * @param modelId          可选当前编辑模型，仅允许沿用该模型同平台密钥
 * @param apiKey           本次输入密钥，留空可沿用当前模型密钥或匿名获取
 * @param clearApiKey      为真时禁止沿用原密钥
 * @author Andersen
 */
public record FetchPlatformModelsRequest(@Min(1) long expectedRevision, @Size(max = 36) String modelId,
                                         @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) @Size(max = 4096) String apiKey,
                                         boolean clearApiKey) {
    /**
     * 避免调试字符串化请求时回显本次输入密钥。
     */
    @Override
    public String toString() {
        return "FetchPlatformModelsRequest[expectedRevision=" + expectedRevision + "]";
    }
}
