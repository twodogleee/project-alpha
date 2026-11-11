/** 解释受限生成页面用例；路由、错误及隔离检查始终由平台执行。@author Andersen */
import { mkdir } from 'node:fs/promises';
import { chromium } from 'playwright';
import { resolve } from 'node:path';
import { browserOptions } from '../src/host-toolchain.mjs';

/** 补充交互不能替代平台路由验收，也不能调用任意代码或浏览器 API。 */
export async function verifyGenerated(signal, request, startStaticServer, screenshots, executablePath) {
  let server;
  let browser;
  let context;
  /** 即使页面在第一条路由就失败，也保留有界的实际浏览器错误供内部修复。 */
  const errors = [];
  /** 被阻止的请求只有目标摘要，不包含宿主配置或凭证。 */
  const denied = [];
  /** 超时和 finally 都清理本次浏览器上下文及静态服务。 */
  async function cleanup() {
    await Promise.allSettled([context?.close(), browser?.close(), server ? new Promise(resolveClosed => { server.closeAllConnections(); server.close(resolveClosed); }) : Promise.resolve()]);
  }
  const abort = () => { void cleanup(); };
  signal.addEventListener('abort', abort, { once: true });
  try {
    const served = await startStaticServer();
    server = served.server;
    const origin = served.origin;
    browser = await chromium.launch(browserOptions(executablePath));
    if (signal.aborted) throw new Error('浏览器验证已取消');
    // opaque sandbox 与 worker-src none 已阻止 Service Worker；Playwright block 注入会误读被禁用的 navigator.serviceWorker。
    context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, acceptDownloads: false });
    const cases = [];
    await context.route('**/*', async route => {
      const url = new URL(route.request().url());
      if (url.origin !== origin || !['GET', 'HEAD'].includes(route.request().method())) { denied.push(`${url.origin}${url.pathname}`.slice(0, 200)); await route.abort(); }
      else await route.continue();
    });
    const page = await context.newPage();
    page.on('pageerror', error => errors.push(String(error.message).slice(0, 500)));
    page.on('console', message => { if (message.type() === 'error') errors.push(message.text().slice(0, 500)); });
    page.on('popup', popup => { denied.push('popup'); void popup.close(); });
    page.on('download', download => { denied.push('download'); void download.cancel(); });
    page.on('response', response => { if (response.status() >= 400) errors.push(`资源返回 ${response.status()}`); });
    page.setDefaultTimeout(8_000);
    await mkdir(screenshots, { recursive: true });
    /** 每次导航都必须停留在指定路由且主区域实际有内容。 */
    async function navigate(path) {
      await page.goto(`${origin}/#${path}`, { waitUntil: 'networkidle' });
      if (new URL(page.url()).origin !== origin || new URL(page.url()).hash !== `#${path}`) throw new Error('页面跳转没有命中声明路由');
      const main = page.locator('.prototype-main');
      await main.waitFor({ state: 'visible' });
      if (!(await main.innerText()).trim()) throw new Error('页面主区域没有可见业务内容');
      if (await page.locator('iframe,object,embed').count()) throw new Error('生成页面不能嵌入外部浏览上下文');
    }
    for (let index = 0; index < request.pages.length; index++) {
      const descriptor = request.pages[index];
      await navigate(descriptor.testPath);
      cases.push({ name: `平台路由：${descriptor.moduleId}/${descriptor.pageId}`, status: 'PASSED' });
      await page.screenshot({ path: resolve(screenshots, `generated-${index + 1}.png`), fullPage: true, animations: 'disabled' });
    }
    for (const scenario of request.cases) {
      await navigate(scenario.path);
      for (const step of scenario.steps) {
        const target = step.testId ? page.getByTestId(step.testId) : page.locator('.prototype-main');
        if (step.action === 'click') await target.click();
        else if (step.action === 'fill') {
          const input = page.locator(`[data-testid="${step.testId}"] input, input[data-testid="${step.testId}"], [data-testid="${step.testId}"] textarea, textarea[data-testid="${step.testId}"]`);
          await input.fill(step.value);
        } else if (step.action === 'assertVisible') await target.waitFor({ state: 'visible' });
        else if (step.action === 'assertText') await target.filter({ hasText: step.text }).waitFor({ state: 'visible' });
        else throw new Error('受限用例动作无效');
      }
      cases.push({ name: `补充交互：${scenario.name}`, status: 'PASSED' });
    }
    // 固定壳重置属于平台基础动作；刷新后所有固定路由仍须可加载。
    await navigate(request.pages.find(entry => entry.menu).testPath);
    await page.getByTestId('reset-demo').click();
    const confirmation = page.locator('.el-message-box');
    await confirmation.waitFor({ state: 'visible' });
    await Promise.all([page.waitForEvent('load'), confirmation.getByRole('button', { name: '重置', exact: true }).click()]);
    await page.locator('.prototype-main').waitFor({ state: 'visible' });
    if (!(await page.locator('.prototype-main').innerText()).trim()) throw new Error('重置后业务页面为空');
    cases.push({ name: '平台重置演示数据与重新加载', status: 'PASSED' });
    if (errors.length || denied.length) throw Object.assign(new Error('浏览器存在运行错误或禁止请求'), { diagnostic: JSON.stringify({ errors, denied }).slice(0, 12000) });
    cases.push({ name: '平台无运行错误及外部请求', status: 'PASSED' });
    return { status: 'PASSED', cases, browser: browser.version(),
      notVerified: ['业务需求语义完整性', '全部用户路径', '人工需求确认', ...(request.mode === 'synthetic' ? ['真实模型生成'] : [])] };
  } catch (error) {
    if (!error.diagnostic && (errors.length || denied.length)) error.diagnostic = JSON.stringify({ errors, denied }).slice(0, 12000);
    throw error;
  } finally { signal.removeEventListener('abort', abort); await cleanup(); }
}
