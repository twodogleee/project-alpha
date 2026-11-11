/** PRD 阅读投影、局部更新和问题定位；不解释或执行外部 HTML。@author Andersen */
import { documentBlocks, globalSections, globalSectionsFor, sectionsFor, modulePath, pagePath, prdLabels } from './prd-template.js';
/** 按稳定 ID 还原讨论范围的各级定位目标；名称中的斜杠不参与解析，失效路径只保留文档入口。 */
export function prdBreadcrumbs(prd, path, globals = globalSectionsFor(prd)) {
  const root = [{ key: 'DOCUMENT', title: 'PRD', kind: '文档' }];
  if (path === 'DOCUMENT') return root;
  const parts = (path || '').split(':');
  if (parts.length === 1) {
    const section = globals.find(([key]) => key === path);
    return section ? [...root, { key: path, title: section[1], kind: '章节' }] : root;
  }
  const module = prd?.modules?.find(item => item.id === parts[1]);
  if (!module) return root;
  const moduleCrumb = { key: modulePath(module), title: module.title, kind: '模块' };
  if (parts[0] === 'module' && parts.length === 2) return [...root, moduleCrumb];
  if (parts[0] === 'module' && parts.length === 3) {
    const section = sectionsFor(prd, 'module').find(([key]) => key === parts[2]);
    return section ? [...root, moduleCrumb, { key: path, title: section[1], kind: '规则' }] : root;
  }
  if (parts[0] !== 'page' || ![3, 4].includes(parts.length)) return root;
  const page = module.pages?.find(item => item.id === parts[2]);
  if (!page) return root;
  const pageCrumb = { key: pagePath(module, page), title: page.title, kind: '页面' };
  if (parts.length === 3) return [...root, moduleCrumb, pageCrumb];
  const section = sectionsFor(prd, 'page').find(([key]) => key === parts[3]);
  return section ? [...root, moduleCrumb, pageCrumb, { key: path, title: section[1], kind: '规则' }] : root;
}
/** 目录只选择一份整体说明、模块说明或页面，稳定 ID 不随改名变化。 */
export function prdNavigation(prd) {
  return [{ key: 'overview', title: '整体说明', kind: 'global' }, ...(prd?.modules || []).flatMap(module => [
    { key: modulePath(module), title: module.title, kind: 'module' },
    ...module.pages.map(page => ({ key: pagePath(module, page), title: page.title, kind: 'page' })),
  ]), { key: 'common', title: '全局约定', kind: 'global' }];
}
/** 正文路径转换为页面目录；历史全局键仍有确定归属。 */
export function prdLocation(path, prd) {
  const parts = (path || '').split(':');
  if (parts[0] === 'page') return parts.slice(0, 3).join(':');
  if (parts[0] === 'module') return parts.slice(0, 2).join(':');
  return globalSectionsFor(prd).slice(4).some(([key]) => key === path) ? 'common' : 'overview';
}
/** 当前目录的正文，不混入其他页面；可选正文的已有内容仍保留。 */
export function readerBlocks(prd, location, globals = globalSections) {
  return documentBlocks(prd, globals).filter(block => block.text !== undefined && prdLocation(block.key, prd) === location);
}
/** 在独立副本上修改一个正文章节，不覆盖同级内容与问题关联。 */
export function updatePrdSection(prd, path, value) {
  const next = JSON.parse(JSON.stringify(prd)), parts = path.split(':');
  if (parts[0] === 'module') next.modules.find(module => module.id === parts[1]).sections[parts[2]] = value;
  else if (parts[0] === 'page') next.modules.find(module => module.id === parts[1]).pages.find(page => page.id === parts[2]).sections[parts[3]] = value;
  else next[path] = value;
  return next;
}
/** 结构删除后问题原文保留，只清理已失效目标，避免保存出悬空关联。 */
export function cleanIssueLinks(prd) {
  const valid = new Set(documentBlocks(prd).filter(block => block.text !== undefined).map(block => block.key));
  // 未展示的可选章节也允许关联问题。
  const labels = prdLabels(prd);
  for (const key of Object.keys(labels)) if (key.split(':').length === 4 || (key.startsWith('module:') && key.split(':').length === 3)) valid.add(key);
  return Object.fromEntries((prd.unresolvedIssues || []).filter(issue => issuePaths(prd, issue).length).map(issue => [issue, issuePaths(prd, issue).filter(path => valid.has(path))]));
}
/** 问题是任意外部文本，仅接受自身属性中的数组，不读取 Object 原型成员。 */
export function issuePaths(prd, issue) { return Object.hasOwn(prd?.issueLinks || {}, issue) && Array.isArray(prd.issueLinks[issue]) ? prd.issueLinks[issue] : []; }
/** 只使用明确保存的关联，不根据同名字段或问题文字猜测。 */
export function linkedIssues(prd, path, directory = false) {
  return (prd?.unresolvedIssues || []).filter(issue => issuePaths(prd, issue).some(target => directory ? prdLocation(target, prd) === path : target === path));
}
/** 解析 Markdown 单行单元格，转义的竖线和反斜杠不会被当成分列符。 */
export function tableCells(line) {
  let value = line.trim(); if (value.startsWith('|')) value = value.slice(1);
  if (value.endsWith('|') && !value.endsWith('\\|')) value = value.slice(0, -1);
  const cells = []; let cell = '';
  for (let i = 0; i < value.length; i++) {
    if (value[i] === '\\' && ['|', '\\'].includes(value[i + 1])) cell += value[++i];
    else if (value[i] === '|') { cells.push(cell.trim()); cell = ''; }
    else cell += value[i];
  }
  cells.push(cell.trim()); return cells;
}
/** 表头分隔行必须完整合法，普通带竖线的业务文本不误判为表格。 */
function tableStart(lines, index) {
  if (!lines[index]?.includes('|') || !lines[index + 1]?.includes('|')) return false;
  const header = tableCells(lines[index]), divider = tableCells(lines[index + 1]);
  return header.length > 1 && header.length === divider.length && divider.every(cell => /^:?-{3,}:?$/.test(cell));
}
/** 只识别表格、标题和列表；未知 Markdown 按原文显示，禁止生成 HTML 字符串。 */
export function parsePrdText(text = '') {
  const lines = text.split(/\r?\n/), blocks = [];
  for (let i = 0; i < lines.length;) {
    if (!lines[i].trim()) { i++; continue; }
    if (tableStart(lines, i)) {
      const headers = tableCells(lines[i]); i += 2; const rows = [];
      while (i < lines.length && lines[i].trim() && lines[i].includes('|') && tableCells(lines[i]).length === headers.length) rows.push(tableCells(lines[i++]));
      blocks.push({ type: 'table', headers, rows }); continue;
    }
    if (/^#{1,6}\s/.test(lines[i])) { blocks.push({ type: 'heading', text: lines[i++].replace(/^#{1,6}\s+/, '') }); continue; }
    if (/^\s*(?:[-*+]\s|\d+[.)、]\s*)/.test(lines[i])) {
      const items = []; while (i < lines.length && /^\s*(?:[-*+]\s|\d+[.)、]\s*)/.test(lines[i])) items.push(lines[i++].replace(/^\s*(?:[-*+]\s|\d+[.)、]\s*)/, ''));
      blocks.push({ type: 'list', items }); continue;
    }
    const paragraph = [lines[i++]];
    while (i < lines.length && lines[i].trim() && !tableStart(lines, i) && !/^\s*(?:#{1,6}\s|[-*+]\s|\d+[.)、]\s*)/.test(lines[i])) paragraph.push(lines[i++]);
    blocks.push({ type: 'paragraph', text: paragraph.join('\n') });
  }
  return blocks;
}
/** 表格编辑序列化只转义分隔符，字段的业务文本保持原义。 */
export function tableMarkdown(table) {
  const row = cells => `| ${cells.map(cell => cell.replace(/\\/g, '\\\\').replace(/\|/g, '\\|').replace(/\r?\n/g, ' ')).join(' | ')} |`;
  return [row(table.headers), row(table.headers.map(() => '---')), ...table.rows.map(row)].join('\n');
}
/** 单一表格允许单元格编辑；混合正文保留原文编辑，避免重排或丢失内容。 */
export function editableTable(text) {
  const blocks = parsePrdText(text);
  return blocks.length === 1 && blocks[0].type === 'table' ? blocks[0] : null;
}
/** 任务结果只提供实际变化与应用状态；旧聊天按历史描述展示。 */
export function messageResult(message, runs) {
  const run = runs.find(item => item.id === message.runId);
  if (run?.kind !== 'WORKBENCH' || run.status !== 'SUCCEEDED') return null;
  try { return JSON.parse(run.resultText); } catch { return null; }
}
