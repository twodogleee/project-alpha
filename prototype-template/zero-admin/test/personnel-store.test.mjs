/** 共享模拟实体必须保留校验、取消隔离和跨页面一致性。 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { findPerson, createPersonDraft, validatePerson, savePerson, resetPersonnel, personnelState } from '../src/mock/generated/personnel-store.js';

test('独立表单取消不会改变记录，0 与 false 可完整保存', () => {
  resetPersonnel();
  const draft = { ...findPerson('person-001'), name: '修改姓名', capacity: 0, enabled: false };
  assert.equal(findPerson('person-001').name, '张明');
  savePerson(draft);
  assert.equal(findPerson('person-001').name, '修改姓名');
  assert.equal(findPerson('person-001').capacity, 0);
  assert.equal(findPerson('person-001').enabled, false);
  resetPersonnel();
  assert.equal(findPerson('person-001').name, '张明');
});

test('无效表单不改变数据，新增记录使用稳定独立 ID', () => {
  resetPersonnel();
  const count = personnelState.people.length;
  assert.throws(() => savePerson(createPersonDraft()), /姓名/);
  assert.equal(personnelState.people.length, count);
  assert.match(validatePerson({ ...createPersonDraft(), name: '测试', departmentId: 'unknown' }), /部门/);
  assert.match(validatePerson({ ...createPersonDraft(), name: '测试', capacity: -1 }), /容量/);
  const record = savePerson({ ...createPersonDraft(), name: '演示新人员' });
  assert.ok(record.id);
  assert.equal(findPerson(record.id).name, '演示新人员');
  assert.equal(personnelState.people.length, count + 1);
  resetPersonnel();
});
