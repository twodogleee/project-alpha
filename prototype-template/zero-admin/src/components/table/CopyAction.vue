<template>
  <el-button
    v-if="copyable"
    class="table-copy-action"
    link
    type="primary"
    :icon="CopyDocument"
    :loading="copying"
    :title="title"
    :aria-label="title"
    @click.stop="handleCopy"
  >
    <span v-if="showLabel">{{ label }}</span>
  </el-button>
</template>

<script setup>
import { CopyDocument } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";
import {
  normalizeClipboardText,
  useClipboardAction,
} from "@/composables/useClipboardAction.js";

const props = defineProps({
  value: {
    type: [String, Number, Boolean],
    default: undefined,
  },
  title: {
    type: String,
    default: "复制",
  },
  label: {
    type: String,
    default: "复制",
  },
  showLabel: {
    type: Boolean,
    default: false,
  },
  successMessage: {
    type: String,
    default: "复制成功",
  },
  errorMessage: {
    type: String,
    default: "复制失败，请手动复制",
  },
});

const emit = defineEmits(["success", "error"]);

const clipboard = useClipboardAction({
  onSuccess: (value) => {
    ElMessage.success(props.successMessage);
    emit("success", value);
  },
  onError: (error) => {
    ElMessage.error(props.errorMessage);
    emit("error", error);
  },
});

/** 当前值是否允许执行复制。 */
const copyable = computed(() => normalizeClipboardText(props.value) !== null);

/** 执行复制并阻止事件处理器产生未捕获 Promise。 */
const handleCopy = async () => {
  try {
    await clipboard.copy(props.value);
  } catch (error) {
    // 错误已由 useClipboardAction 的 onError 统一反馈。
  }
};

const copying = clipboard.copying;

defineExpose({ copy: handleCopy });
</script>

<style lang="scss" scoped>
.table-copy-action.el-button {
  flex: 0 0 auto;
  min-height: 24px !important;
}
</style>
