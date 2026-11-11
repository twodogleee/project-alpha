<template>
  <el-form
    class="query-panel"
    :class="{ 'is-expanded': isExpanded }"
    label-width="96px"
    :model="model"
    :inline="false"
    @submit.prevent
  >
    <div class="query-panel__main">
      <div class="query-panel__content">
        <div class="query-panel__meta">
          <span class="query-panel__title">查询</span>
          <span v-if="hiddenFieldCount > 0 && !isExpanded" class="query-panel__count">
            隐藏 {{ hiddenFieldCount }} 项
          </span>
        </div>

        <div class="query-panel__fields">
          <template v-for="(item, index) in displayFields" :key="item.prop">
            <div
              v-if="shouldShowMoreDivider(item, index)"
              class="query-panel__divider"
            >
              更多条件
            </div>

            <el-form-item
              class="query-panel__field"
              :class="{
                'query-panel__field--wide': isWideField(item),
                'query-panel__field--secondary': isSecondaryField(item),
              }"
              :label="item.label"
              :data-input-help-managed="isHelpManaged(item) || helpDisplay(item) === 'none'"
            >
              <template v-if="getFieldHelp(item) && ['icon', 'control', 'tooltip'].includes(helpDisplay(item))" #label>
                {{ item.label }}<FieldHelp :text="getFieldHelp(item)" :label="item.label" :help-display="helpDisplay(item)" />
              </template>
              <slot
                v-if="item.type === 'slot' || item.type === 'custom'"
                :name="item.slot || `field-${item.prop}`"
                :field="item"
                :model="model"
                :value="model[getModelKey(item)]"
              />
              <QueryField
                v-else
                v-model="model[getModelKey(item)]"
                :field="item"
                @search="handleSearch"
                @clear="handleClear"
                @change="handleFieldChange(item, $event)"
                @error="handleFieldError(item, $event)"
              />
              <div v-if="getFieldHelp(item) && helpDisplay(item) === 'inline'" class="field-help-inline">{{ getFieldHelp(item) }}</div>
            </el-form-item>
          </template>
        </div>
      </div>

      <div class="query-panel__actions">
        <el-button type="primary" :icon="Search" @click="handleSearch">
          查询
        </el-button>
        <el-button :icon="RefreshLeft" @click="handleReset">重置</el-button>
        <el-button
          v-if="hasExpandableFields"
          :icon="isExpanded ? Top : Bottom"
          @click="toggleExpanded"
        >
          {{ expandButtonText }}
        </el-button>

        <FieldSettingsPopover
          v-if="availableFields.length > 1"
          :model-value="pinnedProps"
          :items="fieldSettingItems"
          :default-value="defaultPinnedProps"
          title="查询栏字段设置"
          description="选择收起时显示的查询条件"
          button-title="配置收起栏条件"
          popper-class="query-panel-config-popper"
          @save="saveLayout"
        />
      </div>
    </div>
  </el-form>
</template>

<script setup>
/** 查询帮助采用展示配置，不改变原输入、事件及多行编辑方式。@author Andersen */
import FieldHelp from '../inputHelp/FieldHelp.vue';
import { getFieldHelp, isHelpManaged } from '../inputHelp/fieldHelp.js';
import { resolveHelpDisplay } from '../inputHelp/config.js';
import { useInputHelp } from '../inputHelp/useInputHelp.js';
/** 项目默认说明展示策略。 */
const { options: inputHelpOptions } = useInputHelp();
/** 字段设置优先于项目默认值。 */
const helpDisplay = (field) => resolveHelpDisplay(field, 'query', inputHelpOptions.value);

import { useRoute } from "vue-router";
import {
  Search,
  RefreshLeft,
  Bottom,
  Top,
} from "@element-plus/icons-vue";
import FieldSettingsPopover from "@/components/fieldSettingsPopover/index.vue";
import QueryField from "./QueryField.vue";
import { useQueryPanelPrefsStore } from "@/store/queryPanelPrefs.js";

const props = defineProps({
  model: {
    type: Object,
    required: true,
  },
  fields: {
    type: Array,
    default: () => [],
  },
  storageKey: {
    type: String,
    default: "",
  },
});

const emit = defineEmits([
  "search",
  "reset",
  "clear",
  "change",
  "field-error",
  "layout-change",
]);

const route = useRoute();
const initialPanelRoutePath = route.path;
const queryPanelPrefsStore = useQueryPanelPrefsStore();

const panelKey = computed(() => props.storageKey || initialPanelRoutePath);

const availableFields = computed(() => {
  return props.fields.filter((field) => field?.prop && field?.type);
});

const availableProps = computed(() => availableFields.value.map((field) => field.prop));

