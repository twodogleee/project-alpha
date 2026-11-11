/** 原型交付的精确确认、恢复边界与二进制下载回归，不调用模型。@author Andersen */
import test from 'node:test';
import assert from 'node:assert/strict';
import { prototypeDeliveryCommand, prototypeScopeLabels, prototypeConfirmationLabel } from '../src/workspace/prototype.js';
import { prototypeExportFilename, prototypeExportResponse, prototypeExportMaxBytes } from '../src/workspace/prototype-download.js';

/** 两个模块复用页面 ID，用冻结范围的顺序验证完整确认语义。 */
const prd = { modules: [{ id: 'a', title: '库存', pages: [{ id: 'list', title: '库存列表' }] }, { id: 'b', title: '审批', pages: [{ id: 'list', title: '审核列表' }] }] };
/** 当前有效定稿与四种锁来自不同聚合，不能相互替代。 */
const detail = { workspace: { version: { revision: 3 } }, lifecycle: { revision: 7, prdRevision: 4, prdCurrent: true, prdBaseline: { id: 'baseline-1', sourceRevision: 4, prd } } };
/** 交付修订冻结完整 SELECTED 范围，不因屏幕当前选页而缩小。 */
const revision = { id: 'r2', mode: 'synthetic', freshness: 'CURRENT', source: { id: 'snapshot', kind: 'BASELINE', prdBaselineId: 'baseline-1', scope: { mode: 'SELECTED', pageRefs: [{ moduleId: 'b', pageId: 'list' }, { moduleId: 'a', pageId: 'list' }] }, prd } };
/** 资格由服务端给出；前端仍拦截明显过期的当前身份与定稿。 */
const prototype = { current: revision, revision: 8, status: 'DRAFT', confirmationBlockers: [] };
/** 省略外层会话与模板参数，人工动作完全不依赖模型能力。 */
const input = { kind: 'CONFIRM', detail, prototype, revision, requestId: 'confirm-request' };

test('confirmation freezes the complete ordered scope, baseline and all four locks', () => {
  const command = prototypeDeliveryCommand(input);
  assert.deepEqual(command, { requestId: 'confirm-request', revisionId: 'r2', prdBaselineId: 'baseline-1', scope: revision.source.scope, expectedVersionRevision: 3, expectedLifecycleRevision: 7, expectedPrdRevision: 4, expectedPrototypeRevision: 8 });
  assert.notEqual(command.scope, revision.source.scope);
  command.scope.pageRefs[0].moduleId = 'changed';
  assert.equal(revision.source.scope.pageRefs[0].moduleId, 'b');
});

test('confirmation rejects historical revision, stale source and another baseline', () => {
  assert.throws(() => prototypeDeliveryCommand({ ...input, revision: { ...revision, id: 'r1' } }), /只能确认当前/);
  for (const freshness of ['STALE', 'REVIEW_REQUIRED']) assert.throws(() => prototypeDeliveryCommand({ ...input, revision: { ...revision, freshness } }), /有效 PRD 定稿/);
  assert.throws(() => prototypeDeliveryCommand({ ...input, revision: { ...revision, source: { ...revision.source, prdBaselineId: 'another' } } }), /有效 PRD 定稿/);
  assert.throws(() => prototypeDeliveryCommand({ ...input, revision: { ...revision, source: { ...revision.source, kind: 'DRAFT' } } }), /有效 PRD 定稿/);
});

test('reopened PRD, outdated finalization and server blockers cannot be confirmed', () => {
  for (const lifecycle of [{ ...detail.lifecycle, prdCurrent: false }, { ...detail.lifecycle, prdRevision: 5 }]) assert.throws(() => prototypeDeliveryCommand({ ...input, detail: { ...detail, lifecycle } }), /有效 PRD 定稿/);
  assert.throws(() => prototypeDeliveryCommand({ ...input, prototype: { ...prototype, confirmationBlockers: ['页面验证未通过', '存在未覆盖能力'] } }), /页面验证未通过；存在未覆盖能力/);
});

test('restore uses historical identity and fresh locks without rewriting source or confirmation', () => {
  const old = { ...revision, id: 'r1', freshness: 'STALE', source: { ...revision.source, kind: 'DRAFT' } };
  assert.deepEqual(prototypeDeliveryCommand({ ...input, kind: 'RESTORE', revision: old }), { requestId: 'confirm-request', targetRevisionId: 'r1', expectedVersionRevision: 3, expectedLifecycleRevision: 7, expectedPrdRevision: 4, expectedPrototypeRevision: 8 });
  assert.equal(old.source.kind, 'DRAFT');
  assert.throws(() => prototypeDeliveryCommand({ ...input, revision: null }), /可用修订/);
  assert.throws(() => prototypeDeliveryCommand({ ...input, kind: 'DELETE' }), /不支持/);
});

