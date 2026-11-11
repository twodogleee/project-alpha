/** 确认与离开保护的异步契约回归测试。@author Andersen */
import test from 'node:test';
import assert from 'node:assert/strict';
import { confirmation, confirmAction, settleConfirmation } from '../src/lib/feedback.js';

/** 取消必须解析为 false，避免页面把关闭弹层误当成放弃编辑。 */
test('取消离开保护返回保留编辑，并结束确认状态', async () => {
  const decision = confirmAction({ title: '保留输入', description: '尚未保存', action: '放弃' });
  assert.equal(confirmation.open, true);
  settleConfirmation(false);
  assert.equal(await decision, false);
  assert.equal(confirmation.open, false);
});
/** 明确确认只完成当前动作，后续确认不会沿用旧结果。 */
test('明确确认完成当前动作，下一次确认独立等待', async () => {
  const first = confirmAction({ title: '删除', description: '删除测试配置', destructive: true });
  settleConfirmation(true); assert.equal(await first, true);
  const second = confirmAction({ title: '离开', description: '未保存编辑' });
  assert.equal(confirmation.destructive, false);
  settleConfirmation(false); assert.equal(await second, false);
});
/** 多次导航不会覆盖尚未处理的确认回调，使首个守卫永久悬挂。 */
test('确认进行中拒绝重入，保留原确认的完成回调', async () => {
  const first = confirmAction({ title: '第一次导航', description: '等待用户' });
  const second = confirmAction({ title: '第二次导航', description: '重复动作' });
  assert.equal(await second, false);
  assert.equal(confirmation.title, '第一次导航');
  settleConfirmation(true); assert.equal(await first, true);
});
