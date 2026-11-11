<template>
  <div
    v-if="loading || error || empty"
    class="async-state"
    :style="stateStyle"
    role="status"
    aria-live="polite"
  >
    <slot v-if="loading" name="loading">
      <el-icon class="async-state__icon is-loading" :size="24"><Loading /></el-icon>
      <div class="async-state__title">{{ loadingText }}</div>
    </slot>

    <slot v-else-if="error" name="error" :error="error" :retry="retry">
      <el-icon class="async-state__icon is-error" :size="24"><WarningFilled /></el-icon>
      <div class="async-state__title">{{ resolvedErrorText }}</div>
      <el-button v-if="retryable" type="primary" plain @click="retry">重试</el-button>
    </slot>

    <slot v-else name="empty">
      <el-empty :description="emptyText" :image-size="emptyImageSize" />
    </slot>
  </div>
  <slot v-else />
</template>

<script setup>
import { Loading, WarningFilled } from "@element-plus/icons-vue";

const props = defineProps({
  loading: {
    type: Boolean,
    default: false,
  },
  error: {
    type: [Error, String, Object],
    default: null,
  },
  empty: {
    type: Boolean,
    default: false,
  },
  loadingText: {
    type: String,
    default: "加载中...",
  },
  errorText: {
    type: String,
    default: "数据加载失败",
  },
  emptyText: {
    type: String,
    default: "暂无数据",
  },
  retryable: {
    type: Boolean,
    default: true,
  },
  minHeight: {
    type: [String, Number],
    default: 180,
  },
  emptyImageSize: {
    type: Number,
    default: 72,
  },
});

const emit = defineEmits(["retry"]);

/** 优先展示调用方文案，其次展示错误对象中的安全摘要。 */
const resolvedErrorText = computed(() => {
  if (props.errorText !== "数据加载失败") {
    return props.errorText;
  }
  return props.error?.message || String(props.error || props.errorText);
});

/** 将数字最小高度规范化为 CSS 长度。 */
const stateStyle = computed(() => ({
  minHeight: typeof props.minHeight === "number"
    ? `${props.minHeight}px`
    : props.minHeight,
}));

/** 请求调用方重新加载当前数据。 */
const retry = () => emit("retry");
</script>

<style lang="scss" scoped>
.async-state {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: var(--project-alpha-space-3);
  width: 100%;
  color: var(--project-alpha-text-muted);
}

.async-state__icon.is-loading {
  color: var(--project-alpha-primary);
  animation: async-state-rotate 1s linear infinite;
}

.async-state__icon.is-error {
  color: var(--project-alpha-danger);
}

.async-state__title {
  font-size: var(--project-alpha-font-size-sm);
}

@keyframes async-state-rotate {
  to { transform: rotate(360deg); }
}
</style>
