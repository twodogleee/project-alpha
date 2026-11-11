<script setup>
/** PRD 模板目录与受限草稿管理；已发布定义只读，编辑与发布不改变已有文档。@author Andersen */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router';
import { AlertCircle, Check, Copy, FileText, Loader2, Pencil, RefreshCw, RotateCcw, Trash2 } from '@lucide/vue';
import { toast } from 'vue-sonner';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Input } from '@/components/ui/input';
import { Sheet, SheetContent, SheetTitle, SheetDescription } from '@/components/ui/sheet';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog';
import SelectField from '@/components/form/SelectField.vue';
import PrdTemplateEditor from '@/components/PrdTemplateEditor.vue';
import { confirmAction } from '@/lib/feedback.js';
import { registerTemplateCatalog } from '@/workspace/prd-template.js';
import { cloneTemplateDefinition, templateEditorProblems } from '@/workspace/prd-template-editor.js';
import { deletedTemplateIds, templateManagementCards, updateTemplateDeletion } from '@/workspace/prd-template-management.js';
import { getPrdTemplateCatalog, getPrdTemplateManagement, copyPrdTemplate, openPrdTemplateDraft, savePrdTemplateDraft, publishPrdTemplate, setPrdTemplateDeletion } from '@/api/prd-templates.js';

/** 已发布目录与草稿管理信息分开保存，草稿不会注册为 PRD 可用版本。 */
const catalog = ref(null), managed = ref([]);
/** 默认只显示正常目录，已删除模板通过独立阅读视图恢复。 */
const showDeleted = ref(false);
/** 只读详情选择精确修订或 draft，选择本身不变更文档。 */
const selectedId = ref(''), selectedRevision = ref(null), selectedArea = ref('global'), selectedPageType = ref('');
/** 加载与业务命令分别反馈，业务命令串行执行。 */
const loading = ref(false), busy = ref(false), operation = ref(''), error = ref('');
/** 抽屉编辑副本及成功保存基线，冲突时保留本地输入。 */
const editorRecord = ref(null), editor = ref(null), baseline = ref(''), editorError = ref(''), conflict = ref(false), closing = ref(false);
/** 复制只接收已发布来源，名称表单与新建请求幂等标识独立。 */
const copySource = ref(null), copyName = ref(''), copyInitial = ref(''), copyError = ref('');
/** 不确定的复制或发布响应沿用相同参数与请求 ID 重试。 */
let copyAttempt = null, publishAttempt = null;
/** 读取请求可取消，卸载后不回写页面；已发送写请求不因关闭重发。 */
let controller, disposed = false;
/** 删除状态作用于模板身份，不能只隐藏草稿却漏出发布版本卡片。 */
const deletedIds = computed(() => deletedTemplateIds(catalog.value, managed.value));
/** 当前阅读目录同时覆盖已有发布定义与尚未发布的草稿。 */
const templates = computed(() => templateManagementCards(catalog.value, managed.value, showDeleted.value));
/** 已删除入口计数按身份去重，不按发布版本数量计算。 */
const deletedCount = computed(() => deletedIds.value.size);
/** 当前模板的管理记录；内置模板没有编辑记录。 */
const selectedManaged = computed(() => managed.value.find(record => record.id === selectedId.value));
/** 已删除详情保持可读，但不能复制、编辑或发布。 */
const selectedDeleted = computed(() => deletedIds.value.has(selectedId.value));
/** 已知删除状态也约束尚未关闭的编辑副本；跨窗口变化仍由服务端并发锁拒绝。 */
const editorDeleted = computed(() => Boolean(editorRecord.value && (editorRecord.value.deleted || deletedIds.value.has(editorRecord.value.id))));
/** 精确发布版本全部保留，草稿作为独立选项，不冒充正式版本。 */
const versions = computed(() => (catalog.value?.templates || []).filter(template => template.id === selectedId.value).sort((left, right) => right.revision - left.revision));
/** 详情从已发布快照或服务端草稿读取，不显示抽屉尚未保存的副本。 */
const selectedTemplate = computed(() => selectedRevision.value === 'draft' ? selectedManaged.value?.draft
  : versions.value.find(template => template.revision === selectedRevision.value) || versions.value[0]);
