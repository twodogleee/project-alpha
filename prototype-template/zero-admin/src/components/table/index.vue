<template>
  <div ref="cardRef" class="e-table-card">
    <div v-if="$slots.headerTip" class="e-table-header-tip">
      <slot name="headerTip"></slot>
    </div>

    <QueryPanel
      v-if="searchComs?.length > 0"
      :model="props.tableModule.queryForm"
      :fields="searchComs"
      :storage-key="queryPanelKey"
      @search="handleSearch"
      @reset="handleReset"
      @clear="inputClear"
      @layout-change="calculateTableHeight"
    />

    <div class="e-table-toolbar" v-if="hasTableToolbar">
      <div class="e-table-toolbar__main">
        <slot name="event"> </slot>
      </div>
      <div class="e-table-toolbar__tools">
        <el-button
          v-if="showRefresh"
          :icon="Refresh"
          circle
          title="刷新"
          aria-label="刷新"
          @click="refresh"
        />
        <FieldSettingsPopover
          v-if="hasColumnSettings"
          :model-value="appliedVisibleColumnKeys"
          :items="columnSettingItems"
          :default-value="defaultVisibleColumnKeys"
          :list-columns="1"
          title="表格字段设置"
          description="选择表格中需要显示的字段"
          button-title="设置表格字段"
          popper-class="table-column-settings-popper"
          @save="handleColumnSettingsSave"
        />
      </div>
    </div>

    <div class="e-table-body">
      <el-table
        id="el-table"
        ref="tableRef"
        size="small"
        class="w-full"
        :border="tableBorder"
        :row-key="resolvedRowKey"
        :data="props.tableModule.dataList"
        v-loading="props.tableModule.loading"
        :default-expand-all="props.tableModule.defaultExpandAll"
        :expand-row-keys="props.tableModule.expandRowKeys"
        :height="tableHeight || '100%'"
        @selection-change="selectClick"
      >
        <el-table-column type="expand" v-if="props.isExpand">
          <template #default="scope">
            <slot name="expand" :row="scope.row">
              <el-empty description="暂无扩展内容" :image-size="56" />
            </slot>
          </template>
        </el-table-column>

        <el-table-column
          type="selection"
          width="50"
          align="center"
          v-if="props.isSelection"
          :selectable="props.selectable"
          :reserve-selection="Boolean(resolvedRowKey)"
        />
        <template
          v-for="(item, index) in visibleColumns"
          :key="getColumnKey(item, index)"
        >
          <el-table-column
            :show-overflow-tooltip="item.showOverflowTooltip === false
              ? false
              : { popperClass: 'custom-tooltip' }"
            :width="item.width"
            :prop="item.prop"
            :label="item.label"
            :fixed="item.fixed"
            :min-width="item.minWidth"
          >
            <template #default="scope">
              <slot
                :name="`cell-${getColumnKey(item, index)}`"
                :row="scope.row"
                :column="item"
                :value="getNestedValue(scope.row, item.prop)"
              >
                <TableCell
                  :row="scope.row"
                  :column="item"
                  :value="getNestedValue(scope.row, item.prop)"
                >
                  <template #array="{ value }">
                    <slot name="rowArray" :row="value" :key="item.key" />
                  </template>
                </TableCell>
              </slot>
            </template>
          </el-table-column>
        </template>
        <slot name="tableColumn"></slot>
      </el-table>
    </div>

    <div class="e-table-pagination" v-if="props.isPage">
      <el-pagination
        :current-page="pageIndexState"
        :page-size="pageSizeState"
        :page-sizes="[20, 30, 50, 100]"
        :layout="props.layout"
        :total="Number(props.tableModule.pages?.total) || 0"
        @size-change="sizeChange"
        @current-change="currentChange"
      />
    </div>
  </div>
</template>