/** 将查询字段转换为公共字段设置组件使用的中立选项。 */
const fieldSettingItems = computed(() => {
  return availableFields.value.map((field) => ({
    key: field.prop,
    label: field.label,
  }));
});

const defaultPinnedProps = computed(() => {
  const primaryProps = availableFields.value
    .filter((field) => field.flag === 1)
    .map((field) => field.prop);

  return primaryProps.length
    ? primaryProps
    : availableFields.value.slice(0, 3).map((field) => field.prop);
});

const fieldSignature = computed(() => availableProps.value.join("|"));

const resolvePinnedProps = (props = []) => {
  const existingProps = props.filter((prop) => availableProps.value.includes(prop));
  if (existingProps.length) {
    return existingProps;
  }

  const defaultProps = defaultPinnedProps.value.filter((prop) =>
    availableProps.value.includes(prop)
  );
  if (defaultProps.length) {
    return defaultProps;
  }

  return availableProps.value.slice(0, 1);
};

const pinnedPropsState = ref([]);

const ensurePrefs = () => {
  queryPanelPrefsStore.ensurePrefs(
    panelKey.value,
    defaultPinnedProps.value,
    availableProps.value
  );
};

watch(
  [panelKey, fieldSignature],
  () => {
    ensurePrefs();
    const currentPrefs = queryPanelPrefsStore.getPrefs(panelKey.value);
    const nextPinnedProps = resolvePinnedProps(
      currentPrefs?.pinnedProps?.length ? currentPrefs.pinnedProps : defaultPinnedProps.value
    );
    pinnedPropsState.value = nextPinnedProps;
    nextTick(() => emit("layout-change"));
  },
  { immediate: true }
);

const prefs = computed(() => {
  return queryPanelPrefsStore.getPrefs(panelKey.value);
});

const pinnedProps = computed(() => {
  return pinnedPropsState.value.length
    ? pinnedPropsState.value
    : resolvePinnedProps(prefs.value?.pinnedProps || defaultPinnedProps.value);
});

const isExpanded = computed({
  get() {
    return prefs.value?.expanded === true;
  },
  set(value) {
    queryPanelPrefsStore.updatePrefs(
      panelKey.value,
      { expanded: value },
      defaultPinnedProps.value,
      availableProps.value
    );
  },
});

const displayFields = computed(() => {
  if (isExpanded.value) {
    const pinnedSet = new Set(pinnedProps.value);
    const pinnedFields = availableFields.value.filter((field) => pinnedSet.has(field.prop));
    const secondaryFields = availableFields.value.filter((field) => !pinnedSet.has(field.prop));
    return [...pinnedFields, ...secondaryFields];
  }
  const pinnedSet = new Set(pinnedProps.value);
  return availableFields.value.filter((field) => pinnedSet.has(field.prop));
});

const hasExpandableFields = computed(() => {
  return availableFields.value.length > displayFields.value.length || isExpanded.value;
});

const hiddenFieldCount = computed(() => {
  return Math.max(availableFields.value.length - pinnedProps.value.length, 0);
});

const expandButtonText = computed(() => {
  if (isExpanded.value) {
    return "收起";
  }
  return hiddenFieldCount.value ? `展开 ${hiddenFieldCount.value}` : "展开";
});

const isSecondaryField = (item) => {
  return isExpanded.value && !pinnedProps.value.includes(item.prop);
};

const shouldShowMoreDivider = (item, index) => {
  if (!isSecondaryField(item)) {
    return false;
  }
  const previous = displayFields.value[index - 1];
  return !previous || !isSecondaryField(previous);
};

const getModelKey = (item) => {
  return item.propKey || item.prop;
};

/** 日期范围、显式 wide 或多列字段横跨两个查询网格。 */
const isWideField = (item) => {
  return item.wide === true || Number(item.span) > 1 || [
    "date",
    "daterange",
    "datetimerange",
  ].includes(item.type);
};

const handleSearch = () => {
  emit("search");
};

const handleReset = () => {
  emit("reset");
};

const handleClear = () => {
  emit("clear");
};

/** 转发查询字段值变更，并保留字段上下文。 */
const handleFieldChange = (field, value) => {
  emit("change", { field, value });
};

/** 转发自定义查询字段错误，交由页面决定提示方式。 */
const handleFieldError = (field, error) => {
  emit("field-error", { field, error });
};

const toggleExpanded = () => {
  isExpanded.value = !isExpanded.value;
  nextTick(() => emit("layout-change"));
};

