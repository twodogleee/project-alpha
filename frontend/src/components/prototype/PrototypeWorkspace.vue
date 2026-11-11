<script setup>
/** PRD 原型工作区：生成、预览、修订恢复和交付确认；主会话由父级维护。@author Andersen */
import { computed, ref, watch, nextTick, onMounted, onBeforeUnmount } from 'vue';
import { ExternalLink, FileText, History, Loader2, RefreshCw, Sparkles, Monitor, ArrowLeft, Package, Download, RotateCcw, BadgeCheck } from '@lucide/vue';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog';
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription } from '@/components/ui/sheet';
import PrototypeRevisionInfo from './PrototypeRevisionInfo.vue';
import * as api from '@/api/prototypes.js';
import { createPrototypePoller } from '@/workspace/prototype-poll.js';
import { prototypePages, prototypeSource, prototypeScopeProblem, prototypeCommand, prototypeLocks, prototypePageKey, prototypePageTypes, prototypePhases, prototypeFreshness, prototypeModeLabel, prototypeTime, isPrototypePending, isPrototypeUncertain, acceptPrototypeMessage, prototypePageAtPath, prototypeDeliveryCommand, prototypeScopeLabels, prototypeConfirmationLabel } from '@/workspace/prototype.js';

/** prepare 必须先完成 PRD 保存再返回新锁；readonly 禁止业务写入但允许查看历史。 */
const props = defineProps({ detail: { type: Object, required: true }, readonly: Boolean, busy: Boolean, prepare: { type: Function, required: true } });
/** task-submitted 携带任务和已接受命令，支持未知提交重放后清空匹配输入；changed 刷新业务锁和会话。 */
const emit = defineEmits(['task-submitted', 'changed', 'target-change', 'error', 'state-change']);
/** 当前业务状态与只读历史选择独立，避免回看历史更改当前指针。 */
const prototype = ref(null), historical = ref(null), templates = ref([]), loading = ref(true), submitting = ref(false), error = ref('');
/** 各辅助视图独立开闭，生成参数在失败后保留。 */
const showGenerate = ref(false), showSource = ref(false), showHistory = ref(false), operation = ref('GENERATE'), scopeMode = ref('ALL'), selectedPages = ref([]), instruction = ref('请根据所选 PRD 页面生成可交互的管理端原型。');
/** 任务结果不明时保留整个命令，只允许原样重放以满足幂等契约。 */
const uncertain = ref(null), lastCommand = ref(null);
/** 预览会话、可见路由目标与历史分页具有独立异步代次。 */
const preview = ref(null), frame = ref(null), previewLoading = ref(false), previewError = ref(''), previewReady = ref(false), target = ref({ kind: 'PROTOTYPE', page: null, overlayId: null });
/** 历史一次读取固定页长，不以本地缓存推断更多记录。 */
const history = ref([]), historyPage = ref(0), historyMore = ref(false), historyLoading = ref(false);
/** 交付核对固定打开时的修订，不把后续后台变化当成人工已经核对的内容。 */
const showDelivery = ref(false), showControl = ref(false), controlKind = ref('CONFIRM'), reviewedRevision = ref(null), confirmAcknowledged = ref(false);
/** 交付抽屉卸载后才能打开人工核对，避免两个模态的遮罩与焦点竞争。 */
const controlOpening = ref(false);
/** 恢复会移动当前指针，因此核对时同时固定当前修订，后台变化后要求重新核对。 */
const reviewedCurrentId = ref(null);
/** 人工确认记录与源码修订分开分页，避免将旧记录当作当前确认状态。 */
const historyKind = ref('REVISIONS'), confirmations = ref([]), confirmationPage = ref(0), confirmationMore = ref(false), confirmationLoading = ref(false);
/** 导出只读取固定修订，离开后取消请求，不在浏览器持久化文件内容。 */
const downloading = ref(''), downloadNotice = ref('');
/** 离开/切换后迟到响应不能覆盖当前版本，也不能留下预览会话。 */
let disposed = false, contextSequence = 0, previewSequence = 0, historySequence = 0, confirmationSequence = 0, expiryTimer, readyTimer, exportController;
/** 当前稳定版本身份不由选中的历史修订改变。 */
const versionId = computed(() => props.detail.workspace.version.id);
/** 已存在原型固定模板版本；首次生成使用唯一可用模板。 */
const template = computed(() => templates.value.find(item => item.id === prototype.value?.templateId && item.revision === prototype.value?.templateRevision) || templates.value[0]);
/** 来源选择遵循有效定稿优先规则。 */
const source = computed(() => prototypeSource(props.detail));
/** 待生成页面保留不支持项，要求用户明确选择范围。 */
const sourcePages = computed(() => prototypePages(source.value.prd, template.value));
/** 当前修订优先于空态，历史视图始终只读。 */
const displayed = computed(() => historical.value || prototype.value?.current);
/** 服务端按时间排序，刷新可直接接管未完成任务。 */
const pending = computed(() => prototype.value?.runs?.find(isPrototypePending));
/** 最新失败与冲突分别解释，不把失败误报为可用修订。 */
const latest = computed(() => prototype.value?.runs?.[0]);
/** 只有成功但未应用的候选允许显式核对。 */
const candidate = computed(() => prototype.value?.runs?.find(run => run.applicationStatus === 'CONFLICT' && run.resultRevisionId));
/** 页面讨论目标限定为当前产物清单中的稳定身份。 */
const planPages = computed(() => displayed.value?.manifest?.plan?.pages || []);
/** 历史、后台任务与未知命令均会阻止进一步编辑。 */
const canEdit = computed(() => !!prototype.value?.current && !historical.value && !props.readonly && !props.busy && !pending.value && !submitting.value && !loading.value && !uncertain.value && !!template.value?.available);
/** 页面名用于主会话定位，后端身份始终使用模块和页面的复合引用。 */
const targetLabel = computed(() => target.value.kind === 'PAGE' ? `原型 / ${planPages.value.find(page => prototypePageKey(page) === prototypePageKey(target.value.page))?.title || '当前页面'}` : '整个原型');
/** 缺失能力或非法范围不能创建模型任务。 */
const scopeProblem = computed(() => !template.value?.available ? '原型生成服务当前不可用，请检查运行配置后重试。' : prototypeScopeProblem(sourcePages.value, scopeMode.value, selectedPages.value, template.value?.maxPages || 10));
/** 主操作互斥不影响浏览已有预览。 */
const writeBlocked = computed(() => props.readonly || props.busy || submitting.value || !!pending.value || loading.value || !!uncertain.value);
/** 显示模式来自持久化证据，模板联调不能冒充模型生成。 */
const modeLabel = computed(() => prototypeModeLabel(displayed.value?.mode));
/** 确认依据以服务端资格为准，历史事实仍保留在来源面板中。 */
const confirmationCaption = computed(() => prototypeConfirmationLabel(prototype.value, displayed.value));
/** 正在核对的完整页面列表来自其冻结来源，不能用当前 PRD 替换。 */
const reviewedPages = computed(() => prototypeScopeLabels(reviewedRevision.value));
/** 用户打开对话后若当前稿改变，必须重新核对，不能在新锁下确认未看过的内容。 */
const confirmationProblems = computed(() => [
  ...(prototype.value?.confirmationBlockers || []),
  ...(reviewedRevision.value && reviewedRevision.value.id !== prototype.value?.current?.id ? ['当前原型已有新修订，请重新打开确认检查。'] : []),
]);

