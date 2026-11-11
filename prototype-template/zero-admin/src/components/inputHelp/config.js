/** 输入帮助的项目级配置及字段优先级；不持有业务数据。@author Andersen */

/** Vue 应用级注入键，隔离多个应用的配置和适配器状态。 */
export const INPUT_HELP_KEY = Symbol('input-help');

/** 未安装兼容层的应用保持原布局，公共字段仍可显式使用帮助。 */
export const INPUT_HELP_DEFAULTS = Object.freeze({
  /** 历史页面扫描是项目显式选择的兼容能力。 */
  legacyEnabled: false,
  /** 原有 CRUD 说明保持固定展示。 */
  crudDisplay: 'inline',
  /** 查询说明默认采用紧凑帮助入口。 */
  queryDisplay: 'icon',
  /** 下游可排除自定义编辑器等特殊区域。 */
  excludeSelector: '',
});

/** 合并项目默认值；字段配置在 resolveHelpDisplay 中覆盖项目值。 */
export function resolveInputHelpOptions(options = {}) {
  return { ...INPUT_HELP_DEFAULTS, ...options };
}

/** 解析展示方式，none 是显式关闭，不能被项目默认值覆盖。 */
export function resolveHelpDisplay(field = {}, context = 'query', options = INPUT_HELP_DEFAULTS) {
  const display = field.helpDisplay ?? options[`${context}Display`];
  return ['icon', 'control', 'inline', 'tooltip', 'none'].includes(display) ? display : INPUT_HELP_DEFAULTS[`${context}Display`];
}
