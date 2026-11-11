/**
 * Agent API 适配层，统一处理响应结构和网络错误，不操作页面状态。
 * @author Andersen
 */
import { request } from './http.js';

/** 获取当前模式、模型和工作流阶段，不返回密钥。 */
export function getRuntimeInfo(signal) {
  return request({ method: 'GET', url: '/runtime', signal });
}

/** 提交原始需求，返回需求清单和 PRD 草稿；不自动保存历史。 */
export function analyzeRequirement(content, signal) {
  return request({ method: 'POST', url: '/agent/drafts', data: { content }, signal });
}
