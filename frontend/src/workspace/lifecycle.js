/** 生命周期编辑契约与下载格式，所有内容按纯文本处理。@author Andersen */
import { stages } from "./state.js";
import { documentBlocks, prdLabels, legacyGlobalSections, prdTemplate } from "./prd-template.js";
/** 阶段前进只来自服务端人工确认；已到达原型不意味着交付完成。 */
export function stageCaption(stage, current) {
  if (!stage.available)
    return stage.id === current ? "当前阶段 · 待接入" : "尚未接入";
  if (stage.id === current) return "当前阶段";
  return stages.findIndex((s) => s.id === stage.id) <
    stages.findIndex((s) => s.id === current)
    ? "基线已确认"
    : "等待基线确认";
}
/** 历史九章节名称来自统一模板目录。 */
export const prdSections = legacyGlobalSections;
/** 独立编辑副本，不共享服务器对象引用。 */
export const cloneArtifact = (value) => JSON.parse(JSON.stringify(value));
/** 直接 PRD 以当前确认指针区分开放草稿；归档、删除和页面忙碌仍由调用方限制。 */
export function canEditConversationPrd(version, detail) {
  return Boolean(detail?.directPrd && detail.prd && !detail.prdBaseline && ["PRD", "PROTOTYPE"].includes(version?.stage));
}
/** 手工 PRD 始终引用已确认需求，未定义章节留空。 */
export function manualPrd(baseline) {
  return {
    background: baseline.scope,
    objectives: baseline.goal,
    scope: baseline.scope,
    workflows: "",
    rules: "",
    exceptions: "",
    acceptance: baseline.review.requirements
      .map((r) => `${r.id} · ${r.acceptance}`)
      .join("\n"),
    nonfunctional: "",
    exclusions: "",
    requirementIds: baseline.review.requirements.map((r) => r.id),
    unresolvedIssues: [],
  };
}
/** 只有有效上游和相同保存编号的成功候选可以应用。 */
export function canApplyPrd(run, detail) {
  return Boolean(
    run?.status === "SUCCEEDED" &&
      detail?.reviewCurrent &&
      run.baselineId === detail.reviewBaseline?.id &&
      run.prdRevision === detail.prdRevision,
  );
}
/** 来源描述明确区分模板、手工与实际模型。 */
export function artifactSource(mode, model) {
  return mode === "demo"
    ? "本地模板联调，未调用大模型"
    : mode === "manual"
      ? "人工整理"
      : `${mode || "候选"} · ${model || ""}`;
}
/** PRD 草稿下载和不可变确认下载使用不同标识，覆盖来源一起导出。 */
export function prdMarkdown(label, prd, source, confirmed, mode, model) {
  return (
    `# ${label} · PRD\n\n> ${confirmed ? "已人工确认的 PRD 基线" : "PRD 草稿，尚未确认"}${source ? `\n> 历史评审来源：${source}` : ''}\n> 来源：${artifactSource(mode, model)}\n> 模板：${prdTemplate(prd).name} · v${prdTemplate(prd).revision}\n\n` +
    documentBlocks(prd, prdSections)
      .map(block => `${"#".repeat(block.level)} ${block.title}\n${block.text === undefined ? "" : block.text || "尚未填写"}\n`)
      .join("\n") +
    (prd.requirementIds?.length ? `\n## 历史需求来源\n${prd.requirementIds.join("\n")}\n` : "") + `\n## 待确认事项\n${prd.unresolvedIssues.map(issue => `${issue}${prd.issueLinks?.[issue]?.length ? `（关联：${prd.issueLinks[issue].map(path => prdLabels(prd)[path] || path).join("、")}）` : ""}`).join("\n") || "无"}`
  );
}
