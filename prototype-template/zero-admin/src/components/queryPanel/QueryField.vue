<template>
  <component
    :is="field.component"
    v-if="field.component"
    :model-value="modelValue"
    :field="field"
    v-bind="field.attrs"
    @update:model-value="emit('update:modelValue', $event)"
    @clear="emit('clear')"
    @change="emit('change', $event)"
    @error="emit('error', $event)"
  />

  <el-input
    v-else-if="normalizedType === 'input'"
    :model-value="modelValue"
    :clearable="clearable"
    :placeholder="placeholder"
    v-bind="field.attrs"
    @update:model-value="emit('update:modelValue', $event)"
    @clear="emit('clear')"
    @keyup.enter="emit('search')"
  />

  <el-input
    v-else-if="normalizedType === 'textarea'"
    :model-value="modelValue"
    type="textarea"
    :rows="field.rows || 3"
    :clearable="clearable"
    :placeholder="placeholder"
    v-bind="field.attrs"
    @update:model-value="emit('update:modelValue', $event)"
    @clear="emit('clear')"
  />

  <el-select
    v-else-if="normalizedType === 'select'"
    :model-value="modelValue"
    :clearable="clearable"
    :multiple="field.multiple"
    :filterable="field.filterable"
    :placeholder="placeholder"
    v-bind="field.attrs"
    @update:model-value="emit('update:modelValue', $event)"
    @clear="emit('clear')"
    @change="emit('change', $event)"
  >
    <el-option
      v-for="(option, index) in field.options || []"
      :key="getOptionValue(option) ?? index"
      :label="getOptionLabel(option)"
      :value="getOptionValue(option)"
      :disabled="getOptionDisabled(option)"
    />
  </el-select>

  <el-tree-select
    v-else-if="normalizedType === 'tree-select'"
    :model-value="modelValue"
    :data="field.options || field.data || []"
    :props="treeProps"
    :node-key="field.nodeKey || 'id'"
    :value-key="field.valueKey || 'id'"
    :multiple="field.multiple"
    :clearable="clearable"
    :filterable="field.filterable !== false"
    :check-strictly="field.checkStrictly !== false"
    :render-after-expand="field.renderAfterExpand === true"
    :placeholder="placeholder"
    v-bind="field.attrs"
    @update:model-value="emit('update:modelValue', $event)"
    @clear="emit('clear')"
    @change="emit('change', $event)"
  />

  <el-input-number
    v-else-if="normalizedType === 'number'"
    :model-value="modelValue"
    :controls="field.controls !== false"
    :min="field.min"
    :max="field.max"
    :step="field.step"
    :placeholder="placeholder"
    v-bind="field.attrs"
    @update:model-value="emit('update:modelValue', $event)"
    @change="emit('change', $event)"
  />

  <el-select
    v-else-if="normalizedType === 'boolean'"
    :model-value="modelValue"
    :clearable="clearable"
    :placeholder="placeholder"
    v-bind="field.attrs"
    @update:model-value="emit('update:modelValue', $event)"
    @clear="emit('clear')"
    @change="emit('change', $event)"
  >
    <el-option
      v-for="option in booleanOptions"
      :key="String(option.value)"
      :label="option.label"
      :value="option.value"
    />
  </el-select>

  <el-switch
    v-else-if="normalizedType === 'switch'"
    :model-value="modelValue"
    :active-value="field.activeValue ?? true"
    :inactive-value="field.inactiveValue ?? false"
    v-bind="field.attrs"
    @update:model-value="emit('update:modelValue', $event)"
    @change="emit('change', $event)"
  />

  <el-date-picker
    v-else-if="isDateType"
    :model-value="modelValue"
    :type="datePickerType"
    :clearable="clearable"
    :value-format="field.valueFormat || defaultDateFormat"
    :format="field.format || defaultDateFormat"
    :default-time="field.defaultTime || defaultTime"
    :placeholder="placeholder"
    :start-placeholder="field.startPlaceholder || '开始时间'"
    :end-placeholder="field.endPlaceholder || '结束时间'"
    v-bind="field.attrs"
    @update:model-value="emit('update:modelValue', $event)"
    @clear="emit('clear')"
    @change="emit('change', $event)"
  />

  <el-input
    v-else
    :model-value="modelValue"
    :clearable="clearable"
    :placeholder="placeholder"
    v-bind="field.attrs"
    @update:model-value="emit('update:modelValue', $event)"
    @clear="emit('clear')"
    @keyup.enter="emit('search')"
  />
