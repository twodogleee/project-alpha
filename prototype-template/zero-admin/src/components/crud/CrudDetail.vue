<template>
  <dl ref="detailRef" class="crud-detail" :style="detailStyle">
    <slot />
  </dl>
</template>

<script setup>
import {
  countResolvedGridColumns,
  resolveDetailItemSpans,
} from "./detailLayout.js";

const props = defineProps({
  columns: {
    type: [String, Number],
    default: "auto",
    validator: (value) => value === "auto" || [1, 2].includes(Number(value)),
  },
  minColumnWidth: {
    type: [String, Number],
    default: 320,
  },
  fillRemainder: {
    type: Boolean,
    default: true,
  },
});

const detailRef = ref(null);
let layoutFrame = 0;
let resizeObserver = null;
let previousInlineSize = null;
let previousLayoutSignature = "";
let previousItems = [];

/** 将数字尺寸规范化为合法 CSS 长度。 */
const normalizeSize = (value) => {
  return typeof value === "number" ? `${value}px` : value;
};

/** 根据容器用途生成结构化详情的响应式列布局。 */
const detailStyle = computed(() => {
  if (props.columns !== "auto") {
    return {
      gridTemplateColumns: `repeat(${Number(props.columns)}, minmax(0, 1fr))`,
    };
  }
  const minWidth = normalizeSize(props.minColumnWidth);
  return {
    gridTemplateColumns: `repeat(auto-fit, minmax(min(100%, ${minWidth}), 1fr))`,
  };
});

/** 清除字段上由详情容器维护的自动横跨列数。 */
const clearItemAutoSpan = (item) => {
  item.style.removeProperty("--crud-detail-auto-span");
};

/** 根据当前可见字段和实际列数补齐每段详情的末行。 */
const balanceDetailRows = () => {
  const detailElement = detailRef.value;
  if (!detailElement) return;

  const items = Array.from(detailElement.children).filter((item) =>
    item.classList.contains("crud-detail-item")
  );
  const columnCount = countResolvedGridColumns(
    window.getComputedStyle(detailElement).gridTemplateColumns
  );
  const fullStates = items.map((item) => item.classList.contains("is-full"));
  const spans = props.fillRemainder
    ? resolveDetailItemSpans(fullStates, columnCount)
    : items.map(() => 1);
  const layoutSignature = `${props.fillRemainder}:${columnCount}:${fullStates.join(
    ","
  )}:${spans.join(",")}`;
  const itemsUnchanged =
    items.length === previousItems.length &&
    items.every((item, index) => item === previousItems[index]);
  if (layoutSignature === previousLayoutSignature && itemsUnchanged) return;

  previousLayoutSignature = layoutSignature;
  previousItems = items;
  items.forEach((item, index) => {
    const span = spans[index];
    if (span > 1 && !fullStates[index]) {
      item.style.setProperty("--crud-detail-auto-span", String(span));
      return;
    }
    clearItemAutoSpan(item);
  });
};

/** 将同一渲染帧内的详情更新合并为一次布局计算。 */
const scheduleDetailLayout = () => {
  if (layoutFrame) return;
  layoutFrame = window.requestAnimationFrame(() => {
    layoutFrame = 0;
    balanceDetailRows();
  });
};

onMounted(() => {
  scheduleDetailLayout();
  if (typeof ResizeObserver === "undefined" || !detailRef.value) return;

  resizeObserver = new ResizeObserver(([entry]) => {
    const inlineSize = entry?.contentRect?.width;
    if (inlineSize === previousInlineSize) return;
    previousInlineSize = inlineSize;
    scheduleDetailLayout();
  });
  resizeObserver.observe(detailRef.value);
});

onUpdated(scheduleDetailLayout);

onBeforeUnmount(() => {
  resizeObserver?.disconnect();
  if (layoutFrame) window.cancelAnimationFrame(layoutFrame);
  Array.from(detailRef.value?.children || []).forEach(clearItemAutoSpan);
  previousItems = [];
});
</script>

<style lang="scss">
.crud-detail {
  display: grid;
  margin: 0;
  overflow: hidden;
  border-top: 1px solid var(--project-alpha-border);
  border-left: 1px solid var(--project-alpha-border);
  border-radius: var(--project-alpha-radius-md);
}

.crud-detail .crud-detail-item.is-full {
  grid-column: 1 / -1;
}

.crud-detail .crud-detail-item:not(.is-full) {
  grid-column: span var(--crud-detail-auto-span, 1);
}

@media (max-width: 640px) {
  .crud-detail {
    grid-template-columns: minmax(0, 1fr) !important;
  }
}
</style>
