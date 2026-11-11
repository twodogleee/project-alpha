package com._36knight.alpha.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 分析并保存项目首个草稿的输入。
 * @param name 项目名称
 * @param content 原始需求，保存后不随评审修改
 * @author Andersen
 */
public record CreateProjectRequest(@NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 12000) String content) { }
