/**
 * P5/P6 独立 H2 工作台的浏览器验收；真实 HTTP、持久化、恢复、确认、导出及预览，执行器明确为 synthetic。
 * 启动 prototypeBrowserTest 与 Vite 5179 后运行此脚本，不调用模型、不操作业务数据库。
 * @author Andersen
 */
import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { resolve } from 'node:path';
import { chromium } from 'playwright';

/** 仓库根路径以脚本位置推导，命令可从任意目录运行。 */
const root = resolve(import.meta.dirname, '../..');
/** 隔离 Spring 测试输出的安全身份，不包含执行令牌或凭证。 */
const fixturePath = resolve(root, 'backend/build/reports/prototype-browser-fixture.json');
/** 输出只用于本次合成浏览器证据，不是模型生成验收。 */
const reportDir = resolve(root, 'backend/build/reports');
const output = resolve(reportDir, 'prototype-delivery-browser.json');
/** 观察报告所有检查边界，失败保留阶段与截图。 */
const report = { schemaVersion: 1, mode: 'synthetic', generation: 'test-fixture', status: 'RUNNING',
  notice: '合成执行器沿用已验证静态样例；本次不证明模型生成或页面代码修改效果', checks: [], consoleErrors: [], httpFailures: [] };
/** 浏览器状态只在本次进程存在，不污染用户登录态或本机工作台。 */
let browser, page, fixture;

/** 有界等待实际 API 或 DOM 状态，不能用固定延时宣称后台任务完成。 */
async function until(read, accept, description, timeout = 30_000) {
  const end = Date.now() + timeout;
  let value;
  while (Date.now() < end) {
    value = await read();
    if (accept(value)) return value;
    await new Promise(resolve => setTimeout(resolve, 150));
  }
  const safeState = value && typeof value === 'object'
    ? { status: value.status, exists: value.exists, revision: value.revision, currentRevision: value.current?.revisionNumber, freshness: value.current?.freshness }
    : value;
  throw new Error(`等待超时：${description}；最后状态 ${JSON.stringify(safeState)}`);
}

/** 所有直接读取与布置动作只访问 fixture 声明的隔离服务。 */
async function api(path, data, method = data ? 'POST' : 'GET') {
  assert.equal(fixture.backendUrl, 'http://127.0.0.1:18081');
  const response = await page.request.fetch(`${fixture.backendUrl}/api/alpha/workspace${path}`, { method, data });
  const body = await response.json();
  assert.equal(response.ok(), true, `${method} ${path}: ${JSON.stringify(body)}`);
  return body.data;
}

/** 每项成功断言立即记录，可定位失败而不丢失此前证据。 */
async function checked(name, extra = {}) {
  report.checks.push({ name, status: 'PASSED', ...extra });
  await writeFile(output, JSON.stringify(report, null, 2));
}

/** 当前工作台的原型真值来自服务端，不读取 Vue 内部状态。 */
const prototype = () => api(`/versions/${fixture.versionId}/prototype`);
/** 待审修订身份必须实际落库，随后再验证相应 UI。 */
const currentRevision = async (number) => (await until(prototype, value => value.current?.revisionNumber === number, `原型修订 ${number}`, 45_000)).current;
/** 已保存正文和生命周期锁来自真实读取接口。 */
const workbench = () => api(`/sessions/${fixture.sessionId}/workbench`);
/** 启动或刷新后按公开入口切换原型产物。 */
async function openPrototype() {
  await page.getByTestId('prototype-tab').click();
  await page.getByTestId('prototype-panel').waitFor({ state: 'visible' });
}
/** 公开 iframe 是平台包装层；生成内容位于其内部 opaque iframe。 */
const previewWrapper = () => page.frameLocator('[data-testid="prototype-preview-frame"]');
/** 跨源生成页面只交给浏览器嵌套 frame locator 操作。 */
const preview = () => previewWrapper().frameLocator('iframe');
/** 记录可供人工复核的视图，不裁去来源标签与会话区。 */
async function screenshot(name) {
  await page.screenshot({ path: resolve(reportDir, `prototype-delivery-browser-${name}.png`), fullPage: true });
}

