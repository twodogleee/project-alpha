package com._36knight.alpha.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 模型配置编辑，密钥仅单向输入，空白保留同平台的原凭证。
 * @param expectedRevision 目录并发版本
 * @param platformId 已存在的平台标识
 * @param name 平台内唯一的显示名称
 * @param model API 模型标识
 * @param apiKey 新凭证，可留空但无凭证不能启用
 * @param clearApiKey 明确清空密钥，不能同时提交新密钥
 * @author Andersen
 */
public record SaveModelProfileRequest(@Min(1) long expectedRevision, @NotBlank @Size(max=36) String platformId,
        @NotBlank @Size(max=120) String name, @NotBlank @Size(max=120) String model,
        @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) @Size(max=4096) String apiKey, boolean clearApiKey) {
    /** 诊断输出不包含凭证。 */
    @Override public String toString() { return "SaveModelProfileRequest[credentials=redacted]"; }
}
