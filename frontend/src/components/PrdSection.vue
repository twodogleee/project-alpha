<script setup>
/** 单块 PRD 默认阅读，编辑时立即通知父级自动保存，无独立保存副本。@author Andersen */
import { computed, ref, nextTick } from 'vue';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Input } from '@/components/ui/input';
import PrdContent from './PrdContent.vue';
import { editableTable, tableMarkdown } from '@/workspace/prd-reader.js';
/** path 为稳定章节键，issues 为明确关联问题，located 仅控制正文定位高亮。 */
const props = defineProps({ path: String, title: String, text: { type: String, default: '' }, disabled: Boolean, located: Boolean, issues: { type: Array, default: () => [] }, hint: String, maxlength: { type: Number, default: 10000 } });
/** 修改与讨论独立，阅读切换不触发业务写入。 */
const emit = defineEmits(['change', 'discuss', 'issue']);
/** 局部编辑状态与输入区引用不写入 PRD。 */
const editing = ref(false), raw = ref(false), editor = ref(null);
/** 只有完整单表使用单元格编辑，混合正文使用原文，防止吞掉段落。 */
const table = computed(() => editableTable(props.text));
/** 首次编辑聚焦当前块的首个输入，便于键盘操作。 */
async function edit() { if (props.disabled) return; editing.value = true; await nextTick(); editor.value?.querySelector('textarea,input')?.focus(); }
/** 表格单元格立即合并至完整章节，沿用父级串行自动保存。 */
function cell(row, column, value) { const next = structuredClone(table.value); (row === -1 ? next.headers : next.rows[row])[column] = value; emit('change', tableMarkdown(next)); }
/** 新增或移除表格行保持列数一致，不影响其他章节。 */
function row(index) { const next = structuredClone(table.value); if (index === undefined) next.rows.push(next.headers.map(() => '')); else next.rows.splice(index, 1); emit('change', tableMarkdown(next)); }
/** 供模块空态直接进入本章节，保存仍经原 change 事件完成。 */
defineExpose({ edit });
</script>
<template>
  <section class="prd-block" :data-section="path" tabindex="-1" :class="{ editing, located }">
    <div class="block-heading"><h3>{{ title }}</h3><div class="block-tools"><Button v-if="!disabled" size="sm" variant="ghost" @click="editing ? editing = false : edit()">{{ editing ? '完成编辑' : '编辑' }}</Button><Button size="sm" variant="ghost" @click="emit('discuss', path)">讨论</Button></div></div>
    <div v-if="editing && !disabled" ref="editor" class="block-editor">
      <template v-if="table && !raw"><div class="editable-table"><table><thead><tr><th v-for="(header, column) in table.headers" :key="column"><Input :model-value="header" :aria-label="`${title} 第 ${column + 1} 列名称`" @update:model-value="cell(-1, column, $event)" /></th><th><span class="sr-only">操作</span></th></tr></thead><tbody><tr v-for="(values, index) in table.rows" :key="index"><td v-for="(value, column) in values" :key="column"><Input :model-value="value" :aria-label="`${title} 第 ${index + 1} 行 ${table.headers[column]}`" @update:model-value="cell(index, column, $event)" /></td><td><Button variant="ghost" size="sm" :aria-label="`删除${title}第 ${index + 1} 行`" @click="row(index)">删除</Button></td></tr></tbody></table></div><Button variant="outline" size="sm" @click="row()">添加行</Button></template>
      <Textarea v-else :model-value="text" :aria-label="`编辑${title}`" :maxlength="maxlength" :placeholder="hint || '输入业务说明，未明确的规则保留待确认。'" rows="5" @update:model-value="emit('change', $event)" />
      <div class="editor-note"><span>修改后自动保存</span><Button v-if="table" variant="ghost" size="sm" @click="raw = !raw">{{ raw ? '单元格编辑' : '编辑表格原文' }}</Button></div>
    </div>
    <PrdContent v-else-if="text.trim()" :text="text" />
    <p v-else class="empty-block">尚未补充，可通过会话完善。</p>
    <div v-if="issues.length" class="block-issues"><button v-for="issue in issues" :key="issue" type="button" @click="emit('issue', issue)">待确认 · {{ issue }}</button></div>
  </section>
</template>
<style scoped>
.prd-block { margin: 0 0 30px; scroll-margin-top: 16px; } .block-heading { display: flex; gap: 8px; align-items: center; justify-content: space-between; margin-bottom: 8px; }
h3 { font-size: 15px; font-weight: 600; } .block-tools { display: flex; color: var(--muted-foreground); } .block-tools button { height: 28px; font-size: 12px; padding: 0 8px; }
.block-editor { background: var(--muted); border-radius: 8px; padding: 12px; } .block-editor textarea { background: var(--background); line-height: 1.8; }
.editor-note { display: flex; align-items: center; justify-content: space-between; font-size: 12px; color: var(--muted-foreground); margin-top: 8px; }
.empty-block { color: var(--muted-foreground); font-size: 13px; padding: 6px 0; }.block-issues { display: grid; gap: 6px; margin-top: 10px; }
.block-issues button { text-align: left; font-size: 12px; color: var(--foreground); background: var(--accent); padding: 7px 10px; border-radius: 6px; overflow-wrap: anywhere; }
.editable-table { overflow-x: auto; margin-bottom: 10px; } table { width: 100%; border-collapse: collapse; } th,td { padding: 3px; min-width: 140px; } th:last-child,td:last-child { min-width: 55px; }
.prd-block.located { background: var(--accent); box-shadow: -4px 0 0 var(--primary); border-radius: 4px; padding: 12px; }.prd-block:focus-visible { outline: 2px solid var(--ring); outline-offset: 4px; }
</style>
