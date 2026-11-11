<template>
  <StatusTag
    v-if="column.type === 'status'"
    :value="value"
    :options="column.option || column.options"
    :fallback-type="column.fallbackType || 'info'"
  />

  <span v-else-if="column.type === 'select'">
    {{ resolveStatusLabel(value, column.option || column.options || []) }}
  </span>

  <el-image
    v-else-if="column.type === 'image' && hasDisplayValue(value)"
    class="table-cell__image"
    :src="value"
    :preview-src-list="[value]"
    :preview-teleported="true"
  />

  <el-progress
    v-else-if="column.type === 'progress' && hasDisplayValue(value)"
    :text-inside="true"
    :stroke-width="15"
    :percentage="Number(value) || 0"
  />

  <el-switch
    v-else-if="column.type === 'switch'"
    disabled
    :model-value="value"
    size="small"
  />

  <span v-else-if="column.type === 'svg'" class="table-cell__icon">
    <svg-icon
      v-if="String(value || '').includes('icon')"
      :name="String(value).replace('icon-w', 'icon-')"
      :size="1.4"
    />
    <el-icon v-else-if="iconComponent" :size="16">
      <component :is="iconComponent" />
    </el-icon>
    <span v-else>-</span>
  </span>

  <slot v-else-if="column.type === 'Array'" name="array" :value="value" />

  <span v-else-if="column.type === 'routeOrPerms'">
    <el-tag v-if="row.menuType == 1" type="primary">
      {{ row.component || "-" }}
    </el-tag>
    <el-tag v-else-if="row.menuType == 2" type="warning">
      {{ row.perms || "-" }}
    </el-tag>
    <span v-else class="table-cell__empty">-</span>
  </span>

  <span v-else-if="column.copy" class="table-cell__copyable">
    <span class="table-cell__copyable-text">{{ formatDisplayValue(value) }}</span>
    <CopyAction :value="value" />
  </span>

  <span v-else>{{ formatDisplayValue(value) }}</span>
</template>

<script setup>
import * as icons from "@element-plus/icons-vue";
import StatusTag from "@/components/data/StatusTag.vue";
import {
  formatDisplayValue,
  hasDisplayValue,
} from "@/components/data/displayValue.js";
import { resolveStatusLabel } from "@/components/data/status.js";
import SvgIcon from "@/components/svgIcon/index.vue";
import CopyAction from "./CopyAction.vue";

const props = defineProps({
  value: {
    default: undefined,
  },
  row: {
    type: Object,
    required: true,
  },
  column: {
    type: Object,
    required: true,
  },
});

/** 将 Element Plus 图标名称解析为对应组件。 */
const iconComponent = computed(() => icons[String(props.value || "")] || null);
</script>

<style lang="scss" scoped>
.table-cell__image {
  width: 30px;
  height: 30px;
}

.table-cell__icon {
  display: inline-flex;
  align-items: center;
}

.table-cell__empty {
  color: var(--project-alpha-text-muted);
}

.table-cell__copyable {
  display: inline-flex;
  align-items: center;
  max-width: 100%;
  gap: var(--project-alpha-space-1);
}

.table-cell__copyable-text {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
