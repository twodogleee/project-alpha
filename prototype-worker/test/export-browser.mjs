/**
 * 独立导出包的真实浏览器验收；只解释受限用例，不构建或执行模型验证脚本。
 * 调用：node prototype-worker/test/export-browser.mjs <解压目录> <outputPrefix>
 * source 包须先通过包内固定工具链重建，并将 rebuilt-static.tar.gz 解压出 dist。
 * @author Andersen
 */
import assert from 'node:assert/strict';
import { readFile, stat, mkdir, writeFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { chromium } from 'playwright';

/** 输入只指定导出根目录和证据位置，不接受浏览器脚本、选择器或任意服务命令。 */
const exportDirectory = resolve(process.argv[2] || '.');
const outputPrefix = resolve(process.argv[3] || 'data/prototypes/validation/export-browser');
/** 与 Worker 一致，仅具体本地路径和有限测试标识可以进入浏览器动作。 */
const concretePath = /^\/[A-Za-z0-9_-]+(?:\/[A-Za-z0-9_-]+)*$/;
const testIdPattern = /^[A-Za-z0-9_:-]{1,120}$/;
/** 业务阶段的真实错误和外部请求与主动隔离探测分开记录。 */
const runtimeErrors = [], consoleErrors = [], deniedRequests = [], checks = [], screenshots = [];
/** 独立探针不读取任何宿主服务，安全验证期间收到请求即为失败。 */
let probeRequests = 0;
const probe = createServer((request, response) => { probeRequests++; response.end('unexpected'); });
/** 服务输出与资源句柄仅属于本次运行，失败和进程中断都需要清理。 */
let serverProcess, browser, context, page, manifest, serviceUrl, windowGeometry = null, iframeGeometry = null, serviceOutput = '', serviceError = '', businessPhase = true, completed = false;
/** 防止 SIGTERM 和 finally 同时关闭资源造成悬挂。 */
let cleanupPromise;

/** 清单文本有界读取，不能由文件大小耗尽验收器内存。 */
async function boundedText(path, maximum) {
  const metadata = await stat(path);
  assert.ok(metadata.isFile() && metadata.size <= maximum, '清单或可信脚本超过大小限制');
  const bytes = await readFile(path);
  assert.ok(bytes.length <= maximum, '读取时文件已增长至超过限制');
  return bytes.toString('utf8');
}

/** 拒绝未知步骤字段，避免将未来不受支持的动作静默视为已执行。 */
function exactKeys(value, keys, label) {
  assert.ok(value && typeof value === 'object' && !Array.isArray(value), `${label}不是对象`);
  assert.ok(Object.keys(value).every(key => keys.includes(key)), `${label}包含未知字段`);
}

/** 只接受已声明页面路径、最多二十条用例和一百个受限动作。 */
function validateProtocol(value) {
  assert.equal(value.schemaVersion, 1, '不支持的交付清单版本');
  assert.ok(['source', 'static'].includes(value.format), '必须是源码或静态导出包');
  assert.ok(['live', 'synthetic', 'demo'].includes(value.mode), '来源模式无效');
  const pages = value.prototype?.plan?.pages;
  const cases = value.prototype?.cases || [];
  assert.ok(Array.isArray(pages) && pages.length > 0 && pages.length <= 10, '页面清单必须包含一至十项');
  const paths = new Set();
  for (const descriptor of pages) {
    const path = descriptor.testPath || descriptor.path;
    assert.ok(typeof path === 'string' && path.length <= 200 && concretePath.test(path), '页面必须声明具体本地测试路径');
    assert.ok(typeof descriptor.path === 'string', '页面缺少路由');
    const pattern = descriptor.path.split('/'), concrete = path.split('/');
    assert.ok(pattern.length === concrete.length && pattern.every((part, index) => part.startsWith(':') ? /^:[A-Za-z][A-Za-z0-9_]*$/.test(part) : part === concrete[index]), '测试路径没有匹配页面路由');
    assert.ok(!paths.has(path), '页面测试路径重复'); paths.add(path);
  }
  assert.ok(Array.isArray(cases) && cases.length <= 20, '受限用例数量无效');
  let totalSteps = 0;
  for (const scenario of cases) {
    exactKeys(scenario, ['name', 'path', 'steps'], '用例');
    assert.ok(typeof scenario.name === 'string' && scenario.name.trim() && Buffer.byteLength(scenario.name) <= 200, '用例名称无效');
    assert.ok(paths.has(scenario.path), '用例只能访问已声明页面');
    assert.ok(Array.isArray(scenario.steps) && scenario.steps.length >= 1 && scenario.steps.length <= 20, '用例步骤数量无效');
    totalSteps += scenario.steps.length; assert.ok(totalSteps <= 100, '受限步骤总量超过一百');
    for (const step of scenario.steps) {
      exactKeys(step, ['action', 'testId', 'text', 'value'], '步骤');
      assert.ok(['click', 'fill', 'assertVisible', 'assertText'].includes(step.action), '受限动作无效');
      if (step.testId !== undefined || step.action !== 'assertText') assert.ok(typeof step.testId === 'string' && testIdPattern.test(step.testId), '测试标识无效');
      if (step.action === 'fill') assert.ok(typeof step.value === 'string' && Buffer.byteLength(step.value) <= 2000 && !step.value.includes('\0'), '输入值无效');
      else assert.equal(step.value, undefined, '只有填充动作允许 value');
      if (step.action === 'assertText') assert.ok(typeof step.text === 'string' && step.text.trim() && Buffer.byteLength(step.text) <= 2000 && !/[\x00-\x1f\x7f]/.test(step.text), '断言文本无效');
      else assert.equal(step.text, undefined, '只有文本断言允许 text');
    }
  }
  return { pages, cases };
}

/** 子进程仅执行仓库信任的固定 serve.mjs，绝不将清单字符串当命令执行。 */
async function startService() {
  const script = resolve(exportDirectory, 'serve.mjs');
  const trusted = await boundedText(new URL('../../backend/src/main/resources/prototype-export/serve.mjs', import.meta.url), 128 * 1024);
  assert.equal(await boundedText(script, 128 * 1024), trusted, '导出包服务脚本与可信平台资源不同');
  serverProcess = spawn(process.execPath, [script, 'dist'], { cwd: exportDirectory, stdio: ['ignore', 'pipe', 'pipe'] });
  return new Promise((resolveReady, reject) => {
    const timer = setTimeout(() => { disposeListeners(); reject(new Error('导出服务未在十秒内就绪')); }, 10_000);
    /** 就绪后继续保留有界 stdout/stderr，不让子进程阻塞在未消费的管道。 */
    const stdout = data => {
      serviceOutput = (serviceOutput + data.toString()).slice(-4000);
      const match = /http:\/\/127\.0\.0\.1:([0-9]+)\//.exec(serviceOutput);
      if (match) { disposeListeners(); resolveReady(match[0]); }
    };
    const failed = error => { disposeListeners(); reject(error); };
    const exited = code => failed(new Error(`导出服务启动失败，退出码 ${code}`));
    /** 只清理启动监督监听，输出流监听留到进程退出。 */
    const disposeListeners = () => { clearTimeout(timer); serverProcess.off('error', failed); serverProcess.off('exit', exited); };
    serverProcess.stdout.on('data', stdout);
    serverProcess.stderr.on('data', data => { serviceError = (serviceError + data.toString()).slice(-4000); });
    serverProcess.once('error', failed); serverProcess.once('exit', exited);
  });
}

/** 宿主验收结束后释放独立 Chrome、探针与确切服务进程，不影响其他会话。 */
async function cleanup() {
  if (cleanupPromise) return cleanupPromise;
  cleanupPromise = (async () => {
    await Promise.allSettled([context?.close(), browser?.close()]);
    if (serverProcess?.pid && serverProcess.exitCode === null && serverProcess.signalCode === null) {
      await new Promise(resolveExited => {
        const timer = setTimeout(() => { serverProcess.kill('SIGKILL'); }, 2000);
        serverProcess.once('exit', () => { clearTimeout(timer); resolveExited(); }); serverProcess.kill('SIGTERM');
      });
    }
    await new Promise(resolveClosed => { probe.closeAllConnections(); probe.close(() => resolveClosed()); });
  })();
  return cleanupPromise;
}

/** 报告忠实区分原产物模式与本次浏览器验证；本脚本不调用模型。 */
async function writeReport(passed, extra = {}) {
  const report = { passed, checkedAt: new Date().toISOString(), exportDirectory, format: manifest?.format, generationMode: manifest?.mode,
    invokedModelDuringThisCheck: false, revisionId: manifest?.revisionId, revisionNumber: manifest?.revisionNumber,
    browser: browser?.version() || null, viewportMode: 'native-window', requestedWindow: { width: 1440, height: 1000 }, windowGeometry, iframeGeometry,
    checks, runtimeErrors, consoleErrors, deniedRequests, probeRequests, screenshots, ...extra };
  await writeFile(`${outputPrefix}.json`, JSON.stringify(report, null, 2) + '\n');
  process.stdout.write(JSON.stringify(report, null, 2) + '\n');
}

/** CLI 被中断时不留下预览端口或浏览器。 */
for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => { void cleanup().finally(() => { process.exit(signal === 'SIGINT' ? 130 : 143); }); });

