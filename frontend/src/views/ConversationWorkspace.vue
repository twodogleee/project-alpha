<script setup>
import SelectField from '@/components/form/SelectField.vue';
/** 持久化会话与可编辑候选产物，模型结果必须明确应用和保存。@author Andersen */
import { ref, computed, watch, onBeforeUnmount, nextTick } from "vue";
import { useRoute, useRouter } from "vue-router";
import { toast } from "vue-sonner";
import {
  Send,
  PanelRight,
  FileText,
  Archive,
  Pencil,
  Link,
  ArrowLeft,
  RefreshCw,
  Save,
  Sparkles,
  MessageSquare,
  X,
} from "@lucide/vue";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Badge } from "@/components/ui/badge";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from "@/components/ui/dialog";
import VersionDialog from "@/components/VersionDialog.vue";
import * as api from "@/api/workspace.js";
import { useWorkspace } from "@/lib/workspace.js";
import {
  useWorkspacePage,
  useWorkspaceLeave,
  useDialogClose,
} from "@/composables/useWorkspacePage.js";
import {
  pendingRun,
  latestRunFailure,
  messageRunFailure,
  mergeMessages,
  canApplyDraft,
  runCommand,
  projectPath,
  versionPath,
} from "@/workspace/state.js";
import { confirmAction } from "@/lib/feedback.js";
/** 页面对象身份。 */ const route = useRoute(),
  router = useRouter(),
  shell = useWorkspace();
/** 服务器记录与当前输入/草稿副本互相分离。 */ const detail = ref(null),
  messages = ref([]),
  older = ref(false),
  composer = ref(""),
  draft = ref(""),
  savedDraft = ref(""),
  draftBaseline = ref(0),
  sourceRunId = ref(null),
  panelOpen = ref(false),
  candidate = ref(null),
  promoteOpen = ref(false),
  renameOpen = ref(false),
  newTitle = ref(""),
  linkOpen = ref(false),
  linkTarget = ref(""),
  linkOptions = ref([]),
  linkPage = ref(1),
  linkMore = ref(false),
  messagePane = ref(null),
  draftConflict = ref(false);
/** 关闭仅收起本次查看的失败提示，服务端失败记录与消息仍保留。 */
const dismissedFailureId = ref(null);
/** 请求状态不包含后台模型任务忙碌，允许离开执行中的会话。 */ const {
  busy,
  loading,
  error,
  errorCode,
  read,
  act,
} = useWorkspacePage();
/** 已保存草稿与副本的实际差异。 */ const draftDirty = computed(
  () => draft.value !== savedDraft.value,
);
/** 未提交消息和产物编辑离开时受保护。 */ const dirty = computed(
  () =>
    draftDirty.value ||
    Boolean(composer.value.trim()) ||
    promoteOpen.value ||
    renameOpen.value ||
    linkOpen.value,
);
/** 归档或取消交付后消息和产物只读。 */ const readonly = computed(() =>
  Boolean(
    detail.value?.project.archivedAt ||
      detail.value?.session.archivedAt ||
      (detail.value?.version && detail.value.version.lifecycle !== "ACTIVE"),
  ),
);
/** 真实执行任务由服务器返回状态。 */ const running = computed(() =>
  pendingRun(detail.value?.runs),
);
/** 最新任务失败才突出提醒；新请求执行或成功后旧提醒自然消失。 */
const currentFailure = computed(() => latestRunFailure(detail.value?.runs, dismissedFailureId.value));
/** 已加载的历史失败按消息绑定，折叠展示并保留明确重试入口。 */
const messageFailures = computed(() => Object.fromEntries(messages.value
  .map(message => [message.id, messageRunFailure(message, detail.value?.runs)]).filter(([, run]) => run)));
/** 草稿必须先保存，才能沉淀或同步。 */ const readyDraft = computed(
  () =>
    detail.value?.session.draftRevision > 0 &&
    !draftDirty.value &&
    !running.value &&
    !readonly.value &&
    (!detail.value?.version || detail.value.version.stage === "REVIEW") &&
    !draftConflict.value,
);
/** 当前会话的局部请求幂等键在超时后复用。 */ let attempted = null;
/** 轮询取消与组件代数，防止跨会话回写。 */ let timer,
  disposed = false,
  epoch = 0,
  pollController;
