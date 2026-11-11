/** 独立预览的访问、隔离头和消息边界回归。 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, rm, symlink, realpath } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { request } from 'node:http';
import { startPreview, isPreviewMessage } from '../src/preview.mjs';

/** 创建最小静态产物；测试不执行模型代码或真实业务请求。 */
async function fixture(context, options = {}) {
  /** 使用规范化临时路径，避免 macOS /var 与 /private/var 别名。 */
  const directory = await mkdtemp(join(await realpath(tmpdir()), 'alpha-preview-'));
  await mkdir(join(directory, 'assets'));
  await writeFile(join(directory, 'index.html'), '<!doctype html><html><head></head><body><div id="app">可信样板</div><script type="module" src="./assets/main.js"></script></body></html>');
  await writeFile(join(directory, 'assets/main.js'), 'document.querySelector("#app").textContent="原型就绪";');
  /** 端口由操作系统分配，避免影响用户本地服务。 */
  const service = await startPreview({ artifactDirectory: directory, hostPort: 0, previewPort: 0, revisionId: 'fixture-1', ...options });
  context.after(async () => { await service.close(); await rm(directory, { recursive: true, force: true }); });
  return { directory, service };
}

/** 保留原始 path，覆盖客户端 URL 构造器会消除的点段攻击。 */
function rawRequest(url, { path, method = 'GET', headers = {} } = {}) {
  /** 只有目标 origin 由可信测试服务给出。 */
  const address = new URL(url);
  return new Promise((resolveResponse, reject) => {
    /** 接受原始 request-target 以验证服务端独立防御。 */
    const connection = request({ hostname: address.hostname, port: address.port, path: path ?? address.pathname, method, headers: { Host: address.host, 'Sec-Fetch-Dest': 'iframe', ...headers } }, (response) => {
      /** 错误正文也受固定小文本约束。 */
      const chunks = [];
      response.on('data', (chunk) => chunks.push(chunk));
      response.on('end', () => resolveResponse({ status: response.statusCode, headers: response.headers, body: Buffer.concat(chunks).toString('utf8') }));
    });
    connection.once('error', reject);
    connection.end();
  });
}

