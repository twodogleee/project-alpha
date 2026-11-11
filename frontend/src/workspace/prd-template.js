/** PRD 模板共享服务端版本化定义；正文键与模板名称分离，旧稿按绑定版本读取。@author Andersen */
import catalog from '../../../backend/src/main/resources/prd-templates/catalog.json' with { type: 'json' };
/** 编译期内置目录与运行期已发布版本共同解析文档；草稿定义不得注册。 */
export const templateCatalog = catalog.templates.map(item => freezeDefinition(structuredClone(item)));
/** 递归冻结定义，防止配置编辑的对象引用修改已绑定文档的语义。 */
function freezeDefinition(value) {
  if (value && typeof value === 'object') {
    Object.values(value).forEach(freezeDefinition);
    Object.freeze(value);
  }
  return value;
}
/** 比较定义时忽略 JSON 对象属性顺序，章节数组顺序仍是版本内容。 */
function canonicalDefinition(value) {
  if (Array.isArray(value)) return value.map(canonicalDefinition);
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonicalDefinition(value[key])]));
  return value;
}
/** 仅接收已发布目录端点响应；完整校验后追加，既有版本不可被服务端新值覆盖。 */
export function registerTemplateCatalog(published) {
  if (!Array.isArray(published?.templates)) throw new Error('模板目录格式无效，请重新加载。');
  const additions = [];
  for (const definition of published.templates) {
    if (!definition?.id || !Number.isInteger(definition.revision) || definition.revision < 1
      || !['globalSections', 'moduleSections', 'pageSections', 'pageTypes', 'requiredGlobalSections', 'requiredModuleSections'].every(key => Array.isArray(definition[key]))) {
      throw new Error('已发布模板定义不完整，请重新加载。');
    }
    const existing = [...templateCatalog, ...additions].find(item => item.id === definition.id && item.revision === definition.revision);
    if (existing && JSON.stringify(canonicalDefinition(existing)) !== JSON.stringify(canonicalDefinition(definition))) {
      throw new Error(`模板 ${definition.name} v${definition.revision} 与已发布版本不一致，无法覆盖旧定义。`);
    }
    if (!existing) additions.push(freezeDefinition(structuredClone(definition)));
  }
  templateCatalog.push(...additions);
}
/** 精确引用必须已装载；不允许把未知自定义版本静默回落到内置模板。 */
export function hasPrdTemplate(prd) {
  return templateCatalog.some(item => item.id === (prd?.templateId || 'management') && item.revision === (prd?.templateRevision ?? 1));
}
/** 内置身份来自随构建的统一目录，不使用名称推断。 */
export function isBuiltinTemplate(id) { return catalog.templates.some(item => item.id === id); }
/** 可选目录只显示未删除模板的最新发布版；历史解析始终使用完整定义目录。 */
export function selectableTemplates(published) {
  const deleted = new Set(published?.deletedTemplateIds || []), latest = new Map();
  for (const definition of published?.templates || []) {
    if (deleted.has(definition.id)) continue;
    if (!latest.has(definition.id) || latest.get(definition.id).revision < definition.revision) latest.set(definition.id, definition);
  }
  return [...latest.values()];
}
/** 旧九章节的名称也来自统一定义，结构升级前保持历史阅读效果。 */
export const legacyGlobalSections = catalog.legacyGlobalSections.map(({ key, title }) => [key, title]);
/** 缺少绑定的旧文档固定管理系统第一版，不追随当前最新模板。 */
export function prdTemplate(prd) {
  const id = prd?.templateId || 'management', revision = prd?.templateRevision ?? 1;
  const definition = templateCatalog.find(item => item.id === id && item.revision === revision);
  if (!definition) throw new Error(`无法读取 PRD 模板 ${id} v${revision}，请刷新页面取得对应版本。`);
  return definition;
}
/** 旧调用方使用管理系统第一版；页面新建时应传入文档自身绑定。 */
export const globalSections = prdTemplate().globalSections.map(({ key, title }) => [key, title]);
/** 结构 v1 保留九章节名称；新模板与模块型文档使用自身章节标题。 */
export function globalSectionsFor(prd) {
  const definition = prdTemplate(prd);
  return prd?.templateVersion !== 2 && definition.id === 'management' && definition.revision === 1
    ? legacyGlobalSections : definition.globalSections.map(({ key, title }) => [key, title]);
}
/** 按文档版本取得模块或页面章节的稳定键与显示标题。 */
export function sectionsFor(prd, kind) { return prdTemplate(prd)[`${kind}Sections`].map(({ key, title }) => [key, title]); }
/** 页面类型排序与标题属于模板，切换不会更改已有页面类型。 */
export function pageTypesFor(prd) { return prdTemplate(prd).pageTypes.map(({ key, title }) => [key, title]); }
/** 旧模块组件使用的缺省定义仍来自同一个目录。 */
export const moduleSections = sectionsFor(undefined, 'module'), pageSections = sectionsFor(undefined, 'page'), pageTypes = pageTypesFor();
/** 提示按层级取值，避免页面与模块同名 key 的语义互相覆盖。 */
export function sectionHint(prd, path) {
  const parts = path.split(':'), kind = parts.length === 1 ? 'global' : parts[0];
  return prdTemplate(prd)[`${kind}Sections`]?.find(item => item.key === parts.at(-1))?.hint || '';
}
/** 兼容旧组件的通用提示，只取缺省页面层级。 */
export const sectionHints = Object.fromEntries(prdTemplate().pageSections.map(({ key, hint }) => [key, hint]));
/** 定稿所需章节与后端使用同一份版本化定义。 */
export function requiredPageSections(type, prd) { return prdTemplate(prd).pageTypes.find(item => item.key === type)?.requiredSections || []; }
/** 已填写的非必需内容仍显示，切换类型不丢失原稿。 */
export function visiblePageSections(page, prd) {
  const required = requiredPageSections(page.type, prd);
  return sectionsFor(prd, 'page').filter(([key]) => required.includes(key) || page.sections?.[key]);
}
/** 新模块仅建立空结构，不伪造需求。 */
export function newModule() { return { id: crypto.randomUUID(), title: '未命名模块', sections: {}, pages: [] }; }
/** 新页面采用文档绑定的默认类型，已有页面不随模板切换改型。 */
export function newPage(prd) { return { id: crypto.randomUUID(), title: '未命名页面', type: prdTemplate(prd).defaultPageType, sections: {} }; }
/** 模块和页面的安全定位键只包含服务端约束的稳定标识。 */
export function modulePath(module) { return `module:${module.id}`; }
/** 页面路径包含所属模块，允许不同模块使用各自的页面标识。 */
export function pagePath(module, page) { return `page:${module.id}:${page.id}`; }
/** 用于会话焦点和变更建议显示的可读路径，关联始终使用稳定键。 */
export function prdLabels(prd, globals = globalSectionsFor(prd)) {
  const labels = Object.fromEntries(globals);
  for (const module of prd?.modules || []) {
    const path = modulePath(module); labels[path] = module.title;
    for (const [key, title] of sectionsFor(prd, 'module')) labels[`${path}:${key}`] = `${module.title} / ${title}`;
    for (const page of module.pages) {
      const pageKey = pagePath(module, page); labels[pageKey] = `${module.title} / ${page.title}`;
      for (const [key, title] of sectionsFor(prd, 'page')) labels[`${pageKey}:${key}`] = `${module.title} / ${page.title} / ${title}`;
    }
  }
  return labels;
}
/** 整体说明、模块页面、公共规则组成一个连续文档，历史也使用自身版本投影。 */
export function documentBlocks(prd, legacySections = legacyGlobalSections) {
  if (!prd) return [];
  // 问题可能指向尚未填写的可选规则，完整文档必须保留可定位的章节。
  const linkedPaths = new Set((prd.unresolvedIssues || []).flatMap(issue => Object.hasOwn(prd.issueLinks || {}, issue) && Array.isArray(prd.issueLinks[issue]) ? prd.issueLinks[issue] : []));
  const definition = prdTemplate(prd);
  const globals = prd.templateVersion !== 2 && definition.id === 'management' && definition.revision === 1 ? legacySections : globalSectionsFor(prd);
  const globalBlock = ([key, title]) => ({ key, title, level: 2, text: prd[key] || '' });
  const blocks = globals.slice(0, 4).map(globalBlock);
  if (prd.templateVersion === 2 || prd.modules?.length) blocks.push({ key: 'modules', title: '功能模块', level: 2 });
  for (const module of prd.modules || []) {
    const path = modulePath(module); blocks.push({ key: path, title: module.title, level: 3 });
    for (const [key, title] of sectionsFor(prd, 'module')) blocks.push({ key: `${path}:${key}`, title, level: 4, text: module.sections[key] || '' });
    for (const page of module.pages) {
      const pageKey = pagePath(module, page);
      blocks.push({ key: pageKey, title: `${page.title} · ${pageTypesFor(prd).find(([type]) => type === page.type)?.[1] || page.type}`, level: 4 });
      for (const [key, title] of sectionsFor(prd, 'page').filter(([key]) => requiredPageSections(page.type, prd).includes(key) || page.sections?.[key] || linkedPaths.has(`${pageKey}:${key}`))) blocks.push({ key: `${pageKey}:${key}`, title, level: 5, text: page.sections[key] || '' });
    }
  }
  blocks.push(...globals.slice(4).map(globalBlock));
  return blocks;
}
/** 切换只更新版本绑定；复制保留所有正文、页面类型、问题与关联，绝不调用模型重写。 */
export function switchPrdTemplate(prd, id, revision) {
  const next = JSON.parse(JSON.stringify(prd));
  next.templateId = id; next.templateRevision = revision;
  prdTemplate(next);
  return next;
}
/** 服务器明确拒绝已删除模板时只还原绑定，保留本地正文与问题；不用于结果未知的网络失败。 */
export function restoreTemplateBinding(prd, original) {
  const next = JSON.parse(JSON.stringify(prd));
  for (const key of ['templateId', 'templateRevision']) {
    if (Object.hasOwn(original, key)) next[key] = original[key];
    else delete next[key];
  }
  return next;
}
/** 切换前展示实际标题变化及已有页面的必填项变化，不生成业务正文。 */
export function templateImpact(prd, id, revision) {
  const next = switchPrdTemplate(prd, id, revision), before = prdLabels(prd), after = prdLabels(next);
  const renamed = Object.keys(before).filter(key => before[key] !== after[key]).map(key => ({ key, from: before[key], to: after[key] }));
  const required = document => {
    const template = prdTemplate(document);
    const paths = document.templateVersion !== 2 && template.id === 'management' && template.revision === 1
      ? legacyGlobalSections.map(([key]) => key) : [...template.requiredGlobalSections];
    for (const module of document.modules || []) {
      paths.push(...template.requiredModuleSections.map(key => `${modulePath(module)}:${key}`));
      for (const page of module.pages) paths.push(...requiredPageSections(page.type, document).map(key => `${pagePath(module, page)}:${key}`));
    }
    return paths;
  };
  const oldRequired = required(prd), newRequired = required(next);
  const oldTemplate = prdTemplate(prd), newTemplate = prdTemplate(next);
  const reordered = [['globalSections', '整体说明与全局约定'], ['moduleSections', '模块说明'], ['pageSections', '页面规则']]
    .filter(([key]) => oldTemplate[key].map(item => item.key).join(',') !== newTemplate[key].map(item => item.key).join(','))
    .map(([, title]) => title);
  return { renamed, reordered, added: newRequired.filter(key => !oldRequired.includes(key)).map(key => after[key]), removed: oldRequired.filter(key => !newRequired.includes(key)).map(key => before[key]) };
}
/** 撤销恢复正文及模板绑定，但不降级结构版本，避免模块丢失。 */
export function restorePrdDraft(previous, current) {
  const restored = JSON.parse(JSON.stringify(previous));
  if (current?.templateVersion === 2 && restored?.prd) {
    restored.prd.templateVersion = 2;
    restored.prd.modules ||= [];
  }
  return restored;
}
