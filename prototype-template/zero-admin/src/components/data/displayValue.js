/**
 * 判断值是否应作为有效业务值展示。
 *
 * 数字 0 和布尔 false 都是有效值，只有 null、undefined 和空字符串使用空值占位。
 *
 * @param {unknown} value 待判断的值
 * @returns {boolean} 是否存在可展示值
 */
export const hasDisplayValue = (value) => {
  return value !== null && value !== undefined && value !== "";
};

/**
 * 将任意简单值格式化为界面文本。
 *
 * @param {unknown} value 原始值
 * @param {string} emptyText 空值占位文案
 * @returns {string} 规范化展示文本
 */
export const formatDisplayValue = (value, emptyText = "-") => {
  if (!hasDisplayValue(value)) {
    return emptyText;
  }
  if (typeof value === "boolean") {
    return value ? "是" : "否";
  }
  return String(value);
};
