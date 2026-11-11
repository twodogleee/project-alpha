/** 模板管理目录投影；删除仅影响可选状态，不修改发布定义或草稿。@author Andersen */

/** 合并公开目录与管理记录的删除状态，任一已知删除标记都阻止继续操作。 */
export function deletedTemplateIds(catalog, managed = []) {
  return new Set([...(catalog?.deletedTemplateIds || []), ...managed.filter(record => record.deleted === true).map(record => record.id)]);
}
/** 发布历史与草稿先合并，再统一按模板身份过滤，保留从未发布的已删草稿。 */
export function templateManagementCards(catalog, managed = [], deleted = false) {
  const latest = new Map();
  for (const template of catalog?.templates || []) {
    if (!latest.has(template.id) || latest.get(template.id).revision < template.revision) latest.set(template.id, template);
  }
  for (const record of managed) if (record.draft) latest.set(record.id, record.draft);
  const removed = deletedTemplateIds(catalog, managed);
  return [...latest.values()].filter(template => removed.has(template.id) === deleted);
}
/** 删除命令成功后的本页状态立即更新；历史定义保留，运行期目录仍由 GET 响应注册。 */
export function updateTemplateDeletion(catalog, record) {
  if (!catalog) return catalog;
  const removed = new Set(catalog.deletedTemplateIds || []);
  if (record.deleted) removed.add(record.id); else removed.delete(record.id);
  return { ...catalog, deletedTemplateIds: [...removed] };
}
