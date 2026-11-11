<template>
  <el-popover
    ref="popoverRef"
    :placement="placement"
    trigger="click"
    :width="width"
    :popper-class="popperClass"
    @before-enter="handleBeforeEnter"
    @before-leave="handleBeforeLeave"
  >
    <template #reference>
      <el-button
        :icon="Setting"
        circle
        :title="buttonTitle"
        :aria-label="buttonTitle"
      />
    </template>

    <div class="field-settings">
      <div class="field-settings__header">
        <div class="field-settings__header-top">
          <span class="field-settings__title">{{ title }}</span>
          <span v-if="isDirty" class="field-settings__unsaved">未保存</span>
        </div>
        <span v-if="description" class="field-settings__description">
          {{ description }}
        </span>
      </div>

      <el-checkbox-group
        v-model="draftModel"
        class="field-settings__list"
        :style="fieldListStyle"
      >
        <el-checkbox
          v-for="item in normalizedItems"
          :key="item.key"
          class="field-settings__option"
          :class="{ 'is-required': item.required }"
          :label="item.key"
          :value="item.key"
          :disabled="isOptionDisabled(item)"
        >
          <span class="field-settings__option-content">
            <span class="field-settings__option-label">{{ item.label }}</span>
            <span
              v-if="item.required"
              class="field-settings__required-hint"
            >
              固定
            </span>
          </span>
        </el-checkbox>
      </el-checkbox-group>

      <div class="field-settings__footer">
        <el-button
          link
          type="primary"
          size="small"
          :icon="RefreshLeft"
          title="恢复默认"
          aria-label="恢复默认"
          @click="resetDraft"
        >
          恢复默认
        </el-button>
        <el-button
          class="field-settings__save-button"
          :type="isSavedFeedback ? 'success' : 'primary'"
          size="small"
          :icon="isSavedFeedback ? CircleCheck : DocumentChecked"
          :disabled="!isDirty && !isSavedFeedback"
          :aria-label="isSavedFeedback ? '已保存' : '保存设置'"
          @click="saveDraft"
        >
          {{ isSavedFeedback ? "已保存" : "保存设置" }}
        </el-button>
      </div>
    </div>
  </el-popover>
</template>

<script setup>
import {
  CircleCheck,
  DocumentChecked,
  RefreshLeft,
  Setting,
} from "@element-plus/icons-vue";

const props = defineProps({
  modelValue: {
    type: Array,
    default: () => [],
  },
  items: {
    type: Array,
    default: () => [],
  },
  defaultValue: {
    type: Array,
    default: () => [],
  },
  title: {
    type: String,
    default: "字段设置",
  },
  description: {
    type: String,
    default: "选择需要显示的字段",
  },
  buttonTitle: {
    type: String,
    default: "字段设置",
  },
  placement: {
    type: String,
    default: "bottom-end",
  },
  width: {
    type: [String, Number],
    default: 320,
  },
  popperClass: {
    type: String,
    default: "field-settings-popper",
  },
  minSelected: {
    type: Number,
    default: 1,
  },
  listColumns: {
    type: Number,
    default: 2,
  },
});

const emit = defineEmits(["update:modelValue", "save"]);
const popoverRef = ref();
const draftValue = ref([]);
const isSavedFeedback = ref(false);
let saveCloseTimer;

/** 过滤重复或缺少稳定键的字段设置项。 */
const normalizedItems = computed(() => {
  const keys = new Set();
  return props.items
    .filter((item) => {
      const key = String(item?.key || "").trim();
      if (!key || keys.has(key)) {
        return false;
      }
      keys.add(key);
      return true;
    })
    .map((item) => ({
      ...item,
      key: String(item.key),
      label: String(item.label || item.key),
      required: item.required === true,
    }));
});

/** 当前设置项允许使用的稳定字段键。 */
const availableKeys = computed(() => normalizedItems.value.map((item) => item.key));

