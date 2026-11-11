/** 宿主原型执行器；不载入模型提供的配置、命令或验证脚本。@author Andersen */
import { mkdir, readFile, readdir, lstat, realpath } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { resolve, extname, sep } from 'node:path';
import { runProcess } from '../src/process.mjs';
import { browserOptions } from '../src/host-toolchain.mjs';
import { PrototypeError } from '../src/errors.mjs';
import { chromium } from 'playwright';
import { verifyGenerated } from './generated-verification.mjs';

/** 工具链协议只允许一个 JSON 响应，构建日志单独捕获。 */
const MAX_LOG = 1024 * 1024;
/** 编译静态文件总限额。 */
const MAX_DIST = 50 * 1024 * 1024;
/** 浏览器截图总限额。 */
const MAX_SCREENSHOTS = 20 * 1024 * 1024;
/** 在临时工程中调用锁定的 Vite，macOS 上限制编译器及其子进程的文件和网络访问。 */
async function compile(taskRoot, templateDir, signal) {
  const task = await realpath(taskRoot);
  const modules = await realpath(resolve(templateDir, 'node_modules'));
  const node = await realpath(process.execPath);
  const args = [resolve(modules, 'vite/bin/vite.js'), 'build', '--outDir', resolve(task, 'dist'), '--emptyOutDir'];
  let command = node;
  if (process.platform === 'darwin') {
    const readable = ['/System', '/usr/lib', '/usr/share', '/Library/Apple', '/private/var/db/timezone', '/dev', modules, task];
    const profile = [
      '(version 1)', '(allow default)', '(deny network*)', '(deny file-read-data)', '(deny file-write*)', '(allow file-read-data (vnode-type DIRECTORY))',
      '(allow file-read-data (literal ' + JSON.stringify(node) + ') ' + readable.map(path => '(subpath ' + JSON.stringify(path) + ')').join(' ') + ')',
      '(allow file-write* (subpath ' + JSON.stringify(task) + ') (literal "/dev/null"))',
    ].join('\n');
    command = '/usr/bin/sandbox-exec';
    args.unshift('-p', profile, node);
  }
  const env = { NODE_ENV: 'production', HOME: task, TMPDIR: task, TEMP: task, TMP: task, PATH: process.env.PATH };
  if (process.platform === 'win32') env.SystemRoot = process.env.SystemRoot;
  const result = await runProcess(command, args, { cwd: resolve(task, 'source'), env, signal, timeoutMs: 120_000, maxOutput: MAX_LOG });
  if (result.code !== 0) throw Object.assign(new PrototypeError('PROTOTYPE_BUILD_FAILED', '本机 Vite 编译失败'), {
    diagnostic: Buffer.concat([result.stdout, result.stderr]).toString().slice(-12000),
  });
}

/** 输出仅包含普通文件，序列化前再次检查字节总量。 */
async function encodeTree(root, limit) {
  const files = [];
  let total = 0;
  /** 只在本次任务的输出目录中递归。 */
  async function visit(prefix) {
    for (const name of (await readdir(resolve(root, prefix))).sort()) {
      const path = prefix ? `${prefix}/${name}` : name;
      const stat = await lstat(resolve(root, path));
      if (stat.isSymbolicLink() || (!stat.isFile() && !stat.isDirectory())) throw new Error('输出包含特殊文件');
      if (stat.isDirectory()) await visit(path);
      else {
        total += stat.size;
        if (files.length >= 1500 || total > limit) throw new Error('输出文件超过上限');
        const content = await readFile(resolve(root, path));
        files.push({ path, data: content.toString('base64'), bytes: content.length, sha256: createHash('sha256').update(content).digest('hex') });
      }
    }
  }
  await visit('');
  return files;
}

