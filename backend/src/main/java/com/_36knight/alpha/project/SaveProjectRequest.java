package com._36knight.alpha.project;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import com._36knight.alpha.project.ProjectDocument.RequirementStatus;
import com._36knight.alpha.project.ProjectDocument.SuggestionStatus;

/**
 * 完整替换可编辑内容；原始输入、来源、版本与 PRD 不接受客户端修改。
 * @param expectedRevision 编辑基于的版本，冲突时拒绝覆盖
 * @param name 项目名称
 * @param title 文档标题
 * @param overview 背景目标
 * @param requirements 完整需求清单，可删除普通需求
 * @param questions 完整问题清单，只能修改回答
 * @param suggestions 完整建议清单
 * @author Andersen
 */
public record SaveProjectRequest(@Min(1) long expectedRevision,
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 120) String title,
        @NotBlank @Size(max = 20000) String overview,
        @NotNull @Size(max = 100) List<@NotNull @Valid RequirementEdit> requirements,
        @NotNull @Size(max = 30) List<@NotNull @Valid QuestionEdit> questions,
        @NotNull @Size(max = 30) List<@NotNull @Valid SuggestionEdit> suggestions) {

    /**
     * 需求编辑输入，来源不可由客户端指定。
     * @param id 原有项 ID，新增项为 null
     * @param text 需求正文
     * @param status 请求的确认状态，已确认正文改变时服务端强制待确认
     */
    public record RequirementEdit(@Size(max = 36) String id,
            @NotBlank @Size(max = 12000) String text, @NotNull RequirementStatus status) { }

    /**
     * 澄清问题的人工回答。
     * @param id 项目内现有问题 ID
     * @param answer 回答正文，可为空
     */
    public record QuestionEdit(@NotBlank @Size(max = 36) String id,
            @NotNull @Size(max = 12000) String answer) { }

    /**
     * 建议编辑或处理动作。
     * @param id 项目内现有建议 ID
     * @param text 接受前可编辑正文
     * @param status 接受、拒绝或待处理
     */
    public record SuggestionEdit(@NotBlank @Size(max = 36) String id,
            @NotBlank @Size(max = 2000) String text, @NotNull SuggestionStatus status) { }
}
