<script setup>
/** 同一会话切换 PRD 与原型；正文自动保存、原型修订与人工定稿独立。@author Andersen */
import { computed, ref, nextTick, onMounted, onBeforeUnmount, watch } from 'vue';
import { useRoute, useRouter, onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router';
import { ArrowUp, ArrowUpLeft, ChevronLeft, Crosshair, History, Loader2, RotateCcw, Sparkles, PanelRightClose, PanelRightOpen } from '@lucide/vue';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import PrdDocumentEditor from '@/components/PrdDocumentEditor.vue';
import PrdIssuesPanel from '@/components/PrdIssuesPanel.vue';
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription } from '@/components/ui/sheet';
import { messageResult, prdBreadcrumbs } from '@/workspace/prd-reader.js';
import PrdReadOnly from '@/components/PrdReadOnly.vue';
import PrdFullPreview from '@/components/PrdFullPreview.vue';
import ConversationAttachments from '@/components/ConversationAttachments.vue';
import SessionActions from '@/components/SessionActions.vue';
import PrdTemplateSwitch from '@/components/PrdTemplateSwitch.vue';
import PrototypeWorkspace from '@/components/prototype/PrototypeWorkspace.vue';
import { globalSectionsFor, prdTemplate, switchPrdTemplate, prdLabels, restorePrdDraft, restoreTemplateBinding } from '@/workspace/prd-template.js';
import * as api from '@/api/workspace.js';
import { useWorkspace } from '@/lib/workspace.js';
import { confirmAction } from '@/lib/feedback.js';
import { createAutosave } from '@/workspace/autosave.js';
import { cloneArtifact, prdMarkdown, canEditConversationPrd } from '@/workspace/lifecycle.js';
import { mergeMessages, pendingRun, projectPath, runCommand } from '@/workspace/state.js';
/** 外壳以路径隔离实例，路由只用于首次读取。 */
const route = useRoute(), router = useRouter(), shell = useWorkspace();
/** 已保存真值与可编辑副本独立维护。 */
const detail = ref(null), draft = ref(null), messages = ref([]), runs = ref([]);
/** 页面、命令、保存分别反馈状态。 */
const loading = ref(true), busy = ref(false), saving = ref(false), error = ref(''), saveError = ref(''), composer = ref(''), edited = ref(false);
/** 当前产物与会话编辑章节。 */
const section = ref('DOCUMENT'), moreMessages = ref(false);
/** 两类讨论输入各自保留；视图切换不把尚未发送的 PRD 意图交给原型执行器。 */
const artifactView = ref(route.query.view === 'prototype' ? 'PROTOTYPE' : 'PRD'), prototypeComposer = ref('');
/** 原型面板提供写入资格与讨论范围；内部修订和来源由面板单独管理。 */
const prototypePanel = ref(null), prototypeState = ref({ canEdit: false, submitting: false, pending: null, targetLabel: '整个原型' });
/** 同一个输入框按当前视图读写对应草稿，仅发送成功后清空该草稿。 */
const activeComposer = computed({ get: () => artifactView.value === 'PROTOTYPE' ? prototypeComposer.value : composer.value,
  set: value => { if (artifactView.value === 'PROTOTYPE') prototypeComposer.value = value; else composer.value = value; } });
