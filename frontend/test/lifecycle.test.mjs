/** 产物来源、过期候选和草稿/基线下载的业务边界。@author Andersen */
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  manualPrd,
  canApplyPrd,
  prdMarkdown,
  cloneArtifact,
  artifactSource,
  canEditConversationPrd,
} from "../src/workspace/lifecycle.js";
/** PRD 重新开放与原型里程碑相互独立，旧评审入口不能借此开放编辑。 */
test("PRD 继续编辑保留原型阶段，但仍要求开放草稿且直接编辑模式", () => {
  const draft = { directPrd: true, prd: { scope: "当前范围" }, prdBaseline: null };
  assert.equal(canEditConversationPrd({ stage: "PRD" }, draft), true);
  assert.equal(canEditConversationPrd({ stage: "PROTOTYPE" }, draft), true);
  assert.equal(canEditConversationPrd({ stage: "PROTOTYPE" }, { ...draft, prdBaseline: { id: "confirmed" } }), false);
  assert.equal(canEditConversationPrd({ stage: "PRD" }, { ...draft, directPrd: false }), false);
  assert.equal(canEditConversationPrd({ stage: "REVIEW" }, draft), false);
  assert.equal(canEditConversationPrd({ stage: "PROTOTYPE" }, { ...draft, prd: null }), false);
  assert.equal(canEditConversationPrd(null, null), false);
});
const baseline = {
  id: "baseline-r1",
  scope: "CSV 导入",
  goal: "运营批量建档",
  review: { requirements: [{ id: "req-a", acceptance: "重复行提示" }] },
};
test("手工 PRD 只引用已确认需求，未知章节留空而非假完成", () => {
  const draft = manualPrd(baseline);
  assert.deepEqual(draft.requirementIds, ["req-a"]);
  assert.equal(draft.workflows, "");
  assert.match(draft.acceptance, /重复行提示/);
  assert.deepEqual(draft.unresolvedIssues, []);
  const copy = cloneArtifact(draft);
  copy.scope = "改变";
  assert.equal(draft.scope, "CSV 导入");
});
test("应用 PRD 候选要求有效评审基线与相同 PRD 保存编号", () => {
  const detail = {
    reviewCurrent: true,
    reviewBaseline: baseline,
    prdRevision: 2,
  };
  const run = { status: "SUCCEEDED", baselineId: baseline.id, prdRevision: 2 };
  assert.equal(canApplyPrd(run, detail), true);
  for (const change of [
    { status: "FAILED" },
    { baselineId: "old" },
    { prdRevision: 1 },
  ])
    assert.equal(canApplyPrd({ ...run, ...change }, detail), false);
  assert.equal(canApplyPrd(run, { ...detail, reviewCurrent: false }), false);
});
test("下载明确区分草稿和不可变基线，并保留模型来源和待确认事项", () => {
  const prd = manualPrd(baseline);
  prd.unresolvedIssues = ["异常路径未明确"];
  const draft = prdMarkdown(
    "一期",
    prd,
    baseline.id,
    false,
    "demo",
    "local-template",
  );
  assert.match(draft, /PRD 草稿，尚未确认/);
  assert.match(draft, /未调用大模型/);
  assert.match(draft, /异常路径未明确/);
  const confirmed = prdMarkdown("一期", prd, baseline.id, true, "manual", null);
  assert.match(confirmed, /已人工确认/);
  assert.match(confirmed, /历史评审来源：baseline-r1/);
  assert.equal(artifactSource("manual", null), "人工整理");
});

test("直接 PRD 下载没有空评审来源或虚构需求覆盖", () => {
  const prd = { ...manualPrd(baseline), requirementIds: [] };
  const text = prdMarkdown("客户导入", prd, null, false, "manual", null);
  assert.doesNotMatch(text, /评审来源|需求覆盖|历史需求来源|null|undefined/);
  assert.match(text, /## 交付范围/);
});
