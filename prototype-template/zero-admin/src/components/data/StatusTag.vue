<template>
  <span v-if="!hasValue" class="status-tag__empty">{{ emptyText }}</span>
  <el-tag
    v-else
    class="status-tag"
    :type="resolvedType"
    :effect="effect"
    :size="size"
    :round="round"
  >
    <span v-if="dot" class="status-tag__dot" aria-hidden="true"></span>
    <slot :value="value" :option="resolvedOption">{{ resolvedLabel }}</slot>
  </el-tag>
</template>

<script setup>
import { hasDisplayValue } from "./displayValue.js";
import {
  resolveStatusLabel,
  resolveStatusOption,
  resolveStatusType,
} from "./status.js";

const props = defineProps({
  value: {
    type: [String, Number, Boolean],
    default: undefined,
  },
  options: {
    type: Array,
    default: () => [],
  },
  type: {
    type: String,
    default: "",
  },
  fallbackType: {
    type: String,
    default: "info",
  },
  emptyText: {
    type: String,
    default: "-",
  },
  effect: {
    type: String,
    default: "light",
  },
  size: {
    type: String,
    default: "small",
  },
  round: {
    type: Boolean,
    default: false,
  },
  dot: {
    type: Boolean,
    default: false,
  },
});

/** 当前状态是否存在可展示值。 */
const hasValue = computed(() => hasDisplayValue(props.value));

/** 当前状态匹配到的字典选项。 */
const resolvedOption = computed(() => {
  return resolveStatusOption(props.value, props.options);
});

/** 当前状态的稳定展示文案。 */
const resolvedLabel = computed(() => {
  return resolveStatusLabel(props.value, props.options, props.emptyText);
});

/** 显式类型优先于状态字典和默认推断。 */
const resolvedType = computed(() => {
  return props.type || resolveStatusType(
    props.value,
    props.options,
    props.fallbackType
  );
});
</script>

<style lang="scss" scoped>
.status-tag {
  display: inline-flex;
  align-items: center;
  gap: var(--project-alpha-space-1);
}

.status-tag__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}

.status-tag__empty {
  color: var(--project-alpha-text-muted);
}
</style>