/** 已发布与草稿状态显式区分。 */
const viewingDraft = computed(() => selectedRevision.value === 'draft');
/** 模板版本选项保留数字修订与 draft 标识的原始类型。 */
const versionOptions = computed(() => [
  ...(selectedManaged.value?.draft ? [{ value: 'draft', label: `v${selectedManaged.value.draft.revision} · 草稿` }] : []),
  ...versions.value.map(version => ({ value: version.revision, label: `v${version.revision} · 已发布` })),
]);
/** 阅读层级固定，内容和要求取自所选模板版本。 */
const areas = [{ key: 'global', title: '整体说明' }, { key: 'module', title: '模块说明' }, { key: 'page', title: '页面说明' }];
/** 当前层级的章节顺序保留发布定义。 */
const sections = computed(() => selectedTemplate.value?.[`${selectedArea.value}Sections`] || []);
/** 页面必填要求按具体类型展示。 */
const pageType = computed(() => selectedTemplate.value?.pageTypes.find(type => type.key === selectedPageType.value)
  || selectedTemplate.value?.pageTypes.find(type => type.key === selectedTemplate.value.defaultPageType));
/** 全局、模块和页面分别拥有独立完整性要求。 */
const requiredSections = computed(() => selectedArea.value === 'page' ? pageType.value?.requiredSections || []
  : selectedTemplate.value?.[selectedArea.value === 'global' ? 'requiredGlobalSections' : 'requiredModuleSections'] || []);
/** 只有未保存编辑参与关闭保护；成功保存的草稿可随时关闭。 */
const dirty = computed(() => Boolean(editor.value && JSON.stringify(editor.value) !== baseline.value));
/** 复制名称输入也保护明确的用户修改。 */
const copyDirty = computed(() => Boolean(copySource.value && copyName.value !== copyInitial.value));
/** 显示本地可识别的文本缺失，后端承担结构、并发和发布校验。 */
const problems = computed(() => editor.value ? templateEditorProblems(editor.value) : []);
watch(selectedTemplate, template => { selectedPageType.value = template?.defaultPageType || ''; });