</template>

<script setup>
const props = defineProps({
  modelValue: {
    type: [String, Number, Boolean, Array, Object, Date],
    default: undefined,
  },
  field: {
    type: Object,
    required: true,
  },
});

const emit = defineEmits([
  "update:modelValue",
  "search",
  "clear",
  "change",
  "error",
]);

const defaultTime = [
  new Date(1970, 0, 1, 0, 0, 0),
  new Date(1970, 0, 1, 23, 59, 59),
];
const defaultTreeProps = Object.freeze({
  label: "label",
  children: "children",
  disabled: "disabled",
});
const typeAliases = Object.freeze({
  selectTree: "tree-select",
  treeSelect: "tree-select",
});

/** 优先使用正式 treeProps，并兼容历史 defaultProps。 */
const treeProps = computed(() => {
  return props.field.treeProps || props.field.defaultProps || defaultTreeProps;
});

/** 解析正式选项字段映射，并兼容历史 optionkey 数组。 */
const optionProps = computed(() => {
  const legacyKeys = Array.isArray(props.field.optionkey)
    ? props.field.optionkey
    : [];
  return {
    value: props.field.optionProps?.value || legacyKeys[0] || "value",
    label: props.field.optionProps?.label || legacyKeys[1] || "label",
    disabled:
      props.field.optionProps?.disabled || legacyKeys[2] || "disabled",
  };
});

/** 读取查询选项的提交值。 */
const getOptionValue = (option) => option?.[optionProps.value.value];

/** 读取查询选项的展示文案。 */
const getOptionLabel = (option) => option?.[optionProps.value.label];

/** 读取查询选项的禁用状态。 */
const getOptionDisabled = (option) => option?.[optionProps.value.disabled];

/** 兼容 MES/IoT 历史查询类型别名。 */
const normalizedType = computed(() => {
  const type = props.field.type || props.field.searchType || "input";
  return typeAliases[type] || type;
});

/** 日期及日期范围类型交给统一日期选择器渲染。 */
const isDateType = computed(() => [
  "date",
  "datetime",
  "daterange",
  "datetimerange",
  "month",
  "year",
].includes(normalizedType.value));

/** 旧 date 查询保持日期时间范围语义，显式 pickerType 可以覆盖。 */
const datePickerType = computed(() => {
  if (props.field.pickerType) {
    return props.field.pickerType;
  }
  return normalizedType.value === "date" ? "datetimerange" : normalizedType.value;
});

/** 根据日期类型提供不丢失时间精度的默认序列化格式。 */
const defaultDateFormat = computed(() => {
  return ["datetime", "datetimerange"].includes(datePickerType.value)
    ? "YYYY-MM-DD HH:mm:ss"
    : "YYYY-MM-DD";
});

/** 未显式关闭时，查询字段默认允许清空。 */
const clearable = computed(() => props.field.clearable !== false);

/** 根据输入型或选择型字段生成默认提示。 */
const placeholder = computed(() => {
  if (props.field.compactPlaceholder !== undefined) return props.field.compactPlaceholder;
  if (props.field.placeholder !== undefined) {
    return props.field.placeholder;
  }
  if (props.field.searchPlaceholder !== undefined) {
    return props.field.searchPlaceholder;
  }
  return ["input", "textarea"].includes(normalizedType.value)
    ? `请输入${props.field.label}`
    : `请选择${props.field.label}`;
});

/** 布尔查询默认提供两种稳定取值。 */
const booleanOptions = computed(() => props.field.options || [
  { label: "是", value: true },
  { label: "否", value: false },
]);
</script>

<style lang="scss" scoped>
.el-input-number,
.el-tree-select {
  width: 100%;
}
</style>