useWorkspaceLeave(dirty, busy, () => {
  composer.value = "";
  draft.value = savedDraft.value;
  promoteOpen.value = false;
  renameOpen.value = false;
  linkOpen.value = false;
});
/** 应用服务器记录时不自动覆盖草稿副本。 */
function applyDetail(value, reset = false) {
  detail.value = value;
  if (reset) {
    draft.value = value.session.draftText;
    savedDraft.value = draft.value;
    draftBaseline.value = value.session.revision;
    sourceRunId.value = null;
    draftConflict.value = false;
    panelOpen.value = Boolean(draft.value);
  }
  candidate.value =
    value.runs.find((r) => r.kind === "DRAFT" && r.status === "SUCCEEDED") ||
    null;
}
/** 初次加载恢复任务和消息，归属不一致明确失败。 */
async function load() {
  await read(
    async (signal) => {
      const id = route.params.sessionId;
      const [value, page] = await Promise.all([
        api.loadSession(id, signal),
        api.loadMessages(id, undefined, signal),
      ]);
      if (value.session.projectId !== route.params.projectId)
        throw new Error("会话不属于当前项目");
      return [value, page];
    },
    ([value, page]) => {
      applyDetail(value, true);
      messages.value = page.items;
      older.value = page.hasMore;
      schedule();
      scrollBottom();
    },
  );
}
/** 有界轮询只更新服务器记录，终态停止；网络失败提供显式刷新。 */
function schedule() {
  clearTimeout(timer);
  if (running.value && !disposed) timer = setTimeout(poll, 1400);
}
/** 任务完成后读取新消息，页面切换取消旧状态请求。 */
async function poll() {
  const token = epoch,
    id = route.params.sessionId;
  pollController = new AbortController();
  try {
    const [value, page] = await Promise.all([
      api.loadSession(id, pollController.signal),
      api.loadMessages(id, undefined, pollController.signal),
    ]);
    if (disposed || token !== epoch) return;
    const wasRunning = running.value;
    applyDetail(value);
    messages.value = mergeMessages(messages.value, page.items);
    if (wasRunning && !running.value) {
      await shell.refreshSessions();
      scrollBottom();
    }
    schedule();
  } catch (e) {
    if (!disposed && token === epoch && !pollController.signal.aborted)
      error.value = e.message + "；点击刷新任务状态继续查看。";
  }
}
/** 保持阅读较早历史的能力，结果按稳定顺序合并。 */
async function moreMessages() {
  const result = await act(() =>
    api.loadMessages(route.params.sessionId, messages.value[0]?.sequence),
  );
  if (result) {
    messages.value = mergeMessages(result.items, messages.value);
    older.value = result.hasMore;
  }
}
/** 新消息提交成功才清空输入，HTTP 超时仍保留相同命令标识。 */
async function send(kind = "CHAT", retryContent) {
  if (readonly.value || running.value || busy.value) return;
  const content =
    kind === "CHAT" ? (retryContent ?? composer.value).trim() : "";
  if (kind === "CHAT" && !content) return;
  attempted = runCommand(attempted, route.params.sessionId, kind, content);
  const result = await act(() =>
    api.submitRun(route.params.sessionId, {
      requestId: attempted.requestId,
      kind,
      content,
    }),
  );
  if (result) {
    attempted = null;
    // 先采用服务端返回的任务，避免第一次轮询前仍把旧失败当成当前状态。
    detail.value.runs = [result, ...detail.value.runs.filter(run => run.id !== result.id)];
    if (kind === "CHAT" && retryContent === undefined) composer.value = "";
    await poll();
  }
}
/** 按原消息显式重新提交一个失败任务，不做自动重试。 */
function retry(run) {
  const input = messages.value.find(
    (m) => m.runId === run.id && m.role === "USER",
  )?.content;
  send(run.kind, input);
}
/** 重载前明确放弃本地编辑。 */
async function reload() {
  if (
    dirty.value &&
    !(await confirmAction({
      title: "重新载入会话",
      description: "当前未提交消息与未保存草稿会被放弃，已保存记录保留。",
      action: "放弃并载入",
    }))
  )
    return;
  composer.value = "";
  await load();
}
/** 候选结果应用只替换本地副本，还需要点击保存草稿。 */
async function applyCandidate(run) {
  if (!canApplyDraft(run, detail.value.session, detail.value.version)) {
    toast.error("候选基准已过期，请重新整理或手工合并");
    return;
  }
  if (
    draftDirty.value &&
    !(await confirmAction({
      title: "替换草稿编辑副本",
      description: "当前未保存的草稿将被候选内容替换。需求版本不会被自动修改。",
      action: "应用候选",
    }))
  )
    return;
  draft.value = run.resultText;
  sourceRunId.value = run.id;
  panelOpen.value = true;
}
/** 旧消息的草稿结果可按任务 ID 读取，列表截断不删除结果。 */
async function inspectResult(id) {
  const result = await act(() => api.loadRun(id));
  if (result?.kind === "DRAFT") {
    candidate.value = result;
    panelOpen.value = true;
  }
}
/** 保存只使用编辑开始时修订，轮询不会掩盖并发冲突。 */
async function persistDraft() {
  const result = await act(() =>
    api.saveDraft(route.params.sessionId, {
      expectedRevision: draftBaseline.value,
      content: draft.value,
      sourceRunId: sourceRunId.value,
    }),
  );
  if (result) {
    detail.value.session = result;
    draft.value = result.draftText;
    savedDraft.value = draft.value;
    draftBaseline.value = result.revision;
    sourceRunId.value = null;
    draftConflict.value = false;
    toast.success("草稿已保存，尚未自动修改需求版本");
  } else if (errorCode.value === "WORKSPACE_REVISION_CONFLICT")
    draftConflict.value = true;
}
/** 以已保存草稿复制出独立交付范围。 */
async function promote(data) {
  const result = await act(() =>
    api.promoteDraft(route.params.sessionId, {
      ...data,
      expectedRevision: detail.value.session.revision,
      draftRevision: detail.value.session.draftRevision,
    }),
  );
  if (result) {
    promoteOpen.value = false;
    await poll();
    draftBaseline.value = detail.value.session.revision;
    await shell.refreshSessions();
    toast.success("需求版本已形成");
  }
}
/** 同步前展示明确覆盖目标，保存记录仍保留旧范围。 */
async function sync() {
  if (
    !(await confirmAction({
      title: "同步到 " + detail.value.version.label,
      description:
        "将使用已保存会话草稿替换此版本的需求范围，并产生新的保存记录。不会推进阶段。",
      action: "同步需求范围",
    }))
  )
    return;
  const result = await act(() =>
    api.syncDraft(route.params.sessionId, {
      expectedRevision: detail.value.version.revision,
      draftRevision: detail.value.session.draftRevision,
    }),
  );
  if (result) {
    detail.value.version = result;
    toast.success("需求范围已同步");
  }
}
/** 改名成功刷新公开侧栏。 */
async function rename() {
  const result = await act(() =>
    api.renameSession(route.params.sessionId, {
      expectedRevision: detail.value.session.revision,
      title: newTitle.value.trim(),
    }),
  );
  if (result) {
    applyDetail({ ...detail.value, session: result }, true);
    renameOpen.value = false;
    await shell.refreshSessions();
  }
}
/** 加载同项目活动版本，不从其他项目取选项。 */
async function openLink(more = false) {
  const result = await act(() =>
    api.listRequirementVersions(route.params.projectId, {
      page: more ? linkPage.value + 1 : 1,
    }),
  );
  if (result) {
    linkOptions.value = more
      ? [...linkOptions.value, ...result.items]
      : result.items;
    linkPage.value = result.page;
    linkMore.value = result.hasMore;
    if (!more) {
      linkTarget.value = "";
      linkOpen.value = true;
    }
  }
}
/** 绑定版本不自动复制草稿。 */
async function link() {
  const result = await act(() =>
    api.linkVersion(route.params.sessionId, {
      expectedRevision: detail.value.session.revision,
      versionId: linkTarget.value,
    }),
  );
  if (result) {
    applyDetail({ ...detail.value, session: result }, true);
    linkOpen.value = false;
    await poll();
    await shell.refreshSessions();
  }
}
/** 会话归档不会取消或结束关联版本。 */
async function archive() {
  if (
    !(await confirmAction({
      title: "归档会话",
      description: "讨论移入归档记录，关联版本继续保留。未保存输入将被放弃。",
      action: "归档会话",
    }))
  )
    return;
  const result = await act(() =>
    api.archiveSession(route.params.sessionId, {
      expectedRevision: detail.value.session.revision,
      archived: true,
    }),
  );
  if (result) {
    composer.value = "";
    draft.value = savedDraft.value;
    await shell.refreshSessions();
    router.push(projectPath(route.params.projectId));
  }
}
/** 消息结束后滚到最新记录，不修改用户编辑。 */
async function scrollBottom() {
  await nextTick();
  if (messagePane.value)
    messagePane.value.scrollTop = messagePane.value.scrollHeight;
}
/** 会话元数据输入不会因关闭弹窗而静默丢失。 */
const closeRename = useDialogClose(
  busy,
  () => newTitle.value !== detail.value?.session.title,
  (value) => (renameOpen.value = value),
);
const closeLink = useDialogClose(
  busy,
  () => Boolean(linkTarget.value),
  (value) => (linkOpen.value = value),
);
watch(
  () => route.params.sessionId,
  () => {
    epoch++;
    clearTimeout(timer);
    pollController?.abort();
    detail.value = null;
    messages.value = [];
    composer.value = "";
    draft.value = "";
    savedDraft.value = "";
    candidate.value = null;
    attempted = null;
    dismissedFailureId.value = null;
    panelOpen.value = false;
    load();
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  disposed = true;
  epoch++;
  clearTimeout(timer);
  pollController?.abort();
});
</script>
<template>
  <div class="conversation-page">
    <div v-if="error" class="error-banner mb-4">
      {{ error
      }}<Button
        size="sm"
        variant="outline"
        :disabled="busy"
        @click="running ? poll() : reload()"
        >刷新任务状态 / 重新载入</Button
      >
    </div>
    <p v-if="loading && !detail" class="muted">正在恢复会话…</p>
    <template v-if="detail"
      ><div class="conversation-heading">
        <div class="min-w-0">
          <RouterLink
            :to="projectPath(detail.project.id)"
            class="text-xs text-muted-foreground flex gap-1 items-center"
            ><ArrowLeft class="size-3" />{{ detail.project.name }}</RouterLink
          >
          <h1 class="text-xl font-semibold mt-2 truncate">
            {{ detail.session.title }}
          </h1>
          <div class="flex gap-2 items-center mt-2 text-xs">
            <RouterLink
              v-if="detail.version"
              :to="versionPath(detail.version)"
              class="text-indigo-600"
              >{{ detail.version.label }} ·
              {{
                { REVIEW: "需求评审", PRD: "PRD", PROTOTYPE: "原型待接入" }[
                  detail.version.stage
                ] || detail.version.stage
              }}</RouterLink
            ><span v-else class="text-muted-foreground"
              >探索会话 · 尚未形成需求版本</span
            ><Badge v-if="readonly" variant="secondary">只读</Badge>
          </div>
        </div>
        <div class="flex gap-1 shrink-0">
          <Button
            variant="ghost"
            size="icon"
            aria-label="重命名会话"
            :disabled="readonly || busy || draftDirty"
            @click="
              newTitle = detail.session.title;
              renameOpen = true;
            "
            ><Pencil class="size-4" /></Button
          ><Button
            v-if="!detail.session.versionId"
            variant="ghost"
            size="icon"
            aria-label="关联已有需求版本"
            :disabled="readonly || busy || !!running || draftDirty"
            @click="openLink()"
            ><Link class="size-4" /></Button
          ><Button
            variant="ghost"
            size="icon"
            aria-label="归档会话"
            :disabled="
              !!detail.session.archivedAt ||
              busy ||
              !!running ||
              !!detail.project.archivedAt
            "
            @click="archive"
            ><Archive class="size-4" /></Button
          ><Button variant="outline" size="sm" @click="panelOpen = !panelOpen"
            ><PanelRight class="size-4" />{{
              panelOpen ? "收起草稿" : "需求草稿"
            }}</Button
          >
        </div>
      </div>
      <div :class="['conversation-layout', { 'with-artifact': panelOpen }]">
        <section class="chat-column">
          <div ref="messagePane" class="message-timeline">
            <Button
              v-if="older"
              variant="ghost"
              size="sm"
              :disabled="busy"
              @click="moreMessages"
              >加载更早消息</Button
            >
            <div v-if="!messages.length" class="chat-welcome">
              <span class="project-icon"><MessageSquare class="size-6" /></span>
              <h2>从一个业务问题开始</h2>
              <p>
                告诉我你想解决什么问题。讨论记录会保存在这个项目下，草稿可以形成独立的需求版本。
              </p>
              <div class="text-xs text-muted-foreground">
                {{
                  shell.state.runtime?.aiMode === "demo"
                    ? "当前是本地模板联调，不调用大模型。"
                    : "每次请求使用当前启用模型，生成来源随消息保留。"
                }}
              </div>
            </div>
            <article
              v-for="message in messages"
              :key="message.id"
              :class="[
                'chat-message',
                message.role === 'USER' ? 'from-user' : 'from-assistant',
              ]"
            >
              <div class="message-meta">
                <strong>{{ message.role === "USER" ? "你" : "Alpha" }}</strong
                ><span>{{
                  message.role === "ASSISTANT"
                    ? message.mode === "demo"
                      ? "本地模板 · 未调用大模型"
                      : message.model
                    : new Date(message.createdAt).toLocaleTimeString("zh-CN", {
                        hour: "2-digit",
                        minute: "2-digit",
                      })
                }}</span>
              </div>
              <p class="message-body">{{ message.content }}</p>
              <Button
                v-if="
                  message.role === 'ASSISTANT' &&
                  message.content.startsWith('已整理候选草稿')
                "
                variant="outline"
                size="sm"
                class="mt-3"
                :disabled="busy"
                @click="inspectResult(message.runId)"
                ><FileText class="size-4" />查看候选草稿</Button
              >
              <details v-if="messageFailures[message.id]" class="mt-3 text-sm text-muted-foreground">
                <summary class="cursor-pointer">{{ messageFailures[message.id].status === 'INTERRUPTED' ? '此请求已中断' : '此请求处理失败' }} · 查看详情</summary>
                <p class="mt-2 whitespace-pre-wrap break-words">{{ messageFailures[message.id].errorMessage }}</p>
                <Button type="button" variant="outline" size="sm" class="mt-2"
                  :disabled="busy || !!running || readonly" @click="retry(messageFailures[message.id])">重试此请求</Button>
              </details>
            </article>
            <div v-if="running" class="run-indicator">
              <span class="loading-dot" />{{
                running.status === "QUEUED"
                  ? "任务已保存，等待执行"
                  : "正在处理会话"
              }}<span class="text-xs text-muted-foreground"
                >可切换会话，结果会继续保存</span
              >
            </div>
            <div
              v-if="currentFailure"
              class="notice-banner my-4"
              role="status"
            >
              <div class="flex items-start justify-between gap-3">
                <p class="whitespace-pre-wrap break-words">{{ currentFailure.errorMessage }}</p>
                <Button type="button" variant="ghost" size="icon" class="shrink-0 size-7" aria-label="关闭失败提示"
                  @click="dismissedFailureId = currentFailure.id"><X class="size-4" /></Button>
              </div>
              <Button
                variant="outline"
                size="sm"
                class="mt-3"
                :disabled="busy || !!running || readonly"
                @click="retry(currentFailure)"
                >显式重试</Button
              >
            </div>
          </div>
          <form class="chat-composer" @submit.prevent="send()">
            <Textarea
              v-model="composer"
              aria-label="会话消息"
              placeholder="描述需求、补充规则，或继续追问…"
              maxlength="12000"
              class="min-h-24 border-0 shadow-none resize-none focus-visible:ring-0"
              :disabled="readonly || busy"
              @keydown.ctrl.enter.prevent="send()"
              @keydown.meta.enter.prevent="send()"
            />
            <div class="composer-footer">
              <span class="text-xs text-muted-foreground">{{
                readonly
                  ? "当前记录只读"
                  : `${composer.length} / 12000 · Ctrl / ⌘ + Enter 发送`
              }}</span>
              <div class="flex gap-2">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  :disabled="readonly || busy || !!running || !messages.length"
                  @click="send('DRAFT')"
                  ><Sparkles class="size-3.5" />整理需求草稿</Button
                ><Button
                  size="sm"
                  :disabled="readonly || busy || !!running || !composer.trim()"
                  ><Send class="size-3.5" />发送</Button
                >
              </div>
            </div>
          </form>
        </section>
        <aside v-if="panelOpen" class="artifact-column">
          <div class="artifact-heading">
            <div>
              <h2>需求草稿</h2>
              <p class="text-xs text-muted-foreground mt-1">
                {{
                  detail.session.draftRevision
                    ? `保存记录 #${detail.session.draftRevision}`
                    : "尚未保存"
                }}{{ draftDirty ? " · 有未保存编辑" : "" }}
              </p>
            </div>
            <Button variant="ghost" size="sm" :disabled="busy" @click="reload"
              ><RefreshCw class="size-4"
            /></Button>
          </div>
          <div v-if="candidate" class="candidate-card">
            <div class="flex justify-between gap-3">
              <strong class="text-sm">候选结果</strong
              ><Badge variant="outline">{{
                candidate.mode === "demo" ? "本地模板" : "模型生成"
              }}</Badge>
            </div>
            <p class="text-xs text-muted-foreground mt-2">
              候选尚未保存，也不会自动改变需求版本。
            </p>
            <details class="mt-3">
              <summary class="text-xs cursor-pointer">查看候选全文</summary>
              <pre class="candidate-preview">{{ candidate.resultText }}</pre>
            </details>
            <Button
              variant="outline"
              size="sm"
              class="mt-3"
              :disabled="
                readonly ||
                busy ||
                draftConflict ||
                !canApplyDraft(candidate, detail.session, detail.version)
              "
              @click="applyCandidate(candidate)"
              >应用到编辑副本</Button
            >
            <p
              v-if="!canApplyDraft(candidate, detail.session, detail.version)"
              class="text-xs text-amber-700 mt-2"
            >
              该候选的生成基准已变化，不能直接覆盖当前保存稿。
            </p>
          </div>
          <Textarea
            v-model="draft"
            aria-label="需求草稿正文"
            maxlength="60000"
            class="draft-editor"
            placeholder="应用候选结果，或直接手工编写需求范围、待确认问题与建议。"
            :disabled="readonly || busy || draftConflict"
          />
          <p v-if="draftConflict" class="text-xs text-amber-700 mt-3">
            保存失败时保留了输入，请重载后重新应用或手工合并。
          </p>
          <div class="artifact-actions">
            <Button
              variant="outline"
              :disabled="
                readonly ||
                busy ||
                draftConflict ||
                !draftDirty ||
                !draft.trim()
              "
              @click="persistDraft"
              ><Save class="size-4" />保存草稿</Button
            ><Button
              v-if="!detail.session.versionId"
              :disabled="!readyDraft || busy"
              @click="promoteOpen = true"
              >形成需求版本</Button
            ><Button v-else :disabled="!readyDraft || busy" @click="sync"
              >同步到关联版本</Button
            >
          </div>
          <p class="text-xs text-muted-foreground leading-6 mt-3">
            形成版本使用已保存草稿。需求版本拥有自己的范围和保存记录，会话归档不结束交付。
          </p>
          <p
            v-if="detail.version && detail.version.stage !== 'REVIEW'"
            class="text-xs text-muted-foreground mt-2"
          >
            关联版本已确认上游范围。请先在版本详情记录原因并重新评审，再同步范围变更。
          </p>
        </aside>
      </div>
    </template>
    <VersionDialog
      v-model:open="promoteOpen"
      :busy="busy"
      :error="error"
      from-draft
      @submit="promote"
    />
    <Dialog :open="renameOpen" @update:open="closeRename"
      ><DialogContent
        ><DialogHeader
          ><DialogTitle>重命名会话</DialogTitle
          ><DialogDescription
            >修改标题，消息与版本归属保持原样。</DialogDescription
          ></DialogHeader
        >
        <form class="space-y-4" @submit.prevent="rename">
          <Input
            v-model="newTitle"
            aria-label="新的会话标题"
            maxlength="120"
            :disabled="busy"
          />
          <p v-if="error" class="text-destructive">{{ error }}</p>
          <DialogFooter
            ><Button
              type="button"
              variant="outline"
              @click="closeRename(false)"
              :disabled="busy"
              >取消</Button
            ><Button :disabled="busy || !newTitle.trim()"
              >保存标题</Button
            ></DialogFooter
          >
        </form></DialogContent
      ></Dialog
    >
    <Dialog :open="linkOpen" @update:open="closeLink"
      ><DialogContent
        ><DialogHeader
          ><DialogTitle>关联已有需求版本</DialogTitle
          ><DialogDescription
            >会话只关联一个版本，不自动覆盖该版本的需求范围。</DialogDescription
          ></DialogHeader
        ><SelectField v-model="linkTarget" aria-label="目标需求版本" :disabled="busy"
          :options="[{ value: '', label: '选择当前项目的活动版本' }, ...linkOptions.filter(v => v.lifecycle === 'ACTIVE').map(v => ({ value: v.id, label: v.label }))]" /><Button
          v-if="linkMore"
          variant="ghost"
          :disabled="busy"
          @click="openLink(true)"
          >加载更多版本</Button
        >
        <p v-if="error" class="text-destructive">{{ error }}</p>
        <DialogFooter
          ><Button variant="outline" @click="closeLink(false)" :disabled="busy"
            >取消</Button
          ><Button :disabled="busy || !linkTarget" @click="link"
            >确认关联</Button
          ></DialogFooter
        ></DialogContent
      ></Dialog
    >
  </div>
</template>
