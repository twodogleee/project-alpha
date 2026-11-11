<script setup>
/** 以 Vue 文本插值呈现 PRD 表格、列表与段落，模型输出不能执行 HTML。@author Andersen */
import { computed } from 'vue';
import { parsePrdText } from '@/workspace/prd-reader.js';
/** 源文本保持在上层文档中，本组件只负责阅读。 */
const props = defineProps({ text: { type: String, default: '' } });
/** 每次正文更新后重新投影安全阅读块。 */
const blocks = computed(() => parsePrdText(props.text));
</script>
<template>
  <div class="prd-content">
    <template v-for="(block, index) in blocks" :key="index">
      <div v-if="block.type === 'table'" class="prd-table"><table><thead><tr><th v-for="(cell, column) in block.headers" :key="column">{{ cell }}</th></tr></thead><tbody><tr v-for="(row, rowIndex) in block.rows" :key="rowIndex"><td v-for="(cell, column) in row" :key="column">{{ cell }}</td></tr></tbody></table></div>
      <ul v-else-if="block.type === 'list'"><li v-for="(item, itemIndex) in block.items" :key="itemIndex">{{ item }}</li></ul>
      <h4 v-else-if="block.type === 'heading'">{{ block.text }}</h4>
      <p v-else>{{ block.text }}</p>
    </template>
  </div>
</template>
<style scoped>
.prd-content { font-size: 14px; line-height: 1.85; overflow-wrap: anywhere; }
p { white-space: pre-wrap; margin: 8px 0; } h4 { font-weight: 600; margin: 16px 0 8px; }
ul { padding-left: 20px; list-style: disc; margin: 10px 0; } li { margin: 5px 0; }
.prd-table { overflow-x: auto; margin: 10px 0; } table { border-collapse: collapse; width: 100%; font-size: 13px; }
th, td { text-align: left; padding: 10px 12px; border-bottom: 1px solid var(--border); vertical-align: top; min-width: 85px; }
th { background: var(--muted); font-weight: 500; } td { white-space: pre-wrap; }
</style>