test('预览与宿主不同源，独立窗口保持包装层与 opaque sandbox', async (context) => {
  const { service } = await fixture(context);
  const host = await rawRequest(service.hostUrl);
  const standalone = await rawRequest(service.previewUrl);
  const preview = await rawRequest(service.frameUrl);
  assert.equal(host.status, 200);
  assert.equal(standalone.status, 200);
  assert.notEqual(new URL(service.hostUrl).origin, new URL(service.frameUrl).origin);
  assert.match(host.body, /本地模板联调/);
  assert.match(host.body, /未调用大模型/);
  assert.match(host.body, /sandbox="allow-scripts"/);
  assert.doesNotMatch(host.body, /allow-same-origin|allow-top-navigation|allow-popups/);
  assert.match(standalone.body, /<iframe/);
  assert.match(host.headers['content-security-policy'], new RegExp(`frame-src ${new URL(service.frameUrl).origin}`));
  assert.match(preview.headers['content-security-policy'], /connect-src 'none'/);
  assert.match(preview.headers['content-security-policy'], /worker-src 'none'/);
  assert.match(preview.headers['content-security-policy'], /form-action 'none'/);
  assert.match(preview.headers['content-security-policy'], /sandbox allow-scripts/);
  assert.equal(preview.headers['access-control-allow-origin'], 'null');
  assert.equal(preview.headers['access-control-allow-credentials'], undefined);
  assert.equal(preview.headers['cache-control'], 'no-store');
  assert.equal(preview.headers['referrer-policy'], 'no-referrer');
  assert.match(preview.body, /window.parent.postMessage/);
  assert.doesNotMatch(preview.body, /fetch\(/);
  assert.equal((await rawRequest(service.frameUrl, { headers: { 'Sec-Fetch-Dest': 'document' } })).status, 403);
  assert.equal((await rawRequest(service.frameUrl, { headers: { 'Sec-Fetch-Dest': 'empty' } })).status, 403);
});

test('必须精确匹配会话，GET 和 HEAD 无写入路径', async (context) => {
  const { service } = await fixture(context);
  assert.equal((await rawRequest(service.frameUrl, { method: 'POST' })).status, 405);
  assert.equal((await rawRequest(service.frameUrl, { method: 'OPTIONS' })).status, 405);
  const head = await rawRequest(service.frameUrl, { method: 'HEAD' });
  assert.equal(head.status, 200);
  assert.equal(head.body, '');
  assert.equal((await rawRequest(service.frameUrl, { path: '/p/not-the-session/index.html' })).status, 404);
  assert.equal((await rawRequest(service.hostUrl, { path: '/' })).status, 404);
  assert.equal((await rawRequest(service.frameUrl, { headers: { Host: 'attacker.example' } })).status, 403);
});

test('目录穿越、重复编码与未知文件不能读取磁盘或回落首页', async (context) => {
  const { service } = await fixture(context);
  const prefix = `/p/${service.sessionId}`;
  for (const suffix of ['/../secret', '/%2e%2e/secret', '/%252e%252e/secret', '/assets%2fmain.js', '/assets\\main.js', '/assets/main.js?secret=value', '/%00']) {
    assert.equal((await rawRequest(service.frameUrl, { path: prefix + suffix })).status, 400, suffix);
  }
  assert.equal((await rawRequest(service.frameUrl, { path: `${prefix}/not-a-route` })).status, 404);
  assert.equal((await rawRequest(service.frameUrl, { path: `${prefix}/assets/main.js.map` })).status, 404);
  const script = await rawRequest(service.frameUrl, { path: `${prefix}/assets/main.js` });
  assert.equal(script.status, 200);
  assert.match(script.headers['content-type'], /^text\/javascript/);
});

test('会话撤销后页面、脚本及独立窗口立即拒绝后续请求', async (context) => {
  const { service } = await fixture(context);
  assert.equal((await rawRequest(service.frameUrl)).status, 200);
  service.revokeSession();
  assert.equal((await rawRequest(service.frameUrl)).status, 410);
  assert.equal((await rawRequest(service.hostUrl)).status, 410);
  assert.equal((await rawRequest(service.previewUrl)).status, 410);
  assert.equal((await rawRequest(service.frameUrl, { path: `/p/${service.sessionId}/assets/main.js` })).status, 410);
});

test('父文档可读状态每次复核，状态拒绝及检查异常均失败关闭', async (context) => {
  let readable = true;
  let fail = false;
  let checks = 0;
  const { service } = await fixture(context, { isAvailable: async ({ revisionId }) => {
    checks += 1;
    assert.equal(revisionId, 'fixture-1');
    if (fail) throw new Error('internal storage details');
    return readable;
  } });
  assert.equal((await rawRequest(service.hostUrl)).status, 200);
  assert.equal((await rawRequest(service.frameUrl)).status, 200);
  readable = false;
  assert.equal((await rawRequest(service.frameUrl)).status, 410);
  readable = true;
  fail = true;
  const denied = await rawRequest(service.hostUrl);
  assert.equal(denied.status, 410);
  assert.doesNotMatch(denied.body, /internal storage details/);
  assert.equal(checks, 4);
});

test('有效期不会被读取延长，到期后资源访问拒绝', async (context) => {
  let time = 1000;
  const { service } = await fixture(context, { now: () => time, ttlMs: 500 });
  assert.equal((await rawRequest(service.frameUrl)).status, 200);
  time = 1499;
  assert.equal((await rawRequest(service.frameUrl)).status, 200);
  time = 1500;
  assert.equal((await rawRequest(service.frameUrl)).status, 410);
});

test('预览持有已封存内存快照，不受后续磁盘文件替换影响', async (context) => {
  const { directory, service } = await fixture(context);
  await writeFile(join(directory, 'assets/main.js'), 'altered-after-start');
  const script = await rawRequest(service.frameUrl, { path: `/p/${service.sessionId}/assets/main.js` });
  assert.match(script.body, /原型就绪/);
  assert.doesNotMatch(script.body, /altered-after-start/);
});

test('启动时拒绝链接和未知格式，不公开可执行文件或任意资料', async (context) => {
  const directory = await mkdtemp(join(await realpath(tmpdir()), 'alpha-preview-invalid-'));
  context.after(() => rm(directory, { recursive: true, force: true }));
  await writeFile(join(directory, 'index.html'), '<html></html>');
  await symlink(join(directory, 'index.html'), join(directory, 'linked.html'));
  await assert.rejects(startPreview({ artifactDirectory: directory, hostPort: 0, previewPort: 0 }), /禁止符号链接/);
  await rm(join(directory, 'linked.html'));
  await writeFile(join(directory, 'secret.env'), 'private-example');
  await assert.rejects(startPreview({ artifactDirectory: directory, hostPort: 0, previewPort: 0 }), /产物路径或格式不受支持/);
});

test('监听地址、修订身份和会话期限严格校验', async () => {
  const invalid = { artifactDirectory: '/does-not-matter', hostPort: 0, previewPort: 0 };
  await assert.rejects(startPreview({ ...invalid, host: '0.0.0.0' }), /loopback/);
  await assert.rejects(startPreview({ ...invalid, revisionId: '</script>' }), /修订标识无效/);
  await assert.rejects(startPreview({ ...invalid, hostPort: 18181, previewPort: 18181 }), /不同源/);
  await assert.rejects(startPreview({ ...invalid, ttlMs: Number.MAX_SAFE_INTEGER }), /会话期限无效/);
});

test('桥接只接受对应窗口、opaque origin、令牌和修订的受限通知', () => {
  const source = {};
  const expected = { source, sessionId: 'session', revisionId: 'revision' };
  const event = { source, origin: 'null', data: { type: 'READY', sessionId: 'session', revisionId: 'revision', path: '/personnel' } };
  assert.equal(isPreviewMessage(event, expected), true);
  assert.equal(isPreviewMessage({ ...event, source: {} }, expected), false);
  assert.equal(isPreviewMessage({ ...event, origin: 'https://attacker.example' }, expected), false);
  for (const patch of [{ sessionId: 'other' }, { revisionId: 'other' }, { type: 'EXECUTE' }, { command: 'fetch-private' }, { path: 'https://attacker.example' }, { message: 'x'.repeat(301) }]) {
    assert.equal(isPreviewMessage({ ...event, data: { ...event.data, ...patch } }, expected), false);
  }
});
