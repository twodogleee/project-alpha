package com._36knight.alpha.workspace;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 只读页面规则工具的输入与结果；PRD 保存修订不同于需求版本锁和人工定稿。
 * @author Andersen
 */
public final class PageRuleContracts {
    /** 契约容器不允许实例化。 */
    private PageRuleContracts() { }

    /** 模型只定位绑定文档内的章节；项目与需求版本不属于模型参数。 */
    public record Query(@NotNull @Min(1) Long prdRevision,
            @NotBlank @Pattern(regexp = PrdStructure.ID) String moduleId,
            @NotBlank @Pattern(regexp = PrdStructure.ID) String pageId,
            @NotBlank @Pattern(regexp = "[a-z]{1,40}") String section) { }

    /** FOUND 仅表示有正文且无关联待确认项，不保证语义完整或已经人工确认。 */
    public enum Status {
        FOUND, RULE_UNDEFINED, REVISION_NOT_FOUND, PAGE_NOT_FOUND,
        ACCESS_DENIED, INVALID_ARGUMENT, UNAVAILABLE
    }

    /** 原文出处，身份和保存时间来自数据库，不采用模型自行声称的来源。 */
    public record Source(String versionId, long prdRevision, String moduleId,
            String pageId, String section,
            @JsonFormat(shape = JsonFormat.Shape.STRING) OffsetDateTime savedAt) { }

    /** 可序列化的工具观察；无结果时不携带正文与来源，异常不暴露底层连接信息。 */
    public record Result(Status status, String message, Source source,
            String text, List<String> unresolvedIssues) {
        /** 错误或查无记录时统一返回空证据，避免旧值被当作本次结果。 */
        public static Result empty(Status status, String message) {
            return new Result(status, message, null, "", List.of());
        }
    }
}
