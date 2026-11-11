package com._36knight.alpha.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 一次需求草稿生成的输入；初始化阶段不保存对话历史。
 * @param content 用户原始需求，最大 12000 字符
 * @author Andersen
 */
public record AnalyzeRequirementRequest(@NotBlank @Size(max = 12000) String content) {
}
