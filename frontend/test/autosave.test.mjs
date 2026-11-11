import test from 'node:test';
import assert from 'node:assert/strict';
import { createAutosave } from '../src/workspace/autosave.js';

// 延迟响应期间的输入必须串行提交，不能被旧稿覆盖。
test('serializes edits made during a pending save', async () => {
  let content = 'old', release, active = 0, maxActive = 0;
  const sent = [], applied = [];
  const queue = createAutosave({
    snapshot: () => JSON.stringify({ content }),
    persist: async value => {
      active++; maxActive = Math.max(maxActive, active); sent.push(value.content);
      if (sent.length === 1) await new Promise(resolve => { release = resolve; });
      active--; return value;
    },
    applied: (value, unchanged) => applied.push([value.content, unchanged]),
    failed: error => assert.fail(error),
  });
  queue.reset(JSON.stringify({ content })); content = 'first';
  const saving = queue.flush(); content = 'second';
  const another = queue.flush(); release();
  assert.equal(await saving, true); assert.equal(await another, true);
  assert.deepEqual(sent, ['first', 'second']); assert.equal(maxActive, 1);
  assert.deepEqual(applied, [['first', false], ['second', true]]);
  assert.equal(queue.dirty(), false); queue.dispose();
});

// 冲突不得后台循环重试或清空用户输入。
test('stops on conflict and preserves input until explicit retry', async () => {
  let content = 'before', calls = 0, failed = 0;
  const queue = createAutosave({ snapshot: () => JSON.stringify({ content }), persist: async value => { calls++; if (calls === 1) throw new Error('conflict'); return value; }, applied: () => {}, failed: () => failed++ });
  queue.reset(JSON.stringify({ content })); content = 'unsaved';
  assert.equal(await queue.flush(), false); assert.equal(await queue.flush(), false);
  assert.equal(calls, 1); assert.equal(failed, 1); assert.equal(queue.dirty(), true); assert.equal(content, 'unsaved');
  assert.equal(await queue.retry(), true); assert.equal(queue.dirty(), false); queue.dispose();
});

// 页面卸载后不得应用旧命令响应或继续提交新编辑。
test('ignores responses after disposal', async () => {
  let content = 'before', release, applied = 0;
  const queue = createAutosave({ snapshot: () => JSON.stringify({ content }), persist: async value => { await new Promise(resolve => { release = resolve; }); return value; }, applied: () => applied++, failed: () => {} });
  queue.reset(JSON.stringify({ content })); content = 'sent';
  const saving = queue.flush(); content = 'newer'; queue.dispose(); release();
  assert.equal(await saving, false); assert.equal(applied, 0);
});

// 服务端归一化后更新基准，不产生无意义的重复保存。
test('accepts normalized server snapshots without saving again', async () => {
  let content = 'before', calls = 0;
  const queue = createAutosave({ snapshot: () => JSON.stringify({ content }), persist: async value => { calls++; return { content: value.content.trim() }; }, applied: value => { content = value.content; queue.reset(JSON.stringify({ content })); }, failed: error => assert.fail(error) });
  queue.reset(JSON.stringify({ content })); content = ' normalized ';
  assert.equal(await queue.flush(), true); assert.equal(calls, 1); assert.equal(content, 'normalized'); assert.equal(queue.dirty(), false); queue.dispose();
});

// 修正字段校验后恢复去抖，与并发冲突的显式处理分开。
test('resumes a corrected validation error after a debounce', async () => {
  let content = 'before', calls = 0, complete;
  const completed = new Promise(resolve => { complete = resolve; });
  const queue = createAutosave({ delay: 1, snapshot: () => JSON.stringify({ content }), persist: async value => { calls++; if (calls === 1) throw new Error('invalid'); return value; }, applied: complete, failed: () => {} });
  queue.reset(JSON.stringify({ content })); content = '';
  assert.equal(await queue.flush(), false); content = 'corrected'; queue.resume();
  assert.equal((await completed).content, 'corrected'); assert.equal(calls, 2); queue.dispose();
});