/** 聚合刷新合并并发调用，终态仅通知一次，后续动作不自动重跑。 */
const poller = createPrototypePoller({
  async read() {
    const value = await api.loadPrototype(versionId.value);
    const catalog = await api.loadPrototypeTemplates();
    return { value, catalog };
  },
  applied({ value, catalog }) {
    const previousPending = pending.value?.runId;
    prototype.value = value; templates.value = catalog; loading.value = false;
    if (previousPending && !value.runs?.some(run => run.runId === previousPending && isPrototypePending(run))) emit('changed');
  },
  failed(cause) { loading.value = false; error.value = `${cause.message}。可刷新任务状态，后台任务不会因此重新提交。`; },
  pending: ({ value }) => value.runs?.some(isPrototypePending),
});
/** 父级可刷新而不创建第二条轮询链。 */
async function refresh() { return poller.refresh(); }
/** 初始化目录与原型；切换版本只读新版本且释放旧预览。 */
async function initialize() {
  const own = ++contextSequence;
  poller.reset(); clearPreview(); prototype.value = null; historical.value = null; uncertain.value = null; lastCommand.value = null;
  history.value = []; historyPage.value = 0; historyMore.value = false; historyLoading.value = false; historySequence++;
  confirmations.value = []; confirmationPage.value = 0; confirmationMore.value = false; confirmationLoading.value = false; confirmationSequence++;
  showDelivery.value = false; showControl.value = false; controlOpening.value = false; reviewedRevision.value = null; confirmAcknowledged.value = false; downloadNotice.value = ''; exportController?.abort(); downloading.value = '';
  scopeMode.value = 'ALL'; selectedPages.value = []; showGenerate.value = false; showSource.value = false; showHistory.value = false;
  loading.value = true; error.value = ''; resetTarget();
  try {
    await refresh();
  } catch (cause) { if (!disposed && own === contextSequence) { loading.value = false; error.value = cause.message; } }
}
/** 开始生成前展示真实来源和明确范围；不支持页不会自动选入。 */
function configureGeneration(kind = prototype.value?.current ? 'SYNC_PRD' : 'GENERATE') {
  operation.value = kind; error.value = ''; showGenerate.value = true;
  if (prototype.value?.current) {
    scopeMode.value = prototype.value.current.source.scope.mode;
    selectedPages.value = prototype.value.current.source.scope.pageRefs.map(prototypePageKey);
  }
}
/** 复选框身份独立于展示顺序，支持重名页面。 */
function togglePage(page, checked) {
  const key = prototypePageKey(page);
  selectedPages.value = checked ? [...new Set([...selectedPages.value, key])] : selectedPages.value.filter(value => value !== key);
}
/** 默认范围仅提交选择，ALL 的冻结展开交由后端执行。 */
function requestedScope() {
  return { mode: scopeMode.value, pageRefs: scopeMode.value === 'ALL' ? [] : sourcePages.value.filter(page => selectedPages.value.includes(prototypePageKey(page))).map(({ moduleId, pageId }) => ({ moduleId, pageId })) };
}
/** 每次新任务使用新 ID；准备完成后刷新原型锁再构造不可变命令。 */
async function submitOperation(kind, content, retryOfRunId = null, retryCommand = null) {
  if (writeBlocked.value || !content.trim() || (kind === 'EDIT' && !canEdit.value)) return false;
  const own = contextSequence, id = versionId.value, selectedTarget = JSON.parse(JSON.stringify(retryCommand?.target || target.value)), selectedScope = retryCommand?.scope || requestedScope(), viewedRevisionId = prototype.value?.current?.id;
  submitting.value = true; error.value = '';
  try {
    const fresh = await props.prepare();
    if (disposed || own !== contextSequence) return false;
    const current = await api.loadPrototype(id);
    if (disposed || own !== contextSequence) return false;
    prototype.value = current;
    if (current.runs?.some(isPrototypePending)) throw new Error('已有原型任务正在执行，请等待完成');
    if (kind === 'EDIT' && current.current?.id !== viewedRevisionId) throw new Error('当前原型已有新修订，请核对新预览后再提交修改');
    if (kind !== 'EDIT') {
      const pages = prototypePages(prototypeSource(fresh).prd, template.value);
      const problem = prototypeScopeProblem(pages, selectedScope.mode, selectedScope.pageRefs.map(prototypePageKey), template.value.maxPages);
      if (problem) throw new Error(problem);
    }
    const command = prototypeCommand({ detail: fresh, prototype: current, template: template.value, operation: kind, scope: selectedScope, target: selectedTarget, content, requestId: crypto.randomUUID(), retryOfRunId });
    poller.reset();
    lastCommand.value = command;
    return await sendCommand(id, command, own);
  } catch (cause) { if (!disposed && own === contextSequence) error.value = cause.message; return false; }
  finally { if (!disposed && own === contextSequence) submitting.value = false; }
}
/** 只有明确接受的任务才能清空主输入框；网络未知时保留原请求。 */
async function sendCommand(id, command, own) {
  try {
    const run = await api.submitPrototype(id, command);
    if (disposed || own !== contextSequence) return false;
    uncertain.value = null; showGenerate.value = false; emit('task-submitted', run, command); emit('changed');
    await refresh(); return true;
  } catch (cause) {
    if (!disposed && own === contextSequence) { uncertain.value = isPrototypeUncertain(cause) ? { kind: 'SUBMIT', id, command } : null; error.value = cause.message; }
    return false;
  }
}
/** 父级主会话直接提交页面或整个原型修改，保持原始来源快照。 */
async function submitEdit(content) { return submitOperation('EDIT', content); }
/** 原样重放结果未知命令，不能刷新锁或更换内容。 */
async function replayUncertain() {
  if (!uncertain.value || submitting.value) return;
  submitting.value = true; error.value = '';
  const own = contextSequence, { kind, id, command } = uncertain.value;
  try {
    if (kind === 'APPLY') await sendApply(id, command, own);
    else if (['RESTORE', 'CONFIRM'].includes(kind)) await sendControl(kind, id, command, own);
    else await sendCommand(id, command, own);
  }
  finally { if (!disposed && own === contextSequence) submitting.value = false; }
}
/** 已失败任务显式创建新任务，沿用用户意图但更新锁和请求 ID。 */
async function retryFailed() {
  if (!lastCommand.value || !latest.value || !['FAILED', 'INTERRUPTED'].includes(latest.value.status)) return;
  await submitOperation(lastCommand.value.operation, lastCommand.value.content, latest.value.runId, lastCommand.value);
}
/** 目标重置不修改原型正文。 */
function resetTarget() { target.value = { kind: 'PROTOTYPE', page: null, overlayId: null }; }
/** 手选页面同时导航到已知测试路径，不执行任意预览脚本。 */
function selectTarget(key) {
  const page = planPages.value.find(item => prototypePageKey(item) === key);
  target.value = page ? { kind: 'PAGE', page: { moduleId: page.moduleId, pageId: page.pageId }, overlayId: null } : { kind: 'PROTOTYPE', page: null, overlayId: null };
  if (page && preview.value && frame.value?.contentWindow) frame.value.contentWindow.postMessage({ type: 'SELECT_PAGE', sessionId: preview.value.sessionId, revisionId: preview.value.revisionId, path: page.testPath || page.path }, new URL(preview.value.frameUrl).origin);
}
/** 每个 iframe 会话独立撤销，独立窗口会话由服务端 TTL 回收。 */
function clearPreview() {
  previewSequence++; clearTimeout(expiryTimer); clearTimeout(readyTimer);
  const old = preview.value; preview.value = null; previewReady.value = false; previewLoading.value = false;
  if (old) api.revokePrototypePreview(old.versionId, old.sessionId).catch(() => {});
}
/** 新预览只读取已验证的指定修订，过期不会自动续租。 */
async function openPreview() {
  clearPreview(); previewError.value = '';
  if (!displayed.value) return;
  const own = previewSequence, id = versionId.value, revisionId = displayed.value.id;
  previewLoading.value = true;
  try {
    const session = await api.createPrototypePreview(id, revisionId, location.origin);
    if (disposed || own !== previewSequence) { api.revokePrototypePreview(id, session.sessionId).catch(() => {}); return; }
    preview.value = { ...session, versionId: id };
    expiryTimer = setTimeout(() => { clearPreview(); previewError.value = '预览会话已到期，请重新打开预览。'; }, Math.max(0, Date.parse(session.expiresAt) - Date.now()));
    readyTimer = setTimeout(() => { if (!previewReady.value) previewError.value = '预览未能就绪，可能已失效；请重新打开预览。'; }, 20000);
  } catch (cause) { if (!disposed && own === previewSequence) previewError.value = cause.message; }
  finally { if (!disposed && own === previewSequence) previewLoading.value = false; }
}
/** 同步弹出空窗口保留浏览器用户手势，再为独立窗口申请自身短时会话。 */
async function openWindow() {
  if (!displayed.value) return;
  const popup = window.open('about:blank', '_blank');
  if (!popup) { previewError.value = '浏览器阻止了新窗口，请允许后重试。'; return; }
  popup.opener = null;
  try { const session = await api.createPrototypePreview(versionId.value, displayed.value.id, location.origin); popup.location.replace(session.windowUrl); }
  catch (cause) { popup.close(); previewError.value = cause.message; }
}
/** 只信任当前 iframe 的隔离桥接；历史浏览不改变编辑目标。 */
function receiveMessage(event) {
  const message = acceptPrototypeMessage(event, frame.value?.contentWindow, preview.value);
  if (!message) return;
  if (message.type === 'ERROR') { previewError.value = message.message || '预览执行出现错误，请重新打开。'; return; }
  if (message.type === 'READY') { previewReady.value = true; clearTimeout(readyTimer); }
  if (!historical.value) {
    const page = prototypePageAtPath(planPages.value, message.path);
    if (page) target.value = { kind: 'PAGE', page: { moduleId: page.moduleId, pageId: page.pageId }, overlayId: null };
  }
}
/** 历史列表有界分页，关闭或版本切换后不覆盖新列表。 */
async function loadHistory(reset = true) {
  if (historyLoading.value && !reset) return;
  const own = ++historySequence, page = reset ? 1 : historyPage.value + 1;
  historyLoading.value = true;
  try {
    const result = await api.listPrototypeRevisions(versionId.value, page);
    if (disposed || own !== historySequence) return;
    history.value = reset ? result.items : [...history.value, ...result.items]; historyPage.value = page; historyMore.value = result.hasMore;
  } catch (cause) { if (!disposed && own === historySequence) error.value = cause.message; }
  finally { if (!disposed && own === historySequence) historyLoading.value = false; }
}
/** 确认历史只读加载，不因查看而推进生命周期或创建新确认。 */
async function loadConfirmations(reset = true) {
  if (confirmationLoading.value && !reset) return;
  const own = ++confirmationSequence, page = reset ? 1 : confirmationPage.value + 1;
  confirmationLoading.value = true;
  try {
    const result = await api.listPrototypeConfirmations(versionId.value, page);
    if (disposed || own !== confirmationSequence) return;
    confirmations.value = reset ? result.items : [...confirmations.value, ...result.items]; confirmationPage.value = page; confirmationMore.value = result.hasMore;
  } catch (cause) { if (!disposed && own === confirmationSequence) error.value = cause.message; }
  finally { if (!disposed && own === confirmationSequence) confirmationLoading.value = false; }
}
/** 历史种类切换只读取相应分页，保持修订与人工确认的语义区别。 */
function selectHistory(kind) { historyKind.value = kind; if (kind === 'CONFIRMATIONS') loadConfirmations(); else loadHistory(); }
/** 确认记录链接到确切修订，不能用当前稿替代历史。 */
async function viewConfirmedRevision(record) {
  const own = contextSequence;
  try { const revision = await api.loadPrototypeRevision(versionId.value, record.revisionId); if (!disposed && own === contextSequence) viewRevision(revision); }
  catch (cause) { if (!disposed && own === contextSequence) error.value = cause.message; }
}
/** 指定历史快照可预览，任何编辑都要求先返回当前修订。 */
function viewRevision(revision) { historical.value = revision; showHistory.value = false; resetTarget(); }
/** 回到服务端当前指针，不把历史回看误认为恢复操作。 */
function returnCurrent() { historical.value = null; resetTarget(); }
/** 冲突候选先展示完整来源和验证信息，再显式采纳。 */
async function reviewCandidate() {
  const own = contextSequence;
  try { const revision = await api.loadPrototypeRevision(versionId.value, candidate.value.resultRevisionId); if (!disposed && own === contextSequence) { viewRevision(revision); showSource.value = true; } }
  catch (cause) { if (!disposed && own === contextSequence) error.value = cause.message; }
}
/** 采纳仍校验 PRD 与原型四锁，服务端可拒绝来源过期候选。 */
async function applyCandidate() {
  if (writeBlocked.value || !candidate.value || historical.value?.id !== candidate.value.resultRevisionId || historical.value.freshness !== 'CURRENT') return;
  submitting.value = true; error.value = ''; const own = contextSequence, sourceRunId = candidate.value.runId, targetRevisionId = historical.value.id;
  try {
    const fresh = await props.prepare(), current = await api.loadPrototype(versionId.value);
    if (disposed || own !== contextSequence) return;
    const command = { requestId: crypto.randomUUID(), sourceRunId, targetRevisionId, ...prototypeLocks(fresh, current) };
    poller.reset(); await sendApply(versionId.value, command, own);
  } catch (cause) { if (!disposed && own === contextSequence) error.value = cause.message; }
  finally { if (!disposed && own === contextSequence) submitting.value = false; }
}
/** 采纳命令也保留未知结果的全部四锁，重放不会生成新的幂等 ID。 */
async function sendApply(id, command, own) {
  try {
    await api.applyPrototypeRevision(id, command);
    if (disposed || own !== contextSequence) return;
    uncertain.value = null; historical.value = null; showSource.value = false; await refresh(); emit('changed');
  } catch (cause) {
    if (!disposed && own === contextSequence) { uncertain.value = isPrototypeUncertain(cause) ? { kind: 'APPLY', id, command } : null; error.value = cause.message; }
  }
}
/** 打开产品内人工核对动作，明确恢复不继承确认、确认只作用整个冻结范围。 */
async function reviewControl(kind) {
  if (writeBlocked.value || controlOpening.value || !displayed.value || (kind === 'CONFIRM' && historical.value)) return;
  const own = contextSequence;
  controlKind.value = kind; reviewedRevision.value = JSON.parse(JSON.stringify(displayed.value));
  reviewedCurrentId.value = prototype.value?.current?.id || null;
  confirmAcknowledged.value = false; error.value = ''; controlOpening.value = true; showDelivery.value = false;
  // v-if 先移除整个抽屉及其退场遮罩，nextTick 后只挂载一个新的核对模态。
  await nextTick();
  if (disposed || own !== contextSequence) return;
  showControl.value = true; controlOpening.value = false;
}
/** 抽屉被核对对话替代时由新模态接管焦点，不再恢复到已经卸载的抽屉按钮。 */
function deliveryCloseFocus(event) { if (controlOpening.value || showControl.value) event.preventDefault(); }
/** 先刷保存与锁，再核对眼前修订仍是目标；提交结果未知时保留原始完整命令。 */
async function submitControl() {
  if (writeBlocked.value || !reviewedRevision.value || (controlKind.value === 'CONFIRM' && (!confirmAcknowledged.value || confirmationProblems.value.length))) return;
  const own = contextSequence, id = versionId.value, kind = controlKind.value, reviewed = reviewedRevision.value;
  submitting.value = true; error.value = '';
  try {
    const fresh = await props.prepare(), current = await api.loadPrototype(id);
    if (disposed || own !== contextSequence) return;
    prototype.value = current;
    if (current.runs?.some(isPrototypePending)) throw new Error('已有原型任务正在执行，请等待完成');
    if ((current.current?.id || null) !== reviewedCurrentId.value) throw new Error('当前原型已经变化，请重新打开并核对操作');
    const revision = kind === 'CONFIRM' ? current.current : reviewed;
    if (kind === 'CONFIRM' && (revision?.id !== reviewed.id || revision.source?.id !== reviewed.source?.id)) throw new Error('原型或来源已经变化，请重新核对当前修订');
    const command = prototypeDeliveryCommand({ kind, detail: fresh, prototype: current, revision, requestId: crypto.randomUUID() });
    poller.reset(); await sendControl(kind, id, command, own);
  } catch (cause) { if (!disposed && own === contextSequence) error.value = cause.message; }
  finally { if (!disposed && own === contextSequence) submitting.value = false; }
}
/** 恢复和确认都不调用模型，重放保持原始目标、基线、范围与四锁。 */
async function sendControl(kind, id, command, own) {
  try {
    await (kind === 'RESTORE' ? api.restorePrototypeRevision(id, command) : api.confirmPrototypeRevision(id, command));
    if (disposed || own !== contextSequence) return;
    uncertain.value = null; showControl.value = false; showDelivery.value = false; showHistory.value = false;
    if (kind === 'RESTORE') { historical.value = null; resetTarget(); }
    await refresh(); emit('changed');
  } catch (cause) {
    if (!disposed && own === contextSequence) {
      uncertain.value = isPrototypeUncertain(cause) ? { kind, id, command } : null; error.value = cause.message;
      if (uncertain.value) { showControl.value = false; showDelivery.value = false; }
    }
  }
}
/** 下载绑定点击时的修订；正在查看的历史和当前指针均不会被下载动作改变。 */
async function downloadRevision(format) {
  if (!displayed.value || downloading.value) return;
  const own = contextSequence, id = versionId.value, revision = displayed.value;
  downloading.value = format; downloadNotice.value = ''; error.value = ''; exportController = new AbortController();
  try {
    const file = await api.exportPrototypeRevision(id, revision.id, format, exportController.signal);
    if (disposed || own !== contextSequence) return;
    const url = URL.createObjectURL(file.blob), link = document.createElement('a');
    link.href = url; link.download = file.filename; document.body.appendChild(link); link.click(); link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    downloadNotice.value = `已下载 r${revision.revisionNumber} 的${format === 'source' ? '源码包' : '静态包'}。下载不会创建人工确认。`;
  } catch (cause) { if (!disposed && own === contextSequence && !exportController.signal.aborted) error.value = cause.message; }
  finally { if (!disposed && own === contextSequence) downloading.value = ''; }
}
watch(versionId, initialize, { immediate: true });
watch(() => displayed.value?.id, () => { resetTarget(); openPreview(); });
watch(showHistory, open => { if (open) selectHistory(historyKind.value); });
watch(target, value => emit('target-change', { ...value, label: targetLabel.value }), { deep: true });
watch(() => ({ canEdit: canEdit.value, loading: loading.value, submitting: submitting.value, viewingHistory: !!historical.value, pending: pending.value || null, uncertain: !!uncertain.value, targetLabel: targetLabel.value }), value => emit('state-change', value), { immediate: true });
watch(() => [props.detail.lifecycle.prdRevision, props.detail.workspace.version.revision], () => { if (prototype.value && !submitting.value) refresh(); });
onMounted(() => window.addEventListener('message', receiveMessage));
onBeforeUnmount(() => { disposed = true; contextSequence++; historySequence++; confirmationSequence++; exportController?.abort(); poller.dispose(); clearPreview(); window.removeEventListener('message', receiveMessage); });
defineExpose({ submitEdit, refresh });
</script>

