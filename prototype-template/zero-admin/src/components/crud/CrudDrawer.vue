<template>
  <el-drawer
    :model-value="modelValue"
    class="crud-drawer"
    :size="normalizedSize"
    :show-close="false"
    :style="{ '--el-drawer-padding-primary': 0 }"
    :close-on-click-modal="closeOnClickModal"
    :close-on-press-escape="closeOnPressEscape"
    :destroy-on-close="destroyOnClose"
    :before-close="handleBeforeClose"
    @update:model-value="handleModelUpdate"
    @close="handleClose"
    @closed="emit('closed')"
  >
    <template #header>
      <CrudHeader
        :mode="mode"
        :title="title"
        :saving="busy"
        :save-disabled="blocked"
        :save-text="saveText"
        :show-actions="showHeaderActions"
        :custom-actions="Boolean($slots.actions)"
        @save="handleSave"
        @close="requestClose"
      >
        <slot name="actions" :mode="mode" :saving="busy" :save-disabled="blocked" :submit="handleSave" />
      </CrudHeader>
    </template>

    <el-tabs
      v-if="normalizedTabs.length"
      :model-value="activeTab"
      class="crud-drawer__tabs"
      @update:model-value="handleTabUpdate"
    >
      <el-tab-pane
        v-for="tab in normalizedTabs"
        :key="tab.name"
        :name="tab.name"
        :label="tab.label"
        :disabled="tab.disabled"
      >
        <div class="crud-drawer__body" :class="bodyClass">
          <slot :name="`tab-${tab.name}`" :mode="mode" />
        </div>
      </el-tab-pane>
    </el-tabs>

    <div v-else class="crud-drawer__body" :class="bodyClass">
      <slot :mode="mode" />
    </div>
  </el-drawer>
</template>

<script setup>
/** CRUD 容器等待完整保存 Promise，并拦截关闭动画期间的重复操作。
 * @author Andersen
 */
import { useDialogSubmission } from "../overlay/useDialogSubmission.js";
import CrudHeader from "./CrudHeader.vue";
import { CRUD_MODE, CRUD_MODE_VALUES } from "./crudModes.js";

const props = defineProps({
  modelValue: {
    type: Boolean,
    default: false,
  },
  mode: {
    type: String,
    default: CRUD_MODE.EDIT,
    validator: (value) => CRUD_MODE_VALUES.includes(value),
  },
  title: {
    type: String,
    default: "",
  },
  size: {
    type: [String, Number],
    default: 560,
  },
  tabs: {
    type: Array,
    default: () => [],
  },
  activeTab: {
    type: [String, Number],
    default: "",
  },
  /** 异步保存入口；提供时替代 save 事件执行，调用方负责成功后的关闭。 */
  submit: { type: Function, default: null },
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
  showHeaderActions: {
    type: Boolean,
    default: true,
  },
  bodyClass: {
    type: [String, Array, Object],
    default: "",
  },
  destroyOnClose: {
    type: Boolean,
    default: true,
  },
  closeOnClickModal: {
    type: Boolean,
    default: false,
  },
  closeOnPressEscape: {
    type: Boolean,
    default: true,
  },
  beforeClose: {
    type: Function,
    default: null,
  },
});

const emit = defineEmits([
  "update:modelValue",
  "update:activeTab",
  "tab-change",
  "save",
  "error",
  "close",
  "closed",
]);

/** 默认保存按钮与自定义动作可以共用完整异步提交锁。 */
const { busy, blocked, run, markClosing } = useDialogSubmission({
  visible: () => props.modelValue,
  saving: () => props.saving,
  disabled: () => props.saveDisabled || props.mode === CRUD_MODE.DETAIL,
});

/** 新调用方通过 submit 返回完整 Promise；旧 save 事件继续由 saving 驱动。 */
const handleSave = async () => {
  await run(
    () => typeof props.submit === "function" ? props.submit() : emit("save"),
    (error) => emit("error", error)
  );
};

/** 退出动画开始后不可再次保存，直到下一次打开。 */
const handleClose = () => {
  markClosing();
  emit("close");
};

/** 将数字或纯数字字符串抽屉尺寸限制在当前视口范围内。 */
const normalizedSize = computed(() => {
  if (
    typeof props.size === "number" ||
    /^\d+(\.\d+)?$/.test(String(props.size))
  ) {
    return `min(${Number(props.size)}px, 94vw)`;
  }
  return props.size;
});

/** 过滤无效页签，并保证页签名称和标签可稳定渲染。 */
const normalizedTabs = computed(() => {
  return props.tabs
    .filter((tab) => tab && tab.visible !== false && tab.name)
    .map((tab) => ({
      ...tab,
      name: String(tab.name),
      label: String(tab.label || tab.name),
    }));
});

/** 同步抽屉双向绑定状态。 */
const handleModelUpdate = (value) => {
  if (!value) markClosing();
  emit("update:modelValue", value);
};

/** 同步当前激活页签。 */
const handleTabUpdate = (value) => {
  emit("update:activeTab", value);
  emit("tab-change", value);
};

/**
 * 执行调用方提供的关闭守卫。
 *
 * 守卫返回 false 时保留抽屉，其余返回值均继续 Element Plus 的关闭流程。
 */
const handleBeforeClose = async (done) => {
  if (busy.value) return;
  if (typeof props.beforeClose === "function") {
    const result = await props.beforeClose();
    if (result === false) {
      return;
    }
  }
  if (busy.value) return;
  markClosing();
  done();
};

/** 请求关闭当前抽屉，并复用同一关闭守卫。 */
const requestClose = async () => {
  if (busy.value) return;
  if (typeof props.beforeClose === "function") {
    const result = await props.beforeClose();
    if (result === false) {
      return;
    }
  }
  if (busy.value) return;
  handleModelUpdate(false);
};
</script>

<style lang="scss">
.crud-drawer .el-drawer__header {
  margin: 0 !important;
  padding: 0 !important;
}

.crud-drawer .el-drawer__body {
  min-width: 0;
  padding: 0;
}

.crud-drawer__body {
  min-width: 0;
  padding: var(--project-alpha-space-6);
}

.crud-drawer__tabs > .el-tabs__header {
  margin: 0;
  border-bottom: 1px solid var(--project-alpha-border);
  background: var(--project-alpha-tab-bg);
}

.crud-drawer__tabs > .el-tabs__header .el-tabs__nav-scroll {
  padding: 0 var(--project-alpha-space-6);
}

.crud-drawer__tabs > .el-tabs__header .el-tabs__nav-wrap::after {
  height: 0;
}

.crud-drawer__tabs .el-tabs__item {
  height: 42px;
  color: var(--project-alpha-text-secondary);
  font-size: var(--project-alpha-font-size-sm);
}

.crud-drawer__tabs .el-tabs__item.is-active {
  color: var(--project-alpha-primary);
  font-weight: var(--project-alpha-font-weight-semibold);
}

@media (max-width: 640px) {
  .crud-drawer__body {
    padding: var(--project-alpha-space-4);
  }

  .crud-drawer__tabs > .el-tabs__header .el-tabs__nav-scroll {
    padding: 0 var(--project-alpha-space-4);
  }
}
</style>
