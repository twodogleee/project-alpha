<script setup>
/** PRD 按目录阅读、局部编辑与结构维护，文档真值和保存由父级统一管理。@author Andersen */
import { computed, ref, watch, nextTick } from 'vue';
import { FileText, Plus } from '@lucide/vue';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import PrdSection from './PrdSection.vue';
import PrdPageTypeSelect from './PrdPageTypeSelect.vue';
import { globalSections, sectionsFor, pageTypesFor, sectionHint, prdTemplate, newModule, newPage, modulePath, pagePath, prdLabels } from '@/workspace/prd-template.js';
import { prdNavigation, prdLocation, readerBlocks, updatePrdSection, cleanIssueLinks, linkedIssues } from '@/workspace/prd-reader.js';
/** 仅接收当前草稿，readonly 状态仍允许阅读和定位。 */
const props = defineProps({ prd: { type: Object, required: true }, globals: { type: Array, default: () => globalSections }, disabled: Boolean });
/** 结构和正文都输出新副本，不在子级修改父级真值。 */
const emit = defineEmits(['change', 'discuss', 'issue', 'issues', 'select']);
/** 目录、可选章节、局部元数据与创建表单仅属于界面。 */
const selected = ref('overview'), optional = ref(false), missingOpen = ref(false), metadata = ref(false), creation = ref(''), name = ref(''), type = ref('LIST'), pane = ref(null), focusedPath = ref('');
/** 空态的手动编写入口定位到具体章节编辑器，不创建额外草稿。 */
const sectionEditors = new Map();
/** 定位提示与展开章节分别维护；阅读导航后清除高亮，不写入文档。 */
const locatedPath = ref(''), readerTitle = ref(null);
/** 当前文档目录和名称映射。 */
const navigation = computed(() => prdNavigation(props.prd)), labels = computed(() => prdLabels(props.prd, props.globals));
/** 章节和页面类型随文档绑定变化，正文与定位键保持不变。 */
const pageSections = computed(() => sectionsFor(props.prd, 'page')), pageTypes = computed(() => pageTypesFor(props.prd));
/** 目录定位到稳定模块/页面对象，名称修改不影响选择。 */
const module = computed(() => props.prd.modules?.find(item => item.id === selected.value.split(':')[1]));
const page = computed(() => selected.value.startsWith('page:') ? module.value?.pages.find(item => item.id === selected.value.split(':')[2]) : null);
/** 已有和适用章节默认显示，可选空章节按需展开；明确定位的章节始终可见。 */
const blocks = computed(() => {
  const result = readerBlocks(props.prd, selected.value, props.globals);
  if (page.value) for (const [key, title] of pageSections.value) {
    const path = `${selected.value}:${key}`;
    if ((optional.value || focusedPath.value === path || linkedIssues(props.prd, path).length) && !result.some(block => block.key === path)) result.push({ key: path, title, text: page.value.sections[key] || '' });
  }
  return result;
});
/** 空章节集中在待补充区，已关联问题或正在定位的空规则仍显示在正文。 */
const shown = computed(() => blocks.value.filter(block => block.text.trim() || block.key === focusedPath.value || linkedIssues(props.prd, block.key).length));
const missing = computed(() => blocks.value.filter(block => !shown.value.includes(block)));
/** 是否已有正文与是否显示问题关联章节分别判断，空白规则不冒充已编写内容。 */
const hasContent = computed(() => blocks.value.some(block => block.text.trim()));
/** 兼容历史占位名称，只提示用户补充，不自动改名或删除旧模块。 */
const needsName = computed(() => !!module.value && unnamed(page.value?.title ?? module.value.title));
/** 空白和通用占位名称不能作为新结构的业务名称。 */
function unnamed(value) { return !value?.trim() || ['未命名模块', '未命名页面'].includes(value.trim()); }
/** 删除当前节点后回到整体说明，禁止保留失效编辑目标。 */
watch(navigation, items => { if (!items.some(item => item.key === selected.value)) select('overview'); });
/** 切换阅读页面，讨论目标同步但不会打开会话。 */
function select(key, notify = true) { creation.value = ''; selected.value = key; optional.value = false; missingOpen.value = false; metadata.value = false; focusedPath.value = ''; locatedPath.value = ''; if (notify) emit('select', key.startsWith('module:') || key.startsWith('page:') ? key : 'DOCUMENT'); nextTick(() => { if (pane.value) pane.value.scrollTop = 0; }); }
/** 从问题、任务变更等入口定位到真实正文章节。 */
async function reveal(path, { focus = false } = {}) {
  if (path !== 'DOCUMENT' && !labels.value[path]) return;
  // 定位规则时只切换阅读目录，不把会话的规则级目标改写成所属页面。
  select(prdLocation(path, props.prd), false); focusedPath.value = path; locatedPath.value = path;
  await nextTick();
  const node = [...(pane.value?.querySelectorAll('[data-section]') || [])].find(element => element.dataset.section === path) || readerTitle.value;
  node?.scrollIntoView({ block: 'start' });
  if (focus) node?.focus({ preventScroll: true });
}
/** 单块编辑保留所有未涉及数据，立即进入统一自动保存队列。 */
function change(path, text) { focusedPath.value = path; if (!props.disabled) emit('change', updatePrdSection(props.prd, path, text)); }
/** 模块/页面结构变更清理失效问题路径，但问题原文仍保留。 */
function structure(work) { if (props.disabled) return; const next = JSON.parse(JSON.stringify(props.prd)); next.templateVersion = 2; next.modules ||= []; work(next); next.issueLinks = cleanIssueLinks(next); emit('change', next); }
/** 新建表单放在阅读区顶部，打开后主动定位，避免用户在长页找不到输入。 */
async function beginCreate(kind) { if (props.disabled || (kind === 'page' && !module.value)) return; creation.value = kind; name.value = ''; type.value = prdTemplate(props.prd).defaultPageType; await nextTick(); if (pane.value) { pane.value.scrollTop = 0; pane.value.querySelector('#prd-node-name')?.focus(); } }
/** 创建之前必须有名称，避免生成无业务含义的空模块。 */
function create() { if (props.disabled || unnamed(name.value)) return; let key; structure(next => { if (creation.value === 'module') { const item = newModule(); item.title = name.value.trim(); next.modules.push(item); key = modulePath(item); } else { const owner = next.modules.find(item => item.id === module.value.id), item = newPage(props.prd); item.title = name.value.trim(); item.type = type.value; owner.pages.push(item); key = pagePath(owner, item); } }); creation.value = ''; name.value = ''; if (key) select(key); }
/** 历史占位提示和设置入口共用同一个名称输入，打开后聚焦便于直接补充。 */
async function openSettings() { metadata.value = true; await nextTick(); pane.value?.querySelector('#prd-node-title')?.focus(); }
/** 按稳定路径登记编辑器，章节卸载后移除引用。 */
function registerSection(path, instance) { if (instance) sectionEditors.set(path, instance); else sectionEditors.delete(path); }
/** 手动编写直接进入首个需求章节，仍沿用局部编辑和自动保存。 */
async function writeManually() { if (props.disabled || !blocks.value.length) return; const path = blocks.value[0].key; focusedPath.value = path; await nextTick(); await sectionEditors.get(path)?.edit(); }
/** 元数据修改和正文采用相同保存/校验规则，切换类型不删旧内容。 */
function meta(field, value) { structure(next => { const owner = next.modules.find(item => item.id === module.value.id), target = page.value ? owner.pages.find(item => item.id === page.value.id) : owner; target[field] = value; }); }
/** 可撤销移除当前节点，不额外引入保存确认。 */
function remove() { structure(next => { if (page.value) { const owner = next.modules.find(item => item.id === module.value.id); owner.pages = owner.pages.filter(item => item.id !== page.value.id); } else next.modules = next.modules.filter(item => item.id !== module.value.id); }); select('overview'); }
/** 讨论入口发出稳定章节路径，父级负责展开会话。 */
function discuss(path) { emit('discuss', path); }
defineExpose({ reveal });
</script>
<template>
  <div class="prd-reader">
    <nav class="prd-navigation" aria-label="PRD 目录">
      <div class="navigation-heading"><span>PRD 目录</span><Button v-if="!disabled" size="sm" variant="outline" :disabled="prd.modules?.length >= 30" @click="beginCreate('module')"><Plus class="size-3.5" />新增模块</Button></div>
      <button v-for="item in navigation" :key="item.key" type="button" :class="['navigation-item', item.kind]" :aria-current="selected === item.key ? 'page' : undefined" @click="select(item.key)"><FileText v-if="item.kind === 'global'" class="size-3.5 shrink-0" /><span>{{ item.title }}</span><small v-if="linkedIssues(prd, item.key, true).length">{{ linkedIssues(prd, item.key, true).length }}</small></button>
      <div class="navigation-footer"><button type="button" class="navigation-item" @click="emit('issues')"><span>待确认事项</span><small>{{ prd.unresolvedIssues?.length || 0 }}</small></button></div>
    </nav>
    <div ref="pane" class="prd-reading-pane">
      <form v-if="creation" class="creation-form" @submit.prevent="create"><h3>{{ creation === 'module' ? '新增模块' : '新增页面' }}</h3><p class="creation-description">{{ creation === 'module' ? '为 PRD 新增一个功能模块，创建后继续编写需求。' : '为当前模块新增一份页面需求说明，创建后补充字段、操作和业务规则。' }}</p><p v-if="creation === 'page'" class="creation-owner">所属模块：<strong>{{ module?.title }}</strong></p><label for="prd-node-name">{{ creation === 'module' ? '模块名称' : '页面名称' }}</label><Input id="prd-node-name" v-model="name" maxlength="120" :placeholder="creation === 'module' ? '例如：人员管理' : '例如：人员列表、新增人员'" autofocus required :disabled="disabled" /><template v-if="creation === 'page'"><label for="prd-node-type">页面类型</label><PrdPageTypeSelect :prd="prd" id="prd-node-type" v-model="type" :disabled="disabled" /></template><div class="reader-actions"><Button size="sm" type="submit" :disabled="disabled || unnamed(name)">创建{{ creation === 'module' ? '模块' : '页面' }}</Button><Button size="sm" variant="ghost" type="button" @click="creation = ''">取消</Button></div></form>
      <div v-show="!creation">
      <p class="reader-breadcrumb">{{ page ? `${module.title} / ${pageTypes.find(([key]) => key === page.type)?.[1]}页` : module ? '功能模块' : 'PRD 文档' }}</p>
      <div class="reader-heading"><h2 ref="readerTitle" tabindex="-1" :class="{ 'located-heading': locatedPath === selected || locatedPath === 'DOCUMENT' }">{{ navigation.find(item => item.key === selected)?.title }}</h2><div class="reader-actions"><Button v-if="module && !disabled" variant="ghost" size="sm" @click="metadata ? metadata = false : openSettings()">{{ metadata ? '完成设置' : page ? '页面设置' : '模块设置' }}</Button><Button variant="ghost" size="sm" @click="discuss(module ? selected : 'DOCUMENT')">讨论{{ page ? '页面' : module ? '模块' : '全文' }}</Button></div></div>
      <div v-if="needsName" class="name-reminder"><p>请为这个{{ page ? '页面' : '模块' }}补充一个业务名称，方便阅读和讨论。</p><Button v-if="!disabled" variant="outline" size="sm" @click="openSettings">补充名称</Button></div>
      <div v-if="metadata && module && !disabled" class="metadata-form"><label for="prd-node-title">{{ page ? '页面' : '模块' }}名称</label><Input id="prd-node-title" :model-value="page?.title || module.title" maxlength="120" @update:model-value="meta('title', $event)" /><template v-if="page"><label for="prd-page-type">页面类型</label><PrdPageTypeSelect :prd="prd" id="prd-page-type" :model-value="page.type" :disabled="disabled" @update:model-value="meta('type', $event)" /></template><Button variant="ghost" size="sm" @click="remove">移除{{ page ? '页面' : '模块' }}</Button></div>
      <div v-if="!hasContent && module" class="reader-empty-state"><h3>该{{ page ? '页面' : '模块' }}尚未编写需求</h3><p>{{ page ? '描述页面用途、字段和操作，让 Alpha 完善需求；也可以直接编写。' : '描述模块用途和业务规则，让 Alpha 完善需求；也可以直接编写。' }}</p><div v-if="!disabled" class="reader-actions"><Button size="sm" @click="discuss(selected)">通过会话完善</Button><Button size="sm" variant="outline" @click="writeManually">手动编写</Button></div></div>
      <p v-else-if="!hasContent" class="reader-empty">从会话描述你的想法，逐步形成同一份 PRD；也可以按需手动补充。</p>
      <PrdSection v-for="block in blocks" v-show="shown.includes(block) || missingOpen" :key="block.key" :ref="instance => registerSection(block.key, instance)" :path="block.key" :located="locatedPath === block.key" :title="block.title" :text="block.text" :hint="sectionHint(prd, block.key)" :maxlength="!block.key.includes(':') && !['workflows', 'rules', 'acceptance'].includes(block.key) ? 8000 : 10000" :disabled="disabled" :issues="linkedIssues(prd, block.key)" @change="change(block.key, $event)" @discuss="discuss" @issue="emit('issue', $event)" />
      <Button v-if="missing.length" class="missing-toggle" variant="ghost" size="sm" :aria-expanded="missingOpen" @click="missingOpen = !missingOpen">{{ missingOpen ? '收起' : '展开' }}待补充内容 · {{ missing.length }} 项</Button>
      <Button v-if="page" variant="ghost" size="sm" @click="optional = !optional; missingOpen = true">{{ optional ? '收起可选章节' : '补充其他章节' }}</Button>
      <section v-if="module && !page" class="module-pages"><div class="reader-heading"><h3>页面需求</h3><Button v-if="!disabled" size="sm" variant="outline" :disabled="module.pages.length >= 30" @click="beginCreate('page')">新增页面</Button></div><button v-for="item in module.pages" :key="item.id" type="button" @click="select(pagePath(module, item))">{{ item.title }} <span>→</span></button><p v-if="!module.pages.length" class="reader-empty">该模块尚未添加页面。可新增页面需求，或通过会话描述需要的页面。</p></section>
      </div>
    </div>
  </div>
