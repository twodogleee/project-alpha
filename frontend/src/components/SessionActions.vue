<script setup>
/** 会话列表与工作区共用的业务操作菜单，写入前协调当前编辑页保存。@author Andersen */
import { ref, onBeforeUnmount } from 'vue';
import { useRouter } from 'vue-router';
import { MoreHorizontal, Pencil, Archive, Trash2 } from '@lucide/vue';
import { DropdownMenuRoot, DropdownMenuTrigger, DropdownMenuPortal, DropdownMenuContent, DropdownMenuItem } from 'reka-ui';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog';
import { useWorkspace } from '@/lib/workspace.js';
import { confirmAction } from '@/lib/feedback.js';
import { toast } from 'vue-sonner';
import * as api from '@/api/workspace.js';
import { projectPath } from '@/workspace/state.js';
/** 只使用稳定身份；提交前读取最新状态和影响范围。 */
const props = defineProps({ session: { type: Object, required: true }, disabled: Boolean });
/** 成功结果通知当前列表刷新。 */
const emit = defineEmits(['changed']);
/** 业务状态局限当前操作实例。 */
const shell = useWorkspace(), router = useRouter(), busy = ref(false), submitting = ref(false), rename = ref(null), title = ref(''), error = ref('');
/** 保存保护在弹窗关闭、请求失败或组件卸载时释放。 */
let release, disposed = false;
/** 统一释放编辑页的短期操作锁。 */
function unlock() { release?.(); release = null; busy.value = false; }
/** 准备操作期间阻止重复提交，当前页面保存失败时保留原输入。 */
async function prepare(kind) {
  if (busy.value || props.disabled) return false;
  busy.value = true; error.value = '';
  release = await shell?.prepareSessionAction(props.session.id, kind);
  return !disposed;
}
/** 成功后更新目录；移除当前会话时返回所属项目，重命名则留在原位置。 */
async function complete(record, removed = false) {
  const current = shell?.currentSessionId() === record.id;
  unlock(); shell?.sessionChanged(record); emit('changed', record);
  await shell?.refreshSessions();
  if (removed && current) await router.push(projectPath(record.projectId));
}
/** 改名使用最新会话修订，网络失败保留输入。 */
async function beginRename() {
  try {
    if (!(await prepare('rename'))) return;
    const detail = await api.loadSession(props.session.id);
    if (disposed) return;
    rename.value = detail.session; title.value = detail.session.title;
  } catch (cause) { toast.error(cause.message); unlock(); }
}
/** 保存名称成功后才关闭弹窗。 */
async function saveName() {
  if (!rename.value || !title.value.trim() || submitting.value) return;
  submitting.value = true;
  try {
    const record = await api.renameSession(rename.value.id, { expectedRevision: rename.value.revision, title: title.value.trim() });
    rename.value = null; await complete(record); toast.success('会话名称已更新');
  } catch (cause) { error.value = cause.message; }
  finally { submitting.value = false; }
}
/** 未保存名称关闭时仍可选择继续编辑。 */
async function closeName() {
  if (submitting.value) return;
  if (rename.value && title.value !== rename.value.title && !(await confirmAction({ title: '放弃名称修改？', description: '会话原名称保持不变。', action: '放弃修改' }))) return;
  rename.value = null; unlock();
}
/** 删除确认展示服务器当前共享关系；一次确认覆盖会话和独立文档的回收范围。 */
async function remove(archive = false) {
  try {
    if (!(await prepare(archive ? 'archive' : 'delete'))) return;
    const preview = await api.sessionDeletionPreview(props.session.id);
    if (disposed) return;
    const description = archive ? '会话移入归档，关联 PRD 与历史保留，可在“归档与回收站”恢复。'
      : preview.includesPrd ? '会话消息、附件、独立 PRD、待确认事项和历史版本将一并移入回收站，可恢复。'
      : preview.otherSessions ? `这份 PRD 还关联 ${preview.otherSessions} 个会话。本次仅将当前会话移入回收站，其他会话与 PRD 的现有状态保持不变。`
      : '会话及消息、附件移入回收站，可恢复。';
    if (!(await confirmAction({ title: `${archive ? '归档' : '删除'}会话「${preview.session.title}」`, description, action: archive ? '归档会话' : '移入回收站', destructive: !archive })) || disposed) return;
    const record = archive ? await api.archiveSession(props.session.id, { expectedRevision: preview.session.revision, archived: true })
      : await api.deleteSession(props.session.id, { expectedRevision: preview.session.revision, expectedVersionRevision: preview.versionRevision, expectedDocumentRevision: preview.documentRevision, includesPrd: preview.includesPrd, deleted: true });
    await complete(record, true); toast.success(archive ? '会话已归档' : '会话已移入回收站');
  } catch (cause) { toast.error(cause.message); }
  finally { unlock(); }
}
onBeforeUnmount(() => { disposed = true; unlock(); });
</script>
<template>
  <DropdownMenuRoot>
    <DropdownMenuTrigger as-child><Button variant="ghost" size="icon" class="size-7 shrink-0" :aria-label="`会话操作：${session.title}`" :disabled="disabled || busy || !!session.deletedAt"><MoreHorizontal class="size-4" /></Button></DropdownMenuTrigger>
    <DropdownMenuPortal><DropdownMenuContent side="bottom" align="end" :side-offset="4" class="session-menu">
      <DropdownMenuItem :disabled="!!session.archivedAt" class="session-menu-item" @select="beginRename"><Pencil class="size-4" />重命名</DropdownMenuItem>
      <DropdownMenuItem :disabled="!!session.archivedAt" class="session-menu-item" @select="remove(true)"><Archive class="size-4" />归档会话</DropdownMenuItem>
      <DropdownMenuItem class="session-menu-item text-destructive" @select="remove(false)"><Trash2 class="size-4" />删除会话</DropdownMenuItem>
    </DropdownMenuContent></DropdownMenuPortal>
  </DropdownMenuRoot>
  <Dialog :open="!!rename" @update:open="!$event && closeName()"><DialogContent><DialogHeader><DialogTitle>重命名会话</DialogTitle><DialogDescription>修改会话名称，PRD 和历史版本保持原样。</DialogDescription></DialogHeader>
    <form @submit.prevent="saveName"><label for="session-new-title" class="text-sm">会话名称</label><Input id="session-new-title" v-model="title" :disabled="submitting" maxlength="120" class="mt-2" /><p v-if="error" role="alert" class="text-destructive text-sm mt-3">{{ error }}</p><div class="flex justify-end gap-2 mt-5"><Button type="button" variant="ghost"  :disabled="submitting" @click="closeName">取消</Button><Button :disabled="submitting || !title.trim()">保存名称</Button></div></form>
  </DialogContent></Dialog>
</template>
<style scoped>
/* Reka 浮层根节点不透传当前组件的 scope 属性；用专属全局类保证 Portal 中的背景和层级生效。 */
:global(.session-menu) {
  z-index: 60;
  min-width: 156px;
  padding: 5px;
  background: var(--popover);
  color: var(--popover-foreground);
  border: 1px solid var(--border);
  border-radius: 8px;
  box-shadow: 0 6px 20px #00000015;
}
.session-menu-item { display: flex; align-items: center; gap: 9px; padding: 9px 12px; font-size: 13px; border-radius: 4px; cursor: pointer; outline: none; }
.session-menu-item[data-highlighted] { background: var(--accent); }
.session-menu-item[data-disabled] { opacity: .45; pointer-events: none; }
</style>
