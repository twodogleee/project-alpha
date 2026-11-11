package com._36knight.alpha.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 运行选择与模型编辑分开保存，不隐式调用模型。
 * @param expectedRevision 目录并发版本
 * @param mode demo 或 ai
 * @param activeModelId 选择的模型，真实模式必须提供
 * @author Andersen
 */
public record SaveModelSelectionRequest(@Min(1) long expectedRevision, @NotBlank @Pattern(regexp="demo|ai") String mode,
        @Size(max=36) String activeModelId) { }
