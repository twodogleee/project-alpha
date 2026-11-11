<script setup>
/** 修订来源、范围与实际校验结果的只读视图；不展示模型源码。@author Andersen */
import { computed } from 'vue';
import PrdReadOnly from '@/components/PrdReadOnly.vue';
import { prototypeFreshness, prototypeModeLabel, prototypeTime, prototypePageKey } from '@/workspace/prototype.js';
/** 修订使用自身冻结来源，不从当前 PRD 补全历史。 */
const props = defineProps({ revision: { type: Object, required: true } });
/** 覆盖范围名称按当时 PRD 的模块与页面解析。 */
const pages = computed(() => (props.revision.source?.scope?.pageRefs || []).map(ref => {
  const module = props.revision.source.prd?.modules?.find(item => item.id === ref.moduleId);
  return { key: prototypePageKey(ref), title: `${module?.title || ref.moduleId} / ${module?.pages?.find(item => item.id === ref.pageId)?.title || ref.pageId}` };
}));
/** 仅投影声明式校验项及明确缺口，不输出内部文件清单。 */
const validationCases = computed(() => props.revision.validation?.cases || []);
/** 缺口可能由结构化计划给出，也可能为明确纯文本。 */
function gapLabel(gap) { return typeof gap === 'string' ? gap : [gap.title || gap.pageId, gap.reason || gap.description || gap.message].filter(Boolean).join('：') || '存在尚未覆盖的需求'; }
/** 覆盖记录与冻结范围通过复合身份关联，不通过页面名称反查。 */
function coverageTitle(item) { return pages.value.find(page => page.key === prototypePageKey(item))?.title || item.title || item.pageId || '未标注页面'; }
</script>
<template>
  <div class="revision-info">
    <dl><div><dt>修订</dt><dd>r{{ revision.revisionNumber }} · {{ prototypeTime(revision.createdAt) }}</dd></div><div><dt>生成方式</dt><dd>{{ prototypeModeLabel(revision.mode) }}<span v-if="revision.model"> · {{ revision.model }}</span></dd></div><div><dt>PRD 来源</dt><dd>{{ revision.source?.kind === 'BASELINE' ? '已定稿 PRD' : 'PRD 草稿' }} · r{{ revision.source?.prdRevision }} · {{ prototypeFreshness[revision.freshness] || revision.freshness }}</dd></div></dl>
    <p class="summary">{{ revision.changeSummary || '此修订未附变更说明。' }}</p>
    <section v-if="revision.restoredFromRevisionId"><h3>历史恢复</h3><p>此修订从历史修订 {{ revision.restoredFromRevisionId }} 追加恢复。来源、生成方式和产物沿用目标修订；人工确认不继承。</p><p v-if="revision.parentRevisionId">恢复前当前修订：{{ revision.parentRevisionId }}</p></section>
    <section v-if="revision.latestConfirmation"><h3>人工确认记录</h3><p>确认时间：{{ prototypeTime(revision.latestConfirmation.createdAt) }}</p><p>绑定 PRD 定稿：{{ revision.latestConfirmation.prdBaselineId }}</p><p>确认范围：{{ revision.latestConfirmation.scope?.mode === 'ALL' ? '当时全部页面' : '指定页面' }} · {{ revision.latestConfirmation.scope?.pageRefs?.length || 0 }} 页</p><p>这是本修订的历史确认记录。当前是否有效，以工作区当前修订的确认状态为准。</p></section>
    <section><h3>冻结生成范围</h3><p>{{ revision.source?.scope?.mode === 'ALL' ? '当时全部页面' : '指定页面' }} · {{ pages.length }} 页</p><ul><li v-for="page in pages" :key="page.key">{{ page.title }}</li></ul></section>
    <section><h3>验证结果</h3><p>{{ revision.validation?.status === 'PASSED' ? '已通过构建与交互验证' : (revision.validation?.status || '暂无验证报告') }}</p><ul><li v-for="(item, index) in validationCases" :key="index">{{ item.name || item.title || `检查 ${index + 1}` }} · {{ item.status === 'PASSED' ? '通过' : item.status }}</li></ul><p v-for="(item,index) in revision.validation?.notVerified || []" :key="index">未验证：{{ gapLabel(item) }}</p></section>
    <section v-if="revision.manifest?.coverage?.length"><h3>页面覆盖</h3><ul><li v-for="(item,index) in revision.manifest.coverage" :key="index">{{ coverageTitle(item) }} · {{ item.status === 'IMPLEMENTED' ? '已实现' : item.status }}<span v-if="item.reason"> · {{ item.reason }}</span></li></ul></section>
    <section v-if="revision.manifest?.plan?.gaps?.length"><h3>明确未覆盖的能力</h3><ul><li v-for="(gap,index) in revision.manifest.plan.gaps" :key="index">{{ gapLabel(gap) }}</li></ul></section>
    <details v-if="revision.source?.prd"><summary>查看冻结的完整 PRD</summary><PrdReadOnly :prd="revision.source.prd" /></details>
  </div>
</template>
<style scoped>
.revision-info { display: grid; gap: 22px; font-size: 13px; line-height: 1.8; }.revision-info section { border-top: 1px solid var(--border); padding-top: 16px; }.revision-info h3 { font-weight: 600; margin-bottom: 6px; }.revision-info dl > div { display: grid; grid-template-columns: 76px 1fr; gap: 12px; margin-top: 6px; }.revision-info dt { color: var(--muted-foreground); }.revision-info ul { padding-left: 18px; list-style: disc; }.revision-info .summary, .revision-info dd, .revision-info li { white-space: pre-wrap; overflow-wrap: anywhere; }.revision-info summary { cursor: pointer; font-weight: 500; }
</style>