/** 浏览器仅服务已构建静态资源；不具有反向代理或文件浏览能力。 */
async function startStaticServer(outputRoot) {
  const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.woff2': 'font/woff2' };
  const server = createServer(async (request, response) => {
    try {
      if (request.method !== 'GET') throw new Error('method');
      const pathname = decodeURIComponent(new URL(request.url, 'http://127.0.0.1').pathname);
      if (pathname.includes('..') || pathname.includes('\\') || /[\x00-\x1f]/.test(pathname)) throw new Error('path');
      const path = resolve(outputRoot, `.${pathname === '/' ? '/index.html' : pathname}`);
      if (!path.startsWith(outputRoot + sep)) throw new Error('path');
      const stat = await lstat(path);
      if (!stat.isFile() || stat.isSymbolicLink()) throw new Error('file');
      // 与用户预览同样使用 opaque sandbox，避免验收允许 eval/存储而正式预览失败。
      response.writeHead(200, {
        'Content-Type': `${types[extname(path)] || 'application/octet-stream'}; charset=utf-8`,
        'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer',
        'Access-Control-Allow-Origin': 'null', 'Cross-Origin-Resource-Policy': 'cross-origin',
        'Content-Security-Policy': "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-src 'none'; frame-ancestors 'none'; worker-src 'none'; sandbox allow-scripts",
        'Permissions-Policy': 'camera=(), microphone=(), geolocation=(), payment=(), usb=(), serial=(), bluetooth=(), clipboard-read=(), clipboard-write=()',
      });
      response.end(await readFile(path));
    } catch { response.writeHead(404); response.end('Not found'); }
  });
  await new Promise((resolveListening, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolveListening); });
  return { server, origin: `http://127.0.0.1:${server.address().port}` };
}

/** 固定样板的真实浏览器验收；选择器与断言由平台维护，模型不能替换。 */
async function verifyFixture(signal, serve, screenshots, executablePath) {
  let server;
  let browser;
  let context;
  /** 截止时间也主动关闭浏览器与服务。 */
  async function cleanup() {
    await Promise.allSettled([
      context?.close(), browser?.close(),
      server ? new Promise(resolveClosed => { server.closeAllConnections(); server.close(resolveClosed); }) : Promise.resolve(),
    ]);
  }
  const abort = () => { void cleanup(); };
  signal.addEventListener('abort', abort, { once: true });
  try {
  const served = await serve();
  server = served.server;
  const origin = served.origin;
  browser = await chromium.launch(browserOptions(executablePath));
  if (signal.aborted) throw new Error('浏览器验证已超时');
  // opaque sandbox 与 worker-src none 已阻止 Service Worker，避免额外注入触发访问异常。
  context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, acceptDownloads: false });
  const page = await context.newPage();
  const errors = [];
  const denied = [];
  const cases = [];
  page.on('pageerror', error => errors.push(String(error.message).slice(0, 500)));
  await context.route('**/*', async route => {
    const url = new URL(route.request().url());
    if (url.origin !== origin) { denied.push(url.origin); await route.abort(); }
    else await route.continue();
  });
  page.setDefaultTimeout(12_000);
  /** 在每个固定断言后保存平台实际检查名。 */
  function checked(name) { cases.push({ name, status: 'PASSED' }); }
  /** Element 属性可能透传到输入本身或控件容器，两种结构都指向同一真实输入。 */
  function input(testId) { return page.locator(`[data-testid="${testId}"] input, input[data-testid="${testId}"]`); }
    await page.goto(`${origin}/#/personnel`, { waitUntil: 'networkidle' });
    await page.getByTestId('personnel-page').waitFor();
    await page.getByTestId('personnel-table').waitFor();
    checked('人员列表路由加载');
    await mkdir(screenshots);
    await page.screenshot({ path: resolve(screenshots, 'personnel-list.png'), fullPage: true, animations: 'disabled' });
    await page.locator('.el-pagination .btn-next').click();
    await page.getByTestId('row-detail-person-021').waitFor();
    checked('列表分页');
    await page.locator('.el-pagination .btn-prev').click();
    await input('query-name').fill('张明');
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await page.waitForFunction(() => document.querySelectorAll('[data-testid^="row-detail-"]').length === 1);
    checked('查询筛选');
    await page.getByRole('button', { name: '重置', exact: true }).click();
    await page.waitForFunction(() => document.querySelectorAll('[data-testid^="row-detail-"]').length === 20);
    checked('重置查询');
    await page.getByTestId('row-detail-person-001').click();
    await page.getByTestId('detail-page').waitFor();
    if (!(await page.getByTestId('detail-name').textContent()).includes('张明')) throw new Error('详情没有引用共享种子数据');
    checked('列表与详情共享实体');
    await page.getByTestId('back-list').click();
    await page.getByTestId('row-edit-person-001').click();
    await input('person-name').fill('未保存修改');
    await page.getByRole('button', { name: '关闭', exact: true }).click();
    await page.getByRole('button', { name: '放弃修改', exact: true }).click();
    await page.getByTestId('row-detail-person-001').click();
    if (!(await page.getByTestId('detail-name').textContent()).includes('张明')) throw new Error('取消编辑修改了原实体');
    checked('取消编辑保留原实体');
    await page.getByTestId('back-list').click();
    await page.getByTestId('row-edit-person-001').click();
    await input('person-name').fill('张明已更新');
    await input('person-capacity').fill('0');
    await page.getByTestId('person-status').click();
    await page.getByRole('button', { name: '保存', exact: true }).click();
    await page.getByTestId('row-detail-person-001').click();
    await page.getByTestId('detail-name').filter({ hasText: '张明已更新' }).waitFor();
    await page.getByText('停用', { exact: true }).waitFor();
    await page.getByText('0', { exact: true }).waitFor();
    checked('修改后详情同步且保留零值和停用状态');
    await page.getByTestId('back-list').click();
    await page.getByTestId('create-person').click();
    await page.getByRole('button', { name: '保存', exact: true }).click();
    await page.getByTestId('form-error').waitFor();
    checked('必填校验失败保留表单');
    await input('person-name').fill('原型验收人员');
    await page.getByRole('button', { name: '保存', exact: true }).click();
    await page.getByTestId('personnel-table').getByText('原型验收人员', { exact: true }).waitFor();
    checked('模拟新增后列表更新');
    await page.getByTestId('reset-demo').click();
    await Promise.all([
      page.waitForNavigation({ waitUntil: 'networkidle' }),
      page.getByRole('button', { name: '重置', exact: true }).last().click(),
    ]);
    await page.getByTestId('row-detail-person-001').waitFor();
    if ((await page.getByTestId('personnel-table').textContent()).includes('原型验收人员')) throw new Error('重置后新增记录仍存在');
    await page.getByTestId('row-detail-person-001').click();
    if ((await page.getByTestId('detail-name').textContent()).trim() !== '张明') throw new Error('重置后姓名没有恢复种子值');
    await page.getByText('启用', { exact: true }).waitFor();
    await page.getByText('1', { exact: true }).waitFor();
    await page.getByTestId('back-list').click();
    await page.getByTestId('row-detail-person-001').waitFor();
    checked('重置模拟数据');
    await page.screenshot({ path: resolve(screenshots, 'personnel-after-reset.png'), fullPage: true, animations: 'disabled' });
    if (errors.length || denied.length) throw Object.assign(new Error('浏览器存在运行错误或外部请求'), { diagnostic: JSON.stringify({ errors, denied }) });
    return { status: 'PASSED', cases, browser: browser.version(), notVerified: ['真实模型生成', '业务需求语义完整性', 'P3数据库和权限生命周期'] };
  } finally {
    signal.removeEventListener('abort', abort);
    await cleanup();
  }
}

