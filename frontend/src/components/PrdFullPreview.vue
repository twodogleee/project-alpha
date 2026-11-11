<script setup>
import SelectField from '@/components/form/SelectField.vue';
/** 完整 PRD 阅读窗口；使用打开时已保存的快照，目录跳转不改变编辑位置。@author Andersen */
import { computed, ref } from 'vue';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogTitle, DialogDescription } from '@/components/ui/dialog';
import PrdReadOnly from './PrdReadOnly.vue';
import { documentBlocks, prdLabels } from '@/workspace/prd-template.js';
import { issuePaths } from '@/workspace/prd-reader.js';
import { prdSections } from '@/workspace/lifecycle.js';
/** 预览开关由工作区控制，snapshot 同时用于阅读和下载，防止后台更新改变正在核对的正文。 */
const props = defineProps({ open: Boolean, snapshot: { type: Object, required: true }, canDiscuss: Boolean });
/** 下载交还工作区已有的 Markdown 导出链路，关闭不产生保存或定稿动作。 */
const emit = defineEmits(['update:open', 'download', 'discuss-issue']);
/** 只读文档引用与最后点击的目录位置均只在当前预览中生效。 */
const reader = ref(null), selected = ref('');
/** 定位后保留问题上下文，选择不同关联规则时同步会话目标。 */
const focusedIssue = ref(''), selectedRule = ref('');
/** 所有规则名来自本次预览快照，不根据问题文字猜测。 */
const labels = computed(() => prdLabels(props.snapshot.prd));
/** 目录只展示整体章节、模块及页面，字段级规则仍全部保留在正文。 */
const contents = computed(() => [...documentBlocks(props.snapshot.prd, prdSections).filter(block => block.level === 2 || block.text === undefined), { key: 'issues', title: '待确认事项', level: 2 }]);
/** 目录只滚动完整文档，不切换主编辑器当前模块或页面。 */
function locate(key) { selected.value = key; reader.value?.reveal(key); }
/** 问题及其关联规则在预览内定位；未关联问题仍可带回全文讨论。 */
function locateIssue({ issue, path }) { focusedIssue.value = issue; selectedRule.value = labels.value[path] ? path : ''; locate(selectedRule.value || 'issues'); }
</script>

<template>
  <Dialog :open="open" @update:open="emit('update:open', $event)">
    <DialogContent class="w-[calc(100vw-2rem)] max-w-none sm:max-w-none h-[calc(100dvh-2rem)] p-0 gap-0 flex flex-col overflow-hidden" :show-close-button="false">
      <header class="preview-header">
        <div><DialogTitle>PRD 完整预览</DialogTitle><DialogDescription>连续阅读全部模块与页面，关闭后返回原编辑位置。</DialogDescription></div>
        <div class="preview-actions"><Button size="sm" variant="outline" @click="emit('download')">下载 Markdown</Button><Button size="sm" @click="emit('update:open', false)">关闭预览</Button></div>
      </header>
      <div v-if="focusedIssue" class="preview-issue-context"><div><strong>{{ focusedIssue }}</strong><div class="preview-rule-links"><button v-for="path in issuePaths(snapshot.prd, focusedIssue)" :key="path" type="button" :aria-pressed="path === selectedRule" :disabled="!labels[path]" @click="locateIssue({ issue: focusedIssue, path })">{{ labels[path] || '原关联章节已移除' }}</button></div></div><Button v-if="canDiscuss" size="sm" @click="emit('discuss-issue', { issue: focusedIssue, path: selectedRule })">回到会话讨论</Button><span v-else class="muted text-xs">请先关闭预览并继续编辑草稿，再讨论修改。</span><Button size="sm" variant="ghost" aria-label="收起问题上下文" @click="focusedIssue = ''">收起</Button></div>
      <div class="preview-layout">
        <label class="preview-mobile-contents">跳转章节<SelectField :model-value="selected" aria-label="跳转章节" class="flex-1" placeholder="选择模块或页面"
          :options="contents.map(item => ({ value: item.key, label: `${item.level > 2 ? '　'.repeat(item.level - 2) : ''}${item.title}` }))" @update:model-value="locate" /></label>
        <nav class="preview-contents" aria-label="完整 PRD 目录">
          <p>文档目录</p><button v-for="item in contents" :key="item.key" type="button" :class="[`depth-${item.level}`, { selected: selected === item.key }]" :aria-current="selected === item.key ? 'location' : undefined" @click="locate(item.key)">{{ item.title }}</button>
        </nav>
        <div class="preview-scroll" tabindex="0" aria-label="完整 PRD 正文">
          <article class="preview-paper">
            <header class="preview-cover"><span>{{ snapshot.confirmed ? '已定稿' : '草稿 · 已保存' }} · 修订 {{ snapshot.revision }}<template v-if="snapshot.mode === 'demo'"> · 本地模板联调</template></span><h1>{{ snapshot.title }}</h1><p>产品需求文档 · PRD</p><p v-if="snapshot.prd.unresolvedIssues?.length">{{ snapshot.prd.unresolvedIssues.length }} 项待确认，详见文末及对应规则。</p></header>
            <PrdReadOnly ref="reader" :prd="snapshot.prd" interactive class="preview-document" @locate-issue="locateIssue" />
          </article>
        </div>
      </div>
    </DialogContent>
  </Dialog>
