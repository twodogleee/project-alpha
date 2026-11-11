/** 原型范围、命令和跨源消息的纯投影；不读取浏览器存储。@author Andersen */

/** 模板能力缺失时拒绝生成，默认标签仅用于已有产物展示。 */
export const prototypePageTypes = { LIST: '列表页', FORM: '表单页', DETAIL: '详情页' };
/** 任务阶段对应实际 Worker 状态，不伪造进度百分比。 */
export const prototypePhases = { PREPARING: '准备来源', PLANNING: '规划页面', GENERATING: '生成页面', BUILDING: '构建原型', VERIFYING: '验证交互', PERSISTING: '保存修订', FINISHED: '已结束' };
/** 来源新鲜度与质量校验互不替代。 */
export const prototypeFreshness = { CURRENT: '来源一致', STALE: 'PRD 已更新', REVIEW_REQUIRED: '需重新核对来源' };
/** 持久化任务的终态不会触发自动重新生成。 */
export const isPrototypePending = run => ['QUEUED', 'RUNNING'].includes(run?.status);
/** 模块与页面组成唯一键，避免不同模块复用页面 ID 时串页。 */
export const prototypePageKey = page => JSON.stringify([page.moduleId, page.pageId]);
/** 每个候选页面同时保留来源身份、名称与模板支持状态。 */
export function prototypePages(prd, template) {
  const supported = template?.supportedPageTypes || [];
  return (prd?.modules || []).flatMap(module => (module.pages || []).map(page => ({ moduleId: module.id, pageId: page.id, moduleTitle: module.title, title: page.title, type: page.type, supported: supported.includes(page.type) })));
}
/** 有效定稿优先；重新开放后的旧基线不作为新生成来源。 */
export function prototypeSource(detail) {
  const lifecycle = detail?.lifecycle;
  const baseline = lifecycle?.prdCurrent && lifecycle?.prdBaseline?.sourceRevision === lifecycle?.prdRevision && lifecycle?.prdBaseline;
  return baseline ? { kind: 'BASELINE', id: baseline.id, prd: baseline.prd, revision: baseline.sourceRevision } : { kind: 'DRAFT', id: null, prd: lifecycle?.prd, revision: lifecycle?.prdRevision };
}
/** ALL 不静默排除不支持页面；用户必须明确改为 SELECTED。 */
export function prototypeScopeProblem(pages, mode, selected, maxPages = 10) {
  const chosen = mode === 'ALL' ? pages : pages.filter(page => selected.includes(prototypePageKey(page)));
  if (!chosen.length) return '请在 PRD 中添加页面，或至少选择一个页面。';
  if (chosen.length > maxPages) return `本阶段一次最多生成 ${maxPages} 个页面，请缩小范围。`;
  if (chosen.some(page => !page.supported)) return '范围包含暂不支持的页面类型，请选择支持的页面；这些能力缺口会继续保留在 PRD 中。';
  if (mode === 'SELECTED' && chosen.length !== selected.length) return '所选页面已发生变化，请重新选择生成范围。';
  return '';
}
/** 四种乐观锁只从刷新后的服务端详情取得。 */
export function prototypeLocks(detail, prototype) {
  return { expectedVersionRevision: detail.workspace.version.revision, expectedLifecycleRevision: detail.lifecycle.revision, expectedPrdRevision: detail.lifecycle.prdRevision, expectedPrototypeRevision: prototype?.revision || 0 };
}
/** 初次/同步采用新来源，编辑始终沿用当前修订冻结来源与范围。 */
export function prototypeCommand({ detail, prototype, template, operation, scope, target, content, requestId, retryOfRunId = null }) {
  const current = prototype?.current, source = prototypeSource(detail);
  if (operation === 'EDIT' && !current) throw new Error('当前还没有可编辑的原型修订');
  return { sessionId: detail.workspace.session.id, requestId, operation, ...prototypeLocks(detail, prototype), baseRevisionId: current?.id || null,
    templateId: template.id, templateRevision: template.revision,
    source: operation === 'EDIT' ? { kind: 'EXISTING', id: current.source.id } : { kind: source.kind, id: source.id },
    scope: operation === 'EDIT' ? current.source.scope : scope,
    target: operation === 'EDIT' ? target : { kind: 'PROTOTYPE', page: null, overlayId: null }, content: content.trim(), retryOfRunId };
}
/** 人工动作固定原始目标与四锁；生成完成、下载和恢复均不代替确认。 */
export function prototypeDeliveryCommand({ kind, detail, prototype, revision, requestId }) {
  if (!revision?.id) throw new Error('请先选择一个可用修订');
  const locks = prototypeLocks(detail, prototype);
  if (kind === 'RESTORE') return { requestId, targetRevisionId: revision.id, ...locks };
  if (kind !== 'CONFIRM') throw new Error('不支持的原型交付动作');
  if (revision.id !== prototype?.current?.id) throw new Error('只能确认当前修订，请返回当前原型');
  const source = prototypeSource(detail);
  if (revision.source?.kind !== 'BASELINE' || source.kind !== 'BASELINE' || source.id !== revision.source.prdBaselineId || revision.freshness !== 'CURRENT') throw new Error('请先将原型同步到当前有效 PRD 定稿');
  if (prototype.confirmationBlockers?.length) throw new Error(prototype.confirmationBlockers.join('；'));
  return { requestId, revisionId: revision.id, prdBaselineId: source.id, scope: JSON.parse(JSON.stringify(revision.source.scope)), ...locks };
}
/** 来源中保存的确切范围用于确认和恢复说明，名称改变不改变复合身份。 */
export function prototypeScopeLabels(revision) {
  return (revision?.source?.scope?.pageRefs || []).map(ref => {
    const module = revision.source.prd?.modules?.find(item => item.id === ref.moduleId);
    const page = module?.pages?.find(item => item.id === ref.pageId);
    return { key: prototypePageKey(ref), title: `${module?.title || ref.moduleId} / ${page?.title || ref.pageId}` };
  });
}
/** 历史确认事实保留；只有服务端仍判定当前有效的确认才显示为已确认。 */
export function prototypeConfirmationLabel(prototype, revision) {
  if (prototype?.status === 'CONFIRMED' && prototype.current?.id === revision?.id && prototype.latestConfirmation?.revisionId === revision.id) return '原型已确认，开发与验收未接入';
  if (revision?.latestConfirmation) return '曾有人工确认，当前需重新核对';
  return '原型草稿 · 未人工确认';
}
/** 实际模型、合成联调、静态模板须显式区分。 */
export function prototypeModeLabel(mode) {
  return ({ live: '模型生成', synthetic: '合成联调 · 非模型结果', demo: '模板联调 · 非模型结果' })[mode] || '来源模式未标注';
}
/** 可读时间使用浏览器本地时区，不把非法日期当作有效来源。 */
export function prototypeTime(value) { return value && Number.isFinite(Date.parse(value)) ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'; }
/** 外层只承载平台包装页，严格匹配预览源；包装页再验证 opaque 生成子页。 */
export function acceptPrototypeMessage(event, frameWindow, session) {
  const value = event?.data;
  let origin;
  try { const url = new URL(session?.frameUrl); if (!['http:', 'https:'].includes(url.protocol)) return null; origin = url.origin; } catch { return null; }
  if (!frameWindow || !session || event.source !== frameWindow || event.origin !== origin || !value || typeof value !== 'object' || Array.isArray(value)) return null;
  if (Object.keys(value).some(key => !['type', 'sessionId', 'revisionId', 'path', 'message'].includes(key))) return null;
  if (!['READY', 'NAVIGATED', 'SELECT_PAGE', 'ERROR'].includes(value.type) || value.sessionId !== session.sessionId || value.revisionId !== session.revisionId) return null;
  if (value.path !== undefined && (typeof value.path !== 'string' || value.path.length > 220 || !/^\/[A-Za-z0-9_./-]*$/.test(value.path) || value.path.includes('..') || value.path.startsWith('//'))) return null;
  if (value.message !== undefined && (typeof value.message !== 'string' || value.message.length > 300)) return null;
  if (value.type === 'ERROR' ? typeof value.message !== 'string' || value.path !== undefined : typeof value.path !== 'string' || value.message !== undefined) return null;
  return value;
}
/** 路由模板仅按完整路径段匹配动态参数，绝不把模型路径编译成正则。 */
export function prototypePageAtPath(pages, path) {
  if (!path) return null;
  const parts = path.split(/[?#]/, 1)[0].split('/');
  // 静态路径优先于动态参数，与实际路由匹配一致，避免 /items/new 被误认为详情页。
  const matches = (pages || []).map(page => {
    const pattern = (page.path || '').split('/');
    const matched = pattern.length === parts.length && pattern.every((segment, index) => segment.startsWith(':') ? !!parts[index] : segment === parts[index]);
    return { page, matched, specificity: pattern.filter(segment => !segment.startsWith(':')).length };
  }).filter(value => value.matched).sort((left, right) => right.specificity - left.specificity);
  return matches[0]?.page || null;
}
/** 网络未知结果可重放原命令；明确业务拒绝必须刷新锁后重新提交。 */
export const isPrototypeUncertain = error => !error?.code || ['ERR_NETWORK', 'ECONNABORTED', 'ETIMEDOUT', 'ECONNRESET', 'ERR_BAD_RESPONSE', 'INTERNAL_ERROR', 'STORAGE_UNAVAILABLE'].includes(error.code);