/** 读取两类目录全部成功后才替换；发布定义单独注册到运行时解析器。 */
async function readCatalog() {
  controller?.abort();
  const current = new AbortController(); controller = current;
  const [published, management] = await Promise.all([getPrdTemplateCatalog(current.signal), getPrdTemplateManagement(current.signal)]);
  if (disposed || current.signal.aborted) return false;
  registerTemplateCatalog(published);
  catalog.value = published; managed.value = management;
  if (!templates.value.some(template => template.id === selectedId.value)) selectTemplate(templates.value[0]);
  else if (selectedRevision.value === 'draft' && !selectedManaged.value?.draft) selectedRevision.value = selectedManaged.value?.latestPublishedRevision || versions.value[0]?.revision;
  return true;
}
/** 手动刷新失败保留当前编辑；成功后才能清空明确放弃的副本。 */
async function load() {
  if (!(await allowDiscard())) return;
  loading.value = true; error.value = '';
  try { if (await readCatalog()) clearEditor(); }
  catch (failure) { if (!disposed && !controller?.signal.aborted) { error.value = failure.message; if (editor.value) editorError.value = `重新载入失败：${failure.message}`; } }
  finally { if (!disposed) loading.value = false; }
}
/** 选择卡片优先查看已有草稿，否则查看最新发布版。 */
function selectTemplate(template) {
  selectedId.value = template?.id || '';
  selectedRevision.value = managed.value.find(record => record.id === selectedId.value)?.draft ? 'draft' : template?.revision ?? null;
}
/** 切换阅读目录不保存或丢弃表单；弹层和请求期间不能切换。 */
function selectDirectory(deleted) {
  if (busy.value || loading.value || closing.value || editor.value || copySource.value) return;
  showDeleted.value = deleted; selectTemplate(templates.value[0]);
}
/** 管理写入结果只更新该模板，不把草稿注册为业务模板。 */
function adoptManaged(record) {
  managed.value = [...managed.value.filter(item => item.id !== record.id), record];
  catalog.value = updateTemplateDeletion(catalog.value, record);
  return record;
}
/** 抽屉建立独立副本，保留发布修订及管理锁的不同含义。 */
function editRecord(record) {
  editorRecord.value = record; editor.value = cloneTemplateDefinition(record.draft); baseline.value = JSON.stringify(editor.value);
  editorError.value = ''; conflict.value = false; publishAttempt = null;
}
/** 清理关闭后的局部输入，不删除已保存的服务端草稿。 */
function clearEditor() { editorRecord.value = null; editor.value = null; baseline.value = ''; editorError.value = ''; conflict.value = false; publishAttempt = null; }
/** 防止重复命令，异常按当前弹层保留输入。 */
async function run(command, label, copying = false) {
  if (busy.value || loading.value) return;
  busy.value = true; operation.value = label; error.value = '';
  if (copying) copyError.value = ''; else editorError.value = '';
  try { await command(); }
  catch (failure) {
    if (!disposed) {
      if (copying) copyError.value = failure.message;
      else if (editor.value) { editorError.value = failure.message; conflict.value = ['PRD_TEMPLATE_CONFLICT', 'PRD_TEMPLATE_DELETED'].includes(failure.code); }
      else error.value = failure.message;
    }
  } finally { if (!disposed) { busy.value = false; operation.value = ''; } }
}
/** 内置和自定义已发布版本均可复制，草稿不能作为来源。 */
function beginCopy() {
  if (!selectedTemplate.value || viewingDraft.value || selectedDeleted.value || busy.value || loading.value) return;
  copySource.value = selectedTemplate.value; copyName.value = `${selectedTemplate.value.name}副本`.slice(0, 120);
  copyInitial.value = copyName.value; copyError.value = ''; copyAttempt = null;
}
/** 请求参数不变时沿用请求 ID，避免超时重试创建重复模板。 */
async function createCopy() {
  if (!copySource.value || deletedIds.value.has(copySource.value.id) || !copyName.value.trim()) return;
  const values = { sourceTemplateId: copySource.value.id, sourceTemplateRevision: copySource.value.revision, name: copyName.value.trim() };
  const key = JSON.stringify(values);
  if (!copyAttempt || copyAttempt.key !== key) copyAttempt = { key, data: { ...values, requestId: crypto.randomUUID() } };
  await run(async () => {
    const record = await copyPrdTemplate(copyAttempt.data);
    if (disposed) return;
    adoptManaged(record); showDeleted.value = Boolean(record.deleted); selectedId.value = record.id; selectedRevision.value = record.draft ? 'draft' : record.latestPublishedRevision;
    copySource.value = null; copyAttempt = null;
    if (record.deleted) { toast.info('此前复制的模板已被删除，可在此恢复'); await readCatalog(); }
    else if (record.draft) editRecord(record); else await readCatalog();
  }, 'copy', true);
}
/** 自定义模板继续现有草稿，或由最新发布版创建下一版。 */
async function beginEdit() {
  if (!selectedManaged.value || selectedDeleted.value || !(await allowDiscard())) return;
  await run(async () => {
    const record = selectedManaged.value.draft || null;
    const current = record ? selectedManaged.value : await openPrdTemplateDraft(selectedManaged.value.id, { expectedRevision: selectedManaged.value.revision });
    if (disposed) return;
    adoptManaged(current); selectedRevision.value = 'draft'; editRecord(current);
  }, 'open');
}
/** 仅提交受限配置字段，模板身份和模型指导不进入编辑请求。 */
function configuration() {
  const { name, description, defaultPageType, globalSections, moduleSections, pageSections, pageTypes, requiredGlobalSections, requiredModuleSections } = editor.value;
  return { name, description, defaultPageType, globalSections, moduleSections, pageSections, pageTypes, requiredGlobalSections, requiredModuleSections };
}
/** 保存成功才推进管理锁和基线，失败保留原输入供核对或重试。 */
async function persistDraft() {
  if (!dirty.value) return editorRecord.value;
  const record = await savePrdTemplateDraft(editorRecord.value.id, { expectedRevision: editorRecord.value.revision, configuration: configuration() });
  if (disposed) return null;
  adoptManaged(record); editorRecord.value = record; editor.value = cloneTemplateDefinition(record.draft); baseline.value = JSON.stringify(editor.value);
  conflict.value = false; publishAttempt = null;
  return record;
}
/** 显式保存草稿不发布，也不影响 PRD 模板选择列表。 */
async function saveDraft() {
  if (!editor.value || editorDeleted.value || !dirty.value || conflict.value || problems.value.length) return;
  await run(async () => { if (await persistDraft()) toast.success('模板草稿已保存，尚未发布'); }, 'save');
}
/** 发布先保存当前修改，再以相同幂等请求发布该保存结果。 */
async function publish() {
  if (!editor.value || editorDeleted.value || conflict.value || problems.value.length) return;
  await run(async () => {
    const record = await persistDraft(); if (!record || disposed) return;
    const key = `${record.id}@${record.revision}`;
    if (!publishAttempt || publishAttempt.key !== key) publishAttempt = { key, data: { expectedRevision: record.revision, requestId: crypto.randomUUID() } };
    const result = await publishPrdTemplate(record.id, publishAttempt.data);
    if (disposed) return;
    adoptManaged(result); showDeleted.value = Boolean(result.deleted); selectedId.value = result.id; selectedRevision.value = result.latestPublishedRevision; clearEditor();
    if (result.deleted) toast.info('该模板已被删除，原发布版本仍保留，可在此恢复');
    else toast.success(`模板 v${result.latestPublishedRevision} 已发布，已有 PRD 保持原版本`);
    try { await readCatalog(); } catch (failure) { error.value = `发布已完成，但目录读取失败：${failure.message}`; }
  }, 'publish');
}
/** 删除仅从目录移除；动作不自动保存抽屉输入，关闭抽屉后才能执行。 */
async function changeDeletion(deleted) {
  const record = selectedManaged.value, title = selectedTemplate.value?.name;
  if (!record || busy.value || loading.value || closing.value || editor.value || copySource.value) return;
  if (deleted) {
    closing.value = true;
    let accepted;
    try { accepted = await confirmAction({ title: `删除模板「${title}」`, description: '删除后将从 PRD 可选目录移除。已有 PRD、历史版本、模板草稿及发布记录都会保留，可在“已删除”中恢复。', action: '删除模板', destructive: true }); }
    finally { closing.value = false; }
    if (!accepted || disposed) return;
  }
  await run(async () => {
    const result = await setPrdTemplateDeletion(record.id, { expectedRevision: record.revision, deleted });
    if (disposed) return;
    adoptManaged(result);
    if (result.deleted) selectTemplate(templates.value[0]);
    else { showDeleted.value = false; selectTemplate(templates.value.find(template => template.id === result.id)); }
    toast.success(result.deleted ? '模板已删除，可在“已删除”中恢复' : '模板已恢复');
    try { await readCatalog(); }
    catch (failure) { if (!disposed) error.value = `${result.deleted ? '删除' : '恢复'}已完成，但目录读取失败：${failure.message}`; }
  }, deleted ? 'delete' : 'restore');
}
/** 关闭、导航和刷新采用同一保护，拒绝时保持全部输入。 */
async function allowDiscard() {
  if (busy.value || loading.value || closing.value) return false;
  if (!dirty.value && !copyDirty.value) return true;
  closing.value = true;
  try { return await confirmAction({ title: '保留未保存的模板修改', description: '当前输入尚未保存。放弃后将恢复已保存内容，已发布版本不受影响。', action: '放弃修改并继续' }); }
  finally { closing.value = false; }
}
/** 关闭仅放弃本地未保存副本，服务端草稿保持可继续编辑。 */
async function closeEditor() { if (await allowDiscard()) clearEditor(); }
/** 复制表单关闭不创建模板，修改过名称时先保护输入。 */
async function closeCopy() { if (await allowDiscard()) { copySource.value = null; copyAttempt = null; } }
/** 浏览器退出无法等待保存，保护未保存副本和未确定结果的请求。 */
function beforeUnload(event) { if (dirty.value || copyDirty.value || busy.value) { event.preventDefault(); event.returnValue = ''; } }
onBeforeRouteLeave(allowDiscard); onBeforeRouteUpdate(allowDiscard);
onMounted(() => { load(); window.addEventListener('beforeunload', beforeUnload); });
onBeforeUnmount(() => { disposed = true; controller?.abort(); window.removeEventListener('beforeunload', beforeUnload); });
</script>

