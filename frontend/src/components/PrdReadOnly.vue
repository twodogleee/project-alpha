<script setup>
/** 全文预览、历史和过期候选共用只读投影，不渲染外部 HTML。@author Andersen */
import { computed, ref } from 'vue';
import PrdContent from './PrdContent.vue';
import { prdLabels, prdTemplate } from '@/workspace/prd-template.js';
import { documentBlocks } from '@/workspace/prd-template.js';
import { prdSections } from '@/workspace/lifecycle.js';
import { issuePaths } from '@/workspace/prd-reader.js';
/** 只读文档来自已保存快照或模型候选，不写回当前稿。 */
const props = defineProps({ prd: { type: Object, required: true }, interactive: Boolean });
/** 预览可按明确关联跳转，历史和过期结果默认保持静态只读。 */
const emit = defineEmits(['locate-issue']);
/** 定位限定在当前文档，避免历史面板中的同名章节互相干扰。 */
const documentRoot = ref(null);
/** 根据快照自身版本投影，避免旧定稿套用新格式。 */
const blocks = computed(() => documentBlocks(props.prd, prdSections));
/** 历史问题位置使用快照自己的名称，不引用当前改名后的目录。 */
const labels = computed(() => prdLabels(props.prd));
/** 目录使用稳定路径定位，外部文本不参与选择器拼接。 */
function reveal(key) { Array.from(documentRoot.value?.querySelectorAll('[data-prd-section]') || []).find(element => element.dataset.prdSection === key)?.scrollIntoView({ block: 'start' }); }
defineExpose({ reveal });
</script>
<template>
  <div ref="documentRoot" class="prd-readonly">
    <p class="muted text-xs">模板：{{ prdTemplate(prd).name }} · v{{ prdTemplate(prd).revision }}</p>
    <section v-for="block in blocks" :key="block.key" :data-prd-section="block.key" :class="`level-${block.level}`"><component :is="`h${block.level}`">{{ block.title }}</component><PrdContent v-if="block.text" :text="block.text" /><p v-else-if="block.text !== undefined" class="muted">尚未填写</p></section>
    <section data-prd-section="issues" class="level-2"><h2>待确认事项</h2><p v-if="!prd.unresolvedIssues?.length">无</p><div v-for="issue in prd.unresolvedIssues" :key="issue" class="readonly-issue"><button v-if="interactive" type="button" class="issue-title" @click="emit('locate-issue', { issue, path: issuePaths(prd, issue)[0] })">{{ issue }} ↗</button><p v-else>{{ issue }}</p><div class="issue-targets"><template v-for="path in issuePaths(prd, issue)" :key="path"><button v-if="interactive && labels[path]" type="button" @click="emit('locate-issue', { issue, path })">{{ labels[path] }} ↗</button><small v-else>{{ labels[path] || '原关联章节已移除' }}</small></template><small v-if="!issuePaths(prd, issue).length" class="muted">尚未关联具体规则，可回到会话补充。</small></div></div></section>
    <template v-if="prd.suggestions?.length"><h3>当时的待采纳建议</h3><p v-for="suggestion in prd.suggestions" :key="suggestion.id">{{ suggestion.text }}</p></template>
  </div>
</template>
<style scoped>
section { padding: 12px 0; }
h2, h3, h4, h5 { font-weight: 600; font-size: 14px; }
p { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 13px; line-height: 1.8; margin-top: 8px; }
.level-3 { border-top: 1px solid var(--border); }.level-4, .level-5 { padding-left: 12px; }
.readonly-issue { margin-top: 16px; }.issue-title { text-align: left; font-size: 14px; line-height: 1.8; overflow-wrap: anywhere; }.issue-title:hover, .issue-targets button:hover { text-decoration: underline; }.issue-targets { display: grid; gap: 6px; margin-top: 8px; font-size: 12px; color: var(--muted-foreground); }.issue-targets button { text-align: left; overflow-wrap: anywhere; }
</style>
