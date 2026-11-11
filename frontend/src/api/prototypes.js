/** 原型业务及预览会话接口；公开响应不暴露源码和存储路径。@author Andersen */
import { request } from './http.js';
import { prepareWorkspaceResponse } from './prd-catalog.js';
import { projectProfile } from '@/project/profile.js';
import { prototypeExportResponse } from '@/workspace/prototype-download.js';

/** 版本所属原型统一入口，稳定标识始终编码。 */
const root = versionId => `/workspace/versions/${encodeURIComponent(versionId)}/prototype`;
/** 冻结来源中的 PRD 同样需要加载其发布模板。 */
const call = async config => {
  const response = await request(config);
  // 原型使用 source/current 两层冻结容器，显式投影以复用 PRD 精确模板加载。
  const revisions = [response, response?.current, ...(response?.items || [])].filter(value => value?.source?.prd);
  await prepareWorkspaceResponse({ items: revisions.map(value => ({ prd: value.source.prd })) });
  return response;
};
/** 固定原型模板公开能力目录。 */
export const loadPrototypeTemplates = signal => call({ method: 'GET', url: '/workspace/prototype-templates', signal });
/** 读取当前修订及可接管任务，不初始化业务对象。 */
export const loadPrototype = (versionId, signal) => call({ method: 'GET', url: root(versionId), signal });
/** 显式创建带完整幂等命令的后台任务。 */
export const submitPrototype = (versionId, data) => call({ method: 'POST', url: `${root(versionId)}/runs`, data });
/** 历史修订按服务端固定五十条分页。 */
export const listPrototypeRevisions = (versionId, page = 1, signal) => call({ method: 'GET', url: `${root(versionId)}/revisions`, params: { page }, signal });
/** 只读加载指定修订，当前指针不会改变。 */
export const loadPrototypeRevision = (versionId, revisionId, signal) => call({ method: 'GET', url: `${root(versionId)}/revisions/${encodeURIComponent(revisionId)}`, signal });
/** 经用户核对后按四种修订锁采纳候选。 */
export const applyPrototypeRevision = (versionId, data) => call({ method: 'POST', url: `${root(versionId)}/apply`, data });
/** 恢复只追加新修订，沿用历史来源，不继承旧人工确认。 */
export const restorePrototypeRevision = (versionId, data) => call({ method: 'POST', url: `${root(versionId)}/restore`, data });
/** 人工确认固定当前修订、有效 PRD 定稿与完整范围。 */
export const confirmPrototypeRevision = (versionId, data) => call({ method: 'POST', url: `${root(versionId)}/confirm`, data });
/** 确认历史独立分页，旧记录不能当作当前有效状态。 */
export const listPrototypeConfirmations = (versionId, page = 1, signal) => call({ method: 'GET', url: `${root(versionId)}/confirmations`, params: { page }, signal });
/** 二进制导出只使用指定修订；响应限额、错误解包及安全文件名在统一下载边界处理。 */
export async function exportPrototypeRevision(versionId, revisionId, format, signal) {
  if (!['source', 'static'].includes(format)) throw new Error('请选择源码包或静态包');
  let response;
  try { response = await fetch(`${projectProfile.apiPrefix}${root(versionId)}/revisions/${encodeURIComponent(revisionId)}/export?format=${format}`, { method: 'GET', credentials: 'same-origin', headers: { Accept: 'application/zip, application/json' }, signal }); }
  catch (cause) { if (signal?.aborted) throw cause; throw new Error('导出请求失败，请检查服务后重试'); }
  const fallback = `prototype-${format}-${String(revisionId).replace(/[^A-Za-z0-9_-]/g, '').slice(0, 36) || 'revision'}.zip`;
  return prototypeExportResponse(response, fallback);
}
/** 为已验证修订申请隔离、短时的浏览器预览。 */
export const createPrototypePreview = (versionId, revisionId, parentOrigin) => call({ method: 'POST', url: `${root(versionId)}/revisions/${encodeURIComponent(revisionId)}/preview`, data: { parentOrigin } });
/** 主视图退出后撤销其预览会话。 */
export const revokePrototypePreview = (versionId, sessionId) => call({ method: 'DELETE', url: `${root(versionId)}/preview/${encodeURIComponent(sessionId)}` });
