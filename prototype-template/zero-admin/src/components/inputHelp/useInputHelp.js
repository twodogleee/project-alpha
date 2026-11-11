/** 在公共组件中获取应用配置，避免反向引用下游项目文件。@author Andersen */
import { computed, inject } from 'vue';
import { INPUT_HELP_KEY, INPUT_HELP_DEFAULTS } from './config.js';

/** 未安装插件时只提供公共组件默认展示，不启用历史 DOM 兜底。 */
export function useInputHelp() {
  return inject(INPUT_HELP_KEY, null) || { options: computed(() => INPUT_HELP_DEFAULTS), registry: null };
}
