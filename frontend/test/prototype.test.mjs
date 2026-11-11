/** 原型业务边界与跨源消息回归，不调用模型或真实 Worker。@author Andersen */
import test from 'node:test';
import assert from 'node:assert/strict';
import { prototypePages, prototypePageKey, prototypeSource, prototypeScopeProblem, prototypeCommand, prototypeLocks, prototypeModeLabel, acceptPrototypeMessage, prototypePageAtPath, isPrototypeUncertain } from '../src/workspace/prototype.js';
import { createPrototypePoller } from '../src/workspace/prototype-poll.js';

/** 相同页面 ID 在不同模块下仍属于不同范围。 */
const prd = { modules: [{ id: 'm1', title: '库存', pages: [{ id: 'list', title: '库存列表', type: 'LIST' }] }, { id: 'm2', title: '流程', pages: [{ id: 'list', title: '审批', type: 'APPROVAL' }] }] };
/** 固定模板能力不从 PRD 类型反推。 */
const template = { id: 'zero-admin', revision: 2, maxPages: 10, supportedPageTypes: ['LIST', 'FORM', 'DETAIL'] };
/** 四锁与来源分别来自工作台、生命周期、原型聚合。 */
const detail = { workspace: { session: { id: 'session' }, version: { id: 'version', revision: 3 } }, lifecycle: { revision: 8, prdRevision: 4, prdCurrent: false, prd, prdBaseline: { id: 'baseline', sourceRevision: 2, prd: { modules: [] } } } };

// ALL 不能绕过能力缺口，显式子集必须保留完整复合身份。
test('scope keeps composite identity, rejects unsupported ALL and stale selected references', () => {
  const pages = prototypePages(prd, template);
  assert.notEqual(prototypePageKey(pages[0]), prototypePageKey(pages[1]));
  assert.match(prototypeScopeProblem(pages, 'ALL', []), /暂不支持/);
  assert.equal(prototypeScopeProblem(pages, 'SELECTED', [prototypePageKey(pages[0])]), '');
  assert.match(prototypeScopeProblem(pages, 'SELECTED', [prototypePageKey(pages[0]), '["gone","page"]']), /重新选择/);
  assert.match(prototypeScopeProblem([], 'ALL', []), /至少选择/);
  assert.match(prototypeScopeProblem(Array.from({ length: 11 }, (_, i) => ({ moduleId: 'm', pageId: String(i), supported: true })), 'ALL', []), /最多生成 10/);
});

// 失效基线不得优先，重新定稿后才可切换为 BASELINE。
test('new source prefers a valid baseline and otherwise uses the saved draft', () => {
  assert.equal(prototypeSource(detail).kind, 'DRAFT');
  assert.equal(prototypeSource(detail).prd, prd);
  assert.equal(prototypeSource({ ...detail, lifecycle: { ...detail.lifecycle, prdCurrent: true } }).kind, 'DRAFT');
  const valid = { ...detail, lifecycle: { ...detail.lifecycle, prdCurrent: true, prdBaseline: { ...detail.lifecycle.prdBaseline, sourceRevision: 4 } } };
  assert.deepEqual(prototypeSource(valid), { kind: 'BASELINE', id: 'baseline', prd: { modules: [] }, revision: 4 });
});

// 编辑旧来源与同步新需求的差异必须在命令中可验证。
test('edit freezes current source and scope while synchronization uses fresh PRD locks', () => {
  const prototype = { revision: 12, templateId: 'zero-admin', templateRevision: 1, current: { id: 'r2', source: { id: 'snapshot', scope: { mode: 'SELECTED', pageRefs: [{ moduleId: 'm1', pageId: 'list' }] } } } };
  const input = { detail, prototype, template, operation: 'EDIT', scope: { mode: 'ALL', pageRefs: [] }, target: { kind: 'PAGE', page: { moduleId: 'm1', pageId: 'list' }, overlayId: null }, content: ' 修改名称 ', requestId: 'request-123456789' };
  const edit = prototypeCommand(input);
  assert.equal(edit.templateRevision, template.revision);
  assert.deepEqual(edit.source, { kind: 'EXISTING', id: 'snapshot' });
  assert.deepEqual(edit.scope, prototype.current.source.scope);
  assert.equal(edit.baseRevisionId, 'r2'); assert.equal(edit.target.page.moduleId, 'm1'); assert.equal(edit.content, '修改名称');
  assert.deepEqual(prototypeLocks(detail, prototype), { expectedVersionRevision: 3, expectedLifecycleRevision: 8, expectedPrdRevision: 4, expectedPrototypeRevision: 12 });
  const sync = prototypeCommand({ ...input, operation: 'SYNC_PRD', requestId: 'new-request-12345', retryOfRunId: 'failed-run' });
  assert.deepEqual(sync.source, { kind: 'DRAFT', id: null }); assert.deepEqual(sync.scope, input.scope);
  assert.equal(sync.target.kind, 'PROTOTYPE'); assert.equal(sync.retryOfRunId, 'failed-run');
  assert.throws(() => prototypeCommand({ ...input, prototype: {} }), /没有可编辑/);
});