/** 已落库的控制命令故意丢失一次回包；复用完整原命令后只能返回同一结果。 */
async function loseControlResponse(kind, submit) {
  const commands = [], results = [];
  const pattern = `**/prototype/${kind}`;
  await page.route(pattern, async route => {
    commands.push(route.request().postDataJSON());
    const response = await route.fetch();
    assert.equal(response.ok(), true, await response.text());
    results.push((await response.json()).data);
    if (commands.length === 1) await route.abort('failed');
    else await route.fulfill({ response });
  });
  try {
    await submit();
    const retry = page.getByRole('button', { name: '查询并重试原请求', exact: true });
    await retry.waitFor({ state: 'visible' });
    assert.equal(await page.getByTestId('prd-tab').isDisabled(), true);
    await retry.click();
    await retry.waitFor({ state: 'hidden' });
    assert.equal(commands.length, 2); assert.deepEqual(commands[0], commands[1]);
    assert.deepEqual(results[0], results[1]);
    return results[0];
  } finally { await page.unroute(pattern); }
}

/** 经公开界面核对确切范围，人工勾选后才提交确认命令。 */
async function confirmPrototype(loseResponse = false) {
  await page.getByTestId('prototype-delivery').click();
  await page.getByTestId('prototype-confirm-open').click();
  assert.equal(await page.getByTestId('prototype-confirm-submit').isDisabled(), true);
  await page.getByTestId('prototype-confirm-ack').check();
  const submit = () => page.getByTestId('prototype-confirm-submit').click();
  if (loseResponse) await loseControlResponse('confirm', submit); else await submit();
  await until(prototype, value => value.status === 'CONFIRMED', '人工确认落库');
  await until(() => page.getByTestId('prototype-confirmation-status').textContent(), value => value.includes('已确认'), '当前有效确认展示');
}

/** 历史恢复必须经过独立确认界面，原历史记录保留不变。 */
async function restorePrototype(revisionId, number, loseResponse = false) {
  await page.getByTestId('prototype-history').click();
  await page.getByTestId(`prototype-history-revision-${revisionId}`).click();
  await page.getByTestId('prototype-restore-open').click();
  const submit = () => page.getByTestId('prototype-restore-submit').click();
  if (loseResponse) await loseControlResponse('restore', submit); else await submit();
  const restored = await currentRevision(number);
  await page.getByTestId('prototype-current').waitFor({ state: 'hidden' });
  assert.equal(restored.restoredFromRevisionId, revisionId);
  assert.equal(restored.sourceRunId, null);
  assert.equal((await prototype()).status, 'DRAFT');
  return restored;
}

/** 下载来自真实二进制接口并保存到报告目录，便于独立解包复核。 */
async function downloadPackage(format, revision) {
  const pending = page.waitForEvent('download');
  await page.getByTestId(`prototype-export-${format}`).click();
  const download = await pending;
  assert.match(download.suggestedFilename(), new RegExp(`^prototype-${format}-r${revision.revisionNumber}-[a-z0-9-]+\\.zip$`));
  await download.saveAs(resolve(reportDir, `prototype-delivery-browser-${format}.zip`));
  assert.equal(await download.failure(), null);
}

