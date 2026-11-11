<script setup>
/** PRD 页面类型复用统一单选控件，枚举由模板维护，保存由调用方管理。@author Andersen */
import SelectField from '@/components/form/SelectField.vue';
import { computed } from 'vue';
import { pageTypesFor } from '@/workspace/prd-template.js';
/** 模板决定选项顺序，其他控件属性透传到统一单选组件。 */
const props = defineProps({ prd: Object });
/** 页面类型枚举转为公共控件的展示选项。 */
const options = computed(() => pageTypesFor(props.prd).map(([value, label]) => ({ value, label })));
</script>
<template>
  <SelectField :options="options" placeholder="选择页面类型" />
</template>
