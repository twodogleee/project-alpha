<template>
  <el-dialog
    :model-value="modelValue"
    :class="['app-dialog', dialogClass]"
    :width="width"
    :top="top"
    :show-close="false"
    :destroy-on-close="destroyOnClose"
    :close-on-click-modal="closeOnClickModal && !busy"
    :close-on-press-escape="closeOnPressEscape && !busy"
    :before-close="handleBeforeClose"
    :style="dialogStyle"
    @update:model-value="handleModelUpdate"
    @open="emit('open')"
    @opened="emit('opened')"
    @close="emit('close')"
    @closed="emit('closed')"
  >
    <template #header>
      <div class="app-dialog__header">
        <div class="app-dialog__title">
          <slot name="title">{{ title }}</slot>
        </div>
        <div class="app-dialog__header-actions">
          <slot name="header-actions" />
          <el-button
            v-if="showClose"
            class="app-dialog__close"
            link
            :icon="Close"
            :disabled="busy"
            aria-label="关闭"
            title="关闭"
            @click="requestClose"
          />
        </div>
      </div>
    </template>

    <div class="app-dialog__body" :class="bodyClass">
      <slot :request-close="requestClose" />
    </div>

    <template v-if="$slots.footer" #footer>
      <div class="app-dialog__footer">
        <slot name="footer" :request-close="requestClose" />
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { Close } from "@element-plus/icons-vue";

const props = defineProps({
  modelValue: {
    type: Boolean,
    default: false,
  },
  title: {
    type: String,
    default: "",
  },
  width: {
    type: [String, Number],
    default: 560,
  },
  contentWidth: {
    type: [String, Number],
    default: "100%",
  },
  top: {
    type: String,
    default: "15vh",
  },
  busy: {
    type: Boolean,
    default: false,
  },
  showClose: {
    type: Boolean,
    default: true,
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
  bodyClass: {
    type: [String, Array, Object],
    default: "",
  },
  dialogClass: {
    type: [String, Array, Object],
    default: "",
  },
});

const emit = defineEmits([
  "update:modelValue",
  "open",
  "opened",
  "close",
  "closed",
]);

/** 将数字尺寸规范化为合法的 CSS 长度。 */
const normalizeSize = (value) => {
  return typeof value === "number" ? `${value}px` : value;
};

/** 将内容最大宽度注入公共弹窗正文。 */
const dialogStyle = computed(() => ({
  "--app-dialog-content-width": normalizeSize(props.contentWidth),
}));

/** 执行调用方关闭守卫，忙碌状态或显式 false 都会阻止关闭。 */
const canClose = async () => {
  if (props.busy) {
    return false;
  }
  if (typeof props.beforeClose !== "function") {
    return true;
  }
  try {
    return (await props.beforeClose()) !== false;
  } catch (error) {
    return false;
  }
};

/** 将 Element Plus 发起的关闭请求接入同一关闭守卫。 */
const handleBeforeClose = async (done) => {
  if (await canClose()) {
    done();
  }
};

/** 从自定义关闭按钮请求关闭当前弹窗。 */
const requestClose = async () => {
  if (await canClose()) {
    emit("update:modelValue", false);
  }
};

/** 同步弹窗双向绑定状态。 */
const handleModelUpdate = (value) => {
  emit("update:modelValue", value);
};

defineExpose({ requestClose });
</script>

<style lang="scss">
.app-dialog.el-dialog {
  max-width: 94vw;
  padding: 0 !important;
  overflow: hidden;
  border-radius: var(--project-alpha-radius-lg) !important;
  box-shadow: var(--project-alpha-shadow-dialog);
}

.app-dialog .el-dialog__header,
.app-dialog .el-dialog__body,
.app-dialog .el-dialog__footer {
  margin: 0 !important;
  padding: 0 !important;
}

.app-dialog__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--project-alpha-space-3);
  min-height: 56px;
  padding: 0 var(--project-alpha-space-6);
  border-bottom: 1px solid var(--project-alpha-border-light);
  background: var(--project-alpha-panel-bg);
}

.app-dialog__title {
  min-width: 0;
  overflow: hidden;
  color: var(--project-alpha-text-primary);
  font-size: var(--project-alpha-font-size-lg);
  font-weight: var(--project-alpha-font-weight-semibold);
  line-height: var(--project-alpha-line-height-lg);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.app-dialog__header-actions {
  display: flex;
  align-items: center;
  flex: 0 0 auto;
  gap: var(--project-alpha-space-2);
}

.app-dialog__header-actions .el-button + .el-button {
  margin-left: 0;
}

.app-dialog__close.el-button {
  width: 32px;
  min-height: 32px !important;
  color: var(--project-alpha-text-muted);
  font-size: 18px;
}

.app-dialog__close.el-button:hover {
  color: var(--project-alpha-text-primary);
  background: var(--project-alpha-control-bg);
}

.app-dialog__body {
  width: min(100%, var(--app-dialog-content-width, 100%));
  margin: 0 auto;
  padding: var(--project-alpha-space-6);
  color: var(--project-alpha-text-primary);
}

.app-dialog__footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--project-alpha-space-2);
  min-height: 60px;
  padding: var(--project-alpha-space-3) var(--project-alpha-space-6);
  border-top: 1px solid var(--project-alpha-border-light);
  background: var(--project-alpha-table-toolbar-bg);
}

.app-dialog__footer .el-button + .el-button {
  margin-left: 0;
}

@media (max-width: 640px) {
  .app-dialog__header,
  .app-dialog__body,
  .app-dialog__footer {
    padding-right: var(--project-alpha-space-4);
    padding-left: var(--project-alpha-space-4);
  }
}
</style>