<script setup>
import { useRoute } from "vue-router";
import {
  Refresh,
} from "@element-plus/icons-vue";
import { useTableColumnPrefsStore } from "@/store/tableColumnPrefs.js";
import { normalizeVisibleColumnKeys } from "@/utils/tableColumnPrefs.js";
import FieldSettingsPopover from "@/components/fieldSettingsPopover/index.vue";
import QueryPanel from "@/components/queryPanel/index.vue";
import TableCell from "./TableCell.vue";
const props = defineProps({
  tableModule: {
    type: Object,
    default: () => ({}),
  },
  rowKey: {
    type: [String, Function],
    default: undefined,
  },
  border: {
    type: Boolean,
    default: undefined,
  },
  isSelection: {
    type: Boolean,
    default: false,
  },
  isSingleSelect: {
    type: Boolean,
    default: false,
  },
  isPage: {
    type: Boolean,
    default: true,
  },

  fullHeight: {
    type: String,
    default: null,
  },
  isExpand: {
    type: Boolean,
    default: false,
  },
  layout: {
    type: String,
    default: "total, sizes, prev, pager, next, jumper",
  },
  extraOffset: {
    type: Number,
    default: 0,
  },
  selectable: {
    type: Function,
    default: null,
  },
  showRefresh: {
    type: Boolean,
    default: true,
  },
  columnSettings: {
    type: Boolean,
    default: true,
  },
});

// 检测 #event 插槽是否有内容
const slots = useSlots();
const hasEventSlot = computed(() => !!slots.event);
const tableBorder = computed(() => {
  return props.border ?? props.tableModule?.border ?? true;
});
/** 页面显式 rowKey 优先于历史 tableModule.rowKey。 */
const resolvedRowKey = computed(() => {
  return props.rowKey ?? props.tableModule?.rowKey;
});
const route = useRoute();
const initialQueryPanelRoutePath = route.path;
const queryPanelKey = computed(() => props.tableModule?.queryKey || initialQueryPanelRoutePath);
const tableColumnPrefsStore = useTableColumnPrefsStore();

/** 当前表格声明的业务数据列。 */
const tableColumns = computed(() => {
  return Array.isArray(props.tableModule?.columns)
    ? props.tableModule.columns
    : [];
});

/**
 * 获取字段配置的稳定键，优先使用显式 columnKey。
 *
 * @param {Object} column 表格字段配置
 * @param {number} index 字段在当前配置中的位置
 * @returns {string} 字段稳定键
 */
const getColumnKey = (column, index) => {
  return String(column?.columnKey || column?.prop || `column-${index}`);
};

/** 判断字段是否具备可持久化的稳定标识并允许参与显示设置。 */
const isConfigurableColumn = (column) => {
  return (
    column?.columnSettings !== false &&
    Boolean(column?.columnKey || column?.prop) &&
    Boolean(column?.label)
  );
};

/** 转换为公共字段设置组件需要的表格字段选项。 */
const columnSettingItems = computed(() => {
  return tableColumns.value
    .map((column, index) => ({
      key: getColumnKey(column, index),
      label: column.label,
      required: column.hideable === false,
      configurable: isConfigurableColumn(column),
    }))
    .filter((item) => item.configurable);
});

/** 当前可以持久化的全部表格字段键。 */
const availableColumnKeys = computed(() => {
  return columnSettingItems.value.map((item) => item.key);
});

/** 当前代码声明的默认可见表格字段键。 */
const defaultVisibleColumnKeys = computed(() => {
  return tableColumns.value
    .map((column, index) => ({
      key: getColumnKey(column, index),
      visible:
        column.defaultVisible !== false &&
        column.show !== false &&
        column.show !== 0,
      configurable: isConfigurableColumn(column),
      required: column.hideable === false,
    }))
    .filter((item) => item.configurable && (item.visible || item.required))
    .map((item) => item.key);
});

/** 不允许用户隐藏的表格字段键。 */
const requiredColumnKeys = computed(() => {
  return columnSettingItems.value
    .filter((item) => item.required)
    .map((item) => item.key);
});

/** 表格字段设置使用的页面级持久化键。 */
const tableColumnPrefsKey = computed(() => {
  return (
    props.tableModule?.columnSettingsKey ||
    props.tableModule?.tableKey ||
    queryPanelKey.value
  );
});

