<template>
  <AppDialog
    ref="dialogRef"
    :model-value="modelValue"
    :title="resolvedTitle"
    :width="width"
    :content-width="contentWidth"
    :busy="busy"
    :destroy-on-close="destroyOnClose"
    :close-on-click-modal="closeOnClickModal"
    :close-on-press-escape="closeOnPressEscape"
    :before-close="beforeClose"
    :body-class="['crud-dialog__body', bodyClass]"
    dialog-class="crud-dialog"
    @update:model-value="handleModelUpdate"
    @close="handleClose"
    @closed="emit('closed')"
  >
    <slot :mode="mode" />

    <template v-if="hasFooter" #footer>
      <slot name="footer" :mode="mode" :request-close="requestClose" :saving="busy" :save-disabled="blocked" :submit="handleSave">
        <el-button :disabled="busy" @click="handleCancel">
          {{ cancelText }}
        </el-button>
        <slot v-if="$slots.actions" name="actions" :mode="mode" :saving="busy" :save-disabled="blocked" :submit="handleSave" />
        <el-button
          v-else
          type="primary"
          :loading="busy"
          :disabled="blocked"
          @click="handleSave"
        >
          {{ saveText }}
        </el-button>
      </slot>
    </template>
  </AppDialog>
</template>

<script setup>
/** CRUD 容器等待完整保存 Promise，并拦截关闭动画期间的重复操作。
 * @author Andersen
 */
import { useDialogSubmission } from "../overlay/useDialogSubmission.js";
import AppDialog from "@/components/overlay/AppDialog.vue";
import {
  CRUD_MODE,
  CRUD_MODE_TITLE,
  CRUD_MODE_VALUES,
} from "./crudModes.js";

const props = defineProps({
  modelValue: {
    type: Boolean,
    default: false,
  },
  mode: {
    type: String,
    default: CRUD_MODE.CREATE,
    validator: (value) => CRUD_MODE_VALUES.includes(value),
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
  cancelText: {
    type: String,
    default: "取消",
  },
  showActions: {
    type: Boolean,
    default: true,
  },
  showHeaderActions: {
    type: Boolean,
    default: undefined,
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
  "save",
  "error",
  "cancel",
  "close",
  "closed",
]);

const slots = useSlots();
const dialogRef = ref(null);

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

/** 使用业务标题覆盖中立 CRUD 模式标题。 */
const resolvedTitle = computed(() => {
  return props.title || CRUD_MODE_TITLE[props.mode] || "";
});

/** 兼容旧 showHeaderActions 参数，但所有实体弹窗动作统一渲染到底部。 */
const actionsVisible = computed(() => {
  return props.showHeaderActions === undefined
    ? props.showActions
    : props.showHeaderActions;
});

/** 详情模式默认无动作，自定义 Footer 仍可用于只读结果等受控场景。 */
const hasFooter = computed(() => {
  return Boolean(slots.footer) || (
    actionsVisible.value && props.mode !== CRUD_MODE.DETAIL
  );
});

/** 关闭事件立即锁定保存入口，不等待父组件更新。 */
const handleModelUpdate = (value) => {
  if (!value) markClosing();
  emit("update:modelValue", value);
};

/** 复用公共弹窗关闭守卫请求关闭。 */
const requestClose = () => {
  return dialogRef.value?.requestClose?.();
};

/** 取消实体新增并关闭弹窗。 */
const handleCancel = () => {
  if (busy.value) return;
  emit("cancel");
  requestClose();
};

defineExpose({ requestClose });
</script>

<style lang="scss">
.crud-dialog .crud-dialog__body {
  min-width: 0;
}
</style>
