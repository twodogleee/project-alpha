<template>
  <div class="crud-header">
    <div class="crud-header__main">
      <h4 class="crud-header__title">{{ resolvedTitle }}</h4>
      <div
        v-if="showActions && mode !== CRUD_MODE.DETAIL"
        class="crud-header__actions"
      >
        <slot v-if="customActions" :mode="mode" />
        <el-button
          v-else
          type="primary"
          plain
          :loading="saving"
          :disabled="saveDisabled"
          @click="emit('save')"
        >
          {{ saveText }}
        </el-button>
      </div>
    </div>
    <button
      class="crud-header__close"
      type="button"
      :aria-label="closeLabel"
      :disabled="saving"
      @click="emit('close')"
    >
      <el-icon :size="18"><Close /></el-icon>
    </button>
  </div>
</template>

<script setup>
import { Close } from "@element-plus/icons-vue";
import { CRUD_MODE, CRUD_MODE_TITLE, CRUD_MODE_VALUES } from "./crudModes.js";

const props = defineProps({
  mode: {
    type: String,
    default: CRUD_MODE.EDIT,
    validator: (value) => CRUD_MODE_VALUES.includes(value),
  },
  title: {
    type: String,
    default: "",
  },
  saving: {
    type: Boolean,
    default: false,
  },
  saveDisabled: {
    type: Boolean,
    default: false,
  },
  saveText: {
    type: String,
    default: "保存",
  },
  showActions: {
    type: Boolean,
    default: true,
  },
  customActions: {
    type: Boolean,
    default: false,
  },
  closeLabel: {
    type: String,
    default: "关闭",
  },
});

const emit = defineEmits(["save", "close"]);

/** 当前 CRUD 模式对应的标题，显式标题优先于默认模式标题。 */
const resolvedTitle = computed(() => {
  return props.title || CRUD_MODE_TITLE[props.mode];
});
</script>

<style lang="scss">
.crud-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--project-alpha-space-4);
  height: 56px;
  padding: 0 var(--project-alpha-space-5) 0 var(--project-alpha-space-6);
  border-bottom: 1px solid var(--project-alpha-border);
  background: var(--project-alpha-panel-bg);
}

.crud-header__main,
.crud-header__actions {
  display: flex;
  align-items: center;
}

.crud-header__main {
  min-width: 0;
  gap: 10px;
}

.crud-header__actions {
  flex: 0 0 auto;
  gap: var(--project-alpha-space-2);
}

.crud-header__actions .el-button {
  height: 30px;
  margin-left: 0;
  padding: 0 var(--project-alpha-space-3);
}

.crud-header__title {
  margin: 0;
  overflow: hidden;
  color: var(--project-alpha-text-primary);
  font-size: var(--project-alpha-font-size-lg);
  font-weight: var(--project-alpha-font-weight-semibold);
  line-height: var(--project-alpha-line-height-xl);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.crud-header__close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  width: 36px;
  height: 36px;
  padding: 0;
  border: 0;
  border-radius: var(--project-alpha-radius-sm);
  color: var(--project-alpha-text-secondary);
  background: transparent;
  cursor: pointer;
}

.crud-header__close:hover {
  color: var(--project-alpha-primary);
  background: var(--project-alpha-primary-soft);
}

@media (max-width: 640px) {
  .crud-header {
    padding-right: var(--project-alpha-space-3);
    padding-left: var(--project-alpha-space-4);
  }
}
</style>
