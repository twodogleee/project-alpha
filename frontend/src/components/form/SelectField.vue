<script setup>
/** 项目统一单选控件，负责主题、键盘和浮层交互；业务值与保存由调用方管理。@author Andersen */
import { computed } from 'vue';
import { cn } from '@/lib/utils';
import { Check, ChevronDown, ChevronUp } from '@lucide/vue';
import { SelectRoot, SelectTrigger, SelectValue, SelectPortal, SelectContent, SelectViewport, SelectItem, SelectItemText, SelectItemIndicator, SelectScrollUpButton, SelectScrollDownButton } from 'reka-ui';
/** 标签、辅助说明和焦点事件只转发到真正的触发按钮。 */
defineOptions({ inheritAttrs: false });
/** 单选契约支持字符串、数字和 null；空字符串也是可选业务值。 */
const props = defineProps({
  /** 当前业务值，未匹配到选项时展示占位文字。 */
  modelValue: { type: [String, Number], default: null },
  /** 唯一值的选项集合：{ value, label, disabled? }，不接受 HTML 标签。 */
  options: { type: Array, required: true },
  /** 禁止展开和改变业务值。 */
  disabled: Boolean,
  /** 未匹配选项时展示的辅助文字。 */
  placeholder: { type: String, default: '请选择' },
});
/** 只通知有效选择变化，不因展开、取消或选项加载而触发保存。 */
const emit = defineEmits(['update:modelValue']);
/** 非空内部键避开原语保留的空串，并区分数字、字符串及 null。 */
function optionKey(value) { return JSON.stringify([typeof value, value]); }
/** 动态选项更新仍按业务值定位，不依赖列表索引。 */
const selectedKey = computed(() => props.options.some(option => option.value === props.modelValue) ? optionKey(props.modelValue) : '');
/** 只回传选项原始值，保留清空值语义并拒绝禁用或已移除的选项。 */
function choose(key) {
  const option = props.options.find(item => optionKey(item.value) === key);
  if (!props.disabled && option && !option.disabled && option.value !== props.modelValue) emit('update:modelValue', option.value);
}
</script>

<template>
  <SelectRoot :model-value="selectedKey" :disabled="disabled" @update:model-value="choose">
    <SelectTrigger v-bind="$attrs" :class="cn('border-input bg-background flex h-9 w-full items-center justify-between gap-2 rounded-md border px-3 text-sm shadow-xs outline-none transition-[color,box-shadow] focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-3 disabled:cursor-not-allowed disabled:opacity-50 min-w-0', $attrs.class)">
      <SelectValue :placeholder="placeholder" class="min-w-0 truncate text-left" />
      <ChevronDown class="text-muted-foreground size-4 shrink-0" aria-hidden="true" />
    </SelectTrigger>
    <SelectPortal>
      <SelectContent position="popper" align="start" :side-offset="4" :collision-padding="8" class="bg-popover text-popover-foreground z-50 max-h-[min(20rem,var(--reka-select-content-available-height))] w-[var(--reka-select-trigger-width)] max-w-[calc(100vw-1rem)] overflow-hidden rounded-md border shadow-md">
        <SelectScrollUpButton class="flex items-center justify-center py-1"><ChevronUp class="size-4" aria-hidden="true" /></SelectScrollUpButton>
        <SelectViewport class="p-1">
          <p v-if="!options.length" class="text-muted-foreground px-2 py-3 text-sm">暂无可选项</p>
          <SelectItem v-for="option in options" :key="optionKey(option.value)" :value="optionKey(option.value)" :disabled="option.disabled" :text-value="option.label" class="data-[highlighted]:bg-accent data-[highlighted]:text-accent-foreground data-[disabled]:pointer-events-none data-[disabled]:opacity-50 relative flex min-h-8 cursor-default select-none items-center rounded-sm py-1.5 pr-8 pl-2 text-sm outline-none">
            <SelectItemText class="min-w-0 whitespace-normal break-words">{{ option.label }}</SelectItemText>
            <SelectItemIndicator class="absolute right-2 flex items-center"><Check class="size-4" aria-hidden="true" /></SelectItemIndicator>
          </SelectItem>
        </SelectViewport>
        <SelectScrollDownButton class="flex items-center justify-center py-1"><ChevronDown class="size-4" aria-hidden="true" /></SelectScrollDownButton>
      </SelectContent>
    </SelectPortal>
  </SelectRoot>
</template>