/** 在生成 iframe 内主动尝试越界；探针目标都是本机临时服务，不接触互联网。 */
async function verifyGeneratedIsolation() {
  const received = [], policyErrors = [], sockets = new Set();
  const probe = createServer((request, response) => { received.push(request.url); response.writeHead(204); response.end(); });
  // Chromium 可能为导航建立尚未发送 HTTP 的预连接；测试结束必须回收自己创建的连接。
  probe.on('connection', socket => { sockets.add(socket); socket.once('close', () => sockets.delete(socket)); });
  await new Promise(resolve => probe.listen(0, '127.0.0.1', resolve));
  const probeOrigin = `http://127.0.0.1:${probe.address().port}`;
  const capture = message => { if (message.type() === 'error' && message.text().includes('Content Security Policy')) policyErrors.push(message.text()); };
  page.on('console', capture);
  try {
    const handle = await previewWrapper().locator('iframe').elementHandle();
    const content = await handle.contentFrame();
    const restrictions = await content.evaluate(async ({ probeOrigin, alphaUrl }) => {
      const outcome = { ownNonceCount: document.querySelectorAll('script[nonce]').length };
      const access = {
        parentDocument: () => window.parent.document,
        topDocument: () => window.top.document,
        localStorage: () => window.localStorage.length,
        sessionStorage: () => window.sessionStorage.length,
        cookie: () => document.cookie,
        wrapperNonce: () => window.parent.document.querySelector('script[nonce]').nonce,
      };
      for (const [name, read] of Object.entries(access)) {
        try { read(); outcome[name] = 'ALLOWED'; } catch (error) { outcome[name] = error.name; }
      }
      for (const [name, url] of Object.entries({ alphaFetch: alphaUrl, externalFetch: `${probeOrigin}/fetch` })) {
        try { await fetch(url); outcome[name] = 'ALLOWED'; } catch (error) { outcome[name] = error.name; }
      }
      outcome.externalScript = await new Promise(resolve => {
        const script = document.createElement('script');
        script.src = `${probeOrigin}/dynamic-script.js`; script.nonce = 'p5-cannot-read-wrapper-nonce';
        script.onload = () => resolve('ALLOWED'); script.onerror = () => resolve('BLOCKED');
        document.head.appendChild(script);
      });
      return outcome;
    }, { probeOrigin, alphaUrl: `${fixture.backendUrl}/api/alpha/workspace/projects` });
    assert.equal(restrictions.ownNonceCount, 0);
    for (const name of ['parentDocument', 'topDocument', 'localStorage', 'sessionStorage', 'cookie', 'wrapperNonce']) assert.equal(restrictions[name], 'SecurityError', name);
    for (const name of ['alphaFetch', 'externalFetch']) assert.equal(restrictions[name], 'TypeError', name);
    assert.equal(restrictions.externalScript, 'BLOCKED');
    assert.ok(policyErrors.some(text => text.includes(`${fixture.backendUrl}/api/alpha/workspace/projects`) && text.includes('connect-src')), 'Alpha接口请求须被CSP而非CORS阻断');
    await content.evaluate(url => { window.location.href = url; }, `${probeOrigin}/navigate`).catch(error => {
      if (!error.message.includes('Execution context was destroyed')) throw error;
    });
    await until(() => policyErrors, errors => errors.some(text => text.includes('frame-src') && text.includes(probeOrigin)), '包装层阻断生成iframe跨源导航');
    assert.deepEqual(received, []);
    await checked('生成iframe拒绝父DOM/存储/cookie/包装层nonce；fetch、外域脚本和导航均被CSP阻断且本机探针收到零请求', { restrictions, blockedPolicyCount: policyErrors.length, probeRequests: received.length });
  } finally {
    page.off('console', capture);
    for (const socket of sockets) socket.destroy();
    await new Promise(resolve => probe.close(resolve));
  }
}

