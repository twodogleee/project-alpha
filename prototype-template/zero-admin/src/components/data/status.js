import { hasDisplayValue } from "./displayValue.js";

/**
 * 在状态字典中查找与原始值匹配的选项。
 *
 * @param {unknown} value 状态原始值
 * @param {Array<object>} options 状态字典
 * @returns {object|undefined} 匹配到的状态选项
 */
export const resolveStatusOption = (value, options = []) => {
  return options.find((option) => (
    Object.is(option?.value, value) ||
    String(option?.value) === String(value) ||
    option?.label === value
  ));
};

/**
 * 解析状态展示文案，未知编码保留原值。
 *
 * @param {unknown} value 状态原始值
 * @param {Array<object>} options 状态字典
 * @param {string} emptyText 空值占位
 * @returns {string|number|boolean} 状态展示值
 */
export const resolveStatusLabel = (value, options = [], emptyText = "-") => {
  if (!hasDisplayValue(value)) {
    return emptyText;
  }
  return resolveStatusOption(value, options)?.label ?? value;
};

/**
 * 解析 Element Plus 状态标签类型。
 *
 * @param {unknown} value 状态原始值
 * @param {Array<object>} options 状态字典
 * @param {string} fallbackType 未命中字典时的默认类型
 * @returns {string} 标签颜色类型
 */
export const resolveStatusType = (value, options = [], fallbackType = "info") => {
  const option = resolveStatusOption(value, options);
  if (option?.type) {
    return option.type;
  }
  if (["停用", "已停用", "禁用", "已禁用", "已过期", "失败"].includes(value)) {
    return "danger";
  }
  return fallbackType;
};