</template>
<style scoped>
.prd-reader { min-height: 0; display: grid; grid-template-columns: 180px minmax(0, 1fr); flex: 1; }
.prd-navigation { background: var(--muted); border-right: 1px solid var(--border); padding: 18px 10px; overflow-y: auto; }
.navigation-heading { padding: 0 8px 12px; display: flex; flex-direction: column; align-items: stretch; gap: 10px; font-size: 12px; color: var(--muted-foreground); }
.navigation-heading button { height: 30px; }.navigation-item { display: flex; gap: 7px; align-items: center; text-align: left; padding: 9px 10px; border-radius: 6px; width: 100%; font-size: 13px; margin-bottom: 3px; }
.navigation-item span { min-width: 0; overflow-wrap: anywhere; }.navigation-item small { margin-left: auto; color: var(--muted-foreground); }.navigation-item.page { padding-left: 24px; }.navigation-item.module { margin-top: 12px; font-weight: 500; }
.navigation-item:hover, .navigation-item[aria-current=page] { background: var(--background); }.navigation-item[aria-current=page] { color: var(--primary); font-weight: 600; }.navigation-footer { margin-top: 25px; padding-top: 12px; border-top: 1px solid var(--border); }
.prd-reading-pane { overflow-y: auto; min-width: 0; padding: 24px 32px 40px; }.reader-breadcrumb { font-size: 12px; color: var(--muted-foreground); margin-bottom: 8px; }.reader-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-wrap: wrap; margin-bottom: 26px; } h2 { font-size: 24px; font-weight: 600; } h3 { font-size: 15px; font-weight: 600; }.reader-actions { display: flex; gap: 6px; align-items: center; }
.reader-empty { font-size: 14px; line-height: 1.9; color: var(--muted-foreground); padding: 12px 0 26px; }.missing-sections { border-top: 1px solid var(--border); margin-top: 20px; padding-top: 14px; }.missing-sections summary { color: var(--muted-foreground); font-size: 13px; cursor: pointer; margin-bottom: 18px; }
.reader-empty-state { padding: 24px; margin-bottom: 20px; background: var(--muted); border-radius: 8px; }.reader-empty-state p { margin: 10px 0 18px; font-size: 13px; color: var(--muted-foreground); line-height: 1.8; }.reader-actions { flex-wrap: wrap; }.name-reminder { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; padding: 12px 16px; margin-bottom: 20px; border: 1px solid var(--border); border-radius: 6px; font-size: 13px; }.creation-description { font-size: 13px; line-height: 1.8; color: var(--muted-foreground); }.creation-owner { font-size: 13px; overflow-wrap: anywhere; }.creation-owner strong { font-weight: 500; }
.module-pages { border-top: 1px solid var(--border); padding-top: 20px; margin-top: 24px; }.module-pages .reader-heading { margin-bottom: 10px; }.module-pages > button { display: flex; justify-content: space-between; padding: 12px 0; width: 100%; border-bottom: 1px solid var(--border); font-size: 14px; }
.creation-form, .metadata-form { padding: 16px; border: 1px solid var(--border); border-radius: 8px; margin-bottom: 24px; display: grid; gap: 10px; }.creation-form label, .metadata-form label { font-size: 12px; color: var(--muted-foreground); }
@media (max-width: 1200px) { .prd-reader { grid-template-columns: 150px minmax(0,1fr); }.prd-reading-pane { padding: 22px; } }
.located-heading { background: var(--accent); box-shadow: -4px 0 0 var(--primary); padding-left: 8px; }.reader-heading h2:focus-visible { outline: 2px solid var(--ring); outline-offset: 4px; }
</style>