<template>
  <!-- 外层 iframe 仅运行平台包装页并保留独立来源；包装页内部的生成内容仍固定为 allow-scripts 的 opaque iframe。 -->
  <section class="prototype-workspace" data-testid="prototype-panel" aria-label="原型工作区">
    <header class="prototype-toolbar"><div class="prototype-title"><Monitor class="size-4" /><strong>{{ historical ? `历史修订 r${displayed.revisionNumber}` : '交互原型' }}</strong><span v-if="displayed" class="subtle">r{{ displayed.revisionNumber }}</span></div><div class="toolbar-actions"><Button v-if="historical" variant="outline" size="sm" data-testid="prototype-current" @click="returnCurrent"><ArrowLeft class="size-3.5" />返回当前修订</Button><Button v-if="historical && historical.id !== prototype?.current?.id" variant="outline" size="sm" data-testid="prototype-restore-open" :disabled="writeBlocked" @click="reviewControl('RESTORE')"><RotateCcw class="size-3.5" />恢复为新修订</Button><Button v-if="displayed" variant="ghost" size="sm" data-testid="prototype-delivery" @click="showDelivery = true"><Package class="size-3.5" />确认与导出</Button><Button v-if="displayed" variant="ghost" size="sm" @click="showSource = true"><FileText class="size-3.5" />来源与验证</Button><Button v-if="prototype?.exists" variant="ghost" size="sm" data-testid="prototype-history" @click="showHistory = true"><History class="size-3.5" />历史</Button><Button v-if="!historical" size="sm" :disabled="writeBlocked" data-testid="prototype-generate-config" @click="configureGeneration()"><Sparkles class="size-3.5" />{{ prototype?.current ? '按最新 PRD 更新' : '生成原型' }}</Button></div></header>
    <div v-if="error" class="error-banner mx-4 mt-3" role="alert">{{ error }}<Button variant="ghost" size="sm" @click="refresh">刷新状态</Button></div>
    <div v-if="!loading && template && !template.available && !pending" class="prototype-notice" role="status"><span>生成服务当前不可用或尚未释放执行槽，已有原型仍可查看。</span><Button variant="outline" size="sm" :disabled="submitting" @click="refresh">刷新生成状态</Button></div>
    <div v-if="uncertain" class="prototype-notice" role="status">操作结果暂时未知，原请求已保留。<Button variant="outline" size="sm" :disabled="submitting" @click="replayUncertain">查询并重试原请求</Button></div>
    <div v-if="pending" class="prototype-progress" role="status" data-testid="prototype-run-status"><Loader2 class="size-4 animate-spin" /><div><strong>{{ pending.status === 'QUEUED' ? '任务已排队' : (prototypePhases[pending.phase] || '正在处理') }}</strong><p>{{ pending.mode ? prototypeModeLabel(pending.mode) : '等待执行' }}<span v-if="pending.repairAttempts"> · 正在进行第 {{ pending.repairAttempts }}/2 次修复</span> · 刷新页面后仍可继续查看。已有修订会保留到新修订验证完成。</p></div></div>
    <div v-else-if="latest && ['FAILED', 'INTERRUPTED'].includes(latest.status)" class="prototype-notice" data-testid="prototype-run-status"><span>任务{{ latest.status === 'INTERRUPTED' ? '已中断' : '失败' }}：{{ latest.errorMessage || '请检查输入后重新提交。' }} 当前修订已保留。</span><Button v-if="lastCommand" variant="outline" size="sm" :disabled="writeBlocked" @click="retryFailed">新建任务重试</Button></div>
    <div v-if="candidate" class="prototype-notice"><span>有一份已验证候选因来源或修订变化未自动应用。</span><Button variant="outline" size="sm" @click="reviewCandidate">核对候选</Button><Button v-if="historical?.id === candidate.resultRevisionId" size="sm" :disabled="writeBlocked || historical.freshness !== 'CURRENT'" @click="applyCandidate">采纳此候选</Button></div>
    <template v-if="displayed"><div class="prototype-meta"><span data-testid="prototype-source-status">{{ prototypeFreshness[displayed.freshness] || displayed.freshness }}</span><span>{{ displayed.source.kind === 'BASELINE' ? '已定稿 PRD' : 'PRD 草稿' }} r{{ displayed.source.prdRevision }}</span><span>{{ modeLabel }}</span><span v-if="historical">只读回看</span><span data-testid="prototype-confirmation-status">{{ confirmationCaption }}</span></div><div v-if="displayed.freshness !== 'CURRENT'" class="source-warning">{{ displayed.freshness === 'STALE' ? 'PRD 已有更新。普通原型修改仍沿用这份冻结来源；点击“按最新 PRD 更新”才会同步新需求。' : '来源需要重新核对。请查看冻结 PRD 与当前需求，再决定是否重新生成。' }}</div>
      <div class="preview-toolbar"><label>讨论对象 <select :value="target.page ? prototypePageKey(target.page) : ''" data-testid="prototype-page-target" :disabled="!!historical" @change="selectTarget($event.target.value)"><option value="">整个原型</option><option v-for="page in planPages" :key="prototypePageKey(page)" :value="prototypePageKey(page)">{{ page.title }}</option></select></label><div class="toolbar-actions"><Button variant="ghost" size="sm" :disabled="previewLoading" @click="openPreview"><RefreshCw class="size-3.5" />重新打开</Button><Button variant="ghost" size="sm" @click="openWindow"><ExternalLink class="size-3.5" />独立窗口</Button></div></div>
      <p v-if="previewError" class="error-banner mx-4" role="alert">{{ previewError }}</p><div class="prototype-preview"><div v-if="previewLoading" class="preview-placeholder"><Loader2 class="size-5 animate-spin" />正在准备预览…</div><iframe v-if="preview" ref="frame" :key="preview.sessionId" :src="preview.frameUrl" title="交互原型预览" data-testid="prototype-preview-frame" sandbox="allow-scripts allow-same-origin" referrerpolicy="no-referrer"></iframe><div v-else-if="!previewLoading" class="preview-placeholder"><Monitor class="size-7" /><p>预览尚未打开或已过期。</p><Button variant="outline" @click="openPreview">打开预览</Button></div></div><p class="preview-footnote">演示数据仅用于交互验证；可在原型内重置。{{ historical ? '返回当前修订后可继续修改。' : '浏览页面后，可在会话中提出针对当前页面的修改。' }}</p>
    </template>
    <div v-else class="prototype-empty"><Loader2 v-if="loading" class="size-6 animate-spin" /><template v-else><div class="prototype-empty-icon"><Monitor class="size-8" /></div><h2>把 PRD 变成可以体验的原型</h2><p>使用 {{ template?.name || 'Zero 管理端模板' }}，从已保存的需求生成列表、表单和详情页面。先选择范围，再完成生成、构建和交互验证。</p><div class="empty-facts"><span>{{ source.kind === 'BASELINE' ? '来源：当前已定稿 PRD' : '来源：已保存 PRD 草稿' }}</span><span>{{ sourcePages.length }} 个需求页面</span><span>最多 {{ template?.maxPages || 10 }} 页</span></div><Button :disabled="writeBlocked" @click="configureGeneration('GENERATE')"><Sparkles class="size-4" />选择范围并生成</Button></template></div>
    <Dialog v-model:open="showGenerate"><DialogContent class="sm:max-w-2xl max-h-[90dvh] flex flex-col"><DialogHeader><DialogTitle>{{ operation === 'SYNC_PRD' ? '按最新 PRD 更新原型' : '生成交互原型' }}</DialogTitle><DialogDescription>提交前会保存 PRD。模板与来源将固定到本次任务，新修订通过验证后才会成为当前原型。</DialogDescription></DialogHeader><div class="generation-body"><div class="generation-facts"><strong>{{ template?.name || '模板不可用' }} · v{{ template?.revision || '—' }}</strong><p>{{ source.kind === 'BASELINE' ? '当前有效定稿' : '当前已保存草稿' }} · PRD r{{ source.revision }}</p></div><fieldset class="scope-options"><legend>生成范围</legend><label><input v-model="scopeMode" type="radio" value="ALL" data-testid="prototype-scope-all" />全部页面</label><label><input v-model="scopeMode" type="radio" value="SELECTED" data-testid="prototype-scope-selected" />指定页面</label></fieldset><div class="scope-pages"><label v-for="page in sourcePages" :key="prototypePageKey(page)" :class="{ unsupported: !page.supported }"><input v-if="scopeMode === 'SELECTED'" type="checkbox" :checked="selectedPages.includes(prototypePageKey(page))" :disabled="!page.supported" :data-testid="`prototype-scope-page-${page.moduleId}-${page.pageId}`" @change="togglePage(page, $event.target.checked)" /><span><strong>{{ page.moduleTitle }} / {{ page.title }}</strong><small>{{ prototypePageTypes[page.type] || page.type }}{{ page.supported ? '' : ' · 本阶段暂不支持，请保留为能力缺口' }}</small></span></label><p v-if="!sourcePages.length" class="subtle">PRD 尚无模块页面，请先在 PRD 中补充。</p></div><p v-if="scopeProblem" class="text-sm text-amber-700" role="status">{{ scopeProblem }}<Button v-if="!template?.available" variant="ghost" size="sm" @click="refresh">刷新服务状态</Button></p><label class="generation-instruction">本次生成要求<Textarea v-model="instruction" rows="3" maxlength="12000" placeholder="补充需要体现的页面与交互要求" /></label></div><footer class="generation-footer"><span>仅创建原型修订，PRD 不会自动定稿。</span><Button variant="ghost" :disabled="submitting" @click="showGenerate = false">取消</Button><Button data-testid="prototype-generate-submit" :disabled="writeBlocked || !!scopeProblem || !instruction.trim()" @click="submitOperation(operation, instruction)"><Loader2 v-if="submitting" class="size-4 animate-spin" />开始生成</Button></footer></DialogContent></Dialog>
    <Sheet v-model:open="showSource"><SheetContent class="w-full sm:max-w-2xl overflow-y-auto"><SheetHeader><SheetTitle>来源与验证</SheetTitle><SheetDescription>冻结来源、生成范围和校验记录属于当前查看的修订。</SheetDescription></SheetHeader><PrototypeRevisionInfo v-if="displayed" class="p-5" :revision="displayed" /></SheetContent></Sheet>
    <Sheet v-model:open="showHistory"><SheetContent class="w-full sm:max-w-lg overflow-y-auto">
      <SheetHeader><SheetTitle>历史修订与人工确认</SheetTitle><SheetDescription>查看历史不会替换当前原型。恢复会追加新修订，不继承旧确认。</SheetDescription></SheetHeader>
      <div class="history-list"><div class="toolbar-actions"><Button variant="outline" size="sm" :aria-pressed="historyKind === 'REVISIONS'" @click="selectHistory('REVISIONS')">修订记录</Button><Button variant="outline" size="sm" data-testid="prototype-confirmation-history" :aria-pressed="historyKind === 'CONFIRMATIONS'" @click="selectHistory('CONFIRMATIONS')">确认记录</Button></div>
        <template v-if="historyKind === 'REVISIONS'">
          <button v-for="item in history" :key="item.id" :data-testid="`prototype-history-revision-${item.id}`" type="button" @click="viewRevision(item)"><span><strong>r{{ item.revisionNumber }}</strong><small>{{ item.current ? '当前修订' : '历史修订' }} · {{ prototypeTime(item.createdAt) }}</small></span><p>{{ item.changeSummary || '未附变更说明' }}</p><small>{{ prototypeModeLabel(item.mode) }} · {{ prototypeFreshness[item.freshness] }}<template v-if="item.restoredFromRevisionId"> · 历史恢复</template><template v-if="item.latestConfirmation"> · 曾人工确认</template></small></button>
          <p v-if="historyLoading" class="subtle">正在读取修订…</p><p v-else-if="!history.length" class="subtle">尚无历史修订。</p><Button v-if="historyMore" variant="outline" :disabled="historyLoading" @click="loadHistory(false)">加载更早修订</Button>
        </template>
        <template v-else>
          <article v-for="record in confirmations" :key="record.id" class="confirmation-record" :data-testid="`prototype-confirmation-${record.id}`"><strong>{{ prototype?.status === 'CONFIRMED' && prototype.latestConfirmation?.id === record.id ? '当前有效确认' : '历史人工确认' }}</strong><small>{{ prototypeTime(record.createdAt) }}</small><p>确认范围：{{ record.scope?.mode === 'ALL' ? '当时全部页面' : '指定页面' }} · {{ record.scope?.pageRefs?.length || 0 }} 页</p><p class="subtle">PRD 定稿 {{ record.prdBaselineId }}</p><Button size="sm" variant="outline" @click="viewConfirmedRevision(record)">查看对应修订</Button></article>
          <p v-if="confirmationLoading" class="subtle">正在读取确认记录…</p><p v-else-if="!confirmations.length" class="subtle">尚无人工确认记录。生成或下载不会自动创建确认。</p><Button v-if="confirmationMore" variant="outline" :disabled="confirmationLoading" @click="loadConfirmations(false)">加载更早确认</Button>
        </template>
      </div>
    </SheetContent></Sheet>
    <Sheet v-if="!controlOpening && !showControl" v-model:open="showDelivery"><SheetContent class="w-full sm:max-w-xl overflow-y-auto" @close-auto-focus="deliveryCloseFocus">
      <SheetHeader><SheetTitle>确认与导出 · r{{ displayed?.revisionNumber }}</SheetTitle><SheetDescription>操作绑定当前查看的修订。导出为交互原型，开发交付与生产验收仍未接入。</SheetDescription></SheetHeader>
      <div v-if="displayed" class="delivery-body"><p class="delivery-status">{{ confirmationCaption }}</p><p class="subtle">{{ modeLabel }} · {{ displayed.source.kind === 'BASELINE' ? '已定稿 PRD' : 'PRD 草稿' }} r{{ displayed.source.prdRevision }}</p>
        <section><h3>人工确认</h3><p v-if="historical" class="subtle">历史修订只读。需要确认时，请先恢复为新修订并核对其来源。</p><template v-else><p>确认会固定原型修订、有效 PRD 定稿和这份原型的全部生成范围。</p><ul v-if="prototype?.confirmationBlockers?.length" class="delivery-blockers" data-testid="prototype-confirm-blockers"><li v-for="item in prototype.confirmationBlockers" :key="item">{{ item }}</li></ul><p v-if="displayed.source.kind !== 'BASELINE' || displayed.freshness !== 'CURRENT'" class="subtle">请先完成 PRD 定稿，再使用“按最新 PRD 更新”生成对应来源的修订。</p><Button variant="outline" data-testid="prototype-confirm-open" :disabled="writeBlocked || prototype?.status === 'CONFIRMED'" @click="reviewControl('CONFIRM')"><BadgeCheck class="size-4" />{{ prototype?.status === 'CONFIRMED' ? '此修订已确认' : '核对并确认原型' }}</Button></template></section>
        <section><h3>导出指定修订</h3><p>源码包包含固定模板、生成代码、锁文件和来源说明；静态包包含已验证页面及本地启动说明。两种包均使用模拟数据。</p><div class="delivery-downloads"><Button variant="outline" data-testid="prototype-export-source" :disabled="!!downloading" @click="downloadRevision('source')"><Loader2 v-if="downloading === 'source'" class="size-4 animate-spin" /><Download v-else class="size-4" />下载源码包</Button><Button variant="outline" data-testid="prototype-export-static" :disabled="!!downloading" @click="downloadRevision('static')"><Loader2 v-if="downloading === 'static'" class="size-4 animate-spin" /><Download v-else class="size-4" />下载静态包</Button></div><p v-if="downloadNotice" class="subtle" role="status">{{ downloadNotice }}</p><p v-if="error" class="error-banner" role="alert">{{ error }}</p></section>
        <section><h3>确切生成范围</h3><p>{{ displayed.source.scope.mode === 'ALL' ? '当时全部页面' : '指定页面；不表示覆盖整份 PRD' }} · {{ prototypeScopeLabels(displayed).length }} 页</p><ul><li v-for="page in prototypeScopeLabels(displayed)" :key="page.key">{{ page.title }}</li></ul></section>
      </div>
    </SheetContent></Sheet>
    <Dialog v-model:open="showControl"><DialogContent class="sm:max-w-xl max-h-[90dvh] overflow-y-auto">
      <DialogHeader><DialogTitle>{{ controlKind === 'RESTORE' ? '恢复历史为新修订' : '人工确认原型' }}</DialogTitle><DialogDescription>{{ controlKind === 'RESTORE' ? '原历史保持不变。恢复追加一个当前修订，沿用旧来源，人工确认需要重新核对。' : '请核对页面、模拟交互和验收依据。确认只覆盖下列完整范围，不表示生产交付已经完成。' }}</DialogDescription></DialogHeader>
      <div v-if="reviewedRevision" class="control-body"><dl><div><dt>{{ controlKind === 'RESTORE' ? '恢复目标' : '确认修订' }}</dt><dd>r{{ reviewedRevision.revisionNumber }} · {{ prototypeModeLabel(reviewedRevision.mode) }}</dd></div><div><dt>PRD 来源</dt><dd>{{ reviewedRevision.source.kind === 'BASELINE' ? '已定稿 PRD' : 'PRD 草稿' }} r{{ reviewedRevision.source.prdRevision }} · {{ prototypeFreshness[reviewedRevision.freshness] }}</dd></div></dl><p v-if="controlKind === 'RESTORE'">当前 r{{ prototype?.current?.revisionNumber }} 会保留在历史中。新修订不继承目标修订的人工确认。</p><p v-if="controlKind === 'RESTORE' && reviewedRevision.freshness !== 'CURRENT'" class="subtle">恢复后来源仍可能过期；采用最新需求需要另行同步 PRD。</p><h3>{{ reviewedRevision.source.scope.mode === 'ALL' ? '当时全部页面' : '指定页面；不表示整份 PRD 完成' }} · {{ reviewedPages.length }} 页</h3><ul><li v-for="page in reviewedPages" :key="page.key">{{ page.title }}</li></ul>
        <template v-if="controlKind === 'CONFIRM'"><p class="subtle">PRD 定稿 {{ reviewedRevision.source.prdBaselineId || '尚未绑定' }}</p><ul v-if="confirmationProblems.length" class="delivery-blockers" data-testid="prototype-confirm-blockers"><li v-for="item in confirmationProblems" :key="item">{{ item }}</li></ul><label class="confirm-ack"><input v-model="confirmAcknowledged" type="checkbox" data-testid="prototype-confirm-ack" :disabled="writeBlocked || !!confirmationProblems.length" />我已核对上述页面范围、模拟交互与 PRD 定稿，确认这份原型。</label></template>
        <p v-if="error" class="error-banner" data-testid="prototype-control-error" role="alert">{{ error }}</p><div class="control-actions"><Button variant="ghost" :disabled="submitting" @click="showControl = false">取消</Button><Button :data-testid="controlKind === 'RESTORE' ? 'prototype-restore-submit' : 'prototype-confirm-submit'" :disabled="writeBlocked || (controlKind === 'CONFIRM' && (!confirmAcknowledged || !!confirmationProblems.length))" @click="submitControl"><Loader2 v-if="submitting" class="size-4 animate-spin" />{{ controlKind === 'RESTORE' ? '恢复为新修订' : '确认这份原型' }}</Button></div>
      </div>
    </DialogContent></Dialog>
  </section>
