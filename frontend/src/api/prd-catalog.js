/** 工作区响应的模板预加载边界，历史与当前稿复用全部已发布版本。@author Andersen */
import { getPrdTemplateCatalog } from './prd-templates.js';
import { hydratePrdTemplates } from '../workspace/prd-catalog.js';
/** 合并同一时刻多个工作区对缺失目录的请求；完成后释放，失败可重新请求。 */
let pendingCatalog = null;
/** 加载完整发布目录，不将草稿接口响应作为运行时模板。 */
function loadPublishedCatalog() {
  pendingCatalog ||= getPrdTemplateCatalog().finally(() => { pendingCatalog = null; });
  return pendingCatalog;
}
/** 在 API 返回给页面前完成目录装载，避免 computed 渲染未知版本导致空白。 */
export function prepareWorkspaceResponse(response) { return hydratePrdTemplates(response, loadPublishedCatalog); }
