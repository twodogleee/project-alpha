<!-- shadcn-vue 官方组件源码，使用 Reka UI 原语；业务规则由页面维护。来源与许可见本目录 README。 -->
<script setup>
import { cn } from "@/lib/utils";

const props = defineProps({
  class: { type: null, required: false },
});
</script>

<template>
  <th
    data-slot="table-head"
    :class="
      cn(
        'text-foreground h-10 px-2 text-left align-middle font-medium whitespace-nowrap [&:has([role=checkbox])]:pr-0 *:[[role=checkbox]]:translate-y-0.5',
        props.class,
      )
    "
  >
    <slot />
  </th>
</template>