try {
  await mkdir(reportDir, { recursive: true });
  fixture = JSON.parse(await readFile(fixturePath, 'utf8'));
  assert.equal(fixture.status, 'READY'); assert.equal(fixture.mode, 'synthetic'); assert.equal(fixture.generation, 'test-fixture');
  assert.ok(fixture.url.startsWith('http://127.0.0.1:5179/#/projects/'));
  browser = await chromium.launch({ headless: true, ...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE } : { channel: 'chrome' }) });
  report.browserVersion = browser.version();
  page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
  page.on('pageerror', error => report.consoleErrors.push({ message: error.message, stack: error.stack, afterCheck: report.checks.at(-1)?.name }));
  page.on('response', response => { if (response.url().includes('/api/alpha/') && response.status() >= 400) report.httpFailures.push({ status: response.status(), url: response.url() }); });
  // 重跑只在同一个隔离 H2 中创建新场景，不能覆盖前一次修订或复用已归档会话。
  if ((await prototype()).exists) {
    const seed = await workbench();
    const project = await api('/projects', { name: 'P5合成浏览器重跑', background: '独立H2合成验收；未调用真实模型。' });
    const version = await api(`/projects/${project.id}/versions`, { label: '项目管理原型', goal: '验证工作台原型交互', content: '项目列表与项目详情' });
    const session = await api(`/projects/${project.id}/sessions`, { title: 'P5合成原型讨论', versionId: version.id });
    const opened = await api(`/sessions/${session.id}/workbench/open`, {}, 'POST');
    await api(`/sessions/${session.id}/workbench`, { expectedRevision: opened.lifecycle.revision, expectedVersionRevision: opened.workspace.version.revision, prd: seed.lifecycle.prd }, 'PUT');
    fixture = { ...fixture, projectId: project.id, versionId: version.id, sessionId: session.id,
      url: `http://127.0.0.1:5179/#/projects/${project.id}/sessions/${session.id}` };
  }
  report.scenario = { projectId: fixture.projectId, versionId: fixture.versionId, sessionId: fixture.sessionId, url: fixture.url };
  await page.goto(fixture.url);
  await page.getByTestId('prd-tab').waitFor({ state: 'visible' });
  const before = await workbench();
  assert.equal(before.lifecycle.prd.modules[0].pages.length, 2);
  assert.equal((await prototype()).exists, false);
  await page.getByTestId('workbench-message').fill('暂存的PRD讨论，不提交模型。');
  await openPrototype();
  await page.getByTestId('prd-tab').click();
  assert.equal(await page.getByTestId('workbench-message').inputValue(), '暂存的PRD讨论，不提交模型。');
  await page.getByTestId('workbench-message').fill('');
  await openPrototype();
  await checked('PRD与原型切换保留未发送讨论；首次读取不创建原型聚合');

  await page.getByTestId('prototype-generate-config').click();
  await page.getByTestId('prototype-scope-selected').click();
  const listPage = page.getByTestId('prototype-scope-page-items-list');
  const detailPage = page.getByTestId('prototype-scope-page-items-detail');
  for (const checkbox of [listPage, detailPage]) {
    if (await checkbox.isChecked()) await checkbox.uncheck();
  }
  assert.equal(await page.getByTestId('prototype-generate-submit').isDisabled(), true);
  await listPage.check(); await detailPage.check();
  const submission = page.waitForResponse(response => response.request().method() === 'POST' && /\/prototype\/runs$/.test(new URL(response.url()).pathname));
  await page.getByTestId('prototype-generate-submit').click();
  const submitted = await submission;
  assert.equal(submitted.ok(), true);
  const generateRequest = submitted.request().postDataJSON();
  assert.equal(generateRequest.operation, 'GENERATE'); assert.equal(generateRequest.scope.mode, 'SELECTED');
  assert.deepEqual(generateRequest.scope.pageRefs, [{ moduleId: 'items', pageId: 'list' }, { moduleId: 'items', pageId: 'detail' }]);
  await page.getByTestId('prototype-run-status').first().waitFor({ state: 'visible' });
  await screenshot('progress');
  await page.reload(); await openPrototype();
  await page.getByTestId('prototype-run-status').first().waitFor({ state: 'visible' });
  const first = await currentRevision(1);
  assert.equal(first.mode, 'synthetic'); assert.equal(first.source.kind, 'DRAFT');
  await preview().getByTestId('item-name').waitFor({ state: 'visible' });
  await checked('范围空选禁用、复合页面选择、生成阶段轮询与刷新接管', { revisionId: first.id, sourceId: first.source.id });

  assert.equal(await page.getByTestId('prototype-preview-frame').getAttribute('sandbox'), 'allow-scripts allow-same-origin');
  assert.equal(await previewWrapper().locator('iframe').getAttribute('sandbox'), 'allow-scripts');
  const frameUrl = await page.getByTestId('prototype-preview-frame').getAttribute('src');
  assert.notEqual(new URL(frameUrl).origin, new URL(fixture.url).origin);
  await preview().getByTestId('item-input').fill('P5浏览器共享数据');
  await preview().getByTestId('save-item').click();
  await until(() => preview().getByTestId('item-name').textContent(), value => value === 'P5浏览器共享数据', '列表本地编辑');
  await preview().getByTestId('view-item').click();
  await until(() => preview().getByTestId('detail-name').textContent(), value => value === 'P5浏览器共享数据', '详情共享数据');
  await until(() => page.getByTestId('prototype-page-target').inputValue(), value => value.includes('items') && value.includes('detail'), '受校验路由消息更新当前页面');
  await screenshot('preview');
  await checked('真实隔离iframe加载、模拟数据保存与详情同步、postMessage定位当前页面');

  await page.getByRole('button', { name: '来源与验证', exact: true }).click();
  const sourceDialog = page.getByRole('dialog');
  assert.match(await sourceDialog.textContent(), /合成联调 · 非模型结果/);
  assert.match(await sourceDialog.textContent(), /已通过构建与交互验证/);
  await sourceDialog.getByText('查看冻结的完整 PRD', { exact: true }).click();
  assert.ok((await sourceDialog.textContent()).includes(before.lifecycle.prd.background));
  await page.keyboard.press('Escape');
  const popupPromise = page.waitForEvent('popup');
  await page.getByRole('button', { name: '独立窗口', exact: true }).click();
  const popup = await popupPromise;
  await popup.frameLocator('iframe').getByTestId('item-name').waitFor({ state: 'visible' });
  assert.equal(await popup.locator('iframe').getAttribute('sandbox'), 'allow-scripts');
  await popup.close();
  await checked('来源与验证展示冻结PRD、合成标记与校验记录；独立窗口保持隔离包装');

  await page.getByTestId('workbench-message').fill('只修改当前项目详情页面说明；P5合成回调验收。');
  const editResponse = page.waitForResponse(response => response.request().method() === 'POST' && /\/prototype\/runs$/.test(new URL(response.url()).pathname));
  await page.getByTestId('workbench-submit').click();
  const edited = await editResponse; assert.equal(edited.ok(), true);
  const editRequest = edited.request().postDataJSON();
  assert.equal(editRequest.operation, 'EDIT');
  assert.deepEqual(editRequest.target, { kind: 'PAGE', page: { moduleId: 'items', pageId: 'detail' }, overlayId: null });
  assert.equal(editRequest.baseRevisionId, first.id); assert.equal(editRequest.source.id, first.source.id);
  const second = await currentRevision(2);
  assert.equal(second.parentRevisionId, first.id); assert.equal(second.source.id, first.source.id);
  assert.equal(second.mode, 'synthetic'); assert.match(second.changeSummary, /静态样例沿用/);
  await checked('页面级修改使用当前页面与原来源；异步任务完成并保存父子修订', { revisionId: second.id });

  await page.getByTestId('prototype-history').click();
  await page.getByTestId(`prototype-history-revision-${first.id}`).click();
  await page.getByTestId('prototype-current').waitFor({ state: 'visible' });
  await until(() => page.locator('.prototype-title').textContent(), value => value.includes('历史修订 r1'), '历史修订只读标记');
  assert.equal(await page.getByTestId('workbench-message').isDisabled(), true);
  assert.equal((await prototype()).current.id, second.id);
  await screenshot('history');
  await checked('选择历史修订仅改变预览，当前指针仍保留最新修订');
  await page.getByTestId('prototype-current').click();

  // 服务端真正接受第一次请求后丢失响应，验证界面重放而不是新建第二个任务。
  const replayCommands = [];
  let acceptedRunId;
  await page.route('**/prototype/runs', async route => {
    if (route.request().method() !== 'POST') return route.continue();
    replayCommands.push(route.request().postDataJSON());
    if (replayCommands.length === 1) {
      const response = await route.fetch();
      assert.equal(response.ok(), true);
      acceptedRunId = (await response.json()).data.id;
      await route.abort('failed');
    } else await route.continue();
  });
  await page.getByTestId('workbench-message').fill('P5网络未知回包验收：保持同一请求重放。');
  await page.getByTestId('workbench-submit').click();
  const retry = page.getByRole('button', { name: '查询并重试原请求', exact: true });
  await retry.waitFor({ state: 'visible' });
  assert.equal(await page.getByTestId('prd-tab').isDisabled(), true);
  assert.equal(await page.getByTestId('workbench-message').inputValue(), 'P5网络未知回包验收：保持同一请求重放。');
  await screenshot('uncertain');
  await retry.click();
  await until(() => page.getByTestId('workbench-message').inputValue(), value => value === '', '确认接受后清空原型输入');
  const latest = await currentRevision(3);
  await page.unroute('**/prototype/runs');
  assert.equal(replayCommands.length, 2);
  assert.deepEqual(replayCommands[0], replayCommands[1]);
  assert.equal(latest.sourceRunId, acceptedRunId);
  const replayDetail = await prototype();
  assert.equal(replayDetail.runs.filter(run => run.runId === acceptedRunId).length, 1);
  assert.equal(replayDetail.runs.length, 3);
  await checked('请求已接受但回包丢失时保留输入并锁定切换；原命令重放只产生一个任务和修订', { runId: acceptedRunId, revisionId: latest.id });

  await page.getByTestId('prd-tab').click();
  await page.getByRole('button', { name: '定稿检查', exact: true }).click();
  await page.getByRole('button', { name: '确认 PRD 定稿', exact: true }).click();
  await page.getByRole('button', { name: '继续编辑', exact: true }).click();
  const background = page.locator('[data-section="background"]');
  await background.getByRole('button', { name: '编辑', exact: true }).click();
  const changedBackground = `P5浏览器更新背景：场景 ${fixture.sessionId}，原型仍保留原来源快照。`;
  await background.locator('textarea').fill(changedBackground);
  await until(workbench, value => value.lifecycle.prd.background === changedBackground, 'PRD自动保存');
  await openPrototype();
  await until(prototype, value => value.current.freshness === 'STALE', '来源状态STALE');
  await until(() => page.getByTestId('prototype-source-status').textContent(), value => /变化|更新|过期|陈旧|STALE/.test(value), '界面展示来源变化');
  assert.equal((await prototype()).current.source.prd.background, before.lifecycle.prd.background);
  await page.reload(); await openPrototype();
  assert.equal((await prototype()).current.id, latest.id);
  await screenshot('stale');
  await checked('定稿后继续编辑、PRD自动保存、原来源快照不变、STALE与刷新恢复');

  await page.getByTestId('prototype-delivery').click();
  await page.getByTestId('prototype-confirm-open').click();
  assert.equal(await page.getByTestId('prototype-confirm-submit').isDisabled(), true);
  assert.equal(await page.getByTestId('prototype-confirm-ack').isDisabled(), true);
  assert.match(await page.getByRole('dialog').last().textContent(), /定稿/);
  await screenshot('confirmation-blocked');
  await page.keyboard.press('Escape');
  if (await page.getByTestId('prototype-export-source').isVisible()) await page.keyboard.press('Escape');
  await checked('草稿或过期来源明确阻止人工确认，不能靠勾选绕过');

  await page.getByTestId('prd-tab').click();
  await page.getByRole('button', { name: '定稿检查', exact: true }).click();
  await page.getByRole('button', { name: '确认 PRD 定稿', exact: true }).click();
  await until(workbench, value => value.lifecycle.prdCurrent === true, '新PRD定稿');
  await openPrototype();
  await page.getByTestId('prototype-generate-config').click();
  await page.getByTestId('prototype-scope-selected').click();
  for (const id of ['list', 'detail']) await page.getByTestId(`prototype-scope-page-items-${id}`).check();
  await page.getByTestId('prototype-generate-submit').click();
  const synced = await currentRevision(4);
  assert.equal(synced.source.kind, 'BASELINE'); assert.equal(synced.freshness, 'CURRENT');
  assert.notEqual(synced.source.id, latest.source.id);
  await until(prototype, value => value.runs.every(run => !['QUEUED', 'RUNNING'].includes(run.status)), '同步任务结束');
  await confirmPrototype(true);
  let confirmations = await api(`/versions/${fixture.versionId}/prototype/confirmations`);
  assert.equal(confirmations.items.length, 1);
  assert.equal(confirmations.items[0].revisionId, synced.id);
  assert.deepEqual(confirmations.items[0].scope, synced.source.scope);
  assert.equal((await workbench()).workspace.version.stage, 'PROTOTYPE');
  await screenshot('confirmed');
  await checked('同步到有效PRD定稿，人工勾选确切范围；确认丢失响应原样重放只写一条记录，阶段保持原型', { revisionId: synced.id, confirmationId: confirmations.items[0].id });

  const staleRestore = await restorePrototype(first.id, 5, true);
  assert.equal(staleRestore.parentRevisionId, synced.id);
  assert.equal(staleRestore.source.id, first.source.id); assert.equal(staleRestore.freshness, 'STALE');
  assert.equal((await api(`/versions/${fixture.versionId}/prototype/revisions/${synced.id}`)).latestConfirmation.id, confirmations.items[0].id);
  await checked('恢复旧草稿新增修订并保留STALE来源；丢失响应重放不重复恢复，旧确认可追溯', { revisionId: staleRestore.id });

  const restored = await restorePrototype(synced.id, 6);
  assert.equal(restored.parentRevisionId, staleRestore.id); assert.equal(restored.freshness, 'CURRENT');
  assert.equal(restored.latestConfirmation, null);
  assert.equal((await api(`/versions/${fixture.versionId}/prototype/revisions`)).items.length, 6);
  await confirmPrototype();
  confirmations = await api(`/versions/${fixture.versionId}/prototype/confirmations`);
  assert.equal(confirmations.items.length, 2); assert.equal(confirmations.items[0].revisionId, restored.id);
  await page.getByTestId('prototype-history').click();
  await page.getByTestId('prototype-confirmation-history').click();
  await page.getByTestId(`prototype-confirmation-${confirmations.items[0].id}`).waitFor({ state: 'visible' });
  assert.match(await page.getByTestId(`prototype-confirmation-${confirmations.items[0].id}`).textContent(), /当前有效确认/);
  assert.match(await page.getByTestId(`prototype-confirmation-${confirmations.items[1].id}`).textContent(), /历史人工确认/);
  await screenshot('confirmation-history');
  await page.keyboard.press('Escape');
  await checked('恢复已确认修订仍需重新人工确认；确认历史区分当前有效和历史记录', { revisionId: restored.id });

  await page.getByTestId('prototype-delivery').click();
  await downloadPackage('source', restored); await downloadPackage('static', restored);
  await screenshot('exports');
  await page.keyboard.press('Escape');
  assert.equal((await api(`/versions/${fixture.versionId}/prototype/revisions`)).items.length, 6);
  assert.equal((await api(`/versions/${fixture.versionId}/prototype/confirmations`)).items.length, 2);
  await checked('界面下载确切修订的源码和静态ZIP，下载不改变修订或确认');

  const documentBeforeDeletion = await workbench();
  const deleted = await api(`/versions/${fixture.versionId}/deletion`, { expectedRevision: documentBeforeDeletion.workspace.version.revision,
    deleted: true, expectedDocumentRevision: documentBeforeDeletion.lifecycle.revision, expectedLinkedSessions: 1 }, 'PUT');
  const blockedExport = await page.request.get(`${fixture.backendUrl}/api/alpha/workspace/versions/${fixture.versionId}/prototype/revisions/${restored.id}/export?format=source`);
  assert.equal(blockedExport.status(), 404);
  await api(`/versions/${fixture.versionId}/deletion`, { expectedRevision: deleted.revision, deleted: false }, 'PUT');
  await page.reload(); await openPrototype();
  assert.equal((await prototype()).current.id, restored.id);
  await page.getByTestId('prototype-delivery').click(); await downloadPackage('static', restored); await page.keyboard.press('Escape');
  await checked('文档回收后导出拒绝，恢复文档后同一修订、来源和导出引用恢复');

  const archived = await api(`/sessions/${fixture.sessionId}`);
  await api(`/sessions/${fixture.sessionId}/archive`, { expectedRevision: archived.session.revision, archived: true }, 'PUT');
  await page.reload(); await openPrototype();
  await preview().getByTestId('item-name').waitFor({ state: 'visible' });
  assert.equal(await page.getByTestId('workbench-message').isDisabled(), true);
  assert.equal(await page.getByTestId('workbench-submit').isDisabled(), true);
  assert.equal(await page.getByTestId('prototype-generate-config').isDisabled(), true);
  await page.getByTestId('prototype-delivery').click();
  assert.equal(await page.getByTestId('prototype-confirm-open').isDisabled(), true);
  await downloadPackage('source', restored); await page.keyboard.press('Escape');
  await screenshot('readonly');
  await checked('归档会话只读仍可查看预览和导出，修改与确认入口禁用');
  await page.setViewportSize({ width: 820, height: 700 });
  await page.getByTestId('prototype-generate-config').scrollIntoViewIfNeeded();
  const narrowToolbar = await page.getByTestId('prototype-generate-config').boundingBox();
  assert.ok(narrowToolbar.y >= 0 && narrowToolbar.y + narrowToolbar.height <= 700);
  await page.getByTestId('workbench-message').scrollIntoViewIfNeeded();
  const narrowComposer = await page.getByTestId('workbench-message').boundingBox();
  assert.ok(narrowComposer.y >= 0 && narrowComposer.y + narrowComposer.height <= 700);
  await screenshot('narrow');
  await page.setViewportSize({ width: 1600, height: 1000 });
  await checked('820×700窄屏与矮屏可滚动到原型工具栏及会话输入');
  await verifyGeneratedIsolation();
  assert.deepEqual(report.consoleErrors, []); assert.deepEqual(report.httpFailures, []);
  report.status = 'PASSED';
} catch (error) {
  report.status = 'FAILED'; report.error = error.message;
  if (page) await screenshot('failure').catch(() => {});
  process.exitCode = 1;
} finally {
  report.completedAt = new Date().toISOString();
  await writeFile(output, JSON.stringify(report, null, 2));
  await browser?.close();
  if (process.env.ALPHA_PROTOTYPE_BROWSER_KEEP_SERVER !== 'true') await writeFile(resolve(reportDir, 'prototype-browser.stop'), report.status);
  console.log(JSON.stringify({ status: report.status, checks: report.checks.length, report: output, error: report.error }));
}
