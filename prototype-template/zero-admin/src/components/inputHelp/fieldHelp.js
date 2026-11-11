/** 输入说明的展示适配；不解析业务限制、不读取或改写输入值。@author Andersen */

/** 保留旧 placeholder 的优先级，显式空字符串表示不显示提示。 */
export function getFieldPlaceholder(field) {
  if (field.placeholder !== undefined) return field.placeholder;
  if (field.searchPlaceholder !== undefined) return field.searchPlaceholder;
  return ['input', 'textarea'].includes(field.type) ? `请输入${field.label}` : `请选择${field.label}`;
}

/**
 * 获取完整说明；空字符串、null 和 false 显式关闭帮助。
 * batch 仅供下游已存在的批量编辑模式使用，普通 textarea 不推导逐行规则。
 */
export function getFieldHelp(field, { batch = false } = {}) {
  if (field.help !== undefined) return typeof field.help === 'string' ? field.help : '';
  return batch ? field.placeholder || field.searchPlaceholder || `每行一个${field.label}` : '';
}

/** 简短提示独立配置，普通多行输入仍保留原 placeholder。 */
export function getCompactPlaceholder(field, { batch = false } = {}) {
  if (field.compactPlaceholder !== undefined) return field.compactPlaceholder;
  return batch ? '输入或粘贴' : getFieldPlaceholder(field);
}

/** 显式帮助、关闭配置及批量模式拥有说明展示权，禁止全局重复兜底。 */
export function isHelpManaged(field, { batch = false } = {}) {
  return field.help !== undefined || field.helpDisplay === 'none' || batch;
}

/** 追加或移除本组件的说明，保留校验器及业务已有无障碍标识。 */
export function updateDescription(element, id, enabled) {
  const ids = new Set((element.getAttribute('aria-describedby') || '').split(/\s+/).filter(Boolean));
  if (enabled) ids.add(id);
  else ids.delete(id);
  const description = [...ids].join(' ');
  if (description && description !== element.getAttribute('aria-describedby')) element.setAttribute('aria-describedby', description);
  else if (!description && element.getAttribute('aria-describedby') !== null) element.removeAttribute('aria-describedby');
}
