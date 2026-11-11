/** 删除与恢复只改变目录可见性，发布历史和未发布草稿必须完整保留。 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { templateManagementCards, deletedTemplateIds, updateTemplateDeletion } from '../src/workspace/prd-template-management.js';

test('删除已发布模板时从正常目录排除全部版本，已删除目录保留最近草稿', () => {
  const published = { templates: [{ id: 'management', revision: 1, name: '管理系统' }, { id: 'custom', revision: 1, name: '旧版' }, { id: 'custom', revision: 2, name: '新版' }], deletedTemplateIds: ['custom'] };
  const records = [{ id: 'custom', deleted: true, latestPublishedRevision: 2, draft: { id: 'custom', revision: 3, name: '第三版草稿' } }];
  const original = JSON.stringify({ published, records });
  assert.deepEqual(templateManagementCards(published, records).map(template => template.id), ['management']);
  assert.equal(templateManagementCards(published, records, true)[0].name, '第三版草稿');
  assert.equal(JSON.stringify({ published, records }), original);
  assert.equal(published.templates.filter(template => template.id === 'custom').length, 2);
});

test('未发布模板仍可在已删除目录找到并恢复，恢复不创建新身份或丢弃草稿', () => {
  const published = { templates: [{ id: 'management', revision: 1 }], deletedTemplateIds: [] };
  const draft = { id: 'custom_draft', revision: 1, name: '未发布草稿', pageSections: [{ key: 'rules', title: '业务规则', hint: '保留提示' }] };
  const deleted = { id: draft.id, revision: 2, deleted: true, draft };
  assert.deepEqual(templateManagementCards(published, [deleted], true), [draft]);
  assert.ok(deletedTemplateIds(published, [deleted]).has(draft.id));
  const removedCatalog = updateTemplateDeletion(published, deleted);
  const restored = { ...deleted, revision: 3, deleted: false };
  const nextCatalog = updateTemplateDeletion(removedCatalog, restored);
  assert.deepEqual(templateManagementCards(nextCatalog, [restored], true), []);
  assert.equal(templateManagementCards(nextCatalog, [restored]).find(template => template.id === draft.id), draft);
  assert.deepEqual(nextCatalog.templates, published.templates);
});
