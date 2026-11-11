/** 真实弹窗关闭守卫回归测试；通过 Vite 解析项目别名，不启动服务或连接业务 API。@author Andersen */
import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { createServer } from 'vite';

/** 仅用于加载现有模块的 Vite 实例，不监听 HTTP、WebSocket 或文件变更。 */
let server;
/** 与被测关闭守卫共用同一模块实例，验证真实全局确认契约。 */
let closeModule, feedback;

before(async () => {
  server = await createServer({
    root: fileURLToPath(new URL('../', import.meta.url)),
    configFile: fileURLToPath(new URL('../vite.config.js', import.meta.url)),
    server: { middlewareMode: true, hmr: false, ws: false, watch: null },
    optimizeDeps: { noDiscovery: true, include: [] },
  });
  closeModule = await server.ssrLoadModule('/src/composables/useWorkspacePage.js');
  feedback = await server.ssrLoadModule('/src/lib/feedback.js');
});

after(async () => {
  feedback?.settleConfirmation(false);
  await server?.close();
});

/** 继续编辑必须保留原弹窗，不把关闭确认当作放弃表单。 */
test('脏表单关闭时等待确认，继续编辑保留原弹窗', async () => {
  let open = true;
  const close = closeModule.useDialogClose({ value: false }, () => true, value => { open = value; });
  const pending = close(false);
  assert.equal(feedback.confirmation.open, true);
  assert.equal(open, true);
  feedback.settleConfirmation(false);
  await pending;
  assert.equal(open, true);
  assert.equal(feedback.confirmation.open, false);
});

/** 放弃后结束两个窗口的打开状态，下次打开必须等待新的决定。 */
test('明确放弃关闭原弹窗，重新打开后不沿用上次确认', async () => {
  let open = true;
  const close = closeModule.useDialogClose({ value: false }, () => true, value => { open = value; });
  const first = close(false);
  feedback.settleConfirmation(true);
  await first;
  assert.equal(open, false);
  assert.equal(feedback.confirmation.open, false);
  await close(true);
  const second = close(false);
  assert.equal(open, true);
  assert.equal(feedback.confirmation.open, true);
  feedback.settleConfirmation(false);
  await second;
  assert.equal(open, true);
});

/** 未改动的表单无需生成第二个确认窗口。 */
test('未改动表单直接关闭，不打开全局确认', async () => {
  let open = true;
  const close = closeModule.useDialogClose({ value: false }, () => false, value => { open = value; });
  await close(false);
  assert.equal(open, false);
  assert.equal(feedback.confirmation.open, false);
});

/** 提交过程中保护原表单，完成请求后才能关闭。 */
test('提交期间关闭被阻止，请求结束后允许关闭', async () => {
  let open = true;
  const busy = { value: true };
  const close = closeModule.useDialogClose(busy, () => false, value => { open = value; });
  await close(false);
  assert.equal(open, true);
  assert.equal(feedback.confirmation.open, false);
  busy.value = false;
  await close(false);
  assert.equal(open, false);
});

/** 重复关闭不能替换已有确认回调，也不能多次关闭原窗口。 */
test('连续关闭只处理第一个确认，原弹窗仅关闭一次', async () => {
  let closes = 0;
  const close = closeModule.useDialogClose({ value: false }, () => true, value => { if (!value) closes++; });
  const first = close(false);
  await close(false);
  assert.equal(closes, 0);
  assert.equal(feedback.confirmation.open, true);
  feedback.settleConfirmation(true);
  await first;
  assert.equal(closes, 1);
  assert.equal(feedback.confirmation.open, false);
});
