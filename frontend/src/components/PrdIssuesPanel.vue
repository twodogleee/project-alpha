<script setup>
/** 问题与正文路径显式关联；讨论完善正文后可手动解决，所有变化复用 PRD 自动保存与历史。@author Andersen */
import { computed, ref } from 'vue';
import { Check } from '@lucide/vue';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Checkbox } from '@/components/ui/checkbox';
import SelectField from '@/components/form/SelectField.vue';
import { issuePaths } from '@/workspace/prd-reader.js';
import { prdLabels, globalSections } from '@/workspace/prd-template.js';
/** 全部问题来自同一份 PRD；旧问题未携带关联时保留未关联状态。 */
const props = defineProps({ prd: { type: Object, required: true }, focused: String, disabled: Boolean, globals: { type: Array, default: () => globalSections } });
/** 变更走父级自动保存，讨论走同一个会话。 */
const emit = defineEmits(['change', 'discuss', 'locate']);
/** 新问题只在用户添加时创建；关联选择不自动生成业务结论。 */
const adding = ref(false), text = ref(''), target = ref(''), linking = ref('');
/** 只反馈本地操作，持久化成功或失败仍以工作区统一保存状态为准。 */
const resolutionNotice = ref('');
/** 所有可关联正文章节，包括未填写的可选规则；目录节点不可作为规则路径。 */
const labels = computed(() => prdLabels(props.prd, props.globals));
const targets = computed(() => Object.entries(labels.value).filter(([key]) => !key.includes(':') || key.split(':').length === (key.startsWith('page:') ? 4 : 3)));
/** 当前点击问题优先展示，其余问题仍可查看。 */
const issues = computed(() => [...props.prd.unresolvedIssues].sort((a, b) => Number(b === props.focused) - Number(a === props.focused)));
/** 独立副本与现有保存队列保持一致。 */
function update(work) { if (props.disabled) return; const next = JSON.parse(JSON.stringify(props.prd)); next.issueLinks ||= {}; work(next); emit('change', next); }
/** 每次只增删当前勾选路径，保留其他关联；全部取消即可清空关联。 */
function link(issue, path, checked) { const paths = new Set(issuePaths(props.prd, issue)); if (checked) paths.add(path); else paths.delete(path); update(next => { next.issueLinks = { ...next.issueLinks, [issue]: [...paths] }; }); }
/** 添加问题不创建占位正文，不允许重复问题或超出契约上限。 */
function add() { const issue = text.value.trim(); if (!issue || props.prd.unresolvedIssues.includes(issue)) return; update(next => { next.unresolvedIssues.push(issue); next.issueLinks = { ...next.issueLinks, [issue]: target.value ? [target.value] : [] }; }); text.value = ''; adding.value = false; }
/** 移出当前清单并清理关联，不改写正文；旧问题及关联保留在 PRD 保存历史中。 */
function remove(issue) { update(next => { next.unresolvedIssues = next.unresolvedIssues.filter(item => item !== issue); delete next.issueLinks[issue]; }); }
/** 用户确认正文已经落实后直接解决，不增加二次确认或再次调用模型。 */
function resolve(issue) {
  if (props.disabled || !props.prd.unresolvedIssues.includes(issue)) return;
  remove(issue);
  if (linking.value === issue) linking.value = '';
  resolutionNotice.value = '已标记为已解决，移出待确认列表。保存结果请查看页面顶部，原问题可在历史版本中查看。';
}
</script>
<template>
  <div class="issues-panel"><p class="muted text-sm">讨论并确认正文已完善后，可直接点击“已解决”。此操作只结束待确认问题，不会改写正文，变更随 PRD 自动保存。</p>
    <p v-if="resolutionNotice" class="resolution-notice" role="status">{{ resolutionNotice }}</p>
    <p v-if="!issues.length" class="muted text-sm py-6">当前没有待确认事项。</p>
    <article v-for="issue in issues" :key="issue" :class="{ focused: issue === focused }"><h3>{{ issue }}</h3><div class="issue-links"><button v-for="path in issuePaths(prd, issue)" :key="path" type="button" @click="emit('locate', path)">{{ labels[path] || '原关联章节已移除' }} ↗</button><span v-if="!issuePaths(prd, issue).length" class="muted text-xs">尚未关联具体规则</span></div>
      <div v-if="!disabled" class="issue-actions"><Button size="sm" variant="outline" @click="emit('discuss', issue)">回答 / 讨论</Button><Button size="sm" variant="secondary" @click="resolve(issue)"><Check class="size-3.5" />已解决</Button><Button size="sm" variant="ghost" @click="linking = linking === issue ? '' : issue">{{ linking === issue ? '完成关联' : '关联规则' }}</Button><details><summary>更多</summary><p class="muted text-xs">重复、误生成或不属于本期范围的问题可以删除，正文保持不变。</p><Button variant="ghost" size="sm" @click="remove(issue)">删除此问题</Button></details></div>
      <fieldset v-if="linking === issue && !disabled" class="link-picker"><legend>关联章节（可多选）</legend><div class="link-options"><label v-for="[path, label] in targets" :key="path" class="link-option"><Checkbox :model-value="issuePaths(prd, issue).includes(path)" :aria-label="label" @update:model-value="link(issue, path, $event === true)" /><span>{{ label }}</span></label></div></fieldset>
    </article>
    <form v-if="adding && !disabled" class="issue-add" @submit.prevent="add"><label for="new-prd-question">待确认问题</label><Textarea id="new-prd-question" v-model="text" maxlength="4000" rows="3" /><label for="new-prd-question-target">关联规则</label><SelectField id="new-prd-question-target" v-model="target" :options="[{ value: '', label: '暂不关联' }, ...targets.map(([value, label]) => ({ value, label }))]" /><div class="issue-actions"><Button type="submit" size="sm" :disabled="!text.trim() || prd.unresolvedIssues.includes(text.trim())">添加</Button><Button type="button" size="sm" variant="ghost" @click="adding = false">取消</Button></div></form>
    <Button v-else-if="!disabled" variant="outline" size="sm" :disabled="issues.length >= 100" @click="adding = true">添加待确认事项</Button>
  </div>
</template>
<style scoped>
article { padding: 20px 0; border-bottom: 1px solid var(--border); } article.focused { border-left: 3px solid var(--primary); padding-left: 12px; } h3 { font-size: 14px; font-weight: 500; white-space: pre-wrap; overflow-wrap: anywhere; }.issue-links { display: grid; gap: 6px; margin: 10px 0; }.issue-links button { font-size: 12px; color: var(--muted-foreground); text-align: left; }.issue-actions { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }.issue-actions details { font-size: 12px; color: var(--muted-foreground); } summary { cursor: pointer; }
.link-picker, .issue-add { display: grid; gap: 10px; margin-top: 16px; font-size: 13px; } .issues-panel > button { margin-top: 20px; }
.resolution-notice { margin-top: 12px; padding: 10px 12px; border: 1px solid var(--border); border-radius: 6px; background: var(--muted); color: var(--muted-foreground); font-size: 12px; line-height: 1.6; }
.link-options { max-height: 240px; overflow-y: auto; padding: 4px; border: 1px solid var(--border); border-radius: 6px; }.link-option { display: flex; gap: 10px; align-items: flex-start; padding: 8px; border-radius: 4px; cursor: pointer; }.link-option:hover, .link-option:focus-within { background: var(--accent); }.link-option span { min-width: 0; overflow-wrap: anywhere; }.link-option :deep([data-slot="checkbox"]) { margin-top: 2px; }
</style>