try {
  await mkdir(dirname(outputPrefix), { recursive: true });
  manifest = JSON.parse(await boundedText(resolve(exportDirectory, 'export-manifest.json'), 4 * 1024 * 1024));
  const protocol = validateProtocol(manifest);
  serviceUrl = await startService();
  await new Promise(resolveListening => probe.listen(0, '127.0.0.1', resolveListening));
  const probeUrl = `http://127.0.0.1:${probe.address().port}/isolated-probe`;
  const origin = new URL(serviceUrl).origin, probeOrigin = new URL(probeUrl).origin;
  const executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH;
  // 使用真实窗口，避免旧 Playwright 的 deviceMetrics 仿真与新版 Chrome 的 iframe 输入命中不一致。
  browser = await chromium.launch({ headless: true, args: ['--window-size=1440,1000'], ...(executablePath ? { executablePath } : { channel: 'chrome' }) });
  context = await browser.newContext({ viewport: null, acceptDownloads: false });
  /** 允许隔离探针抵达网络边界，以证实 CSP 本身拦截；其余外部请求全部拒绝。 */
  await context.route('**/*', async route => {
    const destination = new URL(route.request().url());
    if (![origin, probeOrigin].includes(destination.origin) || !['GET', 'HEAD'].includes(route.request().method())) {
      deniedRequests.push(`${destination.origin}${destination.pathname}`.slice(0, 200)); await route.abort();
    } else await route.continue();
  });
  page = await context.newPage(); page.setDefaultTimeout(12_000);
  page.on('pageerror', error => { if (runtimeErrors.length < 100) runtimeErrors.push(error.message.slice(0, 500)); });
  page.on('console', message => { if (businessPhase && message.type() === 'error' && !message.location().url.endsWith('/favicon.ico') && consoleErrors.length < 100) consoleErrors.push(message.text().slice(0, 500)); });
  page.on('popup', popup => { deniedRequests.push('popup'); void popup.close(); });
  page.on('download', download => { deniedRequests.push('download'); void download.cancel(); });
  page.on('response', response => { if (businessPhase && response.status() >= 400 && response.url().startsWith(`${origin}/content/`)) runtimeErrors.push(`静态资源返回 ${response.status()}`); });
  assert.equal((await page.goto(serviceUrl, { waitUntil: 'networkidle' })).status(), 200);
  windowGeometry = await page.evaluate(() => ({ innerWidth, innerHeight, outerWidth, outerHeight, devicePixelRatio }));
  const provenance = { live: '真实模型生成', synthetic: '合成联调 · 未调用模型', demo: '模板联调 · 未调用模型' }[manifest.mode];
  assert.ok((await page.locator('header').innerText()).includes(provenance), '独立预览必须显示准确来源模式');
  assert.equal(await page.locator('iframe').getAttribute('sandbox'), 'allow-scripts');
  const frame = page.frames().find(item => item.parentFrame() === page.mainFrame());
  assert.ok(frame, '缺少受限原型 iframe');
  iframeGeometry = { ...await page.locator('iframe').boundingBox(), ...await frame.evaluate(() => ({ innerWidth, innerHeight })) };

  /** 路由只通过 iframe 的本地哈希导航，主壳和持久来源不会被替换。 */
  async function navigate(path) {
    await frame.goto(`${origin}/content/index.html#${path}`, { waitUntil: 'networkidle' });
    assert.equal(new URL(frame.url()).origin, origin); assert.equal(new URL(frame.url()).hash, `#${path}`);
    const main = frame.locator('.prototype-main'); await main.waitFor({ state: 'visible' });
    assert.ok((await main.innerText()).trim(), '页面主区域没有业务内容');
    assert.equal(await frame.locator('iframe,object,embed').count(), 0, '生成页不能嵌套外部上下文');
  }
  for (let index = 0; index < protocol.pages.length; index++) {
    const descriptor = protocol.pages[index], path = descriptor.testPath || descriptor.path;
    await navigate(path); checks.push({ name: `页面路由：${descriptor.moduleId}/${descriptor.pageId}`, path, status: 'PASSED' });
    const screenshot = `${outputPrefix}-route-${index + 1}.png`;
    await page.screenshot({ path: screenshot, fullPage: true, animations: 'disabled' }); screenshots.push(screenshot);
  }
  for (const scenario of protocol.cases) {
    await navigate(scenario.path);
    for (const step of scenario.steps) {
      const target = step.testId ? frame.getByTestId(step.testId) : frame.locator('.prototype-main');
      if (step.action === 'click') await target.click();
      else if (step.action === 'fill') await frame.locator(`[data-testid="${step.testId}"] input, input[data-testid="${step.testId}"], [data-testid="${step.testId}"] textarea, textarea[data-testid="${step.testId}"]`).fill(step.value);
      else if (step.action === 'assertVisible') await target.waitFor({ state: 'visible' });
      else if (step.action === 'assertText') await target.filter({ hasText: step.text }).waitFor({ state: 'visible' });
    }
    checks.push({ name: `交互用例：${scenario.name}`, steps: scenario.steps.length, status: 'PASSED' });
  }
  await page.screenshot({ path: `${outputPrefix}.png`, fullPage: true, animations: 'disabled' }); screenshots.push(`${outputPrefix}.png`);
  assert.deepEqual(runtimeErrors, [], '页面存在运行异常'); assert.deepEqual(consoleErrors, [], '页面控制台存在错误'); assert.deepEqual(deniedRequests, [], '页面尝试禁止的网络或浏览器动作');
  checks.push({ name: '原始来源模式、路由和受限用例无浏览器错误', status: 'PASSED' });

  /** 下面仅执行平台固定断言，模型文本始终只作为纯值传入受限动作。 */
  businessPhase = false;
  const isolation = await frame.evaluate(async destination => {
    const blocked = read => { try { read(); return false; } catch (error) { return error.name === 'SecurityError'; } };
    let networkBlocked = false;
    try { await fetch(destination); } catch { networkBlocked = true; }
    return { parentDomBlocked: blocked(() => window.parent.document.title), storageBlocked: blocked(() => localStorage.getItem('alpha-private')),
      sessionStorageBlocked: blocked(() => sessionStorage.getItem('alpha-private')), cookieBlocked: blocked(() => document.cookie), networkBlocked };
  }, probeUrl);
  assert.deepEqual(isolation, { parentDomBlocked: true, storageBlocked: true, sessionStorageBlocked: true, cookieBlocked: true, networkBlocked: true });
  assert.equal(probeRequests, 0, '网络隔离探针不应收到请求');
  checks.push({ name: 'opaque iframe 禁止父 DOM、浏览器持久化与外部 fetch', status: 'PASSED' });
  assert.deepEqual(runtimeErrors, []); assert.deepEqual(deniedRequests, []);
  await writeReport(true, { isolation }); completed = true;
} catch (error) {
  if (page) {
    const screenshot = `${outputPrefix}-failure.png`;
    await page.screenshot({ path: screenshot, fullPage: true, animations: 'disabled' }).then(() => screenshots.push(screenshot)).catch(() => {});
  }
  await writeReport(false, { failure: String(error.message).slice(0, 2000), serviceError }).catch(() => {});
  process.exitCode = 1;
} finally {
  await cleanup();
  if (!completed && !process.exitCode) process.exitCode = 1;
}
