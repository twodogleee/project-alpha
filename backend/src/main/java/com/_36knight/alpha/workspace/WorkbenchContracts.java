package com._36knight.alpha.workspace;

import jakarta.validation.Valid;
import com._36knight.alpha.workspace.PrdStructure.ModuleChange;
import jakarta.validation.constraints.*;

import java.util.List;
import java.util.Map;

import static com._36knight.alpha.workspace.LifecycleContracts.*;

/**
 * 会话直接编辑同一份 PRD；建议与正文分离，确认只能由用户发起。@author Andersen
 */
public final class WorkbenchContracts {
    /**
     * 契约容器不能实例化。
     */
    private WorkbenchContracts() {
    }

    /**
     * 文档自动保存只提交当前正文和双修订锁，不存在独立需求采集稿。
     */
    public record Draft(@Min(1) long expectedRevision, @Min(1) long expectedVersionRevision, @Valid @NotNull Prd prd) {
    }

    /**
     * DOCUMENT 默认讨论全文；章节名表达焦点，允许同步修改确实受影响的关联章节。
     */
    public record Message(@NotBlank @Pattern(regexp = "[A-Za-z0-9-]{16,64}") String requestId,
                          @Min(1) long expectedRevision, @Min(1) long expectedVersionRevision,
                          @NotBlank @Size(max = 220) String target,
                          @NotBlank @Size(max = 12000) String content,
                          @Size(max = 3) List<@NotNull @Valid Attachment> attachments) {
        /**
         * 旧客户端不提交附件时按空列表处理。
         */
        public Message {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }

        /**
         * 保留现有纯文本调用方。
         */
        public Message(String requestId, long expectedRevision, long expectedVersionRevision, String target, String content) {
            this(requestId, expectedRevision, expectedVersionRevision, target, content, List.of());
        }
    }

    /**
     * UTF-8 Markdown/TXT 参考原文；名称不包含路径，资料中的命令不作为用户操作授权。
     */
    public record Attachment(@NotBlank @Size(max = 180) String name, @NotBlank @Size(max = 100000) String content) {
    }

    /**
     * 待用户采纳的正文建议，不含模型可设置的确认或接受状态。
     */
    public record Proposal(@NotBlank @Size(max = 80) String id,
                           @NotBlank @Size(max = 220) String section,
                           @NotBlank @Size(max = 4000) String text) {
    }

    /**
     * 模型只返回需要替换的章节；null 表示不改动。建议数组仅包含新增建议。
     */
    public record Reply(@NotBlank @Size(max = 12000) String reply,
                        @Size(max = 300) Map<String, @NotNull @Size(max = 10000) String> sections,
                        @Size(max = 100) List<@NotBlank @Size(max = 4000) String> unresolvedIssues,
                        @Size(max = 30) List<@Valid Proposal> suggestions,
                        @Size(max = 30) List<@NotNull @Valid ModuleChange> modules,
                        @Size(max = 100) Map<@NotBlank @Size(max = 4000) String, @NotNull @Size(max = 20) List<@NotBlank @Size(max = 220) String>> issueLinks) {
        /**
         * 兼容不修改问题关联的结构增量。
         */
        public Reply(String reply, Map<String, String> sections, List<String> unresolvedIssues, List<Proposal> suggestions, List<ModuleChange> modules) {
            this(reply, sections, unresolvedIssues, suggestions, modules, null);
        }

        /**
         * 兼容已有任务和只编辑通用正文的模型输出。
         */
        public Reply(String reply, Map<String, String> sections, List<String> unresolvedIssues, List<Proposal> suggestions) {
            this(reply, sections, unresolvedIssues, suggestions, null);
        }
    }

    /**
     * 采纳将建议原文追加到指定章节；忽略只移除建议，两者均记录历史。
     */
    public record Decision(@Min(1) long expectedRevision, @Min(1) long expectedVersionRevision,
                           @NotBlank @Size(max = 80) String suggestionId,
                           @NotBlank @Pattern(regexp = "ACCEPT|DISMISS") String decision) {
    }

    /**
     * 任务固定当前文档、待处理建议与上下文，不包含凭证，不进入公开响应。
     */
    public record Context(String target, long revision, long versionRevision, Prd prd,
                          List<Proposal> suggestions, String background, List<String> messages, String input,
                          List<Attachment> attachments, List<Attachment> referenceAttachments) {
        /**
         * 兼容旧任务；本轮附件与沿用的最近一批参考资料分别记录。
         */
        public Context {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
            referenceAttachments = referenceAttachments == null ? List.of() : List.copyOf(referenceAttachments);
        }

        /**
         * 兼容原有无附件的上下文构造。
         */
        public Context(String target, long revision, long versionRevision, Prd prd, List<Proposal> suggestions, String background, List<String> messages, String input) {
            this(target, revision, versionRevision, prd, suggestions, background, messages, input, List.of(), List.of());
        }
    }

    /**
     * 冲突时保存候选供核对；changedSections 是实际变化章节，不使用模型自报结果。
     */
    public record Result(String reply, boolean applied, String target, Prd prd,
                         List<Proposal> suggestions, List<String> changedSections, String modelReply) {
    }

    /**
     * 会话、文档和任务一次读取。
     */
    public record Detail(WorkspaceViews.SessionDetail workspace, LifecycleContracts.Detail lifecycle) {
    }
}
