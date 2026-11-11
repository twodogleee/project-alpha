/** 模板受限编辑确保正文定位键、分组边界及页面类型规则隔离。 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import catalog from '../../backend/src/main/resources/prd-templates/catalog.json' with { type: 'json' };
import { cloneTemplateDefinition, moveTemplateSection, updateTemplateSection, setTemplateRequired, updateTemplateMetadata } from '../src/workspace/prd-template-editor.js';

test('章节排序只在组内移动，整体和公共规则边界不可越过', () => {
  const source = cloneTemplateDefinition(catalog.templates[0]), original = JSON.stringify(source);
  const moved = moveTemplateSection(source, 'overview', source.globalSections[0].key, 1);
  assert.deepEqual(moved.globalSections.slice(0, 2).map(item => item.key), source.globalSections.slice(0, 2).map(item => item.key).reverse());
  assert.deepEqual(moved.globalSections.slice(4), source.globalSections.slice(4));
  assert.deepEqual(moveTemplateSection(source, 'overview', source.globalSections[3].key, 1), source);
  assert.deepEqual(moveTemplateSection(source, 'common', source.globalSections[4].key, -1), source);
  assert.equal(JSON.stringify(source), original);
});

test('局部提示编辑保留稳定键和所有其他章节，不开放模型指导字段', () => {
  const source = catalog.templates[0], section = source.pageSections[0];
  const next = updateTemplateSection(source, 'page', section.key, 'hint', '<script>外部纯文本</script>');
  assert.equal(next.pageSections[0].key, section.key);
  assert.equal(next.pageSections[0].hint, '<script>外部纯文本</script>');
  assert.deepEqual(next.pageSections.slice(1), source.pageSections.slice(1));
  assert.deepEqual(next.moduleSections, source.moduleSections);
  assert.equal(updateTemplateSection(source, 'page', section.key, 'key', 'replacement'), source);
  assert.equal(updateTemplateMetadata(source, 'guidance', 'replacement'), source);
  assert.equal(updateTemplateMetadata(source, 'defaultPageType', 'unknown'), source);
});

test('必填调整隔离页面类型，保留基础要求和全部标准章节', () => {
  const source = catalog.templates[0], type = source.pageTypes.find(item => item.key === 'LIST');
  let next = cloneTemplateDefinition(source);
  for (const key of type.requiredSections) next = setTemplateRequired(next, 'page', key, false, 'LIST');
  assert.deepEqual(next.pageTypes.find(item => item.key === 'LIST').requiredSections.sort(), ['overview', 'states', 'acceptance'].sort());
  assert.deepEqual(next.pageTypes.filter(item => item.key !== 'LIST'), source.pageTypes.filter(item => item.key !== 'LIST'));
  assert.deepEqual(next.pageSections, source.pageSections);
  assert.deepEqual(next.globalSections, source.globalSections);
  next = setTemplateRequired(next, 'common', 'rules', false);
  for (const key of source.requiredGlobalSections.filter(key => key !== 'rules')) assert.ok(next.requiredGlobalSections.includes(key));
});
