<!-- Alpha 根节点统一承载路由、确认和通知弹层。@author Andersen -->
<script setup>
import ConfirmDialog from "@/components/ConfirmDialog.vue";
import { Toaster } from "@/components/ui/sonner";
</script>

<template>
  <RouterView />
  <ConfirmDialog />
  <Toaster
    position="bottom-right"
    :rich-colors="true"
    container-aria-label="操作通知"
  />
</template>