/** 发布前先检查全部静态文件，再将同一目录提供给临时 Chromium 会话。 */
export async function buildProject(taskRoot, templateDir, toolchain, signal) {
  await compile(taskRoot, templateDir, signal);
  const output = resolve(taskRoot, 'dist');
  const screenshots = resolve(taskRoot, 'screenshots');
  const files = await encodeTree(output, MAX_DIST);
  let request;
  try { request = JSON.parse(await readFile(resolve(taskRoot, 'source/prototype-verification.json'), 'utf8')); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
  const deadline = AbortSignal.timeout(120_000);
  const active = AbortSignal.any([signal, deadline]);
  active.throwIfAborted();
  const serve = () => startStaticServer(output);
  let validation;
  try {
    validation = request ? await verifyGenerated(active, request, serve, screenshots, toolchain.executablePath)
      : await verifyFixture(active, serve, screenshots, toolchain.executablePath);
    active.throwIfAborted();
  } catch (error) {
    if (active.aborted) throw new PrototypeError('PROTOTYPE_TIMEOUT', '本机浏览器验证已取消或超时');
    throw Object.assign(new PrototypeError('PROTOTYPE_PREVIEW_FAILED', '本机 Chromium 验证失败'), {
      diagnostic: String(error.diagnostic || error.message).slice(-12000),
    });
  }
  if (validation.browser !== toolchain.chromium) throw new PrototypeError('PROTOTYPE_TOOLCHAIN_NOT_PREPARED', 'Chromium 版本已变化，请重新执行 prototype:prepare');
  validation.browser = 'Chromium ' + validation.browser;
  validation.playwright = toolchain.playwright;
  validation.toolchain = { node: process.version, platform: process.platform, architecture: process.arch, vite: toolchain.vite, execution: 'host' };
  return { protocolVersion: 1, status: 'PASSED', validation, files, screenshots: await encodeTree(screenshots, MAX_SCREENSHOTS) };
}
