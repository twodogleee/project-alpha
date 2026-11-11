<script setup>
/** 定义交付版本名称和目标，不提交生命周期或生成来源。@author Andersen */
import { reactive, watch, computed } from "vue";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { confirmAction } from "@/lib/feedback.js";
import { Button } from "@/components/ui/button";
/** 形成草稿版本和手工版本复用名称输入，提交由页面负责。 */
const props = defineProps({
  open: Boolean,
  busy: Boolean,
  fromDraft: Boolean,
  error: String,
});
/** 关闭与提交都是显式事件，不隐藏保存动作。 */
const emit = defineEmits(["update:open", "submit"]);
/** 当前弹窗局部副本。 */
const form = reactive({ label: "", goal: "" });
/** 必填与长度约束与服务器一致。 */
const valid = computed(
  () =>
    form.label.trim() &&
    form.goal.trim() &&
    form.label.length <= 120 &&
    form.goal.length <= 4000,
);
/** 关闭和 ESC 保护未提交的版本定义，成功提交由调用方关闭。 */
async function close(value) {
  if (value) {
    emit("update:open", true);
    return;
  }
  if (props.busy) return;
  if (
    (form.label || form.goal) &&
    !(await confirmAction({
      title: "放弃版本定义",
      description: "尚未提交的名称和目标会被放弃。",
      action: "放弃输入",
    }))
  )
    return;
  emit("update:open", false);
}
watch(
  () => props.open,
  (value) => {
    if (value) {
      form.label = "";
      form.goal = "";
    }
  },
);
</script>
<template>
  <Dialog :open="open" @update:open="close">
    <DialogContent
      ><DialogHeader
        ><DialogTitle>{{
          fromDraft ? "形成需求版本" : "新建需求版本"
        }}</DialogTitle
        ><DialogDescription
          >版本承载一次交付范围，保存记录在版本内部累积。名称可以使用
          v1.0、一期客户管理等。</DialogDescription
        ></DialogHeader
      >
      <form
        class="space-y-5"
        @submit.prevent="
          emit('submit', { label: form.label.trim(), goal: form.goal.trim() })
        "
      >
        <div class="space-y-2">
          <label for="version-label">版本名称</label
          ><Input
            id="version-label"
            v-model="form.label"
            maxlength="120"
            placeholder="例如 v1.0 · 客户导入"
            :disabled="busy"
          />
        </div>
        <div class="space-y-2">
          <label for="version-goal">交付目标</label
          ><Textarea
            id="version-goal"
            v-model="form.goal"
            maxlength="4000"
            placeholder="这个版本要解决什么问题，完成后达到什么效果？"
            :disabled="busy"
          />
        </div>
        <p class="text-xs text-muted-foreground">
          {{
            fromDraft
              ? "使用当前已保存草稿，不包含尚未保存的输入。"
              : "先定义目标和版本，之后可以通过会话补充需求范围。"
          }}
        </p>
        <p v-if="error" class="text-sm text-destructive">{{ error }}</p>
        <DialogFooter
          ><Button
            type="button"
            variant="outline"
            :disabled="busy"
            @click="close(false)"
            >取消</Button
          ><Button :disabled="busy || !valid">{{
            busy ? "正在保存…" : fromDraft ? "形成版本" : "创建版本"
          }}</Button></DialogFooter
        >
      </form>
    </DialogContent>
  </Dialog>
</template>
