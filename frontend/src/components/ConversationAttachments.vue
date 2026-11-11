<script setup>
/** 待发送附件只保留在当前输入区；发送后原文由服务端任务快照持久化。@author Andersen */
import { ref, onBeforeUnmount } from 'vue';
import { Paperclip, X } from '@lucide/vue';
import { Button } from '@/components/ui/button';
import { readAttachments } from '@/workspace/attachments.js';
/** 外部控制待发送批次，禁用时不允许改变正在提交的输入。 */
const props = defineProps({ files: { type: Array, default: () => [] }, disabled: Boolean });
/** 读取状态阻止父级在文件未完成读取时发送。 */
const emit = defineEmits(['change', 'reading', 'preview']);
/** 文件选择器和错误反馈不写入 PRD 或浏览器持久存储。 */
const picker = ref(null), reading = ref(false), error = ref('');
/** 卸载后忽略迟到的本地文件读取结果。 */
let disposed = false;
/** 一批文件作为一个操作校验，失败保留已有附件。 */
async function choose(event) {
  const selected = Array.from(event.target.files || []); event.target.value = '';
  if (!selected.length || props.disabled || reading.value) return;
  reading.value = true; emit('reading', true); error.value = '';
  try { const next = await readAttachments(selected, props.files); if (!disposed) emit('change', next); }
  catch (cause) { if (!disposed) error.value = cause.message; }
  finally { if (!disposed) { reading.value = false; emit('reading', false); } }
}
onBeforeUnmount(() => { disposed = true; });
</script>
<template>
  <div class="conversation-attachments">
    <input ref="picker" type="file" accept=".md,.txt,text/plain,text/markdown" multiple hidden aria-label="选择参考附件" @change="choose" />
    <div v-for="(file, index) in files" :key="file.name" class="attachment-chip"><button type="button" class="attachment-name" @click="emit('preview', file)">{{ file.name }}</button><Button type="button" size="icon" variant="ghost" :disabled="disabled || reading" :aria-label="`移除附件 ${file.name}`" @click="emit('change', files.filter((_, position) => position !== index))"><X class="size-3" /></Button></div>
    <Button type="button" variant="ghost" size="sm" :disabled="disabled || reading || files.length >= 3" @click="picker.click()"><Paperclip class="size-3.5" />{{ reading ? '正在读取…' : '添加附件' }}</Button>
    <p class="attachment-hint">MD / TXT · 每份 ≤ 256 KB · 最多 3 份；最近一批附件用于后续讨论。</p>
    <p v-if="files.length" class="attachment-hint">待发送 · 点击发送后作为参考资料分析。</p>
    <p v-if="error" role="alert" class="text-destructive text-xs">{{ error }}</p>
  </div>
</template>
<style scoped>
.conversation-attachments { margin-top: 8px; }.attachment-chip { display: flex; align-items: center; gap: 4px; background: var(--muted); border-radius: 6px; margin-bottom: 6px; padding-left: 8px; }.attachment-name { flex: 1; min-width: 0; overflow-wrap: anywhere; font-size: 12px; text-align: left; }.attachment-chip > button:last-child { width: 26px; height: 26px; flex-shrink: 0; }.attachment-hint { font-size: 11px; line-height: 1.6; color: var(--muted-foreground); margin: 4px 0; }
</style>
