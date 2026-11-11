package com._36knight.alpha.project;

import java.time.Instant;
import java.util.List;

/**
 * 已保存的业务文档快照；不承担 Graph 恢复或正式审批。
 * @param id 项目稳定标识
 * @param name 项目名称
 * @param originalContent 创建时的原始输入，评审不覆盖来源
 * @param mode 生成来源 demo/dashscope/openai-compatible，不随当前配置改写
 * @param runId 创建草稿的运行标识
 * @param revision 业务保存版本，从 1 开始
 * @param createdAt 创建时间
 * @param updatedAt 当前版本保存时间
 * @param title PRD 标题
 * @param overview 背景与目标
 * @param requirements 独立需求项
 * @param questions 澄清问题与人工回答
 * @param suggestions 设计建议与处理状态
 * @param prdMarkdown 由当前已保存版本渲染的文档
 * @author Andersen
 */
public record ProjectDocument(String id, String name, String originalContent, String mode, String runId,
        long revision, Instant createdAt, Instant updatedAt, String title, String overview,
        List<RequirementItem> requirements, List<Clarification> questions,
        List<DesignSuggestion> suggestions, String prdMarkdown) {

    /** 确认只表示用户确认此项，不能代替项目验收。 */
    public enum RequirementStatus { PENDING, CONFIRMED }
    /** GENERATED 为生成草稿，USER 为手工新增，SUGGESTION 为接受建议所得。 */
    public enum RequirementSource { GENERATED, USER, SUGGESTION }
    /** ACCEPTED 建议保留接受时正文及关联需求，不能撤销或丢弃来源。 */
    public enum SuggestionStatus { PENDING, ACCEPTED, REJECTED }

    /**
     * 一项可独立评审的需求。
     * @param id 稳定 ID
     * @param text 可编辑正文
     * @param status 人工确认状态
     * @param source 服务端维护的来源类型
     * @param sourceId 来源建议 ID，其他来源为空
     */
    public record RequirementItem(String id, String text, RequirementStatus status,
            RequirementSource source, String sourceId) { }

    /**
     * 澄清回答保留用户输入，不自动形成已确认需求。
     * @param id 稳定 ID
     * @param text 生成时的问题正文
     * @param answer 用户回答，空字符串表示未回答
     */
    public record Clarification(String id, String text, String answer) { }

    /**
     * 尚需用户处理的设计建议。
     * @param id 稳定 ID
     * @param text 接受前可编辑的建议正文
     * @param status 处理状态
     */
    public record DesignSuggestion(String id, String text, SuggestionStatus status) { }
}
