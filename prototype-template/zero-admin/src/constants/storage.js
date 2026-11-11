/** 原型偏好只保存在 Pinia 内存，键仅兼容 Zero Store 契约。@author Andersen */
export const APP_STORAGE_KEYS = Object.freeze({
  /** 查询面板内存偏好身份；入口不安装持久化插件。 */
  queryPanelPrefs: 'alpha-prototype:query-panel',
  /** 表格列内存偏好身份；不访问浏览器存储。 */
  tableColumnPrefs: 'alpha-prototype:table-columns',
});
