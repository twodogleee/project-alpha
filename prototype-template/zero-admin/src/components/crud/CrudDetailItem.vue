<template>
  <div class="crud-detail-item" :class="{ 'is-full': span === 'full' }">
    <dt class="crud-detail-item__label">{{ label }}</dt>
    <dd class="crud-detail-item__value">
      <slot>{{ normalizedValue }}</slot>
    </dd>
  </div>
</template>

<script setup>
const props = defineProps({
  label: {
    type: String,
    required: true,
  },
  value: {
    type: [String, Number, Boolean],
    default: "",
  },
  emptyText: {
    type: String,
    default: "—",
  },
  span: {
    type: [String, Number],
    default: 1,
    validator: (value) => value === "full" || Number(value) === 1,
  },
});

/** 将空字符串、null 和 undefined 统一转换为详情空值占位符。 */
const normalizedValue = computed(() => {
  if (props.value === "" || props.value === null || props.value === undefined) {
    return props.emptyText;
  }
  return String(props.value);
});
</script>

<style lang="scss">
.crud-detail-item {
  display: grid;
  grid-template-columns: minmax(112px, 132px) minmax(0, 1fr);
  min-width: 0;
  margin: 0;
  border-right: 1px solid var(--project-alpha-border);
  border-bottom: 1px solid var(--project-alpha-border);
}

.crud-detail-item__label,
.crud-detail-item__value {
  min-width: 0;
  margin: 0;
  padding: 10px var(--project-alpha-space-3);
  font-size: var(--project-alpha-font-size-sm);
  line-height: var(--project-alpha-line-height-base);
}

.crud-detail-item__label {
  display: flex;
  align-items: center;
  border-right: 1px solid var(--project-alpha-border);
  color: var(--project-alpha-text-secondary);
  background: var(--project-alpha-table-header-bg);
  font-weight: var(--project-alpha-font-weight-medium);
}

.crud-detail-item__value {
  overflow-wrap: anywhere;
  color: var(--project-alpha-text-primary);
  background: var(--project-alpha-panel-bg);
}

@media (max-width: 420px) {
  .crud-detail-item {
    grid-template-columns: minmax(96px, 116px) minmax(0, 1fr);
  }
}
</style>

