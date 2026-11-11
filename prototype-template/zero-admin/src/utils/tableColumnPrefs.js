/** 规范化表格字段设置的页面键。 */
export const normalizeTableColumnPageKey = (pageKey) => {
  return String(pageKey || "").split("?")[0] || "default";
};

/**
 * 计算当前表格最终可见字段，并保证必选字段和最少一个字段可见。
 *
 * @param {Array<string>} visibleKeys 用户选择的可见字段
 * @param {Array<string>} defaultVisibleKeys 默认可见字段
 * @param {Array<string>} availableKeys 当前实际存在的字段
 * @param {Array<string>} requiredKeys 不允许隐藏的字段
 * @returns {Array<string>} 按当前字段顺序排列的可见字段
 */
export const normalizeVisibleColumnKeys = (
  visibleKeys = [],
  defaultVisibleKeys = [],
  availableKeys = [],
  requiredKeys = []
) => {
  const normalizedAvailableKeys = [...new Set(availableKeys.map(String))];
  const availableSet = new Set(normalizedAvailableKeys);
  const selectedSet = new Set(
    visibleKeys.map(String).filter((key) => availableSet.has(key))
  );
  requiredKeys
    .map(String)
    .filter((key) => availableSet.has(key))
    .forEach((key) => selectedSet.add(key));

  if (!selectedSet.size) {
    defaultVisibleKeys
      .map(String)
      .filter((key) => availableSet.has(key))
      .forEach((key) => selectedSet.add(key));
  }
  if (!selectedSet.size && normalizedAvailableKeys.length) {
    selectedSet.add(normalizedAvailableKeys[0]);
  }
  return normalizedAvailableKeys.filter((key) => selectedSet.has(key));
};