/** 文档始终可见，会话和辅助面板按需展开。 */
const showChat = ref(true), showIssues = ref(false), focusedIssue = ref(''), showReview = ref(false), showSuggestions = ref(false);
/** 全文预览使用打开时的已保存快照，后台任务完成不会打断正在核对的内容。 */
const showPreview = ref(false), previewSnapshot = ref(null);
/** 模板切换只在可编辑草稿执行，面板选择本身不更改文档。 */
const showTemplateSwitch = ref(false);
/** 标明当前绑定版本，模板升级不会隐式影响旧稿。 */
const template = computed(() => prdTemplate(draft.value?.prd));
/** 待发送附件与消息同生命周期；原文查看按需加载，不混入 PRD 正文。 */
const attachments = ref([]), readingAttachments = ref(false), showAttachments = ref(false), attachmentDocuments = ref([]), attachmentLoading = ref(false), attachmentError = ref('');
/** 防止关闭或切换原文查看时迟到响应覆盖新选择。 */
let attachmentRequest = 0;
/** 历史长消息按需展开，原始内容不被截断或改写。 */
const expandedMessages = ref({});
/** 页面内最近变更支持撤销，历史记录按页读取。 */
const undo = ref(null), showHistory = ref(false), history = ref([]), historyKind = ref('PRD'), historyPage = ref(1), historyMore = ref(false);
/** 文档目录定位与会话末尾滚动的页面引用。 */
const messagePane = ref(null), documentEditor = ref(null);
/** 定时器、卸载边界和不确定提交的幂等标识。 */
let timer, disposed = false, attempted = null;
/** 忽略早于最近一次读取的轮询响应。 */
let pollSequence = 0;
/** 只有字段校验失败可在修正输入后自动恢复，冲突与网络失败仍需明确处理。 */
let saveErrorCode = '';
/** 会话、版本和产物分别持有服务端锁值。 */
const session = computed(() => detail.value?.workspace.session), version = computed(() => detail.value?.workspace.version), lifecycle = computed(() => detail.value?.lifecycle);
/** 归档与取消对象不能从统一入口写入。 */
const readonly = computed(() => !session.value || !!session.value.deletedAt || !!version.value?.deletedAt || !!session.value.archivedAt || !!detail.value?.workspace.project.archivedAt || !!version.value?.archivedAt || version.value?.lifecycle === 'CANCELED');
/** 后台任务可在刷新后接管。 */
const running = computed(() => pendingRun(runs.value) || pendingRun(lifecycle.value?.runs) || prototypeState.value.pending);
/** PRD 定稿后必须明确继续编辑；已到达原型阶段不锁住重新开放的草稿。 */
const canEdit = computed(() => !readonly.value && !busy.value && canEditConversationPrd(version.value, lifecycle.value) && !!draft.value?.prd);
/** 原型会话遵循具体修订的资格，查看历史、执行中与不可写父对象均禁用发送。 */
const canCompose = computed(() => artifactView.value === 'PROTOTYPE' ? !readonly.value && !busy.value && prototypeState.value.canEdit : canEdit.value);
/** 会话对象以章节名称呈现。 */
const contextLabel = computed(() => artifactView.value === 'PROTOTYPE' ? prototypeState.value.targetLabel || '整个原型' : section.value === 'DOCUMENT' ? '整份 PRD' : `PRD / ${labels.value[section.value] || '整份文档'}`);
/** 整体说明与公共规则使用兼容旧稿的字段，模块结构独立展开。 */
const globals = computed(() => globalSectionsFor(draft.value?.prd));
/** 讨论焦点和建议定位均从当前文档的稳定标识取得。 */
const labels = computed(() => prdLabels(draft.value?.prd, globals.value));
/** 面包屑分别携带模块、页面、规则的稳定键，定位上级不改变当前讨论范围。 */
const contextBreadcrumbs = computed(() => prdBreadcrumbs(draft.value?.prd, section.value, globals.value));
/** 消息正文、历史来源和实际变更位置独立投影，旧消息不充当当前保存状态。 */
const conversationMessages = computed(() => messages.value.map(message => {
  const prototype = runs.value.some(run => run.id === message.runId && run.kind === 'PROTOTYPE');
  const result = messageResult(message, runs.value);
  return { ...message, result, prototype, legacy: message.role === 'ASSISTANT' && !prototype && !Object.hasOwn(result || {}, 'modelReply') };
}));
/** 保存状态只读取当前编辑队列与持久化结果，不采用模型自报。 */
const saveCaption = computed(() => readonly.value ? '只读' : saveError.value ? '保存失败 · 输入已保留' : saving.value ? '正在保存…' : edited.value ? '等待保存' : lifecycle.value?.prdBaseline ? '已定稿' : '已自动保存');
/** 内容缺失在主动作旁说明，最终确认仍由后端校验。 */
const blockers = computed(() => lifecycle.value?.prdBlockers || []);
/** 最新失败不掩盖后续成功。 */
const failure = computed(() => ['FAILED', 'INTERRUPTED'].includes(runs.value[0]?.status) ? runs.value[0] : null);
/** 过期候选仅供核对，不提供强制覆盖当前稿的入口。 */
const unapplied = computed(() => {
  const run = runs.value[0];
  if (run?.kind !== 'WORKBENCH' || run.status !== 'SUCCEEDED') return null;
  try { const result = JSON.parse(run.resultText); return !result.applied && (result.review || result.prd || result.sectionText || result.goal) ? result : null; } catch { return null; }
});
/** 外壳菜单同样先保存当前 PRD，未发送输入由用户自行处理以避免隐式丢弃。 */
async function prepareSessionAction(id, kind) {
  if (id !== session.value?.id) return;
  if (prototypeState.value.uncertain) throw new Error('原型请求结果尚未明确，请先在原型面板查询并重试原请求');
  if (busy.value || saving.value || readingAttachments.value || running.value || prototypeState.value.submitting) throw new Error('当前操作尚未结束，请稍后再试');
  if (kind !== 'rename' && (composer.value.trim() || prototypeComposer.value.trim() || attachments.value.length)) throw new Error('请先处理未发送的消息、附件，再归档或删除会话');
  if (!(await autosave.flush())) throw new Error('PRD 尚未保存成功，请先处理保存错误');
  busy.value = true;
  return () => { busy.value = false; };
}
/** 移除自己的保存保护，避免离开后仍阻塞侧栏操作。 */
const unregisterSessionGuard = shell?.registerSessionGuard(prepareSessionAction);
/** 名称及回收状态变更不覆盖本地 PRD 内容。 */
watch(() => shell?.sessionChange.value, record => {
  if (record?.id !== session.value?.id) return;
  detail.value.workspace.session = record; shell?.setConversationContext(detail.value.workspace);
});
/** 当前 PRD 是唯一保存快照，问题与关联规则一并持久化。 */
function snapshot() { return JSON.stringify(draft.value); }
/** 保存串行化，新输入不被先前响应覆盖。 */
const autosave = createAutosave({
  snapshot,
  async persist(value) {
    saving.value = true;
    try { return await api.saveWorkbench(session.value.id, { ...value, expectedRevision: lifecycle.value.revision, expectedVersionRevision: version.value.revision }); }
    finally { if (!disposed) saving.value = false; }
  },
  applied(value, unchanged) { if (!disposed) { accept(value, unchanged, true); edited.value = autosave.dirty(); saveError.value = ''; saveErrorCode = ''; } },
  failed(cause) {
    if (disposed) return;
    if (cause.code === 'PRD_TEMPLATE_DELETED' && lifecycle.value?.prd) {
      // 明确拒绝新绑定时恢复服务端引用；正文保留，网络结果未知时不能进入此分支。
      draft.value.prd = restoreTemplateBinding(draft.value.prd, lifecycle.value.prd);
      autosave.reset(JSON.stringify(serverDraft(detail.value)));
      edited.value = autosave.dirty(); saveError.value = ''; saveErrorCode = '';
      error.value = '所选模板已删除，已恢复原模板并保留正文。你可以继续编辑或选择其他模板。';
      if (edited.value) autosave.schedule();
      return;
    }
    saveError.value = cause.message; saveErrorCode = cause.code; edited.value = true;
  },
});
autosave.reset('null');
/** 从已保存真值组装唯一编辑副本，不再维护会话草稿与版本范围两份输入。 */
function serverDraft(value) { return value.lifecycle ? cloneArtifact({ prd: value.lifecycle.prd }) : null; }
/** 更新锁值；仅未发生后续输入时替换编辑副本。 */
function accept(value, replace = true, remember = false) {
  if (disposed) return;
  if (remember && detail.value && detail.value.workspace.version?.stage === value.workspace.version?.stage) {
    const prior = serverDraft(detail.value);
    if (JSON.stringify(detail.value.lifecycle?.suggestions) === JSON.stringify(value.lifecycle?.suggestions) && JSON.stringify(prior) !== JSON.stringify(serverDraft(value))) undo.value = prior;
  }
  detail.value = value; runs.value = value.workspace.runs;
  shell?.setConversationContext(value.workspace);
  if (replace) { draft.value = serverDraft(value); autosave.reset(snapshot()); edited.value = false; if (section.value !== 'DOCUMENT' && !labels.value[section.value]) section.value = 'DOCUMENT'; }
}
/** 原型命令先落盘 PRD，再读取最新四种业务锁；保存失败保留正文并终止提交。 */
async function preparePrototype() {
  if (disposed || readonly.value || busy.value || readingAttachments.value) throw new Error('当前工作台暂时不能提交修改');
  ++pollSequence;
  if (!(await autosave.flush())) throw new Error('请先处理 PRD 保存错误，再提交原型任务');
  const value = await api.loadWorkbench(session.value.id);
  if (disposed || autosave.dirty()) throw new Error('PRD 又有未保存内容，请保存后重试');
  accept(value); return value;
}
/** 切换不清空任何讨论草稿；退出原型会撤销面板的临时预览，后台任务保留。 */
async function switchArtifact(view) {
  if (busy.value || prototypeState.value.submitting || prototypeState.value.uncertain || readingAttachments.value || artifactView.value === view) return;
  if (!(await autosave.flush()) || disposed) return;
  await router.replace({ query: { ...route.query, view: view === 'PROTOTYPE' ? 'prototype' : undefined } });
}
/** 视图写入 URL 以支持刷新恢复，路由实例仍按业务路径复用，未发送输入保持在内存。 */
watch(() => route.query.view, value => {
  artifactView.value = value === 'prototype' ? 'PROTOTYPE' : 'PRD';
  if (artifactView.value === 'PRD') prototypeState.value = { canEdit: false, submitting: false, pending: null, targetLabel: '整个原型' };
});
/** 新任务立即进入通用消息轮询，刷新页面后仍依据服务端记录恢复。 */
function prototypeSubmitted(run, command) {
  if (disposed) return;
  if (command?.operation === 'EDIT' && prototypeComposer.value.trim() === command.content) prototypeComposer.value = '';
  runs.value = [run, ...runs.value.filter(item => item.id !== run.id)];
  shell?.refreshSessions(); poll();
}
/** 输入去抖后自动保存。 */
async function changed() {
  await nextTick(); if (disposed) return;
  edited.value = autosave.dirty();
  if (canEdit.value) {
    if (saveErrorCode === 'INVALID_INPUT') { saveErrorCode = ''; saveError.value = ''; autosave.resume(); }
    else autosave.schedule();
  }
}
/** 首次打开允许建立关联；轮询仅执行读取。 */
async function load() {
  loading.value = true; error.value = '';
  try {
    const value = route.params.sessionId ? await api.openWorkbench(route.params.sessionId) : await api.openVersionWorkbench(route.params.versionId);
    if (disposed) return;
    if (value.workspace.project.id !== route.params.projectId) throw new Error('会话不属于当前项目');
    accept(value);
    if (session.value) { const page = await api.loadMessages(session.value.id); if (!disposed) { messages.value = page.items; moreMessages.value = page.hasMore; } }
    shell?.refreshSessions(); await nextTick(); scrollMessages(); schedulePoll();
  } catch (cause) { if (!disposed) error.value = cause.message; }
  finally { if (!disposed) { loading.value = false; await nextTick(); scrollMessages(); } }
}
/** 模型运行期间更新消息，存在本地输入时不更新编辑锁或正文。 */
async function poll() {
  if (disposed || !session.value) return;
  const sequence = ++pollSequence;
  const nearBottom = !messagePane.value || messagePane.value.scrollHeight - messagePane.value.scrollTop - messagePane.value.clientHeight < 100;
  try {
    const [value, page] = await Promise.all([api.loadWorkbench(session.value.id), api.loadMessages(session.value.id)]);
    if (disposed || sequence !== pollSequence) return;
    const newer = (!lifecycle.value || value.lifecycle?.revision >= lifecycle.value.revision) && value.workspace.session.revision >= session.value.revision;
    if (newer && !autosave.dirty() && !saving.value && !busy.value) accept(value, true, true);
    else if (newer) runs.value = value.workspace.runs;
    messages.value = mergeMessages(messages.value, page.items); error.value = '';
    if (nearBottom) { await nextTick(); scrollMessages(); }
  } catch (cause) { if (!disposed) error.value = cause.message; }
  schedulePoll();
}
/** 仅用户接近会话底部时跟随新消息，历史阅读不被打断。 */
function scrollMessages() { if (messagePane.value) messagePane.value.scrollTop = messagePane.value.scrollHeight; }
/** 变更位置和问题关联定位当前文档；失效目标不触发错误写入。 */
async function selectSection(key) {
  if (artifactView.value !== 'PRD') await switchArtifact('PRD');
  if (key === 'issues') { showIssues.value = true; return; }
  if (!labels.value[key]) return;
  section.value = key; await documentEditor.value?.reveal(key);
}
/** 讨论入口展开会话并聚焦输入，不自动发送或修改正文。 */
async function discuss(key) { section.value = labels.value[key] ? key : 'DOCUMENT'; showChat.value = true; await nextTick(); document.getElementById('workspace-message')?.focus(); }
/** 范围栏只定位并聚焦正文，不切换讨论目标、发送消息或清空输入。 */
async function locateContext(key) { if (draft.value?.prd && contextBreadcrumbs.value.some(item => item.key === key)) await documentEditor.value?.reveal(key, { focus: true }); }
/** 问题入口可查看全部关联，原文由同一 PRD 保存。 */
function openIssue(issue) { focusedIssue.value = issue; showIssues.value = true; }
/** 回答明确指出问题及关联范围，用户补充回答后才发送。 */
async function discussIssue(issue, selectedPath) {
  const paths = draft.value.prd.issueLinks?.[issue] || [];
  const path = paths.includes(selectedPath) ? selectedPath : paths[0];
  showIssues.value = false; if (path) await selectSection(path); await discuss(path || 'DOCUMENT');
  const prompt = `关于待确认事项「${issue}」：\n`;
  composer.value = composer.value.trim() ? `${composer.value}\n\n${prompt}` : prompt;
}
/** 预览快照可能已过期，返回会话时重新验证问题及规则仍属于当前 PRD。 */
async function discussPreviewIssue({ issue, path }) {
  showPreview.value = false; await nextTick();
  if (!draft.value.prd.unresolvedIssues.includes(issue)) { error.value = '这项问题已被后续修改更新，请重新打开预览查看最新内容。'; return; }
  await discussIssue(issue, path);
}
/** 本地附件预览不上传，只有点击发送才进入分析任务。 */
function previewAttachment(file) { ++attachmentRequest; attachmentDocuments.value = [file]; attachmentError.value = ''; attachmentLoading.value = false; showAttachments.value = true; }
/** 历史附件按消息读取，原文保留但不在会话中重复铺开。 */
async function viewMessageAttachments(message) {
  const request = ++attachmentRequest; showAttachments.value = true; attachmentLoading.value = true; attachmentError.value = ''; attachmentDocuments.value = [];
  try { const files = await api.loadWorkbenchAttachments(session.value.id, message.runId); if (!disposed && request === attachmentRequest) attachmentDocuments.value = files; }
  catch (cause) { if (!disposed && request === attachmentRequest) attachmentError.value = cause.message; }
  finally { if (!disposed && request === attachmentRequest) attachmentLoading.value = false; }
}
/** 所有局部变更复用串行自动保存；未涉及内容保持原样。 */
function updatePrd(prd) { if (!canEdit.value) return; draft.value.prd = prd; if (section.value !== 'DOCUMENT' && !labels.value[section.value]) section.value = 'DOCUMENT'; changed(); }
/** 切换前保存局部输入，切换结果继续进入相同的保存、撤销和历史链路。 */
async function applyTemplate(target) {
  if (!canEdit.value || running.value || !(await autosave.flush())) return;
  await action(async () => {
    draft.value.prd = switchPrdTemplate(draft.value.prd, target.id, target.revision);
    edited.value = autosave.dirty();
    if (await autosave.flush()) showTemplateSwitch.value = false;
  });
}
/** 定稿检查必须先同步本地草稿，检查结果与将要定稿的版本一致。 */
async function reviewPrd() { if (await autosave.flush()) showReview.value = true; }
/** 刷完局部输入再打开全文，保存失败时留在编辑页展示现有错误。 */
async function previewPrd() {
  if (!(await autosave.flush()) || disposed) return;
  previewSnapshot.value = savedPrdSnapshot();
  showPreview.value = !!previewSnapshot.value;
}
/** 终态停止轮询，失败读取仍允许用户手动刷新。 */
function schedulePoll() { clearTimeout(timer); if (!disposed && running.value) timer = setTimeout(poll, 1300); }
/** 用户命令串行化，异常保留当前输入。 */
async function action(work) {
  if (busy.value || disposed) return false;
  busy.value = true; error.value = '';
  try { await work(); return true; }
  catch (cause) { if (!disposed) error.value = cause.message; return false; }
  finally { if (!disposed) busy.value = false; }
}
/** 更早消息使用序号游标合并。 */
async function older() { await action(async () => { const page = await api.loadMessages(session.value.id, messages.value[0]?.sequence); if (!disposed) { messages.value = mergeMessages(messages.value, page.items); moreMessages.value = page.hasMore; } }); }
/** 先保存本地编辑再提交定向任务，超时沿用同一命令标识。 */
async function send() {
  if (readonly.value || running.value || busy.value || readingAttachments.value) return;
  if (artifactView.value === 'PROTOTYPE') {
    const content = prototypeComposer.value.trim();
    if (!content || !canCompose.value) return;
    const sent = await prototypePanel.value?.submitEdit(content);
    if (sent && prototypeComposer.value.trim() === content) prototypeComposer.value = '';
    return;
  }
  if (!canEdit.value || (!composer.value.trim() && !attachments.value.length)) return;
  if (!(await autosave.flush())) return;
  await action(async () => {
    const field = section.value;
    const input = composer.value.trim() || '请分析附件，按当前讨论范围完善同一份 PRD，未明确的规则保留为待确认事项。';
    attempted = runCommand(attempted, session.value.id, field, JSON.stringify({ input, attachments: attachments.value }));
    const run = await api.submitWorkbench(session.value.id, { requestId: attempted.requestId, target: field, content: input, attachments: attachments.value, expectedRevision: lifecycle.value.revision, expectedVersionRevision: version.value.revision });
    if (disposed) return;
    attempted = null; composer.value = ''; attachments.value = [];
    runs.value = [run, ...runs.value.filter(item => item.id !== run.id)];
    const value = await api.loadWorkbench(session.value.id); accept(value); shell?.refreshSessions();
  });
  await poll();
}
/** 保存失败后的明确重试仍保留原修订校验。 */
async function retrySave() { await autosave.retry(); }
/** 重新载入仅在用户明确放弃未保存输入后替换正文。 */
async function reload() {
  if (autosave.dirty() && !(await confirmAction({ title: '重新载入已保存内容', description: '未保存输入会被替换，请先复制需要保留的文字。', action: '重新载入' }))) return;
  await action(async () => { accept(await api.loadWorkbench(session.value.id)); saveError.value = ''; saveErrorCode = ''; });
}
/** 建议只有用户采纳才加入正文，忽略不影响当前 PRD。 */
async function decideSuggestion(suggestion, decision) {
  if (!(await autosave.flush())) return;
  await action(async () => {
    accept(await api.decideWorkbenchSuggestion(session.value.id, { expectedRevision: lifecycle.value.revision, expectedVersionRevision: version.value.revision, suggestionId: suggestion.id, decision }));
    undo.value = null;
  });
}
/** 撤销最近保存的内容，作为新修订记录而非删除历史。 */
async function undoChange() {
  if (!undo.value || !(await autosave.flush())) return;
  const previous = undo.value; draft.value = restorePrdDraft(previous, lifecycle.value.prd);
  await changed();
  if (await autosave.flush()) undo.value = null;
  else undo.value = previous;
}
/** 定稿后继续在同一份 PRD 修改，旧确认版本保持不可变。 */
async function reopen() {
  if (running.value || !(await autosave.flush())) return;
  await action(async () => {
    accept(await api.resumeWorkbenchPrd(session.value.id, { expectedRevision: lifecycle.value.revision, expectedVersionRevision: version.value.revision, reason: '在会话中继续编辑 PRD' }));
    undo.value = null;
  });
}
/** PRD 定稿仍需一次明确动作，保存不自动确认。 */
async function confirmPrd() {
  if (!(await autosave.flush())) return;
  await action(async () => { accept(await api.confirmWorkbenchPrd(session.value.id, { expectedRevision: lifecycle.value.revision, sourceRevision: lifecycle.value.prdRevision, requestId: crypto.randomUUID() })); undo.value = null; showReview.value = false; });
}
/** 只读历史支持分页，不默认覆盖当前草稿。 */
async function loadHistory(kind = 'PRD', more = false) {
  if (!(await autosave.flush())) return;
  await action(async () => {
    const page = more ? historyPage.value + 1 : 1, result = await (kind === 'BASELINES' ? api.lifecycleBaselines(version.value.id, page) : api.lifecycleHistory(version.value.id, kind, page));
    if (disposed) return;
    historyKind.value = kind; historyPage.value = page; historyMore.value = result.hasMore; history.value = more ? [...history.value, ...result.items] : result.items; showHistory.value = true;
  });
}
/** 历史文档只读解析，来源文本始终按纯文本展示。 */
function historyDocument(record) { try { return record.prd || JSON.parse(record.contentJson || 'null'); } catch { return null; } }
/** 已保存草稿或不可变确认稿的统一快照，供预览和导出使用。 */
function savedPrdSnapshot() {
  const baseline = lifecycle.value.prdBaseline, prd = baseline?.prd || lifecycle.value.prd;
  if (!prd) return null;
  return cloneArtifact({ title: session.value?.title || version.value.label, prd, source: baseline?.sourceBaselineId || lifecycle.value.prdSourceBaselineId, confirmed: !!baseline, revision: baseline?.sourceRevision ?? lifecycle.value.prdRevision, mode: baseline?.mode || lifecycle.value.prdMode, model: baseline?.model || lifecycle.value.prdModel });
}
/** 下载已保存草稿或不可变确认稿，来源与正式状态显式标注。 */
async function download() {
  if (!(await autosave.flush())) return;
  downloadSnapshot(savedPrdSnapshot());
}
/** 预览下载始终匹配眼前快照，后台更新的新版在下次打开预览时取得。 */
function downloadSnapshot(value) {
  if (!value) return;
  const text = prdMarkdown(value.title, value.prd, value.source, value.confirmed, value.mode, value.model);
  const url = URL.createObjectURL(new Blob([text], { type: 'text/markdown;charset=utf-8' }));
  const link = document.createElement('a'); link.href = url; link.download = `${value.title.replace(/[\\/:*?"<>|]/g, '-')}-PRD.md`; link.click(); URL.revokeObjectURL(url);
}
/** 正常离开先刷完保存，仅失败或未发送消息需要明确选择。 */
async function leave() {
  if (prototypeState.value.uncertain) { error.value = '原型请求结果尚未明确，请先在原型面板查询并重试原请求，再离开工作台。'; return false; }
  if (busy.value || readingAttachments.value || prototypeState.value.submitting) return false;
  await autosave.flush();
  if (!autosave.dirty() && !composer.value.trim() && !prototypeComposer.value.trim() && !attachments.value.length) return true;
  return confirmAction({ title: '保留当前输入', description: '仍有未保存内容、未发送消息或附件。离开会放弃这些输入，已保存内容和后台任务不受影响。', action: '放弃输入并离开' });
}
/** 浏览器关闭无法等待异步保存，保护未提交输入。 */
function beforeUnload(event) { if (autosave.dirty() || composer.value.trim() || prototypeComposer.value.trim() || attachments.value.length || readingAttachments.value || busy.value || prototypeState.value.submitting || prototypeState.value.uncertain) { event.preventDefault(); event.returnValue = ''; } }
onBeforeRouteLeave(leave);
/** 同文档切换视图只刷保存，不丢弃两份会话输入；提交结果未知时必须先核对。 */
onBeforeRouteUpdate(async (to, from) => to.path === from.path
  ? !busy.value && !prototypeState.value.submitting && !prototypeState.value.uncertain && !readingAttachments.value && await autosave.flush()
  : leave());
