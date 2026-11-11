/**
 * 可信固定样板的宿主浏览器验证入口；不构建或执行未验证生成源码。
 * 调用：node prototype-worker/test/preview-browser.mjs <已验证 dist> [截图目录]
 * @author Andersen
 */
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { createServer } from 'node:http';
import { chromium } from 'playwright';
import { startPreview } from '../src/preview.mjs';

/** 此检查只能指向平台维护的可信样板静态目录。 */
const artifactDirectory = resolve(process.argv[2] || 'prototype-template/zero-admin/dist');
/** 截图和实际检查结果位于忽略的产物目录，不混入源码。 */
const outputDirectory = resolve(process.argv[3] || 'data/prototypes/validation');
/** 如本机浏览器缓存版本不同，可明确使用已安装的测试浏览器。 */
const executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH || undefined;
/** 捕获真实运行错误，与主动隔离攻击产生的预期 CSP 拒绝分开。 */
const errors = [];
/** 预期网络攻击的接收计数，必须始终为零。 */
let probeRequests = 0;
/** 外部探针只返回空响应，不访问 Alpha 或用户其他服务。 */
const probe = createServer((request, response) => { probeRequests += 1; response.end('unexpected'); });
/** 父文档可读性模拟回调，P3 会替换为真实文档状态。 */
let readable = true;
/** 受控双源服务不固定端口，避免端口冲突。 */
const service = await startPreview({ artifactDirectory, hostPort: 0, previewPort: 0, revisionId: 'browser-fixture', isAvailable: async () => readable });
/** 使用独立无凭证 browser context，不读取用户浏览器会话。 */
let browser;