/** 保存查询栏字段草稿，并在保存后更新正式布局。 */
const saveLayout = (value) => {
  const nextPinnedProps = resolvePinnedProps(value);
  pinnedPropsState.value = nextPinnedProps;
  queryPanelPrefsStore.updatePrefs(
    panelKey.value,
    { pinnedProps: nextPinnedProps },
    defaultPinnedProps.value,
    availableProps.value
  );
  nextTick(() => {
    emit("layout-change");
  });
};
</script>

<style lang="scss" scoped>
.query-panel {
  --query-panel-field-width: 360px;
  --query-panel-field-column-gap: 40px;
  --query-panel-field-row-gap: 20px;
  flex: 0 0 auto;
  min-height: 64px;
  padding: var(--project-alpha-space-3) var(--project-alpha-space-4);
  border-bottom: 1px solid var(--project-alpha-border-light);
  background: var(--project-alpha-panel-bg);
}

.query-panel__main {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: start;
  gap: 12px 20px;
}

.query-panel__content {
  display: grid;
  grid-template-columns: 64px minmax(0, 1fr);
  align-items: start;
  gap: 10px 14px;
  min-width: 0;
}

.query-panel__meta {
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-height: 32px;
  gap: 4px;
}

.query-panel__title {
  color: var(--project-alpha-text-primary);
  font-size: var(--project-alpha-font-size-sm);
  font-weight: var(--project-alpha-font-weight-semibold);
  line-height: var(--project-alpha-line-height-xs);
}

.query-panel__count {
  color: var(--project-alpha-text-muted);
  font-size: var(--project-alpha-font-size-xs);
  line-height: var(--project-alpha-line-height-xs);
}

.query-panel__fields {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(var(--query-panel-field-width), var(--query-panel-field-width)));
  align-items: center;
  gap: var(--query-panel-field-row-gap) var(--query-panel-field-column-gap);
  max-width: calc(var(--query-panel-field-width) * 5 + var(--query-panel-field-column-gap) * 4);
  min-width: 0;
}

.query-panel__field {
  display: grid;
  grid-template-columns: max-content minmax(0, 1fr);
  align-items: center;
  column-gap: 12px;
  width: var(--query-panel-field-width);
  min-width: 0;
}

.query-panel__field--wide {
  grid-column: span 2;
  width: 100%;
}

.query-panel__field--secondary {
  opacity: 0.98;
}

.query-panel__divider {
  display: flex;
  align-items: center;
  grid-column: 1 / -1;
  height: 24px;
  color: var(--project-alpha-text-muted);
  font-size: var(--project-alpha-font-size-xs);
  line-height: 24px;
}

.query-panel__divider::after {
  flex: 1;
  height: 1px;
  margin-left: 10px;
  background: var(--project-alpha-border-light);
  content: "";
}

.query-panel__actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: nowrap;
  gap: 8px;
  min-height: 32px;
  padding-left: 16px;
  border-left: 1px solid var(--project-alpha-border-light);
}

.query-panel__actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

:deep(.el-form-item) {
  margin: 0;
}

:deep(.el-form-item__label) {
  flex: 0 0 auto !important;
  width: auto !important;
  height: 32px;
  padding-right: 0;
  justify-content: flex-start;
  color: var(--project-alpha-text-primary);
  font-size: var(--project-alpha-font-size-base);
  font-weight: var(--project-alpha-font-weight-semibold);
  line-height: 32px;
  text-align: left;
}

:deep(.el-form-item__content) {
  flex: 1 1 auto;
  min-width: 0;
}

:deep(.el-form-item__content > .el-input),
:deep(.el-form-item__content > .el-select),
:deep(.el-form-item__content > .el-date-editor) {
  width: 100%;
}

:deep(.el-form-item__content > .el-input-number),
:deep(.el-form-item__content > .el-tree-select) {
  width: 100%;
}

@media (max-width: 1180px) {
  .query-panel__main {
    grid-template-columns: 1fr;
  }

  .query-panel__actions {
    justify-content: flex-start;
    padding-left: 78px;
    border-left: 0;
  }
}

@media (max-width: 760px) {
  .query-panel {
    padding: var(--project-alpha-space-3) var(--project-alpha-space-4);
  }

  .query-panel__content {
    grid-template-columns: 1fr;
    width: 100%;
    max-width: none;
  }

  .query-panel__meta {
    flex-direction: row;
    align-items: center;
    justify-content: space-between;
    width: 100%;
    min-height: auto;
  }

  .query-panel__fields {
    grid-template-columns: 1fr;
  }

  .query-panel__field,
  .query-panel__field--wide {
    grid-column: auto;
    width: 100%;
  }

  .query-panel__actions {
    flex-wrap: wrap;
    padding-left: 0;
  }
}
</style>
