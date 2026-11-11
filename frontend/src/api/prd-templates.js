/** PRD 已发布目录与受限模板管理 API；草稿不进入业务文档可选目录。@author Andersen */
import { request } from './http.js';

/** 读取已发布模板及旧文档兼容章节，响应由统一请求层解包。 */
export function getPrdTemplateCatalog(signal) {
  return request({ method: 'GET', url: '/config/prd-templates', signal });
}
/** 读取自定义模板的管理锁、最新发布版及可空草稿。 */
export function getPrdTemplateManagement(signal) { return request({ method: 'GET', url: '/config/prd-templates/management', signal }); }
/** 从精确已发布版本复制草稿，requestId 在不确定结果重试时沿用。 */
export function copyPrdTemplate(data, signal) { return request({ method: 'POST', url: '/config/prd-templates/copies', data, signal }); }
/** 继续已有草稿或从最新发布版创建下一版，expectedRevision 为管理锁。 */
export function openPrdTemplateDraft(id, data, signal) { return request({ method: 'POST', url: `/config/prd-templates/${encodeURIComponent(id)}/draft`, data, signal }); }
/** 保存受限可编辑配置；不能改写模板身份、发布修订或模型指导。 */
export function savePrdTemplateDraft(id, data, signal) { return request({ method: 'PUT', url: `/config/prd-templates/${encodeURIComponent(id)}/draft`, data, signal }); }
/** 发布已保存草稿为不可变版本，requestId 支持不确定响应的幂等重试。 */
export function publishPrdTemplate(id, data, signal) { return request({ method: 'POST', url: `/config/prd-templates/${encodeURIComponent(id)}/publish`, data, signal }); }
/** 删除或恢复自定义模板身份；保留草稿与所有发布版本，expectedRevision 防止并发覆盖。 */
export function setPrdTemplateDeletion(id, data, signal) { return request({ method: 'PUT', url: `/config/prd-templates/${encodeURIComponent(id)}/deletion`, data, signal }); }
