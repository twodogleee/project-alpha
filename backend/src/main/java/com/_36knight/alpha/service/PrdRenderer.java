package com._36knight.alpha.service;

import com._36knight.alpha.dto.RequirementDraft;
import com._36knight.alpha.project.ProjectDocument;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 将同一份需求数据渲染成 PRD 草稿，不额外推导业务规则。
 * @author Andersen
 */
@Component
public class PrdRenderer {
    /** 渲染已保存业务版本，区分人工确认与生成来源；不自动推导验收标准。 */
    public String render(ProjectDocument project) {
        StringBuilder document = new StringBuilder("# ").append(project.title().replace("\n", " ")).append("\n\n");
        document.append("> 项目：").append(project.name().replace("\n", " ")).append(" · 保存版本：v")
                .append(project.revision()).append("\n\n");
        document.append("demo".equals(project.mode()) ? "> 初始草稿为本地模板联调结果，未调用大模型。\n\n"
                : "> 初始草稿由 AI 生成；确认状态反映人工评审，不代表项目验收完成。\n\n");
        document.append("## 背景与目标\n\n").append(project.overview()).append("\n\n");
        appendItems(document, "已确认需求", project.requirements().stream()
                .filter(item -> item.status() == ProjectDocument.RequirementStatus.CONFIRMED)
                .map(this::requirementLine).toList());
        appendItems(document, "待确认需求", project.requirements().stream()
                .filter(item -> item.status() == ProjectDocument.RequirementStatus.PENDING)
                .map(this::requirementLine).toList());
        appendItems(document, "澄清问答", project.questions().stream().map(item -> item.text()
                + "\n回答：" + (item.answer().isBlank() ? "待回答" : item.answer())).toList());
        appendItems(document, "设计建议处理记录", project.suggestions().stream().map(item -> "["
                + switch (item.status()) { case PENDING -> "待处理"; case ACCEPTED -> "已接受，见关联需求"; case REJECTED -> "已拒绝"; }
                + "] " + item.text()).toList());
        document.append("## 验收标准\n\n需按已确认业务规则另行制定，本草稿不自动推导。\n\n");
        document.append("## 原始输入\n\n").append(project.originalContent()).append("\n");
        return document.toString();
    }

    /** 在导出中保留需求稳定 ID 及来源，建议接受不等于需求确认。 */
    private String requirementLine(ProjectDocument.RequirementItem item) {
        String source = switch (item.source()) {
            case GENERATED -> "初始草稿";
            case USER -> "人工新增";
            case SUGGESTION -> "接受建议 " + item.sourceId();
        };
        return item.text() + "\n来源：" + source + "；需求 ID：" + item.id();
    }

    /** 渲染可下载的 Markdown，并保留待确认事项与来源说明。 */
    public String render(RequirementDraft draft, boolean demo) {
        StringBuilder document = new StringBuilder("# ").append(draft.title()).append("\n\n");
        document.append(demo ? "> 本地模板联调结果，未调用大模型。\n\n" : "> AI 生成草稿，需评审后形成正式需求。\n\n");
        document.append("## 背景与目标\n\n").append(draft.overview()).append("\n\n");
        appendItems(document, "需求清单", draft.requirements());
        appendItems(document, "待确认问题", draft.openQuestions());
        appendItems(document, "未确认的设计建议", draft.assumptions());
        document.append("## 验收标准\n\n待确认业务规则后补充，不自动将建议转为验收标准。\n");
        return document.toString();
    }

    /** 为每个章节追加列表，并给空列表保留明确说明。 */
    private void appendItems(StringBuilder document, String title, List<String> items) {
        document.append("## ").append(title).append("\n\n");
        if (items.isEmpty()) {
            document.append("暂无。\n\n");
            return;
        }
        for (String item : items) {
            document.append("- ").append(item.replace("\n", "\n  ")).append("\n");
        }
        document.append("\n");
    }
}