<template>
  <section class="template-settings">
    <div class="page-heading"><div><div class="eyebrow">SYSTEM SETTINGS</div><h1>PRD 模板</h1><p>选择内置模板，或复制并调整为适合自己的写作结构。</p></div><Button variant="outline" :disabled="loading || busy" @click="load"><RefreshCw :class="['size-4', { 'animate-spin': loading }]" />刷新目录</Button></div>
    <div v-if="error" class="inline-alert error" role="alert"><AlertCircle /><span class="flex-1">{{ error }}</span><Button variant="outline" size="sm" :disabled="loading || busy" @click="load">重试</Button></div>
    <nav v-if="catalog" class="template-directory-switch" aria-label="模板目录状态"><Button size="sm" :variant="!showDeleted ? 'secondary' : 'ghost'" :aria-pressed="!showDeleted" :disabled="busy || loading || closing" @click="selectDirectory(false)">模板目录</Button><Button size="sm" :variant="showDeleted ? 'secondary' : 'ghost'" :aria-pressed="showDeleted" :disabled="busy || loading || closing" @click="selectDirectory(true)"><Trash2 class="size-3.5" />已删除<span v-if="deletedCount">{{ deletedCount }}</span></Button></nav>
    <div v-if="loading && !catalog" class="empty-state" role="status"><Loader2 class="animate-spin" /><p>正在读取模板…</p></div>
    <div v-else-if="catalog && !templates.length" class="empty-state"><FileText /><p>{{ showDeleted ? '没有已删除的模板。' : '暂时没有可用模板。' }}</p></div>
    <template v-if="templates.length">
      <div class="template-cards" aria-label="模板目录">
        <button v-for="template in templates" :key="template.id" type="button" :class="['template-card', { selected: selectedId === template.id }]" :aria-pressed="selectedId === template.id" :aria-label="`查看${template.name}模板`" @click="selectTemplate(template)">
          <span class="template-card-top"><FileText class="size-5" /><Badge variant="outline">{{ showDeleted ? '已删除' : managed.some(record => record.id === template.id) ? '自定义' : '内置' }} · {{ managed.find(record => record.id === template.id)?.draft ? `v${template.revision} 草稿` : `v${template.revision}` }}</Badge></span>
          <span class="template-card-title">{{ template.name }}<Check v-if="selectedId === template.id" class="size-4" /></span><span class="template-card-description">{{ template.description }}</span>
        </button>
      </div>
      <p class="template-policy">{{ showDeleted ? '已删除模板保留草稿与所有发布版本，已有 PRD 继续使用原版本；恢复后可继续使用和编辑。' : '草稿发布后才可在 PRD 中选择。每份 PRD 保留所选模板版本，发布新版不会改变已有文档。' }}</p>
      <section v-if="selectedTemplate" class="panel template-detail" aria-label="模板结构预览">
        <div class="template-detail-heading"><div><div class="flex flex-wrap items-center gap-2"><h2>{{ selectedTemplate.name }}</h2><Badge variant="secondary">{{ selectedDeleted ? '已删除 · 只读' : viewingDraft ? '草稿预览' : selectedManaged ? '已发布 · 只读' : '内置 · 只读' }}</Badge></div><p>{{ selectedTemplate.description }}</p></div>
          <div class="template-detail-actions"><div v-if="versionOptions.length > 1" class="template-version"><label for="template-revision">查看版本</label><SelectField id="template-revision" v-model="selectedRevision" :options="versionOptions" /></div><Badge v-else variant="outline">v{{ selectedTemplate.revision }}</Badge>
            <Button v-if="!viewingDraft && !selectedDeleted" size="sm" variant="outline" :disabled="busy || loading || closing" @click="beginCopy"><Copy class="size-3.5" />复制为自定义</Button>
            <Button v-if="selectedManaged && !selectedDeleted" size="sm" :disabled="busy || loading || closing" @click="beginEdit"><Pencil class="size-3.5" />{{ selectedManaged.draft ? '继续编辑草稿' : '编辑下一版' }}</Button>
            <Button v-if="selectedManaged && !selectedDeleted" size="sm" variant="ghost" class="text-destructive" :disabled="busy || loading || closing || Boolean(editor)" @click="changeDeletion(true)"><Trash2 class="size-3.5" />删除模板</Button>
            <Button v-if="selectedManaged && selectedDeleted" size="sm" :disabled="busy || loading || closing" @click="changeDeletion(false)"><RotateCcw class="size-3.5" />恢复模板</Button></div>
        </div>
        <p v-if="selectedDeleted" class="template-draft-notice">此模板已从新建和切换的可选目录移除。已有文档与历史仍可读取，恢复后可以继续管理。</p>
        <p v-else-if="viewingDraft" class="template-draft-notice">v{{ selectedTemplate.revision }} 尚未发布，不会出现在 PRD 模板选择中。{{ selectedManaged.latestPublishedRevision ? `当前可使用 v${selectedManaged.latestPublishedRevision}。` : '' }}</p>
        <div class="template-detail-controls"><div class="template-area-switch" aria-label="章节层级"><Button v-for="area in areas" :key="area.key" :variant="selectedArea === area.key ? 'secondary' : 'ghost'" size="sm" :aria-pressed="selectedArea === area.key" @click="selectedArea = area.key">{{ area.title }}</Button></div>
          <div v-if="selectedArea === 'page'" class="template-page-type"><label for="template-page-type">页面类型</label><SelectField id="template-page-type" v-model="selectedPageType" :options="selectedTemplate.pageTypes.map(type => ({ value: type.key, label: `${type.title}${type.key === selectedTemplate.defaultPageType ? '（默认）' : ''}` }))" /></div><span v-else class="template-default-type">默认页面：{{ selectedTemplate.pageTypes.find(type => type.key === selectedTemplate.defaultPageType)?.title }}</span></div>
        <p class="template-requirement-note">标记“定稿必填”的章节会在定稿时检查；其他章节可按需补充。</p>
        <ol class="template-sections"><li v-for="(section, index) in sections" :key="section.key"><span class="template-section-number">{{ String(index + 1).padStart(2, '0') }}</span><div><div class="template-section-heading"><h3>{{ section.title }}</h3><Badge v-if="requiredSections.includes(section.key)" variant="outline">定稿必填</Badge></div><p>{{ section.hint }}</p></div></li></ol>
      </section>
    </template>
    <Dialog :open="Boolean(copySource)" @update:open="!$event && closeCopy()"><DialogContent><DialogHeader><DialogTitle>复制为自定义模板</DialogTitle><DialogDescription>以「{{ copySource?.name }} · v{{ copySource?.revision }}」创建可编辑草稿，原模板保持不变。</DialogDescription></DialogHeader><form @submit.prevent="createCopy"><div class="field"><label for="template-copy-name">模板名称</label><Input id="template-copy-name" v-model="copyName" maxlength="120" :disabled="busy" autofocus /></div><p v-if="copyError" class="template-form-error" role="alert">{{ copyError }}</p><div class="template-copy-footer"><Button type="button" variant="ghost" :disabled="busy" @click="closeCopy">取消</Button><Button type="submit" :disabled="busy || !copyName.trim()"><Loader2 v-if="busy" class="size-4 animate-spin" />创建草稿</Button></div></form></DialogContent></Dialog>
    <Sheet :open="Boolean(editor)" @update:open="!$event && closeEditor()"><SheetContent class="w-full sm:max-w-[680px] p-0 gap-0">
      <template v-if="editor"><header class="template-editor-header"><SheetTitle>编辑模板草稿 · v{{ editor.revision }}</SheetTitle><SheetDescription>调整适用场景与标准章节，保存后可继续编辑，发布后供 PRD 选择。</SheetDescription></header>
        <div class="template-editor-body"><div v-if="editorError" class="inline-alert error" role="alert"><AlertCircle /><div>{{ editorError }}<p>本地输入已保留。{{ conflict ? '请保留需要的输入，重新载入目录核对模板状态后再继续。' : '如请求结果不确定，可重新读取目录核对已保存内容。' }}</p><Button v-if="conflict" variant="outline" size="sm" class="mt-2" :disabled="busy || loading" @click="load">放弃并重新载入</Button></div></div><PrdTemplateEditor v-model="editor" :disabled="busy || loading || conflict || editorDeleted" /></div>
        <footer class="template-editor-footer"><p>{{ loading ? '正在重新载入…' : dirty ? '有未保存修改' : '草稿已保存' }} · 发布后旧文档仍使用原版本</p><p v-if="problems.length" class="template-form-error">{{ problems.join('；') }}</p><div><Button variant="ghost" :disabled="busy || loading" @click="closeEditor">关闭</Button><Button variant="outline" :disabled="busy || loading || conflict || editorDeleted || !dirty || problems.length > 0" @click="saveDraft"><Loader2 v-if="operation === 'save'" class="size-4 animate-spin" />保存草稿</Button><Button :disabled="busy || loading || conflict || editorDeleted || problems.length > 0" @click="publish"><Loader2 v-if="operation === 'publish'" class="size-4 animate-spin" />发布 v{{ editor.revision }}</Button></div></footer>
      </template>
    </SheetContent></Sheet>
  </section>
