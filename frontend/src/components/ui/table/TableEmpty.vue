<!-- shadcn-vue 官方组件源码，使用 Reka UI 原语；业务规则由页面维护。来源与许可见本目录 README。 -->
<script setup>
import { reactiveOmit } from "@vueuse/core";
import { cn } from "@/lib/utils";
import TableCell from "./TableCell.vue";
import TableRow from "./TableRow.vue";

const props = defineProps({
  class: { type: null, required: false },
  colspan: { type: Number, required: false, default: 1 },
});

const delegatedProps = reactiveOmit(props, "class");
</script>

<template>
  <TableRow>
    <TableCell
      :class="
        cn(
          'p-4 whitespace-nowrap align-middle text-sm text-foreground',
          props.class,
        )
      "
      v-bind="delegatedProps"
    >
      <div class="flex items-center justify-center py-10">
        <slot />
      </div>
    </TableCell>
  </TableRow>
</template>
