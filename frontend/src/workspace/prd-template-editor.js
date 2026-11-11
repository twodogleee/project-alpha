/** 受限模板编辑的纯数据变换；保持标准键、分组边界和未编辑配置不变。@author Andersen */

/** 层级只用于编辑界面，整体前四项与全局约定后五项不允许互换。 */
export const templateEditorAreas = [
  { key: 'basic', title: '基本信息' }, { key: 'overview', title: '整体说明' },
  { key: 'common', title: '全局约定' }, { key: 'module', title: '模块' }, { key: 'page', title: '页面' },
];
/** 编辑副本与目录快照分离，任何未保存修改都不写入共享定义。 */
export function cloneTemplateDefinition(definition) { return JSON.parse(JSON.stringify(definition)); }
/** 获取当前层级的标准章节；顺序来自草稿而非固定标题。 */
export function templateEditorSections(definition, area) {
  if (area === 'overview') return definition.globalSections.slice(0, 4);
  if (area === 'common') return definition.globalSections.slice(4);
  return definition[`${area}Sections`] || [];
}
/** 读取当前层级或页面类型的必填集合，切换查看不修改其他类型。 */
export function templateRequiredKeys(definition, area, pageType) {
  if (area === 'page') return definition.pageTypes.find(type => type.key === pageType)?.requiredSections || [];
  return area === 'module' ? definition.requiredModuleSections : definition.requiredGlobalSections;
}
/** 服务端保证的基础完整性底线；界面说明这些要求不能通过模板取消。 */
export function templateBaseRequiredKeys(area) {
  if (area === 'page') return ['overview', 'states', 'acceptance'];
  if (area === 'module') return ['overview', 'acceptance'];
  return ['background', 'objectives', 'scope', 'workflows'];
}
/** 仅开放名称、说明和默认页面类型，不允许表单修改身份或模型指导。 */
export function updateTemplateMetadata(definition, field, value) {
  if (!['name', 'description', 'defaultPageType'].includes(field)) return definition;
  if (field === 'defaultPageType' && !definition.pageTypes.some(type => type.key === value)) return definition;
  return { ...definition, [field]: value };
}
/** 标题和提示均按纯文本保存，章节键和其他章节保持原样。 */
export function updateTemplateSection(definition, area, key, field, value) {
  if (!['title', 'hint'].includes(field) || !templateEditorSections(definition, area).some(section => section.key === key)) return definition;
  const next = cloneTemplateDefinition(definition);
  const sections = ['overview', 'common'].includes(area) ? next.globalSections : next[`${area}Sections`];
  sections.find(section => section.key === key)[field] = value;
  return next;
}
/** 一次移动一个位置；分组首尾和未知键不产生跨层级移动。 */
export function moveTemplateSection(definition, area, key, direction) {
  const visible = templateEditorSections(definition, area), index = visible.findIndex(section => section.key === key);
  if (![-1, 1].includes(direction) || index < 0 || index + direction < 0 || index + direction >= visible.length) return definition;
  const next = cloneTemplateDefinition(definition);
  const sections = ['overview', 'common'].includes(area) ? next.globalSections : next[`${area}Sections`];
  const fullIndex = sections.findIndex(section => section.key === key);
  [sections[fullIndex], sections[fullIndex + direction]] = [sections[fullIndex + direction], sections[fullIndex]];
  return next;
}
/** 勾选仅改变当前规则，保留基础完整性底线；服务端仍执行最终发布校验。 */
export function setTemplateRequired(definition, area, key, enabled, pageType) {
  if (!templateEditorSections(definition, area).some(section => section.key === key)) return definition;
  if (enabled !== true && templateBaseRequiredKeys(area).includes(key)) return definition;
  const next = cloneTemplateDefinition(definition);
  const required = new Set(templateRequiredKeys(next, area, pageType));
  if (enabled === true) required.add(key); else required.delete(key);
  if (area === 'page') {
    const type = next.pageTypes.find(type => type.key === pageType);
    if (!type) return definition;
    type.requiredSections = next.pageSections.filter(section => required.has(section.key)).map(section => section.key);
  } else {
    const field = area === 'module' ? 'requiredModuleSections' : 'requiredGlobalSections';
    const sections = area === 'module' ? next.moduleSections : next.globalSections;
    next[field] = sections.filter(section => required.has(section.key)).map(section => section.key);
  }
  return next;
}
/** 提前指出影响保存的文本缺失，结构及发布约束由后端统一检查。 */
export function templateEditorProblems(definition) {
  const problems = [];
  if (!definition?.name?.trim()) problems.push('请填写模板名称');
  for (const [kind, title] of [['global', '整体 / 全局'], ['module', '模块'], ['page', '页面']]) {
    const empty = (definition?.[`${kind}Sections`] || []).filter(section => !section.title?.trim());
    if (empty.length) problems.push(`${title}有 ${empty.length} 个章节尚未填写名称`);
  }
  return problems;
}
