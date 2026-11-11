<script setup>
/** 全局纯文本确认弹窗，危险操作和离开保护使用相同契约。@author Andersen */
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { confirmation, settleConfirmation } from "@/lib/feedback.js";
</script>

<template>
  <Dialog
    :open="confirmation.open"
    @update:open="!$event && settleConfirmation(false)"
  >
    <!-- 全局确认可由业务弹窗触发，遮罩和内容均高于普通弹窗的 z-50。 -->
    <DialogContent class="z-[61]" overlay-class="z-[60] bg-black/30">
      <DialogHeader
        ><DialogTitle>{{ confirmation.title }}</DialogTitle
        ><DialogDescription class="leading-7">{{
          confirmation.description
        }}</DialogDescription></DialogHeader
      >
      <DialogFooter
        ><Button variant="outline" @click="settleConfirmation(false)">{{
          confirmation.destructive ? "取消" : "继续编辑"
        }}</Button
        ><Button
          :variant="confirmation.destructive ? 'destructive' : 'default'"
          @click="settleConfirmation(true)"
          >{{ confirmation.action }}</Button
        ></DialogFooter
      >
    </DialogContent>
  </Dialog>
</template>
