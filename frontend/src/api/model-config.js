/** 平台、模型目录与启用选择 API；不缓存或回显密钥。@author Andersen */
import { request } from './http.js';

/** 读取数据库公开目录和统一版本。 */
export function getModelConfiguration(signal) { return request({ method: 'GET', url: '/model-config', signal }); }
/** 新增或编辑平台，只发送平台元数据。 */
export function saveModelPlatform(id, data, signal) { return request({ method: id ? 'PUT' : 'POST', url: `/model-config/platforms${id ? `/${encodeURIComponent(id)}` : ''}`, data, signal }); }
/** 删除无引用平台，必须提供目录版本。 */
export function deleteModelPlatform(id, expectedRevision, signal) { return request({ method: 'DELETE', url: `/model-config/platforms/${encodeURIComponent(id)}`, params: { expectedRevision }, signal }); }
/** 新增或编辑模型，密钥单向提交。 */
export function saveModelProfile(id, data, signal) { return request({ method: id ? 'PUT' : 'POST', url: `/model-config/models${id ? `/${encodeURIComponent(id)}` : ''}`, data, signal }); }
/** 删除未启用模型，必须提供目录版本。 */
export function deleteModelProfile(id, expectedRevision, signal) { return request({ method: 'DELETE', url: `/model-config/models/${encodeURIComponent(id)}`, params: { expectedRevision }, signal }); }
/** 单独保存当前模式和模型，不隐式测试网络。 */
export function selectModel(data, signal) { return request({ method: 'PUT', url: '/model-config/runtime', data, signal }); }
/** 测试当前模型编辑副本，不保存、不创建项目。 */
export function testModelConfiguration(id, data, signal) { return request({ method: 'POST', url: '/model-config/test', params: id ? { modelId: id } : {}, data, signal }); }
/** 后端显式 GET 平台目录；密钥只在请求体中单向提交，返回列表不自动保存。 */
export function fetchPlatformModels(platformId, data, signal) { return request({ method: 'POST', url: `/model-config/platforms/${encodeURIComponent(platformId)}/catalog`, data, signal }); }