/** 表格字段结构签名，用于代码新增或移除字段后重新校准偏好。 */
const tableColumnSignature = computed(() => {
  return columnSettingItems.value
    .map((item) => `${item.key}:${item.required ? 1 : 0}`)
    .join("|");
});

const visibleColumnKeysState = ref([]);

/** 当前已保存并正式应用的可见表格字段键。 */
const appliedVisibleColumnKeys = computed(() => {
  return normalizeVisibleColumnKeys(
    visibleColumnKeysState.value,
    defaultVisibleColumnKeys.value,
    availableColumnKeys.value,
    requiredColumnKeys.value
  );
});

/** 根据正式保存的字段设置过滤表格数据列。 */
const visibleColumns = computed(() => {
  if (!props.columnSettings) {
    return tableColumns.value;
  }
  const visibleSet = new Set(appliedVisibleColumnKeys.value);
  return tableColumns.value.filter((column, index) => {
    if (!isConfigurableColumn(column)) {
      return true;
    }
    return visibleSet.has(getColumnKey(column, index));
  });
});

/** 至少两个可配置字段时才展示字段设置入口。 */
const hasColumnSettings = computed(() => {
  return props.columnSettings && columnSettingItems.value.length > 1;
});

/** 有页面操作或公共工具时展示表格工具栏。 */
const hasTableToolbar = computed(() => {
  return hasEventSlot.value || props.showRefresh || hasColumnSettings.value;
});

watch(
  [tableColumnPrefsKey, tableColumnSignature],
  () => {
    if (!props.columnSettings || !availableColumnKeys.value.length) {
      visibleColumnKeysState.value = [...availableColumnKeys.value];
      return;
    }
    const prefs = tableColumnPrefsStore.ensurePrefs(
      tableColumnPrefsKey.value,
      defaultVisibleColumnKeys.value,
      availableColumnKeys.value,
      requiredColumnKeys.value
    );
    visibleColumnKeysState.value = [...prefs.visibleKeys];
  },
  { immediate: true }
);

// 获取嵌套属性的值
function getNestedValue(obj, path) {
  if (!path) return undefined;
  const keys = path.split(".");
  let value = obj;
  for (const key of keys) {
    if (value === null || value === undefined) return undefined;
    value = value[key];
  }
  return value;
}

const emit = defineEmits([
  "selectRow",
  "singleSelect",
  "clear",
  "search",
  "reset",
  "refresh",
  "page-change",
  "size-change",
  "column-layout-change",
]);
const tableRef = ref();
const cardRef = ref(null);
const pageIndexState = ref(1);
const pageSizeState = ref(20);

watch(
  () => [
    props.tableModule?.pages?.pageIndex,
    props.tableModule?.pages?.pageSize,
  ],
  ([pageIndex, pageSize]) => {
    pageIndexState.value = Math.max(Number(pageIndex) || 1, 1);
    pageSizeState.value = Math.max(Number(pageSize) || 20, 1);
  },
  { immediate: true }
);

// 动态计算表格高度
const tableHeight = ref(null);

/**
 * 计算表格的最大高度
 * 基于父容器的可用高度，减去搜索表单、操作按钮区、分页等固定元素的高度
 */
const calculateTableHeight = () => {
  // 如果手动指定了 fullHeight，优先使用
  if (props.fullHeight) {
    tableHeight.value = props.fullHeight;
    return;
  }

  nextTick(() => {
    try {
      const tableElement = tableRef.value?.$el;
      if (!tableElement) {
        tableHeight.value = "calc(100vh - 380px)";
        return;
      }

      const tableTop = tableElement.getBoundingClientRect().top;
      const bottomReserved = props.isPage ? 64 : 18;
      const calculatedHeight = window.innerHeight - tableTop - bottomReserved - props.extraOffset;

      tableHeight.value = `${Math.max(calculatedHeight, 260)}px`;
    } catch (error) {
      console.error("计算表格高度失败:", error);
      // 出错时使用保守的默认值
      tableHeight.value = "calc(100vh - 380px)";
    }
  });
};