</template>

<style scoped>
.preview-issue-context { display: flex; align-items: center; gap: 12px; padding: 12px 24px; border-bottom: 1px solid var(--border); background: var(--accent); flex-wrap: wrap; max-height: 28dvh; overflow: auto; }.preview-issue-context > div { flex: 1; min-width: 180px; }.preview-issue-context strong { font-size: 13px; font-weight: 500; overflow-wrap: anywhere; }.preview-rule-links { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 6px; font-size: 12px; }.preview-rule-links button { color: var(--muted-foreground); text-align: left; }.preview-rule-links button[aria-pressed="true"] { color: var(--foreground); text-decoration: underline; }
.preview-header { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 18px 24px; border-bottom: 1px solid var(--border); }.preview-header [data-slot="dialog-description"] { margin-top: 6px; }.preview-actions { display: flex; flex-shrink: 0; gap: 8px; }
.preview-layout { display: grid; grid-template-columns: 220px minmax(0, 1fr); flex: 1; min-height: 0; }.preview-contents { overflow-y: auto; padding: 20px 12px; border-right: 1px solid var(--border); }.preview-contents p { padding: 0 12px 12px; font-size: 12px; color: var(--muted-foreground); }.preview-contents button { display: block; width: 100%; padding: 8px 12px; border-radius: 5px; font-size: 13px; text-align: left; overflow-wrap: anywhere; }.preview-contents button:hover, .preview-contents .selected { background: var(--accent); }.preview-contents .depth-3 { padding-left: 22px; font-weight: 600; }.preview-contents .depth-4 { padding-left: 32px; }
.preview-scroll { overflow: auto; background: var(--muted); padding: 24px; }.preview-paper { max-width: 960px; min-height: 100%; margin: 0 auto; padding: 44px 52px; background: var(--background); border: 1px solid var(--border); border-radius: 6px; }.preview-cover { padding-bottom: 30px; }.preview-cover span, .preview-cover p { font-size: 12px; color: var(--muted-foreground); }.preview-cover h1 { font-size: 28px; font-weight: 650; margin: 16px 0 8px; overflow-wrap: anywhere; }.preview-cover p + p { margin-top: 8px; }
/* 阅读窗口的章节层级更突出；历史抽屉仍沿用紧凑排版。 */
.preview-document :deep(section) { padding: 18px 0; scroll-margin-top: 20px; }.preview-document :deep(.level-2) { border-top: 1px solid var(--border); padding-top: 26px; margin-top: 16px; }.preview-document :deep(h2) { font-size: 21px; margin-bottom: 12px; }.preview-document :deep(h3) { font-size: 19px; margin-bottom: 10px; }.preview-document :deep(h4) { font-size: 16px; margin-bottom: 10px; }.preview-document :deep(h5) { font-size: 14px; margin-bottom: 10px; }.preview-document :deep(.level-4), .preview-document :deep(.level-5) { padding-left: 0; }
.preview-mobile-contents { display: none; padding: 12px 16px; gap: 12px; align-items: center; font-size: 12px; border-bottom: 1px solid var(--border); }
/* 窄窗口使用章节选择器，避免长目录横向滚动并占用正文宽度。 */
@media (max-width: 760px) { .preview-header { padding: 14px; flex-wrap: wrap; }.preview-layout { grid-template-columns: 1fr; grid-template-rows: auto minmax(0, 1fr); }.preview-contents { display: none; }.preview-mobile-contents { display: flex; }.preview-scroll { padding: 10px; }.preview-paper { padding: 24px 20px; }.preview-cover h1 { font-size: 23px; } }
</style>
