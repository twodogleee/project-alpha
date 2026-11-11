package com._36knight.alpha.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 需求分析节点的结构化结果，推测与事实分别存放。
 *
 * @param title         草稿标题，最大 120 字符
 * @param overview      对用户输入的概括
 * @param requirements  从输入提取的需求，不能包含自行编造的规则
 * @param openQuestions 影响后续设计的待确认问题
 * @param assumptions   尚未被用户确认的设计建议
 * @author Andersen
 */
public record RequirementDraft(
    @NotBlank @Size(max = 120) String title,
    @NotBlank @Size(max = 20000) String overview,
    @NotEmpty @Size(max = 100) List<@NotBlank @Size(max = 12000) String> requirements,
    @NotNull @Size(max = 30) List<@NotBlank @Size(max = 2000) String> openQuestions,
    @NotNull @Size(max = 30) List<@NotBlank @Size(max = 2000) String> assumptions) {
}