</template>

<style scoped>
.prototype-workspace { display: flex; flex-direction: column; min-height: 0; height: 100%; overflow-y: auto; background: var(--background); }.prototype-toolbar, .preview-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 16px; border-bottom: 1px solid var(--border); flex-wrap: wrap; }.prototype-title, .toolbar-actions { display: flex; align-items: center; gap: 8px; }.prototype-title { font-size: 14px; }.subtle, .prototype-meta, .preview-footnote { color: var(--muted-foreground); font-size: 12px; }.prototype-meta { display: flex; gap: 14px; flex-wrap: wrap; padding: 11px 16px 0; }.prototype-meta > :first-child { color: var(--foreground); font-weight: 500; }.source-warning { margin: 10px 16px 0; font-size: 12px; line-height: 1.7; color: var(--muted-foreground); }.prototype-progress, .prototype-notice { margin: 12px 16px 0; padding: 12px; border: 1px solid var(--border); border-radius: 8px; display: flex; align-items: center; gap: 10px; font-size: 13px; line-height: 1.7; background: var(--muted); }.prototype-notice { flex-wrap: wrap; }.prototype-progress p { color: var(--muted-foreground); font-size: 12px; }.prototype-progress svg { flex-shrink: 0; }.preview-toolbar { border-bottom: 0; font-size: 12px; }.preview-toolbar label { display: flex; align-items: center; gap: 8px; }.preview-toolbar select { border: 1px solid var(--border); background: var(--background); padding: 6px 8px; border-radius: 6px; max-width: 230px; }.prototype-preview { flex: 1; min-height: 390px; position: relative; margin: 0 16px; border: 1px solid var(--border); border-radius: 8px; overflow: hidden; background: var(--muted); }.prototype-preview iframe { display: block; border: 0; width: 100%; height: 100%; min-height: 390px; background: white; }.preview-placeholder { min-height: 390px; display: flex; flex-direction: column; justify-content: center; align-items: center; gap: 14px; color: var(--muted-foreground); font-size: 13px; }.preview-footnote { padding: 10px 16px 14px; line-height: 1.7; }.prototype-empty { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 48px 28px; text-align: center; gap: 20px; }.prototype-empty h2 { font-size: 21px; font-weight: 600; }.prototype-empty p { max-width: 490px; line-height: 1.9; font-size: 14px; color: var(--muted-foreground); }.prototype-empty-icon { padding: 18px; background: var(--muted); border-radius: 16px; }.empty-facts { display: flex; gap: 18px; flex-wrap: wrap; justify-content: center; font-size: 12px; color: var(--muted-foreground); }.generation-body { display: grid; gap: 18px; min-height: 0; overflow-y: auto; padding: 2px; }.generation-facts { border-radius: 8px; padding: 12px; background: var(--muted); font-size: 13px; line-height: 1.8; }.generation-facts p { color: var(--muted-foreground); }.scope-options { display: flex; gap: 22px; font-size: 13px; }.scope-options legend { font-weight: 500; margin-bottom: 10px; }.scope-options label { display: flex; align-items: center; gap: 8px; }.scope-pages { max-height: 250px; overflow-y: auto; display: grid; gap: 8px; }.scope-pages > label { padding: 10px 12px; border: 1px solid var(--border); border-radius: 7px; display: flex; align-items: center; gap: 10px; font-size: 13px; }.scope-pages strong { font-weight: 500; }.scope-pages small { display: block; margin-top: 3px; color: var(--muted-foreground); }.scope-pages .unsupported { background: var(--muted); }.generation-instruction { display: grid; gap: 8px; font-size: 13px; }.generation-footer { display: flex; align-items: center; gap: 8px; border-top: 1px solid var(--border); padding-top: 16px; }.generation-footer span { flex: 1; font-size: 12px; color: var(--muted-foreground); }.history-list { padding: 20px; display: grid; gap: 12px; }.history-list > button:not([data-slot]) { text-align: left; padding: 14px; border: 1px solid var(--border); border-radius: 8px; }.history-list > button:hover { background: var(--muted); }.history-list > button span { display: flex; justify-content: space-between; gap: 10px; }.history-list small { color: var(--muted-foreground); font-size: 11px; }.history-list p { font-size: 13px; white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.8; margin: 9px 0; }
.toolbar-actions { flex-wrap: wrap; }
.delivery-body, .control-body { display: grid; gap: 18px; font-size: 13px; line-height: 1.8; }.delivery-body { padding: 20px; }.delivery-body section { border-top: 1px solid var(--border); padding-top: 18px; display: grid; gap: 12px; }.delivery-body h3, .control-body h3, .delivery-status { font-weight: 600; }.delivery-body ul, .control-body ul { list-style: disc; padding-left: 18px; }.delivery-blockers { color: var(--muted-foreground); }.delivery-downloads, .control-actions { display: flex; flex-wrap: wrap; gap: 10px; }.control-actions { justify-content: flex-end; }.control-body dl > div { display: grid; grid-template-columns: 75px 1fr; gap: 12px; }.control-body dt { color: var(--muted-foreground); }.confirm-ack { display: flex; align-items: flex-start; gap: 10px; }.confirm-ack input { flex-shrink: 0; margin-top: 6px; }.confirmation-record { padding: 14px; border: 1px solid var(--border); border-radius: 8px; }.confirmation-record small { display: block; margin-top: 4px; }.delivery-body p, .delivery-body li, .control-body p, .control-body li { overflow-wrap: anywhere; }
/* 工作区自身允许纵向滚动，矮窗口及窄屏不会被外层布局裁掉工具条或预览；预览保持可操作的最小高度。 */
@media (max-width: 760px) { .prototype-toolbar { align-items: flex-start; }.toolbar-actions { flex-wrap: wrap; }.prototype-preview { margin: 0 10px; }.prototype-empty { padding: 30px 16px; }.empty-facts { gap: 10px; } }
</style>