</template>

<style scoped>
.template-directory-switch { display: flex; align-items: center; gap: 6px; margin-bottom: 20px; }.template-directory-switch span { color: var(--muted-foreground); font-size: 12px; }
.template-settings { max-width: 1200px; margin: 0 auto; }.template-cards { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; }.template-card { text-align: left; border: 1px solid var(--border); border-radius: 12px; background: var(--card); padding: 22px; display: flex; flex-direction: column; gap: 18px; transition: border-color .15s, box-shadow .15s; }.template-card:hover { background: var(--accent); }.template-card.selected { border-color: var(--primary); box-shadow: 0 0 0 1px var(--primary); }.template-card:focus-visible { outline: 2px solid var(--ring); outline-offset: 4px; }.template-card-top, .template-card-title { display: flex; align-items: center; justify-content: space-between; gap: 12px; }.template-card-top { color: var(--muted-foreground); }.template-card-title { font-size: 17px; font-weight: 650; overflow-wrap: anywhere; }.template-card-description { color: var(--muted-foreground); font-size: 13px; line-height: 1.8; overflow-wrap: anywhere; }.template-policy { color: var(--muted-foreground); font-size: 13px; line-height: 1.8; margin: 20px 0 28px; }
.template-detail { overflow: hidden; }.template-detail-heading { padding: 24px; display: flex; align-items: flex-start; justify-content: space-between; gap: 24px; border-bottom: 1px solid var(--border); }.template-detail-heading h2 { font-size: 18px; font-weight: 650; overflow-wrap: anywhere; }.template-detail-heading p { margin-top: 12px; color: var(--muted-foreground); font-size: 13px; line-height: 1.8; max-width: 700px; overflow-wrap: anywhere; }.template-detail-actions { display: flex; align-items: flex-end; flex-wrap: wrap; justify-content: flex-end; gap: 10px; flex-shrink: 0; max-width: 360px; }.template-version { width: 160px; }.template-version label { display: block; font-size: 12px; color: var(--muted-foreground); margin-bottom: 6px; }.template-detail-controls { padding: 16px 24px; display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap; }.template-area-switch { display: flex; gap: 4px; }.template-page-type { display: flex; align-items: center; gap: 10px; min-width: 265px; }.template-page-type label, .template-default-type { color: var(--muted-foreground); font-size: 12px; white-space: nowrap; }.template-requirement-note { color: var(--muted-foreground); font-size: 12px; margin: 0 24px 8px; line-height: 1.8; }.template-draft-notice { padding: 12px 24px; font-size: 12px; line-height: 1.8; color: var(--muted-foreground); background: var(--muted); }
.template-sections { padding: 0 24px 12px; }.template-sections li { display: grid; grid-template-columns: 28px minmax(0, 1fr); gap: 12px; padding: 18px 0; border-bottom: 1px solid var(--border); }.template-sections li:last-child { border-bottom: 0; }.template-section-number { color: var(--muted-foreground); font-size: 12px; line-height: 24px; font-variant-numeric: tabular-nums; }.template-section-heading { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; }.template-section-heading h3 { font-weight: 600; font-size: 14px; line-height: 24px; }.template-sections li p { margin-top: 6px; color: var(--muted-foreground); font-size: 13px; line-height: 1.8; overflow-wrap: anywhere; }
.template-editor-header { padding: 26px 28px 20px; border-bottom: 1px solid var(--border); flex-shrink: 0; }.template-editor-header [data-slot=sheet-title] { padding-right: 20px; }.template-editor-header [data-slot=sheet-description] { margin-top: 8px; line-height: 1.8; }.template-editor-body { padding: 22px 24px; overflow-y: auto; min-height: 0; flex: 1; }.template-editor-body .inline-alert p { margin-top: 8px; font-size: 12px; line-height: 1.8; }.template-editor-footer { padding: 16px 24px; border-top: 1px solid var(--border); flex-shrink: 0; }.template-editor-footer p { font-size: 12px; color: var(--muted-foreground); line-height: 1.8; }.template-editor-footer > div { display: flex; justify-content: flex-end; gap: 8px; margin-top: 12px; }.template-form-error, .template-editor-footer .template-form-error { font-size: 12px; color: var(--destructive); line-height: 1.8; margin-top: 10px; }.template-copy-footer { display: flex; justify-content: flex-end; gap: 8px; margin-top: 24px; }
/* 三张模板卡在窄内容区垂直排列，避免说明文字被压缩成窄列。 */
@media (max-width: 1000px) { .template-cards { grid-template-columns: 1fr; }.template-card { gap: 12px; }.template-detail-heading { flex-wrap: wrap; }.template-detail-actions { justify-content: flex-start; max-width: none; } }
@media (max-width: 640px) { .template-detail-heading { padding: 18px; gap: 12px; }.template-detail-controls { padding: 16px 18px; }.template-requirement-note { margin: 0 18px 8px; }.template-sections { padding: 0 18px 8px; }.template-editor-header { padding: 24px 18px 16px; }.template-editor-body { padding: 18px; }.template-editor-footer { padding: 14px 18px; } }
</style>
