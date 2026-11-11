/** 动态发布目录的首次载入、历史精确绑定与失败恢复回归。@author Andersen */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { hydratePrdTemplates, responsePrds } from '../src/workspace/prd-catalog.js';
import { registerTemplateCatalog, prdTemplate, documentBlocks, switchPrdTemplate, templateImpact, hasPrdTemplate, newPage, sectionHint, selectableTemplates, restoreTemplateBinding } from '../src/workspace/prd-template.js';
import { prdBreadcrumbs, linkedIssues, readerBlocks } from '../src/workspace/prd-reader.js';
import { prdMarkdown } from '../src/workspace/lifecycle.js';

/** 合成发布定义仅改标准章节显示，页面与正文键保持稳定。 */
function definition(id, revision = 1) {
  return { ...structuredClone(prdTemplate()), id, revision, name: '团队模板' };
}
/** 合成人员页面包含空规则关联，验证换模板不遗失讨论目标。 */
function document(id, revision = 1) {
  return { templateVersion: 2, templateId: id, templateRevision: revision, scope: '一期范围', background: '原始背景', objectives: '原始目标', unresolvedIssues: ['排序如何处理？'], issueLinks: { '排序如何处理？': ['page:people:list:sorting'] }, modules: [{ id: 'people', title: '人员管理', sections: {}, pages: [{ id: 'list', title: '人员列表', type: 'LIST', sections: { search: '姓名模糊匹配' } }] }] };
}

test('首次打开同时装载当前稿、历史、基线和未应用候选所需的所有精确版本', async () => {
  const id = 'test-history', current = document(id, 2), old = document(id);
  const response = { lifecycle: { prd: current, prdBaseline: { prd: old }, runs: [{ result: old }] }, workspace: { runs: [{ kind: 'WORKBENCH', resultText: JSON.stringify({ prd: old }) }] }, items: [{ kind: 'PRD', contentJson: JSON.stringify(old) }] };
  assert.equal(responsePrds(response).length, 4);
  let loads = 0;
  const result = await hydratePrdTemplates(response, async () => { loads++; return { templates: [definition(id), definition(id, 2)] }; });
  assert.equal(result, response); assert.equal(loads, 1);
  assert.equal(prdTemplate(current).revision, 2); assert.equal(prdTemplate(old).revision, 1);
  await hydratePrdTemplates(response, async () => { throw new Error('已有精确版本不应再次读取'); });
  assert.equal(loads, 1);
});

test('目录加载失败和缺失版本都阻止渲染未知文档，后续重试仍可成功', async () => {
  const prd = document('test-retry');
  await assert.rejects(hydratePrdTemplates({ prd }, async () => { throw new Error('暂时无法连接'); }), /无法连接/);
  assert.equal(hasPrdTemplate(prd), false);
  await assert.rejects(hydratePrdTemplates({ prd }, async () => ({ templates: [] })), /无法读取 PRD 模板/);
  await hydratePrdTemplates({ prd }, async () => ({ templates: [definition('test-retry')] }));
  assert.equal(prdTemplate(prd).id, 'test-retry');
});

test('草稿和任意业务正文不会被注册，重复发布定义不能覆盖已装载版本', () => {
  const id = 'test-immutable', version = definition(id);
  assert.throws(() => registerTemplateCatalog({ draft: version }), /目录格式无效/);
  assert.equal(hasPrdTemplate(document(id)), false);
  registerTemplateCatalog({ templates: [version] });
  version.name = '服务端对象后来被改写';
  assert.equal(prdTemplate(document(id)).name, '团队模板');
  const conflicting = definition(id); conflicting.pageSections[0].title = '覆盖旧内容';
  assert.throws(() => registerTemplateCatalog({ templates: [definition('test-atomic'), conflicting] }), /无法覆盖旧定义/);
  assert.equal(hasPrdTemplate(document('test-atomic')), false);
  assert.throws(() => { prdTemplate(document(id)).pageSections[0].title = '本地误改'; }, TypeError);
  assert.deepEqual(responsePrds({ content: JSON.stringify(document('fake')), resultText: JSON.stringify(document('fake')), attachments: [{ content: JSON.stringify(document('fake')) }] }), []);
});