onMounted(() => { load(); window.addEventListener('beforeunload', beforeUnload); });
onBeforeUnmount(() => { unregisterSessionGuard?.(); disposed = true; clearTimeout(timer); autosave.dispose(); window.removeEventListener('beforeunload', beforeUnload); });
</script>

<template>
  <div class="unified-workspace">
    <div v-if="error" class="error-banner" role="alert">{{ error }} <Button variant="outline" size="sm" :disabled="busy" @click="detail ? poll() : load()">重试读取</Button></div>
    <p v-if="loading" class="muted">正在打开会话…</p>
    <template v-if="detail && !loading">
      <header class="uw-heading">
        <div><RouterLink :to="projectPath(detail.workspace.project.id)" class="uw-back"><ChevronLeft class="size-3" />{{ detail.workspace.project.name }}</RouterLink>
          <div class="uw-actions"><h1>{{ session?.title || version?.label }}</h1><SessionActions v-if="session && !readonly" :session="session" :disabled="busy || saving || !!running" /><span class="uw-badge">{{ artifactView === 'PRD' ? 'PRD · ' + (lifecycle?.prdBaseline ? '已定稿' : '草稿') : '原型工作台' }}</span></div>
        </div>
        <div class="uw-actions"><span v-if="artifactView === 'PRD'" class="uw-save" role="status">{{ saveCaption }}</span><Button v-if="artifactView === 'PRD' && undo && canEdit" size="sm" variant="ghost" :disabled="saving" @click="undoChange"><RotateCcw class="size-4" />撤销</Button><Button v-if="artifactView === 'PRD' && version" variant="ghost" size="sm" :disabled="busy" @click="loadHistory()"><History class="size-4" />历史</Button><Button size="sm" variant="ghost" :aria-pressed="showChat" @click="showChat = !showChat"><PanelRightClose v-if="showChat" class="size-4" /><PanelRightOpen v-else class="size-4" />{{ showChat ? '收起会话' : '打开会话' }}</Button><Button v-if="artifactView === 'PRD' && canEdit" size="sm" :disabled="!!running || saving || !!saveError" @click="reviewPrd">定稿检查</Button></div>
      </header>
      <div v-if="saveError" class="error-banner" role="alert">{{ saveError }}<div class="uw-actions"><Button size="sm" variant="outline" @click="retrySave">重试保存</Button><Button size="sm" variant="outline" @click="reload">重新载入</Button></div></div>
      <p v-if="session?.deletedAt" class="uw-confirmed">会话已移入回收站，当前内容只读。<RouterLink to="/archives?tab=trash">前往回收站恢复</RouterLink></p>
      <p v-else-if="version?.deletedAt" class="uw-confirmed">文档已移入回收站，正文和关联会话保留，当前内容只读。<RouterLink to="/archives?tab=trash">前往回收站恢复文档</RouterLink></p>
      <nav class="uw-artifact-tabs" aria-label="工作台产物视图">
        <button type="button" data-testid="prd-tab" :aria-pressed="artifactView === 'PRD'" :disabled="busy || saving || prototypeState.submitting || prototypeState.uncertain || readingAttachments" @click="switchArtifact('PRD')">PRD<span v-if="composer.trim() || attachments.length" class="uw-draft-dot" aria-label="有未发送输入" /></button>
        <button type="button" data-testid="prototype-tab" :aria-pressed="artifactView === 'PROTOTYPE'" :disabled="!version || busy || saving || prototypeState.submitting || prototypeState.uncertain || readingAttachments" @click="switchArtifact('PROTOTYPE')">原型<span v-if="prototypeComposer.trim()" class="uw-draft-dot" aria-label="有未发送输入" /></button>
      </nav>
      <div class="uw-layout" :class="{ 'chat-closed': !showChat, 'prototype-view': artifactView === 'PROTOTYPE' }">
        <section v-show="artifactView === 'PRD'" class="uw-artifact" aria-label="PRD 文档">
          <div class="uw-pane-title"><div class="uw-document-title"><h2>PRD 文档</h2><button v-if="draft?.prd" type="button" class="uw-template" :disabled="!canEdit || !!running || saving" :aria-label="`切换 PRD 模板，当前${template.name} v${template.revision}`" @click="showTemplateSwitch = true">{{ template.name }} · v{{ template.revision }}<span v-if="canEdit"> ▾</span></button></div><div class="uw-actions"><span v-if="lifecycle?.prdMode === 'demo'" class="muted text-xs">本地模板联调</span><Button v-if="lifecycle?.suggestions?.length" size="sm" variant="ghost" @click="showSuggestions = true">设计建议 · {{ lifecycle.suggestions.length }}</Button><Button v-if="draft?.prd" size="sm" variant="outline" :disabled="busy || saving" @click="previewPrd">完整预览</Button><Button v-if="draft?.prd" size="sm" variant="ghost" @click="download">下载</Button></div></div>
          <div v-if="lifecycle?.prdBaseline" class="uw-confirmed"><span>已定稿，后续编辑将保留此确认版本。可切换到原型视图，使用这份定稿生成原型。</span><Button v-if="!readonly" variant="outline" size="sm" :disabled="busy || !!running" @click="reopen">继续编辑</Button></div>
          <PrdDocumentEditor v-if="draft?.prd" ref="documentEditor" :prd="draft.prd" :globals="globals" :disabled="!canEdit" @change="updatePrd" @discuss="discuss" @select="section = $event" @issue="openIssue" @issues="focusedIssue = ''; showIssues = true" />
          <div v-else class="uw-empty"><h3>历史会话资料</h3><p>当前对象只读，恢复后可继续编辑 PRD。</p><pre>{{ session?.draftText || version?.content }}</pre></div>
        </section>
        <PrototypeWorkspace v-if="artifactView === 'PROTOTYPE' && version" ref="prototypePanel" class="uw-artifact" :detail="detail" :readonly="readonly" :busy="busy || saving || readingAttachments || !!(running && running.kind !== 'PROTOTYPE' && !running.runId)" :prepare="preparePrototype" @task-submitted="prototypeSubmitted" @changed="poll" @state-change="prototypeState = $event" @error="error = $event" />
        <section v-show="showChat" class="uw-chat" aria-label="持续会话">
          <div class="uw-pane-title"><h2><Sparkles class="size-4" />与 Alpha 讨论</h2><Button size="icon" variant="ghost" aria-label="收起右侧会话" @click="showChat = false"><PanelRightClose class="size-4" /></Button></div>
          <div class="uw-context" role="region" aria-label="当前讨论范围">
            <div class="uw-context-heading"><span class="uw-context-caption"><Crosshair class="size-3.5" aria-hidden="true" />当前讨论范围</span><Button v-if="artifactView === 'PRD' && section !== 'DOCUMENT'" size="sm" variant="ghost" @click="section = 'DOCUMENT'">切换到全文</Button></div>
            <span class="sr-only" role="status" aria-atomic="true">{{ contextLabel }}</span>
            <p v-if="artifactView === 'PROTOTYPE'" class="uw-prototype-target" data-testid="prototype-discussion-target">{{ contextLabel }}<small v-if="prototypeState.viewingHistory">历史修订只读，请返回当前修订后修改</small></p>
            <nav v-else class="uw-context-path" aria-label="讨论范围路径"><ol><li v-for="(item, index) in contextBreadcrumbs" :key="item.key"><span v-if="index" class="uw-context-separator" aria-hidden="true">/</span><button type="button" :disabled="!draft?.prd" :class="{ current: item.key === section }" :aria-label="`在文档中定位${item.kind}：${item.title}`" :title="`查看${item.kind}：${item.title}`" @click="locateContext(item.key)">{{ item.title }}</button></li></ol><ArrowUpLeft class="size-3.5" aria-hidden="true" /></nav>
          </div>
          <div ref="messagePane" class="uw-messages" aria-live="polite">
            <Button v-if="moreMessages" variant="ghost" size="sm" @click="older">加载更早消息</Button>
            <div v-if="!messages.length" class="uw-welcome"><h3>{{ artifactView === 'PROTOTYPE' ? '从 PRD 生成原型' : '从一个想法开始' }}</h3><p>{{ artifactView === 'PROTOTYPE' ? '先选择生成范围。生成后可定位到具体页面，在这里描述修改意图。' : '描述产品或当前模块的业务规则，Alpha 会直接完善这份 PRD。不明确的内容保留为待确认事项。' }}</p></div>
            <article v-for="message in conversationMessages" :key="message.id" :class="['uw-message', { 'uw-user': message.role === 'USER' }]"><span class="uw-message-role">{{ message.role === 'USER' ? '你' : 'Alpha' }} <small v-if="message.mode === 'demo'">· 本地模板</small><small v-else-if="message.mode === 'synthetic'">· 合成联调，未调用模型</small><small v-if="message.prototype"> · 原型</small></span>
              <small v-if="message.legacy" class="uw-legacy-note">历史消息 · 当时的流程描述不代表当前保存状态</small>
              <p>{{ message.legacy && !expandedMessages[message.id] ? '历史讨论原文已保留，可展开查阅。' : message.content.length > 360 && !expandedMessages[message.id] ? message.content.slice(0, 240) + '…' : message.content }}</p>
              <button v-if="message.attachmentNames?.length" type="button" class="uw-message-attachments" @click="viewMessageAttachments(message)">参考附件：{{ message.attachmentNames.join("、") }} ↗</button>
              <Button v-if="message.legacy || message.content.length > 360" size="sm" variant="ghost" @click="expandedMessages[message.id] = !expandedMessages[message.id]">{{ expandedMessages[message.id] ? '收起完整消息' : '展开完整消息' }}</Button>
              <div v-if="message.result?.applied && message.result.changedSections?.length" class="uw-changes"><button v-for="key in message.result.changedSections.slice(0, 3)" :key="key" type="button" :disabled="key !== 'issues' && !labels[key]" @click="selectSection(key)">{{ key === 'issues' ? '待确认事项' : labels[key] || '原章节已移除' }} ↗</button><details v-if="message.result.changedSections.length > 3"><summary>另 {{ message.result.changedSections.length - 3 }} 处修改</summary><button v-for="key in message.result.changedSections.slice(3)" :key="key" type="button" :disabled="key !== 'issues' && !labels[key]" @click="selectSection(key)">{{ key === 'issues' ? '待确认事项' : labels[key] || '原章节已移除' }} ↗</button></details></div>
            </article>
            <div v-if="running" class="uw-task"><Loader2 class="size-4 animate-spin" /><span>{{ running.kind === 'PROTOTYPE' || running.runId ? '原型任务执行中；验证通过后保存修订。' : '正在完善 PRD；成功后自动保存。' }}</span></div>
            <div v-if="failure" class="error-banner">{{ failure.errorMessage }}<p>消息和文档已保留，可重新发送修改意图。</p></div>
            <details v-if="unapplied" class="uw-retained"><summary>查看未应用结果</summary><PrdReadOnly v-if="unapplied.prd" :prd="unapplied.prd" /><pre v-else>{{ unapplied.sectionText || unapplied.reply }}</pre></details>
          </div>
          <form class="uw-composer" @submit.prevent="send()">
            <label for="workspace-message">{{ artifactView === 'PROTOTYPE' ? '描述原型修改意图' : '继续完善当前内容' }}</label>
            <Textarea id="workspace-message" data-testid="workbench-message" v-model="activeComposer" :disabled="!canCompose || !!running" :maxlength="12000" rows="3" :placeholder="artifactView === 'PROTOTYPE' ? '例如：给当前列表增加状态筛选…' : '描述想法、回答问题，或告诉我如何修改…'" @keydown.ctrl.enter.prevent="send()" @keydown.meta.enter.prevent="send()" />
            <ConversationAttachments v-if="artifactView === 'PRD'" :files="attachments" :disabled="!canEdit || !!running" @change="attachments = $event" @reading="readingAttachments = $event" @preview="previewAttachment" />
            <div class="uw-actions justify-between"><span class="muted text-xs">{{ artifactView === 'PROTOTYPE' ? '按当前讨论范围创建原型修订' : shell?.state.runtime?.aiMode === 'demo' ? '本地联调，未调用大模型' : '直接更新同一份 PRD' }}</span><Button type="submit" data-testid="workbench-submit" size="sm" :disabled="(!activeComposer.trim() && (artifactView === 'PROTOTYPE' || !attachments.length)) || !canCompose || !!running || readingAttachments"><ArrowUp class="size-4" />发送</Button></div>
          </form>
        </section>
      </div>
      <PrdTemplateSwitch v-if="showTemplateSwitch && draft?.prd" v-model:open="showTemplateSwitch" :prd="draft.prd" :disabled="!canEdit || !!running || saving" :error="saveError || error" @apply="applyTemplate" />
      <PrdFullPreview v-if="showPreview && previewSnapshot" v-model:open="showPreview" :snapshot="previewSnapshot" :can-discuss="canEdit" @download="downloadSnapshot(previewSnapshot)" @discuss-issue="discussPreviewIssue" />
      <Sheet v-model:open="showIssues"><SheetContent class="w-full sm:max-w-xl overflow-y-auto"><SheetHeader><SheetTitle>待确认事项 · {{ draft?.prd?.unresolvedIssues.length || 0 }}</SheetTitle><SheetDescription>定位业务规则，在原会话中继续完善。</SheetDescription></SheetHeader><div class="uw-sheet-body"><PrdIssuesPanel v-if="draft?.prd" :prd="draft.prd" :focused="focusedIssue" :globals="globals" :disabled="!canEdit" @change="updatePrd" @discuss="discussIssue" @locate="showIssues = false; selectSection($event)" /></div></SheetContent></Sheet>
      <Sheet v-model:open="showReview"><SheetContent class="w-full sm:max-w-lg overflow-y-auto"><SheetHeader><SheetTitle>PRD 定稿检查</SheetTitle><SheetDescription>定稿保存独立确认版本，后续仍可继续编辑。</SheetDescription></SheetHeader><div class="uw-sheet-body"><p v-if="blockers.length" class="muted text-sm">还需完善 {{ blockers.length }} 项</p><ul class="uw-blockers"><li v-for="item in blockers" :key="item">{{ item }}</li></ul><p v-if="!blockers.length" class="text-sm mb-6">内容检查通过。确认后保留当前 PRD 的定稿版本。</p><Button v-if="blockers.length" variant="outline" @click="showReview = false">继续完善</Button><Button v-else :disabled="!canEdit || !!running || saving || edited || !!saveError" @click="confirmPrd">确认 PRD 定稿</Button></div></SheetContent></Sheet>
      <Sheet v-model:open="showAttachments"><SheetContent class="w-full sm:max-w-3xl overflow-y-auto"><SheetHeader><SheetTitle>参考附件原文</SheetTitle><SheetDescription>附件作为业务分析资料，不等同于已确认的 PRD 规则。</SheetDescription></SheetHeader><div class="uw-sheet-body"><p v-if="attachmentLoading" class="muted">正在读取附件…</p><p v-if="attachmentError" role="alert" class="error-banner">{{ attachmentError }}</p><article v-for="file in attachmentDocuments" :key="file.name" class="uw-question"><h3>{{ file.name }}</h3><pre>{{ file.content }}</pre></article></div></SheetContent></Sheet>
      <Sheet v-model:open="showSuggestions"><SheetContent class="w-full sm:max-w-xl overflow-y-auto"><SheetHeader><SheetTitle>设计建议</SheetTitle><SheetDescription>采纳后才写入正文，不属于已确认需求。</SheetDescription></SheetHeader><div class="uw-sheet-body"><article v-for="suggestion in lifecycle?.suggestions || []" :key="suggestion.id" class="uw-question"><button type="button" class="muted text-xs" @click="showSuggestions = false; selectSection(suggestion.section)">{{ labels[suggestion.section] || '原关联页面已移除' }} ↗</button><p>{{ suggestion.text }}</p><div v-if="canEdit" class="uw-actions"><Button size="sm" variant="outline" :disabled="!labels[suggestion.section]" @click="decideSuggestion(suggestion, 'ACCEPT')">采纳到文档</Button><Button size="sm" variant="ghost" @click="decideSuggestion(suggestion, 'DISMISS')">忽略</Button></div></article></div></SheetContent></Sheet>
      <Sheet v-model:open="showHistory"><SheetContent class="w-full sm:max-w-3xl overflow-y-auto"><SheetHeader><SheetTitle>历史版本</SheetTitle><SheetDescription>历史只读，继续编辑不会修改旧版本。</SheetDescription></SheetHeader><div class="uw-sheet-body uw-history"><div class="uw-actions"><Button variant="outline" size="sm" @click="loadHistory('PRD')">草稿保存记录</Button><Button variant="outline" size="sm" @click="loadHistory('BASELINES')">定稿版本</Button></div><p v-if="!history.length" class="muted text-sm py-4">暂无记录。</p><details v-for="record in history" :key="record.id || `${record.kind}-${record.revision}`"><summary>{{ historyKind === 'BASELINES' ? (record.kind === 'PRD' ? 'PRD 定稿' : '历史需求确认') : '草稿保存' }} #{{ record.sourceRevision || record.revision }} · {{ new Date(record.createdAt).toLocaleString('zh-CN') }}</summary><PrdReadOnly v-if="historyDocument(record)?.scope !== undefined" :prd="historyDocument(record)" /><pre v-else>{{ record.contentJson || JSON.stringify(record.review, null, 2) }}</pre></details><Button v-if="historyMore" variant="outline" size="sm" @click="loadHistory(historyKind, true)">更多记录</Button><details v-if="session?.draftText || version?.content || lifecycle?.review?.requirements.length || lifecycle?.review?.questions.length || lifecycle?.review?.suggestions.length"><summary>原始需求资料</summary><p class="muted text-xs">旧资料与决策保留，当前编辑以这份 PRD 为准。</p><pre>{{ session?.draftText || version?.content }}</pre><article v-for="item in lifecycle.review.requirements" :key="item.id" class="uw-question"><h3>{{ item.title }}</h3><p>{{ item.description }}</p><p>验收：{{ item.acceptance || '未填写' }}</p></article><article v-for="item in lifecycle.review.questions" :key="item.id" class="uw-question"><h3>{{ item.text }}</h3><p>{{ item.answer || item.reason || '尚未明确' }}</p></article><article v-for="item in lifecycle.review.suggestions" :key="item.id" class="uw-question"><h3>历史建议</h3><p>{{ item.text }}</p><p>{{ { ACCEPTED: '已采纳', REJECTED: '未采用', DEFERRED: '延期', PENDING: '当时未处理' }[item.decision] }}</p></article></details></div></SheetContent></Sheet>
    </template>
  </div>