/** 计算当前设置必须保留的最少字段数。 */
const normalizedMinSelected = computed(() => {
  return Math.min(
    Math.max(Number(props.minSelected) || 0, 0),
    availableKeys.value.length
  );
});

/** 字段选项列表使用的列数，限制在一至四列以避免弹层内容失衡。 */
const normalizedListColumns = computed(() => {
  return Math.min(Math.max(Math.trunc(Number(props.listColumns) || 1), 1), 4);
});

/** 把列表列数传递给样式变量，供查询设置和表格设置选择不同排布。 */
const fieldListStyle = computed(() => ({
  "--field-settings-list-columns": normalizedListColumns.value,
}));

/**
 * 按设置项顺序规范化选中字段，并补齐必选字段和最少选中数量。
 *
 * @param {Array<string>} value 待规范化的字段键
 * @param {Array<string>} fallback 选中数量不足时使用的回退字段键
 * @returns {Array<string>} 可安全应用的字段键
 */
const normalizeSelection = (value = [], fallback = []) => {
  const availableSet = new Set(availableKeys.value);
  const selectedSet = new Set(
    value.map(String).filter((key) => availableSet.has(key))
  );

  normalizedItems.value
    .filter((item) => item.required)
    .forEach((item) => selectedSet.add(item.key));

  const candidates = [...fallback, ...availableKeys.value]
    .map(String)
    .filter((key) => availableSet.has(key));
  for (const key of candidates) {
    if (selectedSet.size >= normalizedMinSelected.value) {
      break;
    }
    selectedSet.add(key);
  }

  return availableKeys.value.filter((key) => selectedSet.has(key));
};

/** 已正式保存并由调用方应用的字段键。 */
const appliedValue = computed(() => {
  return normalizeSelection(props.modelValue, props.defaultValue);
});

/** 设置面板中的临时草稿，只有保存时才向调用方提交。 */
const draftModel = computed({
  /** 读取字段设置面板中的当前草稿。 */
  get() {
    return draftValue.value;
  },
  /** 规范化并更新字段设置面板中的临时草稿。 */
  set(value) {
    draftValue.value = normalizeSelection(value, draftValue.value);
  },
});

/** 判断草稿是否与已应用设置不同。 */
const isDirty = computed(() => {
  return (
    draftValue.value.length !== appliedValue.value.length ||
    draftValue.value.some((key, index) => key !== appliedValue.value[index])
  );
});

/** 判断指定字段是否因必选或最少选中约束而不可取消。 */
const isOptionDisabled = (item) => {
  if (item.required) {
    return true;
  }
  return (
    draftValue.value.includes(item.key) &&
    draftValue.value.length <= normalizedMinSelected.value
  );
};

/** 将面板草稿恢复为当前已正式应用的设置。 */
const syncDraft = () => {
  draftValue.value = [...appliedValue.value];
};

/** 打开设置面板前丢弃历史草稿并清理保存反馈。 */
const handleBeforeEnter = () => {
  isSavedFeedback.value = false;
  syncDraft();
};

/** 未保存关闭时丢弃草稿，确保下次打开仍从已应用设置开始。 */
const handleBeforeLeave = () => {
  syncDraft();
};

/** 仅把草稿恢复为默认值，不立即应用或持久化。 */
const resetDraft = () => {
  draftValue.value = normalizeSelection(props.defaultValue, availableKeys.value);
};

/** 保存草稿并在短暂成功反馈后关闭设置面板。 */
const saveDraft = () => {
  if (!isDirty.value) {
    return;
  }
  const nextValue = normalizeSelection(draftValue.value, props.defaultValue);
  emit("update:modelValue", nextValue);
  emit("save", nextValue);
  isSavedFeedback.value = true;
  if (saveCloseTimer) {
    clearTimeout(saveCloseTimer);
  }
  saveCloseTimer = setTimeout(() => {
    popoverRef.value?.hide();
  }, 500);
};

onUnmounted(() => {
  if (saveCloseTimer) {
    clearTimeout(saveCloseTimer);
  }
});
</script>