test('发布新版只在显式切换后改变标题顺序和必填，保留正文与规则定位', () => {
  const id = 'test-upgrade', v1 = definition(id), v2 = definition(id, 2);
  v2.globalSections.splice(0, 2, v2.globalSections[1], v2.globalSections[0]);
  v2.pageSections.find(item => item.key === 'search').title = '筛选条件';
  v2.pageSections.find(item => item.key === 'search').hint = '说明可组合的条件';
  v2.pageSections.reverse(); v2.defaultPageType = 'HOME';
  v2.pageTypes.find(item => item.key === 'LIST').requiredSections = v2.pageTypes.find(item => item.key === 'LIST').requiredSections.filter(key => key !== 'sorting');
  registerTemplateCatalog({ templates: [v1] });
  const prd = document(id), previous = documentBlocks(prd);
  registerTemplateCatalog({ templates: [v1, v2] });
  assert.deepEqual(documentBlocks(prd), previous);
  const next = switchPrdTemplate(prd, id, 2), impact = templateImpact(prd, id, 2);
  assert.deepEqual({ ...next, templateRevision: 1 }, prd);
  assert.equal(readerBlocks(next, 'overview')[0].text, '原始目标');
  assert.equal(readerBlocks(next, 'overview')[1].text, '原始背景');
  assert.deepEqual(impact.reordered, ['整体说明与全局约定', '页面规则']);
  assert.ok(impact.removed.includes('人员管理 / 人员列表 / 排序与分页'));
  assert.deepEqual(linkedIssues(next, 'page:people:list', true), ['排序如何处理？']);
  assert.ok(documentBlocks(next).some(block => block.key === 'page:people:list:sorting' && block.text === ''));
  assert.equal(prdBreadcrumbs(next, 'page:people:list:search').at(-1).title, '筛选条件');
  assert.equal(sectionHint(next, 'page:people:list:search'), '说明可组合的条件');
  assert.equal(newPage(next).type, 'HOME');
  assert.match(prdMarkdown('模板试用', next, null, false, 'manual'), /##### 筛选条件/);
  assert.doesNotMatch(prdMarkdown('旧历史', prd, null, false, 'manual'), /##### 筛选条件/);
});

test('缺少模板引用的历史稿固定管理系统首版，不受新目录影响', async () => {
  const legacy = { scope: '历史原文', unresolvedIssues: [] };
  await hydratePrdTemplates({ items: [{ kind: 'PRD', contentJson: JSON.stringify(legacy) }, { kind: 'PRD', contentJson: '{无效JSON' }] }, async () => { throw new Error('旧稿不应请求动态目录'); });
  assert.equal(prdTemplate(legacy).id, 'management'); assert.equal(prdTemplate(legacy).revision, 1);
});

test('删除只隐藏新选择，冷启动旧稿仍解析全部历史版本且恢复后最新版本重新可选', async () => {
  const id = 'test-deleted-history', old = document(id), versions = [definition(id), definition(id, 2)];
  const catalog = { templates: [...versions, definition('test-active')], deletedTemplateIds: [id, 'unpublished-draft'] };
  await hydratePrdTemplates({ lifecycle: { prd: old }, items: [{ kind: 'PRD', contentJson: JSON.stringify(document(id, 2)) }] }, async () => catalog);
  assert.deepEqual(selectableTemplates(catalog).map(item => item.id), ['test-active']);
  assert.equal(prdTemplate(old).revision, 1);
  assert.ok(documentBlocks(old).some(item => item.key === 'page:people:list:sorting'));
  assert.match(prdMarkdown('旧文档', old, null, false, 'manual'), /团队模板 · v1/);
  const restored = { ...catalog, deletedTemplateIds: [] };
  registerTemplateCatalog(restored);
  assert.equal(selectableTemplates(restored).find(item => item.id === id).revision, 2);
  assert.equal(prdTemplate(old).revision, 1);
});

test('被删除模板的新绑定遭拒后，只还原引用，保留提交期间的正文和待确认关联', () => {
  const original = document('test-original', 3), changed = { ...structuredClone(original), templateId: 'test-deleted', templateRevision: 1 };
  changed.modules[0].pages[0].sections.search = '新输入的组合查询';
  changed.unresolvedIssues.push('新增待确认'); changed.issueLinks['新增待确认'] = ['page:people:list:search'];
  const recovered = restoreTemplateBinding(changed, original);
  assert.deepEqual(recovered, { ...changed, templateId: original.templateId, templateRevision: 3 });
  assert.equal(changed.templateId, 'test-deleted');
  const legacy = restoreTemplateBinding(changed, { scope: '旧文档无显式绑定' });
  assert.equal(Object.hasOwn(legacy, 'templateId'), false);
  assert.equal(Object.hasOwn(legacy, 'templateRevision'), false);
  assert.deepEqual(legacy.issueLinks, changed.issueLinks);
});