onMounted(() => {
  // 初始化时计算表格高度
  calculateTableHeight();

  // 监听窗口大小变化
  window.addEventListener("resize", calculateTableHeight);
});

// 清理监听器
onUnmounted(() => {
  window.removeEventListener("resize", calculateTableHeight);
});
/** 调用 tableModule 显式处理器，并为旧模块回退到 callback(context)。 */
const invokeTableHandler = (handlerName, context = {}) => {
  const handler = props.tableModule?.[handlerName];
  if (typeof handler === "function") {
    return handler(context);
  }
  return props.tableModule?.callback?.(context);
};

/** 请求页面切换每页数量，不直接修改调用方分页对象。 */
function sizeChange(item) {
  pageSizeState.value = item;
  pageIndexState.value = 1;
  const context = { pageIndex: 1, pageSize: item };
  emit("size-change", item, context);
  invokeTableHandler("onPageChange", context);
}

/** 请求页面切换页码，不直接修改调用方分页对象。 */
function currentChange(item) {
  pageIndexState.value = item;
  const context = { pageIndex: item, pageSize: pageSizeState.value };
  emit("page-change", item, context);
  invokeTableHandler("onPageChange", context);
}

/** 请求页面按当前状态刷新列表。 */
function refresh() {
  const context = {
    pageIndex: pageIndexState.value,
    pageSize: pageSizeState.value,
  };
  emit("refresh", context);
  invokeTableHandler("onRefresh", context);
}

/** 保存表格字段草稿并在布局完成后通知业务页面。 */
const handleColumnSettingsSave = (visibleKeys) => {
  const prefs = tableColumnPrefsStore.updatePrefs(
    tableColumnPrefsKey.value,
    visibleKeys,
    defaultVisibleColumnKeys.value,
    availableColumnKeys.value,
    requiredColumnKeys.value
  );
  visibleColumnKeysState.value = [...prefs.visibleKeys];
  nextTick(() => {
    tableRef.value?.doLayout?.();
    calculateTableHeight();
    emit("column-layout-change", [...prefs.visibleKeys]);
  });
};
function handleSearch() {
  pageIndexState.value = 1;
  const context = { pageIndex: 1, pageSize: pageSizeState.value };
  emit("search", context);
  invokeTableHandler("onSearch", context);
}
function handleReset() {
  inputClear();
  pageIndexState.value = 1;
  const context = {
    pageIndex: 1,
    pageSize: pageSizeState.value,
    resetQuery: true,
  };
  emit("reset", context);
  invokeTableHandler("onReset", context);
}

/** 将历史选项键数组转换为正式查询字段选项映射。 */
const resolveLegacyOptionProps = (optionKeys) => {
  if (!Array.isArray(optionKeys)) {
    return undefined;
  }
  const [value, label, disabled] = optionKeys;
  return {
    ...(value ? { value } : {}),
    ...(label ? { label } : {}),
    ...(disabled ? { disabled } : {}),
  };
};

/** 将历史业务列查询配置转换为新版正式查询字段。 */
const resolveSearchField = (column) => {
  return {
    filterable: column.filterable,
    treeProps: column.treeProps || column.defaultProps,
    optionProps:
      column.optionProps ||
      resolveLegacyOptionProps(column.optionkey),
    placeholder: column.searchPlaceholder,
    ...(column.searchProps || {}),
    prop: column.prop,
    type: column.searchType,
    label: column.label,
    options: column.option,
    propKey: column.propKey || column.prop,
    multiple: column.multiple || false,
  };
};