<style lang="scss" scoped>
.field-settings {
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
  width: 100%;
  gap: 16px;
}

.field-settings__header {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.field-settings__header-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 24px;
}

.field-settings__title {
  color: var(--project-alpha-text-primary);
  font-size: var(--project-alpha-font-size-md);
  font-weight: var(--project-alpha-font-weight-semibold);
  line-height: var(--project-alpha-line-height-lg);
  white-space: nowrap;
}

.field-settings__description {
  color: var(--project-alpha-text-muted);
  font-size: var(--project-alpha-font-size-xs);
  line-height: var(--project-alpha-line-height-sm);
}

.field-settings__unsaved {
  flex: 0 0 auto;
  padding: 2px 7px;
  border: 1px solid #fde68a;
  border-radius: 999px;
  background: var(--project-alpha-warning-soft);
  color: var(--project-alpha-warning);
  font-size: var(--project-alpha-font-size-xs);
  line-height: var(--project-alpha-line-height-sm);
  white-space: nowrap;
}

.field-settings__list {
  display: grid;
  grid-template-columns: repeat(
    var(--field-settings-list-columns, 2),
    minmax(0, 1fr)
  );
  gap: 8px;
  max-height: 220px;
  overflow-y: auto;
}

.field-settings__option {
  box-sizing: border-box;
  display: flex !important;
  align-items: center;
  width: 100%;
  min-height: 36px;
  margin: 0 !important;
  padding: 6px 10px;
  border: 1px solid var(--project-alpha-border-light);
  border-radius: var(--project-alpha-radius-md);
  background: var(--project-alpha-panel-bg);
  transition: border-color 0.15s ease, background-color 0.15s ease;
}

.field-settings__option:hover {
  border-color: var(--project-alpha-primary-soft-border);
  background: var(--project-alpha-info-soft);
}

.field-settings__option.is-checked {
  border-color: var(--project-alpha-primary-soft-border);
  background: var(--project-alpha-primary-soft);
}

.field-settings__option.is-checked:hover {
  background: var(--project-alpha-primary-soft);
}

.field-settings__option.is-disabled {
  cursor: not-allowed;
}

.field-settings__option.is-disabled:not(.is-required) {
  opacity: 0.72;
}

.field-settings__option :deep(.el-checkbox__input) {
  flex: 0 0 auto;
}

.field-settings__option :deep(.el-checkbox__label) {
  display: block;
  flex: 1 1 auto;
  min-width: 0;
  padding-left: 8px;
  color: var(--project-alpha-text-secondary);
  font-size: var(--project-alpha-font-size-sm);
  font-weight: var(--project-alpha-font-weight-medium);
  line-height: var(--project-alpha-line-height-base);
}

.field-settings__option.is-checked :deep(.el-checkbox__label) {
  color: var(--project-alpha-text-primary);
}

.field-settings__option-content {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  min-width: 0;
  gap: 12px;
}

.field-settings__option-label {
  display: inline-block;
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.field-settings__required-hint {
  flex: 0 0 auto;
  color: var(--project-alpha-text-muted);
  font-size: var(--project-alpha-font-size-xs);
  font-weight: var(--project-alpha-font-weight-regular);
  line-height: var(--project-alpha-line-height-sm);
}

.field-settings__list :deep(.el-checkbox__input.is-checked .el-checkbox__inner) {
  border-color: var(--project-alpha-primary);
  background-color: var(--project-alpha-primary);
}

.field-settings__list :deep(.el-checkbox) {
  margin-right: 0;
}

.field-settings__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-top: 10px;
  border-top: 1px solid var(--project-alpha-border-light);
}

.field-settings__save-button {
  min-width: 104px;
}

.field-settings__save-button.is-disabled {
  --el-button-disabled-bg-color: var(--project-alpha-info-soft);
  --el-button-disabled-border-color: var(--project-alpha-border-light);
  --el-button-disabled-text-color: var(--project-alpha-text-muted);
}
</style>
