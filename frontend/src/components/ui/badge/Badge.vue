<!-- shadcn-vue 官方组件源码，使用 Reka UI 原语；业务规则由页面维护。来源与许可见本目录 README。 -->
<script setup>
import { reactiveOmit } from "@vueuse/core";
import { Primitive } from "reka-ui";
import { cn } from "@/lib/utils";
import { badgeVariants } from ".";

const props = defineProps({
  asChild: { type: Boolean, required: false },
  as: { type: null, required: false },
  variant: { type: null, required: false },
  class: { type: null, required: false },
});

const delegatedProps = reactiveOmit(props, "class");
</script>

<template>
  <Primitive
    data-slot="badge"
    :class="cn(badgeVariants({ variant }), props.class)"
    v-bind="delegatedProps"
  >
    <slot />
  </Primitive>
</template>
