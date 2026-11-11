/** 阅读与局部编辑的保真、安全和问题关联回归。@author Andersen */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { prdNavigation, prdBreadcrumbs, readerBlocks, updatePrdSection, cleanIssueLinks, linkedIssues, parsePrdText, editableTable, tableMarkdown, prdLocation, issuePaths } from '../src/workspace/prd-reader.js';
/** 两个同名列表用于检验稳定 ID，不通过名字猜测问题位置。 */
function fixture() {
  return { templateVersion: 2, background: '背景', objectives: '', scope: '', workflows: '', rules: '', exceptions: '', acceptance: '', nonfunctional: '', exclusions: '', unresolvedIssues: ['Q01'], issueLinks: { Q01: ['page:a:list:search'] }, modules: ['a','b'].map(id => ({ id, title: '人员', sections: {}, pages: [{ id: 'list', title: '列表', type: 'LIST', sections: { search: `${id} 条件` } }] })) };
}
test('目录选择只呈现当前页面，局部修改保留其他页面和问题关联', () => {
  const prd = fixture(), next = updatePrdSection(prd, 'page:a:list:search', '部门筛选');
  assert.equal(prd.modules[0].pages[0].sections.search, 'a 条件');
  assert.equal(next.modules[1].pages[0].sections.search, 'b 条件');
  assert.deepEqual(next.issueLinks, prd.issueLinks);
  assert.ok(readerBlocks(next, 'page:a:list').every(block => block.key.startsWith('page:a:list:')));
  assert.ok(prdNavigation(prd).some(item => item.key === 'page:b:list'));
  assert.equal(prdLocation('rules'), 'common');
  assert.deepEqual(linkedIssues(prd,'page:b:list',true), []);
  assert.deepEqual(linkedIssues(prd,'page:a:list:search'), ['Q01']);
});
test('删除节点只解绑问题，旧问题与可选正文的关联不丢失', () => {
  const prd = fixture(); prd.issueLinks.Q01.push('page:b:list:permissions'); prd.modules.shift();
  assert.deepEqual(cleanIssueLinks(prd), { Q01: ['page:b:list:permissions'] });
  assert.deepEqual(prd.unresolvedIssues, ['Q01']);
});
test('Markdown 表格安全解析并保留转义竖线、反斜杠和外部 HTML 文本', () => {
  const text = '| 字段 | 说明 |\n| --- | --- |\n| 姓名 | A\\|B |\n| 原文 | <script>alert(1)</script> |';
  const table = editableTable(text);
  assert.equal(table.rows[0][1], 'A|B');
  assert.equal(table.rows[1][1], '<script>alert(1)</script>');
  assert.deepEqual(editableTable(tableMarkdown(table)), table);
  assert.equal(editableTable('说明\n\n' + text), null);
  assert.equal(parsePrdText('A | B\n不是表格')[0].type, 'paragraph');
  assert.equal(parsePrdText('<img src=x onerror=alert(1)>')[0].text, '<img src=x onerror=alert(1)>');
});

test('任意问题文字不会读取对象原型中的伪关联', () => {
  const prd = fixture(); prd.unresolvedIssues = ['constructor', '__proto__']; prd.issueLinks = {};
  assert.deepEqual(issuePaths(prd, 'constructor'), []);
  assert.deepEqual(linkedIssues(prd, 'page:a:list:search'), []);
  assert.deepEqual(cleanIssueLinks(prd), {});
});

test('范围面包屑按所属 ID 定位，重名和名称斜杠不改变层级，空规则仍可导航', () => {
  const prd = fixture();
  prd.modules[1].title = '人员 / 团队';
  prd.modules[1].pages[0].title = '新增 / 编辑人员';
  assert.deepEqual(prdBreadcrumbs(prd, 'page:b:list:permissions'), [
    { key: 'DOCUMENT', title: 'PRD', kind: '文档' },
    { key: 'module:b', title: '人员 / 团队', kind: '模块' },
    { key: 'page:b:list', title: '新增 / 编辑人员', kind: '页面' },
    { key: 'page:b:list:permissions', title: '页面权限', kind: '规则' },
  ]);
  assert.equal(prdBreadcrumbs(prd, 'module:a:rules').at(-1).key, 'module:a:rules');
  assert.equal(prdBreadcrumbs(prd, 'page:a:list').at(-1).key, 'page:a:list');
  assert.equal(prdBreadcrumbs(prd, 'module:a').at(-1).key, 'module:a');
  assert.equal(prdBreadcrumbs(prd, 'rules', [['rules', '旧版业务规则']]).at(-1).title, '旧版业务规则');
  for (const path of ['DOCUMENT', 'page:missing:list:search', 'page:a:missing', 'page:a:list:unknown', 'module:a:unknown', 'page:a:list:search:extra']) {
    assert.deepEqual(prdBreadcrumbs(prd, path), [{ key: 'DOCUMENT', title: 'PRD', kind: '文档' }]);
  }
});
