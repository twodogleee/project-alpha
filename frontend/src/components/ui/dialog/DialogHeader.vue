<!-- shadcn-vue 官方组件源码，使用 Reka UI 原语；业务规则由页面维护。来源与许可见本目录 README。 -->
<script setup>
import { cn } from "@/lib/utils";

const props = defineProps({
  class: { type: null, required: false },
});
</script>

<template>
  <div
    data-slot="dialog-header"
    :class="cn('flex flex-col gap-2 text-center sm:text-left', props.class)"
  >
    <slot />
  </div>
</template>