</template>
<style scoped>
/* 产物切换只变更阅读区域，输入草稿分别保留并以小圆点提示。 */
.uw-artifact-tabs { display: flex; gap: 6px; margin: 0 0 12px; }.uw-artifact-tabs button { display: inline-flex; align-items: center; gap: 6px; padding: 7px 18px; font-size: 13px; color: var(--muted-foreground); border-radius: 6px; }.uw-artifact-tabs button[aria-pressed="true"] { color: var(--foreground); background: var(--accent); font-weight: 600; }.uw-artifact-tabs button:disabled { opacity: .55; }.uw-artifact-tabs button:focus-visible { outline: 2px solid var(--ring); }.uw-draft-dot { width: 5px; height: 5px; border-radius: 50%; background: var(--primary); }.uw-prototype-target { margin-top: 7px; font-size: 13px; overflow-wrap: anywhere; }.uw-prototype-target small { display: block; margin-top: 5px; color: var(--muted-foreground); }
.uw-document-title { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }.uw-template { color: var(--muted-foreground); font-size: 12px; border-radius: 5px; padding: 4px 6px; text-align: left; }.uw-template:not(:disabled):hover { background: var(--accent); color: var(--foreground); }.uw-template:focus-visible { outline: 2px solid var(--ring); outline-offset: 2px; }
.uw-message-attachments { display: block; margin-top: 10px; font-size: 12px; text-align: left; overflow-wrap: anywhere; color: var(--primary); }
.unified-workspace { max-width: 1800px; margin: 0 auto; }
.uw-heading, .uw-actions, .uw-pane-title { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }.uw-heading { justify-content: space-between; margin-bottom: 18px; }.uw-heading h1 { font-size: 20px; font-weight: 600; margin-top: 5px; }.uw-back { display: inline-flex; align-items: center; color: var(--muted-foreground); font-size: 12px; }.uw-save { color: var(--muted-foreground); font-size: 12px; }.uw-badge { font-size: 11px; color: var(--muted-foreground); background: var(--muted); border-radius: 4px; padding: 3px 6px; }
/* 文档获得主要宽度，会话独立滚动；收起后整块空间让给正文。 */
.uw-layout { height: calc(100dvh - 232px); min-height: 500px; display: grid; grid-template-columns: minmax(0,1fr) 320px; border: 1px solid var(--border); background: var(--card); border-radius: 10px; overflow: hidden; }.uw-layout.chat-closed { grid-template-columns: minmax(0,1fr); }
.uw-artifact, .uw-chat { min-width: 0; min-height: 0; display: flex; flex-direction: column; }.uw-chat { border-left: 1px solid var(--border); }.uw-pane-title { flex-shrink: 0; justify-content: space-between; padding: 12px 18px; border-bottom: 1px solid var(--border); min-height: 58px; }.uw-pane-title h2 { font-size: 14px; font-weight: 600; display: flex; align-items: center; gap: 8px; }.uw-pane-title > button { height: 30px; width: 30px; }
/* 讨论范围属于固定工具区，以标签和分隔线区别于消息气泡；不随长消息压缩或滚动。 */
.uw-context { flex-shrink: 0; padding: 10px 18px 12px; border-bottom: 1px solid var(--border); }
.uw-context-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; min-height: 24px; }.uw-context-caption { display: inline-flex; align-items: center; gap: 6px; color: var(--muted-foreground); font-size: 11px; }.uw-context-heading button { flex-shrink: 0; height: 24px; padding: 0 6px; font-size: 11px; }
/* 完整路径可换行和滚动，长模块名称不挤压按钮或覆盖消息区。 */
.uw-context-path { display: flex; align-items: flex-start; gap: 6px; margin-top: 4px; padding-left: 20px; font-size: 13px; line-height: 1.7; }.uw-context-path ol { display: flex; align-items: baseline; flex-wrap: wrap; gap: 2px 4px; min-width: 0; max-height: 88px; overflow-y: auto; padding: 2px; }.uw-context-path li { display: inline-flex; align-items: baseline; gap: 4px; min-width: 0; max-width: 100%; }.uw-context-separator { color: var(--muted-foreground); }.uw-context-path button { min-width: 0; overflow-wrap: anywhere; text-align: left; border-radius: 2px; color: var(--muted-foreground); }.uw-context-path button.current { color: var(--foreground); font-weight: 600; }.uw-context-path button:hover:not(:disabled) { color: var(--foreground); text-decoration: underline; text-underline-offset: 3px; }.uw-context-path button:focus-visible { outline: 2px solid var(--ring); outline-offset: 1px; }.uw-context-path button:disabled { cursor: default; }.uw-context-path > svg { flex-shrink: 0; margin-top: 5px; color: var(--muted-foreground); }
.uw-messages { min-height: 0; overflow-y: auto; padding: 20px 16px; flex: 1; }.uw-welcome { padding: 12px 0; color: var(--muted-foreground); }.uw-welcome h3 { font-size: 16px; color: var(--foreground); margin-bottom: 12px; }.uw-welcome p, .uw-message p { font-size: 13px; line-height: 1.85; white-space: pre-wrap; overflow-wrap: anywhere; }.uw-message { margin-bottom: 24px; }.uw-user { margin-left: 16px; padding: 10px 12px; border-radius: 8px; background: var(--muted); }.uw-message-role { display: block; font-size: 11px; color: var(--muted-foreground); margin-bottom: 6px; }.uw-legacy-note { display: block; font-size: 11px; color: var(--muted-foreground); margin-bottom: 7px; }.uw-message > button { font-size: 12px; padding-left: 0; }.uw-changes { display: grid; gap: 4px; margin-top: 10px; }.uw-changes button { font-size: 12px; text-align: left; color: var(--primary); overflow-wrap: anywhere; }.uw-changes details { font-size: 12px; }.uw-changes details button { display: block; margin-top: 6px; }.uw-changes button:disabled { color: var(--muted-foreground); }
.uw-composer { flex-shrink: 0; padding: 14px; border-top: 1px solid var(--border); }.uw-composer label { display: block; font-size: 12px; color: var(--muted-foreground); margin-bottom: 8px; }.uw-composer .uw-actions { margin-top: 10px; }.uw-composer textarea { max-height: 200px; overflow-y: auto; }.uw-task { display: flex; align-items: start; gap: 8px; font-size: 12px; color: var(--muted-foreground); }.uw-task svg { flex-shrink: 0; }
.uw-confirmed { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; padding: 12px 20px; background: var(--accent); font-size: 13px; }.uw-empty { padding: 32px; font-size: 14px; color: var(--muted-foreground); }.uw-sheet-body { padding: 8px 24px 24px; }.uw-blockers { list-style: disc; padding-left: 20px; font-size: 14px; line-height: 1.8; margin: 16px 0 24px; }.uw-question { padding: 16px 0; border-bottom: 1px solid var(--border); }.uw-question p { white-space: pre-wrap; font-size: 14px; line-height: 1.8; margin: 10px 0; }.uw-history details { padding: 16px 0; border-bottom: 1px solid var(--border); font-size: 13px; } summary { cursor: pointer; } pre { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 12px; padding-top: 12px; }
/* 桌面小窗口优先保证正文可读，会话宽度降低；仍可一键收起。 */
@media (max-width: 1250px) { .uw-layout { grid-template-columns: minmax(0,1fr) 280px; } }
@media (max-width: 900px) { .uw-layout { height: auto; grid-template-columns: 1fr; }.uw-artifact { min-height: 550px; height: 70dvh; }.uw-chat { border-left: 0; border-top: 1px solid var(--border); max-height: 520px; }.uw-messages { min-height: 170px; } }
</style>