const searchComs = computed(() => {
  if (Array.isArray(props.tableModule?.searchFields)) {
    return props.tableModule.searchFields;
  }
  let options = [];
  if (props.tableModule.query?.a) {
    let a = props.tableModule.query.a
      .map((key) => {
        if (key == "all") {
          return { key: "all" };
        }
        return props.tableModule.columns
          .filter((e) => e.prop == key)
          .map(resolveSearchField)[0];
      })
      .filter((e) => e);
    a.forEach((e) => (e.flag = 1));
    options = options.concat(a);
  }
  if (props.tableModule.query?.b) {
    let b = props.tableModule.query.b
      .map((key) => {
        if (key == "all") {
          return { key: "all" };
        }
        return props.tableModule.columns
          .filter((e) => e.prop == key)
          .map(resolveSearchField)[0];
      })
      .filter((e) => e);
    b.forEach((e) => (e.flag = 2));
    options = options.concat(b);
  }
  return options;
});
let syncingSingleSelection = false;

/** 统一处理多选和受控单选，单选时只保留最后一次选择的行。 */
const selectClick = (selection) => {
  if (syncingSingleSelection) {
    return;
  }
  if (props.isSingleSelect && selection.length > 1) {
    const selectedRow = selection[selection.length - 1];
    syncingSingleSelection = true;
    tableRef.value?.clearSelection?.();
    tableRef.value?.toggleRowSelection?.(selectedRow, true);
    nextTick(() => {
      syncingSingleSelection = false;
      emit("selectRow", [selectedRow]);
      emit("singleSelect", selectedRow);
    });
    return;
  }
  const selectedRow = selection[0] || null;
  emit("selectRow", selection);
  if (props.isSingleSelect) {
    emit("singleSelect", selectedRow);
  }
};
const inputClear = () => {
  emit("clear");
};
defineExpose({
  tableRef,
  clearSelection: () => tableRef.value?.clearSelection?.(),
  refresh,
});
</script>

<style lang="scss" scoped>
.e-table-card {
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
  min-height: 0;
  overflow: hidden;
  border: 1px solid var(--project-alpha-border);
  border-radius: var(--project-alpha-radius-md);
  background: var(--project-alpha-panel-bg);
}

.e-table-header-tip {
  flex: 0 0 auto;
  padding: var(--project-alpha-space-3) var(--project-alpha-space-4) 0;
  background: var(--project-alpha-panel-bg);
}

.e-table-header-tip :deep(.el-alert) {
  margin: 0;
}

.e-table-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex: 0 0 auto;
  gap: var(--project-alpha-space-3);
  min-height: 40px;
  padding: 6px var(--project-alpha-space-4);
  border-bottom: 1px solid var(--project-alpha-border-light);
  background: var(--project-alpha-table-toolbar-bg);
}

.e-table-toolbar__main,
.e-table-toolbar__tools {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.e-table-toolbar__tools :deep(.el-button + .el-button) {
  margin-left: 0;
}

.e-table-body {
  flex: 1 1 auto;
  min-height: 0;
  overflow: hidden;
}

.e-table-pagination {
  display: flex;
  justify-content: flex-end;
  flex: 0 0 auto;
  min-height: 40px;
  padding: 6px var(--project-alpha-space-4);
  border-top: 1px solid var(--project-alpha-border-light);
  background: var(--project-alpha-panel-bg);
}

:deep(.el-form-item) {
  margin: 0;
}

:deep(.el-form-item__label) {
  height: 32px;
  padding-right: 8px;
  justify-content: flex-start;
  color: var(--project-alpha-text-secondary);
  font-size: var(--project-alpha-font-size-sm);
  line-height: 32px;
  text-align: left;
}

:deep(.el-form-item__content > .el-input) {
  width: 100%;
}
:deep(.el-form-item__content > .el-select) {
  width: 100%;
}
:deep(.el-form-item__content > .el-date-editor) {
  width: 100%;
}
:deep(.el-space__item) {
  width: auto;
}
:deep(.el-pagination) {
  justify-content: flex-end;
}

:deep(.el-table) {
  --el-table-border-color: var(--project-alpha-border-light);
  --el-table-header-bg-color: var(--project-alpha-table-header-bg);
}

:deep(.el-table__inner-wrapper::before) {
  height: 0;
}

</style>
