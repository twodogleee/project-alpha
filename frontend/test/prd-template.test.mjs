/** 模块型 PRD 的类型边界、历史读取及导出保真验证。@author Andersen */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { documentBlocks, prdLabels, requiredPageSections, visiblePageSections, restorePrdDraft, templateCatalog, prdTemplate, switchPrdTemplate, templateImpact, newPage, sectionHint } from '../src/workspace/prd-template.js';
import { prdBreadcrumbs } from '../src/workspace/prd-reader.js';
import { prdMarkdown } from '../src/workspace/lifecycle.js';
/** 合成文档使用同名页面但不同模块，检验定位不依赖名称。 */
function fixture() {
  return { templateVersion: 2, background: '背景', objectives: '目标', scope: '范围', workflows: '流程', rules: '公共规则', exceptions: '', acceptance: '', nonfunctional: '边界', exclusions: '排除', requirementIds: [], unresolvedIssues: ['Q01 · 人员 / 列表 · 排序待确认'], modules: [
    { id: 'people', title: '人员管理', sections: { overview: '维护人员' }, pages: [{ id: 'list', title: '列表', type: 'LIST', sections: { fields: '| 字段 | 说明 |\n| --- | --- |\n| 姓名 | 文本 |', search: '姓名模糊查询' } }, { id: 'form', title: '新增', type: 'FORM', sections: { form: '姓名必填', interactions: '保存关闭' } }] },
    { id: 'team', title: '团队管理', sections: {}, pages: [{ id: 'list', title: '列表', type: 'LIST', sections: { fields: '团队名称' } }] },
  ] };
}
test('模块页面导出完整包含字段表格、表单和待确认事项，不串同名页面', () => {
  const prd = fixture(), before = JSON.stringify(prd);
  const text = prdMarkdown('一期', prd, null, false, 'manual', null);
  for (const expected of ['### 人员管理', '### 团队管理', '#### 列表 · 列表', '##### 表单字段与校验', '| 姓名 | 文本 |', '团队名称', 'Q01 · 人员 / 列表']) assert.ok(text.includes(expected), expected);
  assert.equal(JSON.stringify(prd), before);
  assert.equal(prdLabels(prd)['page:people:list:fields'], '人员管理 / 列表 / 展示字段');
  assert.equal(prdLabels(prd)['page:team:list:fields'], '团队管理 / 列表 / 展示字段');
});
test('表单不强制列表搜索排序，切换类型后已写正文仍展示和导出', () => {
  assert.ok(requiredPageSections('FORM').includes('form'));
  assert.ok(!requiredPageSections('FORM').includes('search'));
  const page = { type: 'FORM', sections: { search: '原有搜索说明' } };
  assert.ok(visiblePageSections(page).some(([key]) => key === 'search'));
  for (const type of ['CALENDAR', 'GANTT', 'DAG', 'CUSTOM']) {
    assert.ok(requiredPageSections(type).includes('interactions'));
    assert.ok(!requiredPageSections(type).includes('sorting'));
  }
});
test('历史九章节文档不出现虚构模块，按快照本身结构读取', () => {
  const prd = fixture(); delete prd.modules; delete prd.templateVersion;
  const blocks = documentBlocks(prd);
  assert.equal(blocks.length, 9);
  assert.ok(!blocks.some(block => block.key === 'modules'));
  assert.equal(blocks.find(block => block.key === 'scope').text, '范围');
});
test('问题关联的空白可选规则也出现在完整文档中，可定位且不虚构正文', () => {
  const prd = fixture(); prd.issueLinks = { [prd.unresolvedIssues[0]]: ['page:people:list:rules'] };
  const block = documentBlocks(prd).find(item => item.key === 'page:people:list:rules');
  assert.equal(block.text, ''); assert.equal(block.title, '页面业务规则');
  assert.ok(!documentBlocks(prd).some(item => item.key === 'page:team:list:rules'));
});

test('升级后撤销恢复旧正文，同时保留新版结构以通过防降级检查', () => {
  const previous = { prd: { scope: '旧正文', unresolvedIssues: ['旧问题'] } };
  const restored = restorePrdDraft(previous, { templateVersion: 2 });
  assert.equal(restored.prd.templateVersion, 2);
  assert.equal(restored.prd.scope, '旧正文');
  assert.deepEqual(restored.prd.modules, []);
  assert.equal(previous.prd.templateVersion, undefined);
});

