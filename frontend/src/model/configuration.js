/** 系统设置目录编辑与白名单提交，不定义平台或模型预设。@author Andersen */

/** 已实现的客户端协议；平台名称、地址和模型名单来自数据库。 */
export const modelProtocols = [{ value: 'dashscope', label: '百炼原生协议' }, { value: 'openai-compatible', label: 'OpenAI 兼容协议' }];
/** 平台编辑副本不包含模型或凭证。 */
export function createPlatformEditor(platform) { return { name: platform?.name || '', protocol: platform?.protocol || 'openai-compatible', baseUrl: platform?.baseUrl || '', modelCatalogUrl: platform?.modelCatalogUrl || '' }; }
/** 模型编辑只取公开字段，已有密钥不会进入副本。 */
export function createModelEditor(model, platformId = '') { return { platformId: model?.platformId || platformId, name: model?.name || '', model: model?.model || '', apiKey: '', clearApiKey: false }; }
/** 切换模型所属平台时清除暂存密钥，要求用户选择凭证策略。 */
export function changeModelPlatform(editor, platformId) { editor.platformId = platformId; editor.apiKey = ''; editor.clearApiKey = false; }
/** 平台提交仅包含公开元数据与目录版本。 */
export function platformPayload(editor, expectedRevision) { return { expectedRevision, name: editor.name.trim(), protocol: editor.protocol, baseUrl: editor.baseUrl.trim(), modelCatalogUrl: editor.modelCatalogUrl.trim() }; }
/** 获取目录不依赖模型名称或标识，只提交当前模型凭证策略，不借用其他模型的密钥。 */
export function catalogFetchPayload(editor, expectedRevision, modelId) { return { expectedRevision, modelId: modelId || null, apiKey: editor.apiKey.trim(), clearApiKey: editor.clearApiKey }; }
/** 模型提交不包含 id、密文或服务端状态。 */
export function modelConfigurationPayload(editor, expectedRevision) { return { expectedRevision, platformId: editor.platformId, name: editor.name.trim(), model: editor.model.trim(), apiKey: editor.apiKey.trim(), clearApiKey: editor.clearApiKey }; }
/** 平台基础表单校验，完整 URL 校验由后端负责。 */
export function validPlatformEditor(editor) { return Boolean(editor?.name.trim() && editor.name.length <= 120 && modelProtocols.some(p => p.value === editor.protocol) && editor.baseUrl.trim() && editor.baseUrl.length <= 500 && validCatalogUrl(editor)); }
/** 可选目录 URL 必须是平台同源的独立路径；完整安全边界仍由后端执行。 */
function validCatalogUrl(editor) {
  if (!editor.modelCatalogUrl.trim()) return true;
  try {
    const url = new URL(editor.modelCatalogUrl.trim()); const base = new URL(editor.baseUrl.trim());
    return editor.modelCatalogUrl.length <= 500 && url.origin === base.origin && ['https:', 'http:'].includes(url.protocol)
      && !url.username && !url.password && !url.search && !url.hash && url.pathname !== '/';
  } catch { return false; }
}
/** 模型允许不填密钥先保存，跨平台凭证规则与启用校验由后端最终执行。 */
export function validModelEditor(editor, platforms, previous) {
  if (!editor || !platforms.some(p => p.id === editor.platformId) || !editor.name.trim() || editor.name.length > 120
      || !/^[A-Za-z0-9][A-Za-z0-9._:/-]{0,119}$/.test(editor.model.trim()) || editor.apiKey.length > 4096 || editor.clearApiKey && editor.apiKey.trim()) return false;
  return !(previous?.apiKeyConfigured && previous.platformId !== editor.platformId && !editor.apiKey.trim() && !editor.clearApiKey);
}