// 不能仅凭窗口来源或 sessionId 其中一个条件信任 iframe。
test('preview bridge validates trusted wrapper origin, source, both identities, fields, and paths', () => {
  const frame = {}, session = { sessionId: 'session', revisionId: 'revision', frameUrl: 'http://127.0.0.1:19999/frame/session/index.html' };
  const message = { type: 'NAVIGATED', sessionId: session.sessionId, revisionId: session.revisionId, path: '/items/item-1' };
  const event = { source: frame, origin: 'http://127.0.0.1:19999', data: message };
  assert.equal(acceptPrototypeMessage(event, frame, session), message);
  for (const invalid of [{ ...event, source: {} }, { ...event, origin: 'null' }, { ...event, origin: 'http://localhost' }, { ...event, data: { ...message, sessionId: 'other' } }, { ...event, data: { ...message, revisionId: 'other' } }, { ...event, data: { ...message, code: 'alert(1)' } }, { ...event, data: { ...message, type: 'EXECUTE' } }, { ...event, data: { ...message, path: '//evil.example' } }, { ...event, data: { ...message, path: {} } }]) assert.equal(acceptPrototypeMessage(invalid, frame, session), null);
  for (const path of ['/a/../secret', '/x?secret=1', '/%2Fsecret', `/${'a'.repeat(220)}`, undefined]) assert.equal(acceptPrototypeMessage({ ...event, data: { ...message, path } }, frame, session), null);
  assert.equal(acceptPrototypeMessage({ ...event, data: { ...message, message: 'extra' } }, frame, session), null);
  assert.equal(acceptPrototypeMessage({ ...event, data: { ...message, type: 'ERROR', message: 'error' } }, frame, session), null);
  const errorMessage = { type: 'ERROR', sessionId: session.sessionId, revisionId: session.revisionId, message: '预览执行错误' };
  assert.equal(acceptPrototypeMessage({ ...event, data: errorMessage }, frame, session), errorMessage);
  assert.equal(acceptPrototypeMessage({ ...event, data: { ...errorMessage, message: 'a'.repeat(301) } }, frame, session), null);
  assert.equal(acceptPrototypeMessage(event, frame, { ...session, frameUrl: 'javascript:alert(1)' }), null);
  const pages = [{ moduleId: 'm', pageId: 'list', path: '/items' }, { moduleId: 'm', pageId: 'detail', path: '/items/:id' }, { moduleId: 'm', pageId: 'new', path: '/items/new' }];
  assert.equal(prototypePageAtPath(pages, '/items/item-1?preview=1').pageId, 'detail');
  assert.equal(prototypePageAtPath(pages, '/items/new').pageId, 'new');
  assert.equal(prototypePageAtPath(pages, '/items/item-1/extra'), null);
  assert.equal(prototypePageAtPath(pages, '/items/'), null);
});

// 网络超时和明确拒绝使用不同重试契约，联调来源不能冒充模型。
test('uncertain requests are distinguishable from rejected commands and provenance is explicit', () => {
  assert.equal(isPrototypeUncertain({ code: 'ECONNABORTED' }), true);
  assert.equal(isPrototypeUncertain({ code: 'INTERNAL_ERROR' }), true);
  assert.equal(isPrototypeUncertain({ code: 'PROTOTYPE_CONFLICT' }), false);
  assert.equal(isPrototypeUncertain({ code: 'PROTOTYPE_RUNNER_UNAVAILABLE' }), false);
  assert.match(prototypeModeLabel('synthetic'), /非模型/); assert.match(prototypeModeLabel('demo'), /非模型/);
  assert.equal(prototypeModeLabel('live'), '模型生成');
});

// 主动刷新与定时刷新合并；终态不会再建轮询定时器。
test('poller merges reads and stops at terminal status', async () => {
  let release, reads = 0, callback, cancellations = 0;
  const values = [], queue = createPrototypePoller({ read: async () => { reads++; return new Promise(resolve => { release = resolve; }); }, applied: value => values.push(value), failed: assert.fail, pending: value => value.pending, schedule: fn => { callback = fn; return 1; }, cancel: () => cancellations++ });
  const first = queue.refresh(), second = queue.refresh(); assert.equal(first, second);
  await Promise.resolve(); release({ pending: true }); await first; assert.equal(reads, 1); assert.equal(typeof callback, 'function');
  callback = null; const final = queue.refresh(); await Promise.resolve(); release({ pending: false }); await final;
  assert.equal(callback, null); assert.equal(values.length, 2); assert.ok(cancellations >= 2); queue.dispose();
});

// 版本切换和卸载都须丢弃旧请求，后台失败不在前端自动重提。
test('poller rejects stale generations and disposal, reports failures without retry', async () => {
  const releases = [], applied = [], failures = []; let scheduled = 0;
  const queue = createPrototypePoller({ read: () => new Promise(resolve => releases.push(resolve)), applied: value => applied.push(value), failed: error => failures.push(error), pending: () => true, schedule: () => scheduled++, cancel: () => {} });
  const stale = queue.refresh(); await Promise.resolve(); queue.reset();
  const fresh = queue.refresh(); await Promise.resolve(); releases[0]('old'); await stale; releases[1]('new'); await fresh;
  assert.deepEqual(applied, ['new']); assert.equal(scheduled, 1);
  const disposed = queue.refresh(); await Promise.resolve(); queue.dispose(); releases[2]('disposed'); await disposed;
  assert.deepEqual(applied, ['new']);
  const failed = createPrototypePoller({ read: async () => { throw new Error('offline'); }, applied: assert.fail, failed: error => failures.push(error.message), pending: () => true, schedule: () => scheduled++, cancel: () => {} });
  await failed.refresh(); assert.deepEqual(failures, ['offline']); assert.equal(scheduled, 1); failed.dispose();
});