// 以带内容与空白规则关联的业务样本验证切换，不只校验模板结构。
test('三种模板往返切换只改变引用，正文、已有页面类型和空白问题关联完全保留', () => {
  const original = fixture();
  original.issueLinks = { [original.unresolvedIssues[0]]: ['page:people:list:permissions', 'page:people:form:search'] };
  const before = structuredClone(original);
  let next = original;
  for (const id of ['mobile', 'general', 'management']) {
    next = switchPrdTemplate(next, id, 1);
    const { templateId, templateRevision, ...body } = next;
    assert.deepEqual(body, original);
    assert.equal(prdTemplate(next).id, id);
    const blocks = documentBlocks(next);
    for (const key of original.issueLinks[original.unresolvedIssues[0]]) assert.ok(blocks.some(item => item.key === key && item.text === ''));
    assert.equal(next.modules[0].pages[0].type, 'LIST');
  }
  assert.deepEqual(original, before);
});

test('移动模板统一影响阅读、面包屑、填写提示、新页面与下载，不伪造正文', () => {
  const prd = switchPrdTemplate(fixture(), 'mobile', 1);
  assert.equal(prdLabels(prd)['page:people:list:fields'], '人员管理 / 列表 / 内容与展示元素');
  assert.equal(prdBreadcrumbs(prd, 'page:people:list:fields').at(-1).title, '内容与展示元素');
  assert.match(sectionHint(prd, 'page:people:list:states'), /弱网/);
  assert.match(sectionHint(prd, 'nonfunctional'), /离线/);
  const page = newPage(prd);
  assert.equal(page.type, 'HOME'); assert.deepEqual(page.sections, {});
  assert.ok(!requiredPageSections(page.type, prd).includes('search'));
  assert.ok(!requiredPageSections(page.type, prd).includes('sorting'));
  assert.match(prdMarkdown('移动产品', prd, null, false, 'manual'), /模板：App \/ 小程序 · v1/);
  assert.match(prdMarkdown('移动产品', prd, null, false, 'manual'), /##### 内容与展示元素/);
});

test('新模板版本发布不改变旧稿与旧版撤销的解析，未知版本明确报错', () => {
  const old = fixture(), before = documentBlocks(old);
  const future = structuredClone(prdTemplate(old));
  future.revision = 99; future.name = '未来管理模板'; future.pageSections.find(item => item.key === 'fields').title = '新版本字段';
  templateCatalog.push(future);
  try {
    assert.equal(prdTemplate(old).revision, 1);
    assert.deepEqual(documentBlocks(old), before);
    assert.match(prdLabels(switchPrdTemplate(old, 'management', 99))['page:people:list:fields'], /新版本字段/);
    const restored = restorePrdDraft({ prd: old }, switchPrdTemplate(old, 'mobile', 1));
    assert.equal(prdTemplate(restored.prd).id, 'management');
    assert.deepEqual(documentBlocks(restored.prd), before);
    assert.throws(() => switchPrdTemplate(old, 'missing', 1), /无法读取 PRD 模板/);
  } finally { templateCatalog.pop(); }
});

test('切换评估指出具体必填变化，关联路径与文档未被评估过程改写', () => {
  const prd = fixture(), before = structuredClone(prd);
  const impact = templateImpact(prd, 'general', 1);
  assert.ok(impact.removed.includes('人员管理 / 权限与数据范围'));
  assert.ok(impact.renamed.some(item => item.key === 'page:people:list:fields' && item.to.endsWith('内容与输出')));
  assert.deepEqual(prd, before);
});

test('旧九章节显式切换后按目标模板检查，切回原模板恢复旧版要求', () => {
  const old = fixture(); old.templateVersion = 1; old.modules = [];
  const impact = templateImpact(old, 'mobile', 1);
  assert.ok(impact.removed.includes('异常路径'));
  assert.ok(impact.removed.includes('验收标准'));
  const mobile = switchPrdTemplate(old, 'mobile', 1);
  assert.equal(mobile.templateVersion, 1);
  assert.equal(documentBlocks(mobile).find(item => item.key === 'workflows').title, '用户旅程与核心流程');
  assert.ok(templateImpact(mobile, 'management', 1).added.includes('异常路径'));
});