test('scope labels preserve frozen composite identity and explicitly fall back to missing IDs', () => {
  const labels = prototypeScopeLabels(revision);
  assert.deepEqual(labels, [{ key: '["b","list"]', title: '审批 / 审核列表' }, { key: '["a","list"]', title: '库存 / 库存列表' }]);
  assert.deepEqual(prototypeScopeLabels({ source: { scope: { pageRefs: [{ moduleId: 'gone', pageId: 'page' }] } } }), [{ key: '["gone","page"]', title: 'gone / page' }]);
});

test('only matching current effective confirmation is labelled confirmed', () => {
  const confirmed = { ...revision, latestConfirmation: { id: 'c1', revisionId: 'r2' } };
  const current = { ...prototype, current: confirmed, status: 'CONFIRMED', latestConfirmation: confirmed.latestConfirmation };
  assert.equal(prototypeConfirmationLabel(current, confirmed), '原型已确认，开发与验收未接入');
  assert.equal(prototypeConfirmationLabel({ ...current, status: 'DRAFT' }, confirmed), '曾有人工确认，当前需重新核对');
  assert.equal(prototypeConfirmationLabel(current, { ...confirmed, id: 'r1' }), '曾有人工确认，当前需重新核对');
  assert.equal(prototypeConfirmationLabel(prototype, revision), '原型草稿 · 未人工确认');
});

test('ZIP filename accepts a safe ASCII basename and rejects paths, controls and other extensions', () => {
  const fallback = 'prototype-source-r2.zip';
  assert.equal(prototypeExportFilename('attachment; filename="prototype-source-r2-1234abcd.zip"', fallback), 'prototype-source-r2-1234abcd.zip');
  for (const unsafe of ['../../secrets.zip', '/tmp/file.zip', 'folder\\file.zip', 'bad\nfile.zip', '导出.zip', 'file.html', '.hidden.zip']) assert.equal(prototypeExportFilename(`attachment; filename="${unsafe}"`, fallback), fallback);
});

test('successful ZIP download preserves bytes and returns a validated filename', async () => {
  const bytes = new Uint8Array([80, 75, 3, 4, 10]);
  const result = await prototypeExportResponse(new Response(bytes, { headers: { 'Content-Type': 'application/zip', 'Content-Disposition': 'attachment; filename="source.zip"' } }), 'fallback.zip');
  assert.equal(result.filename, 'source.zip'); assert.equal(result.blob.type, 'application/zip');
  assert.deepEqual(new Uint8Array(await result.blob.arrayBuffer()), bytes);
});

test('download safely unwraps JSON business errors instead of downloading an error document', async () => {
  for (const status of [200, 409]) await assert.rejects(() => prototypeExportResponse(new Response(JSON.stringify({ code: 'PROTOTYPE_READONLY', message: '回收文档禁止读取' }), { status, headers: { 'Content-Type': 'application/json; charset=UTF-8' } }), 'fallback.zip'), error => error.message === '回收文档禁止读取' && error.code === 'PROTOTYPE_READONLY');
  await assert.rejects(() => prototypeExportResponse(new Response('<html>internal details</html>', { status: 500 }), 'fallback.zip'), error => error.message === '导出失败，请刷新修订状态后重试' && error.code === 'PROTOTYPE_EXPORT_FAILED');
});

test('download rejects non-ZIP, empty and oversized responses without retaining their streams', async () => {
  await assert.rejects(() => prototypeExportResponse(new Response('html', { headers: { 'Content-Type': 'text/html' } }), 'fallback.zip'), /不是 ZIP/);
  await assert.rejects(() => prototypeExportResponse(new Response('', { headers: { 'Content-Type': 'application/zip' } }), 'fallback.zip'), /响应为空/);
  let canceled = false;
  const stream = new ReadableStream({ cancel() { canceled = true; } });
  await assert.rejects(() => prototypeExportResponse(new Response(stream, { headers: { 'Content-Type': 'application/zip', 'Content-Length': String(prototypeExportMaxBytes + 1) } }), 'fallback.zip'), /超过允许大小/);
  assert.equal(canceled, true);
});

test('streaming JSON errors without content length are bounded before showing business text', async () => {
  let canceled = false;
  const stream = new ReadableStream({ start(controller) { controller.enqueue(new Uint8Array(256 * 1024 + 1)); }, cancel() { canceled = true; } });
  await assert.rejects(() => prototypeExportResponse(new Response(stream, { status: 500, headers: { 'Content-Type': 'application/json' } }), 'fallback.zip'), /导出失败/);
  assert.equal(canceled, true);
});
