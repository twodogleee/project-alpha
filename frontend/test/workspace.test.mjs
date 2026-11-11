/** 会话任务与产物应用的回归验证，不镜像组件样式。@author Andersen */
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  mergeMessages,
  canApplyDraft,
  pendingRun,
  latestRunFailure,
  messageRunFailure,
  runCommand,
  stages,
  sessionPath,
  versionPath,
} from "../src/workspace/state.js";
test("轮询和历史分页按稳定 ID 去重并按消息序号合并", () => {
  assert.deepEqual(
    mergeMessages(
      [
        { id: "b", sequence: 2 },
        { id: "c", sequence: 3 },
      ],
      [
        { id: "a", sequence: 1 },
        { id: "b", sequence: 2 },
      ],
    ).map((m) => m.id),
    ["a", "b", "c"],
  );
});
test("失败和中断停止轮询，刷新只接管真实运行任务", () => {
  assert.equal(
    pendingRun([{ status: "FAILED" }, { status: "INTERRUPTED" }]),
    null,
  );
  const run = { id: "r", status: "RUNNING" };
  assert.equal(pendingRun([{ status: "SUCCEEDED" }, run]), run);
});
/** 失败记录保留，但只把最近一次失败作为当前提醒。 */
test("新任务排队、执行或成功后不再显示历史失败提醒", () => {
  const failed = { id: 'old', status: 'FAILED' };
  assert.equal(latestRunFailure([failed]), failed);
  for (const status of ['QUEUED', 'RUNNING', 'SUCCEEDED']) {
    assert.equal(latestRunFailure([{ id: 'new', status }, failed]), null);
  }
  assert.equal(latestRunFailure([]), null);
  assert.equal(latestRunFailure(undefined), null);
});
/** 同一次失败关闭后不会在轮询时恢复；后续新的失败仍需明确提示。 */
test("关闭提示不删除失败任务，新失败不受上次关闭影响", () => {
  const failed = { id: 'old', status: 'FAILED' }; const runs = [failed];
  assert.equal(latestRunFailure(runs, 'old'), null);
  assert.deepEqual(runs, [failed]);
  const interrupted = { id: 'new', status: 'INTERRUPTED' };
  assert.equal(latestRunFailure([interrupted, failed], 'old'), interrupted);
});
/** 历史错误归属用户请求，不绑定成功消息或助手回复。 */
test("历史失败可按原消息查看，成功任务与助手回复不标记失败", () => {
  const failed = { id: 'failed', status: 'FAILED' }; const success = { id: 'ok', status: 'SUCCEEDED' };
  assert.equal(messageRunFailure({ role: 'USER', runId: 'failed' }, [success, failed]), failed);
  assert.equal(messageRunFailure({ role: 'ASSISTANT', runId: 'failed' }, [failed]), null);
  assert.equal(messageRunFailure({ role: 'USER', runId: 'ok' }, [success, failed]), null);
  assert.equal(messageRunFailure({ role: 'USER', runId: 'missing' }, [failed]), null);
});
test("草稿候选应用要求同会话、成功结果和相同草稿修订", () => {
  const run = {
    sessionId: "s",
    kind: "DRAFT",
    status: "SUCCEEDED",
    draftRevision: 0,
  };
  const s = { id: "s", draftRevision: 0 };
  assert.equal(canApplyDraft(run, s), true);
  for (const changed of [
    { ...run, sessionId: "other" },
    { ...run, status: "FAILED" },
    { ...run, kind: "CHAT" },
    { ...run, draftRevision: 1 },
  ])
    assert.equal(canApplyDraft(changed, s), false);
  assert.equal(canApplyDraft(run, { ...s, archivedAt: "2026-10-13" }), false);
});
test("HTTP 超时重试同命令保持幂等键，输入和归属变化创建新命令", () => {
  let count = 0;
  const id = () => `request-${++count}`;
  const a = runCommand(null, "s", "CHAT", "输入", id);
  assert.equal(runCommand(a, "s", "CHAT", "输入", id), a);
  assert.notEqual(
    runCommand(a, "s", "CHAT", "新输入", id).requestId,
    a.requestId,
  );
  assert.notEqual(
    runCommand(a, "other", "CHAT", "输入", id).requestId,
    a.requestId,
  );
  assert.notEqual(runCommand(a, "s", "DRAFT", "", id).requestId, a.requestId);
});
test("评审、PRD 与原型已接入，开发阶段保持未接入", () => {
  assert.equal(stages.filter((s) => s.available).length, 3);
  assert.equal(stages[0].id, "REVIEW");
  assert.equal(stages[2].available, true);
  assert.equal(stages[3].available, false);
});
test("业务对象稳定路由携带项目归属，名称不参与身份", () => {
  assert.equal(
    sessionPath({ projectId: "p", id: "s", title: "可改名" }),
    "/projects/p/sessions/s",
  );
  assert.equal(
    versionPath({ projectId: "p", id: "v", label: "一期" }),
    "/projects/p/versions/v",
  );
});

test("关联版本范围变化或探索会话改绑后，旧候选不能继续应用", () => {
  const run = {
    sessionId: "s",
    kind: "DRAFT",
    status: "SUCCEEDED",
    draftRevision: 1,
    versionId: "v",
    versionRevision: 2,
  };
  const session = { id: "s", draftRevision: 1, versionId: "v" };
  assert.equal(canApplyDraft(run, session, { revision: 2 }), true);
  assert.equal(canApplyDraft(run, session, { revision: 3 }), false);
  assert.equal(
    canApplyDraft({ ...run, versionId: null }, session, { revision: 2 }),
    false,
  );
});