try {
  browser = await chromium.launch({ headless: true, executablePath });
  await mkdir(outputDirectory, { recursive: true });
  await new Promise((resolveListen) => probe.listen(0, '127.0.0.1', resolveListen));
  /** 外部探针用于证实 connect-src 和 frame-src 阻断真实网络。 */
  const probeUrl = `http://127.0.0.1:${probe.address().port}/private-probe`;
  /** 桌面管理端验证固定可复查的视口。 */
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  page.setDefaultTimeout(15000);
  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto(service.hostUrl);
  /** iframe 限定范围，所有业务交互都在 opaque origin 内发生。 */
  const preview = page.frameLocator('#prototype-frame');
  await preview.getByTestId('personnel-page').waitFor();
  await page.getByText('预览就绪', { exact: false }).waitFor();
  await page.screenshot({ path: join(outputDirectory, 'prototype-list.png'), fullPage: true, animations: 'disabled' });

  await preview.getByPlaceholder('按姓名搜索').fill('张明');
  await preview.getByRole('button', { name: '查询', exact: true }).click();
  assert.equal(await preview.getByTestId('row-detail-person-001').count(), 1);
  await preview.getByTestId('row-detail-person-001').click();
  await preview.getByTestId('detail-page').waitFor();
  assert.equal(await preview.getByTestId('detail-name').textContent(), '张明');
  await page.locator('#preview-status').filter({ hasText: '预览就绪 · /personnel/person-001' }).waitFor();
  await page.screenshot({ path: join(outputDirectory, 'prototype-detail.png'), fullPage: true, animations: 'disabled' });
  await preview.getByTestId('back-list').click();
  await preview.getByTestId('personnel-page').waitFor();
  await page.locator('#preview-status').filter({ hasText: /^预览就绪 · \/personnel$/ }).waitFor();

  await preview.getByTestId('row-edit-person-001').click();
  await preview.getByTestId('person-name').fill('张明已更新');
  await preview.getByRole('button', { name: '保存', exact: true }).click();
  await preview.getByTestId('row-detail-person-001').click();
  assert.equal(await preview.getByTestId('detail-name').textContent(), '张明已更新');
  await preview.getByTestId('back-list').click();

  await preview.getByTestId('create-person').click();
  await preview.getByRole('button', { name: '保存', exact: true }).click();
  await preview.getByTestId('form-error').waitFor();
  await preview.getByTestId('person-name').fill('原型联调新增');
  await preview.getByTestId('person-department').click();
  await preview.getByRole('option', { name: '研发部', exact: true }).click();
  await page.screenshot({ path: join(outputDirectory, 'prototype-form.png'), fullPage: true, animations: 'disabled' });
  await preview.getByRole('button', { name: '保存', exact: true }).click();
  await preview.getByPlaceholder('按姓名搜索').fill('原型联调新增');
  await preview.getByRole('button', { name: '查询', exact: true }).click();
  await preview.getByText('原型联调新增', { exact: true }).waitFor();
  assert.deepEqual(errors, [], '可信样板不能产生运行异常');

  /** 获取真实 Frame，仅运行平台定义的安全断言。 */
  const frame = page.frames().find((item) => item.parentFrame() === page.mainFrame());
  /** opaque origin 下父 DOM、cookie 与持久存储应被浏览器拒绝。 */
  const isolation = await frame.evaluate(async (destination) => {
    /** 验证被保护能力；成功访问视为隔离失败。 */
    const blocked = (read) => { try { read(); return false; } catch (error) { return error.name === 'SecurityError'; } };
    /** fetch 拒绝必须来自 CSP，外部探针另行核对没有收到请求。 */
    let networkBlocked = false;
    try { await fetch(destination); } catch { networkBlocked = true; }
    return {
      parentDomBlocked: blocked(() => window.parent.document.title),
      storageBlocked: blocked(() => localStorage.getItem('alpha-private')),
      cookieBlocked: blocked(() => document.cookie),
      networkBlocked,
    };
  }, probeUrl);
  assert.deepEqual(isolation, { parentDomBlocked: true, storageBlocked: true, cookieBlocked: true, networkBlocked: true });
  assert.equal(probeRequests, 0);

  /** 顶层直接打开资源页必须拒绝，独立窗口打开受控包装页。 */
  const direct = await browser.newPage();
  assert.equal((await direct.goto(service.frameUrl)).status(), 403);
  await direct.goto(service.previewUrl);
  await direct.frameLocator('#prototype-frame').getByTestId('personnel-page').waitFor();
  await direct.close();

  /** iframe 自导航不能绕过 connect-src 通过外部 URL 泄露内容。 */
  await frame.evaluate((destination) => { location.href = destination; }, probeUrl).catch(() => {});
  await page.waitForTimeout(300);
  assert.equal(probeRequests, 0);

  readable = false;
  assert.equal((await page.request.get(service.frameUrl)).status(), 410);
  /** 保存真实检查记录，明确这是可信模板及预览验证而非模型生成。 */
  const report = {
    mode: 'local-template', modelInvoked: false, runtime: await browser.version(),
    checks: ['opaque-module-load', 'hash-route-detail-back', 'history-navigation-bridge', 'query', 'edit-shared-record', 'create-validation-save', 'parent-dom-storage-cookie-blocked', 'network-blocked', 'top-level-denied', 'standalone-wrapper', 'frame-navigation-blocked', 'parent-state-revoked'],
    runtimeErrors: errors, probeRequests, passed: true,
  };
  await writeFile(join(outputDirectory, 'preview-browser-report.json'), JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify(report, null, 2));
} catch (error) {
  /** 失败证据仅针对可信模板，便于定位实际浏览器问题。 */
  const page = browser?.contexts()[0]?.pages()[0];
  if (page) await page.screenshot({ path: join(outputDirectory, 'prototype-failure.png'), fullPage: true, animations: 'disabled' });
  console.error(JSON.stringify({ runtimeErrors: errors, probeRequests, failure: error.message }, null, 2));
  throw error;
} finally {
  await browser?.close();
  await service.close();
  await new Promise((resolveClose) => { probe.closeAllConnections(); probe.close(() => resolveClose()); });
}
