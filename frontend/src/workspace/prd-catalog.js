/** 在工作区响应进入同步阅读组件前装载精确模板版本；不改写文档或解释外部正文。@author Andersen */
import { hasPrdTemplate, registerTemplateCatalog } from './prd-template.js';

/** 仅检查已知产物容器，涵盖草稿、基线、历史和未应用结果，避免扫描业务文本。 */
export function responsePrds(response) {
  const found = [], queue = [response], visited = new Set();
  while (queue.length) {
    const value = queue.shift();
    if (!value || typeof value !== 'object' || visited.has(value)) continue;
    visited.add(value);
    if (typeof value.scope === 'string' && Array.isArray(value.unresolvedIssues)) {
      found.push(value); continue;
    }
    for (const key of ['lifecycle', 'workspace', 'prdBaseline', 'prd', 'result']) if (value[key]) queue.push(value[key]);
    for (const key of ['items', 'runs']) if (Array.isArray(value[key])) queue.push(...value[key]);
    // 历史记录和持久化任务使用 JSON 字符串；解析只读取数据，不运行任何脚本。
    const serialized = value.kind === 'PRD' ? value.contentJson : value.kind === 'WORKBENCH' ? value.resultText : null;
    if (typeof serialized === 'string') {
      try { queue.push(JSON.parse(serialized)); } catch { /* 无效历史内容交由现有只读原文视图展示。 */ }
    }
  }
  return found;
}

/** 目录读取失败时不把未知绑定送入视图；重试仍可重新请求，旧缓存不会替代缺失版本。 */
export async function hydratePrdTemplates(response, loadCatalog) {
  const documents = responsePrds(response);
  if (documents.some(prd => !hasPrdTemplate(prd))) {
    registerTemplateCatalog(await loadCatalog());
    const missing = documents.find(prd => !hasPrdTemplate(prd));
    if (missing) throw new Error(`无法读取 PRD 模板 ${missing.templateId} v${missing.templateRevision}，请重新加载或检查该发布版本。`);
  }
  return response;
}
