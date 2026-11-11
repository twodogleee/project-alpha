<script setup>
/** 需求版本范围、正式产物与生命周期；阶段通过人工基线推进。@author Andersen */
import { ref, reactive, computed, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { toast } from "vue-sonner";
import {
  ArrowLeft,
  Save,
  Plus,
  Download,
  History,
  Archive,
  MessageSquare,
  Check,
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
import * as api from "@/api/workspace.js";
import { useWorkspace } from "@/lib/workspace.js";
import {
  useWorkspacePage,
  useWorkspaceLeave,
  useDialogClose,
} from "@/composables/useWorkspacePage.js";
import { stages, projectPath, sessionPath } from "@/workspace/state.js";
import VersionLifecycle from "@/components/VersionLifecycle.vue";
import { stageCaption } from "@/workspace/lifecycle.js";
import { confirmAction } from "@/lib/feedback.js";
/** 稳定对象路由与共享目录。 */ const route = useRoute(),
  router = useRouter(),
  shell = useWorkspace();
/** 服务器范围与编辑副本分开。 */ const version = ref(null),
  project = ref(null),
  sessions = ref([]),
  sessionMore = ref(false),
  sessionPage = ref(1),
  form = reactive({ label: "", goal: "", content: "" }),
  baseline = ref(""),
  historyOpen = ref(false),
  history = ref([]),
  historyPage = ref(1),
  historyMore = ref(false),
  selectedHistory = ref(null),
  sessionOpen = ref(false),
  title = ref(""),
  cancelOpen = ref(false),
  reason = ref(""),
  conflicted = ref(false),
  artifactDirty = ref(false),
  artifactBusy = ref(false);
/** 页面请求不执行模型。 */ const {
  busy,
  loading,
  error,
  errorCode,
  read,
  act,
} = useWorkspacePage();
/** 编辑正文和名称均参与冲突保护。 */ const contentDirty = computed(
  () => JSON.stringify(form) !== baseline.value && !!version.value,
);
/** 对话中的未保存副本在离开时保护。 */ const dirty = computed(
  () => contentDirty.value || sessionOpen.value || cancelOpen.value,
);
/** 取消或归档后的版本范围只读。 */ const readonly = computed(() =>
  Boolean(
    project.value?.archivedAt ||
      version.value?.archivedAt ||
      version.value?.lifecycle !== "ACTIVE",
  ),
);
useWorkspaceLeave(
  dirty,
  computed(() => busy.value || artifactBusy.value),
  () => {
    baseline.value = JSON.stringify(form);
    sessionOpen.value = false;
    cancelOpen.value = false;
  },
);
/** 替换当前已保存真值，不从历史快照恢复编辑。 */
function adopt(value) {
  version.value = value;
  Object.assign(form, {
    label: value.label,
    goal: value.goal,
    content: value.content,
  });
  baseline.value = JSON.stringify(form);
  conflicted.value = false;
}
/** 校验路由归属，防止错误项目标题与合法版本拼接。 */
async function load() {
  await read(
    async (signal) => {
      const value = await api.loadVersion(route.params.versionId, signal);
      if (value.projectId !== route.params.projectId)
        throw new Error("需求版本不属于当前项目");
      const [p, s] = await Promise.all([
        api.loadProject(value.projectId, signal),
        api.listSessions(value.projectId, { versionId: value.id }, signal),
      ]);
      return [value, p, s];
    },
    ([v, p, s]) => {
      adopt(v);
      project.value = p;
      sessions.value = s.items;
      sessionPage.value = 1;
      sessionMore.value = s.hasMore;
    },
  );
}
/** 重载明确放弃当前输入。 */ async function reload() {
  if (
    contentDirty.value &&
    !(await confirmAction({
      title: "重新载入需求版本",
      description: "当前未保存的范围编辑会被放弃，已保存记录保留。",
      action: "放弃并载入",
    }))
  )
    return;
  await load();
}
/** 保存增加内部记录，不产生第二个业务版本或推进生命周期。 */ async function save() {
  const result = await act(() =>
    api.editVersion(version.value.id, {
      expectedRevision: version.value.revision,
      ...form,
    }),
  );
  if (result) {
    adopt(result);
    toast.success("范围已保存，基线确认仍是独立操作");
  } else if (errorCode.value === "WORKSPACE_REVISION_CONFLICT")
    conflicted.value = true;
}
/** 为同一交付版本建立新阶段讨论入口。 */ async function createSession() {
  const result = await act(() =>
    api.newSession(version.value.projectId, {
      title: title.value.trim(),
      versionId: version.value.id,
    }),
  );
  if (result) {
    sessionOpen.value = false;
    await shell.refreshSessions();
    router.push(sessionPath(result));
  }
}
/** 有界读取范围保存记录。 */ async function showHistory(more = false) {
  const result = await act(() =>
    api.listRevisions(version.value.id, more ? historyPage.value + 1 : 1),
  );
  if (result) {
    history.value = more ? [...history.value, ...result.items] : result.items;
    historyPage.value = result.page;
    historyMore.value = result.hasMore;
    historyOpen.value = true;
    if (!more) selectedHistory.value = result.items[0] || null;
  }
}
/** 版本内关联会话分页。 */ async function moreSessions() {
  const result = await act(() =>
    api.listSessions(version.value.projectId, {
      versionId: version.value.id,
      page: sessionPage.value + 1,
    }),
  );
  if (result) {
    sessions.value.push(...result.items);
    sessionPage.value++;
    sessionMore.value = result.hasMore;
  }
}
/** 明确取消后才能归档，首期没有伪完成操作。 */ async function cancel() {
  const result = await act(() =>
    api.cancelVersion(version.value.id, {
      expectedRevision: version.value.revision,
      reason: reason.value.trim(),
    }),
  );
  if (result) {
    cancelOpen.value = false;
    adopt(result);
    toast.success("版本已取消，交付未标记完成");
  }
}
/** 取消版本可移入历史目录。 */ async function archive() {
  if (
    !(await confirmAction({
      title: "归档已取消版本",
      description: "版本和保存记录仍可查看，恢复归档不改变取消状态。",
      action: "归档版本",
    }))
  )
    return;
  const result = await act(() =>
    api.archiveVersion(version.value.id, {
      expectedRevision: version.value.revision,
      archived: true,
    }),
  );
  if (result) {
    adopt(result);
    router.push("/archives");
  }
}
/** 下载已保存的需求范围，名称过滤不影响文档内容。 */
function download() {
  const text = `# ${version.value.label}\n\n> 需求范围草稿，尚未完成正式评审、PRD、原型与开发验收。\n\n## 交付目标\n${version.value.goal}\n\n${version.value.content}`;
  const url = URL.createObjectURL(
    new Blob([text], { type: "text/markdown;charset=utf-8" }),
  );
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = `${version.value.label.replace(/[^\p{L}\p{N}._-]/gu, "_")}-scope-r${version.value.revision}.md`;
  anchor.click();
  URL.revokeObjectURL(url);
}
/** 新建讨论的未提交标题在关闭时受保护。 */
const closeSession = useDialogClose(
  busy,
  () => Boolean(title.value),
  (value) => (sessionOpen.value = value),
);
/** 放弃取消命令直接返回交付页面；原因只服务于本次命令，不再嵌套确认。 */
function closeCancel(value) {
  if (busy.value) return;
  cancelOpen.value = value;
  if (!value) reason.value = "";
}
watch(
  () => route.params.versionId,
  () => {
    version.value = null;
    project.value = null;
    historyOpen.value = false;
    form.label = "";
    form.goal = "";
    form.content = "";
    load();
  },
  { immediate: true },
);
</script>
<template>
  <div class="workspace-page">
    <div v-if="error" class="error-banner mb-5">
      {{ error
      }}<Button size="sm" variant="outline" :disabled="busy" @click="reload"
        >重新载入</Button
      >
    </div>
    <p v-if="loading && !version" class="muted">正在读取需求版本…</p>
    <template v-if="version"
      ><RouterLink
        :to="projectPath(version.projectId)"
        class="text-xs text-muted-foreground inline-flex gap-1 items-center"
        ><ArrowLeft class="size-3" />{{ project.name }}</RouterLink
      >
      <div class="flex justify-between items-start gap-6 mt-3">
        <div>
          <p class="eyebrow">REQUIREMENT VERSION</p>
          <h1 class="page-title">{{ version.label }}</h1>
          <div class="flex gap-2 mt-3">
            <Badge variant="secondary">{{
              version.lifecycle === "CANCELED"
                ? "已取消"
                : `进行中 · ${stages.find((s) => s.id === version.stage)?.name || version.stage}`
            }}</Badge
            ><Badge variant="outline">保存记录 #{{ version.revision }}</Badge
            ><Badge v-if="contentDirty" variant="outline">未保存</Badge>
          </div>
        </div>
        <div class="flex gap-2">
          <Button
            variant="outline"
            :disabled="busy || contentDirty || artifactDirty || artifactBusy"
            @click="download"
            ><Download class="size-4" />下载范围</Button
          ><Button
            :disabled="
              readonly ||
              busy ||
              artifactDirty ||
              artifactBusy ||
              conflicted ||
              !contentDirty ||
              !form.label.trim() ||
              !form.goal.trim()
            "
            @click="save"
            ><Save class="size-4" />保存范围</Button
          >
        </div>
      </div>
      <div class="lifecycle-track mt-8">
        <div
          v-for="(stage, index) in stages"
          :key="stage.id"
          :class="[
            'lifecycle-step',
            { 'is-current': stage.id === version.stage },
          ]"
        >
          <span>{{ index + 1 }}</span>
          <div>
            <strong>{{ stage.name }}</strong
            ><small>{{ stageCaption(stage, version.stage) }}</small>
          </div>
        </div>
      </div>
      <div class="notice-banner mt-5">
        范围、评审、PRD
        保存各自产生记录，只有人工确认有效基线才推进阶段。原型生成与预览已接入会话工作台，原型人工确认和开发验收仍待接入，生成成功不代表交付完成。
      </div>
      <div v-if="version.lifecycle === 'CANCELED'" class="notice-banner mt-4">
        已取消：{{ version.cancelReason }}。需求范围与历史记录只读。
      </div>
      <details class="mt-6">
        <summary class="cursor-pointer font-medium">
          交付范围、版本信息与关联会话
        </summary>
        <div class="version-layout mt-4">
          <section class="workspace-panel space-y-5">
            <div class="section-heading">
              <h2>需求范围</h2>
              <Button
                variant="ghost"
                size="sm"
                :disabled="busy"
                @click="showHistory()"
                ><History class="size-4" />保存记录</Button
              >
            </div>
            <div class="space-y-2">
              <label for="edit-version-label">版本名称</label
              ><Input
                id="edit-version-label"
                v-model="form.label"
                maxlength="120"
                :disabled="
                  readonly ||
                  busy ||
                  conflicted ||
                  artifactDirty ||
                  artifactBusy
                "
              />
            </div>
            <div class="space-y-2">
              <label for="edit-version-goal">交付目标</label
              ><Textarea
                id="edit-version-goal"
                v-model="form.goal"
                maxlength="4000"
                :disabled="
                  readonly ||
                  busy ||
                  conflicted ||
                  artifactDirty ||
                  artifactBusy ||
                  version.stage !== 'REVIEW'
                "
              />
            </div>
            <div class="space-y-2">
              <label for="edit-version-content">范围正文</label
              ><Textarea
                id="edit-version-content"
                v-model="form.content"
                maxlength="60000"
                class="min-h-80 font-mono text-sm leading-7"
                placeholder="手工编写范围，或通过关联会话整理草稿后显式同步。"
                :disabled="
                  readonly ||
                  busy ||
                  conflicted ||
                  artifactDirty ||
                  artifactBusy ||
                  version.stage !== 'REVIEW'
                "
              />
            </div>
            <p v-if="conflicted" class="text-xs text-amber-700">
              保存失败时保留了输入，请重载后重新编辑或合并。
            </p>
          </section>
          <aside class="space-y-5">
            <section class="workspace-panel">
              <div class="section-heading">
                <h2>关联会话</h2>
                <Button
                  variant="ghost"
                  size="icon"
                  aria-label="为版本新建会话"
                  :disabled="
                    readonly ||
                    busy ||
                    contentDirty ||
                    artifactDirty ||
                    artifactBusy
                  "
                  @click="
                    title = '';
                    sessionOpen = true;
                  "
                  ><Plus class="size-4"
                /></Button>
              </div>
              <p v-if="!sessions.length" class="muted text-sm">
                围绕这个交付版本开启讨论。
              </p>
              <RouterLink
                v-for="s in sessions"
                :key="s.id"
                :to="sessionPath(s)"
                class="flex gap-2 py-3 border-b text-sm"
                ><MessageSquare class="size-4 shrink-0 mt-0.5" />{{
                  s.title
                }}</RouterLink
              ><Button
                v-if="sessionMore"
                variant="ghost"
                size="sm"
                :disabled="busy"
                @click="moreSessions"
                >更多会话</Button
              >
            </section>
            <section class="workspace-panel">
              <h2>来源与边界</h2>
              <p class="text-xs text-muted-foreground leading-6 mt-3">
                {{
                  version.sourceSessionId
                    ? "由会话已保存草稿形成；后续同步保留各次来源记录。"
                    : "手工创建的交付版本，可关联多个会话。"
                }}<br />多个保存记录始终属于同一个业务版本。
              </p>
            </section>
            <Button
              v-if="version.lifecycle === 'ACTIVE' && !readonly"
              variant="ghost"
              class="text-destructive"
              :disabled="busy || contentDirty || artifactDirty || artifactBusy"
              @click="
                reason = '';
                cancelOpen = true;
              "
              >取消本次交付</Button
            ><Button
              v-if="
                version.lifecycle === 'CANCELED' &&
                !version.archivedAt &&
                !project.archivedAt
              "
              variant="outline"
              :disabled="busy"
              @click="archive"
              ><Archive class="size-4" />归档版本</Button
            >
          </aside>
        </div>
      </details>
    </template>
    <VersionLifecycle
      v-if="version && project"
      :version="version"
      :project-archived="!!project.archivedAt"
      :scope-dirty="contentDirty"
      @changed="load()"
      @dirty-change="artifactDirty = $event"
      @busy-change="artifactBusy = $event"
    />
    <Dialog :open="historyOpen" @update:open="historyOpen = $event"
      ><DialogContent class="max-w-3xl"
        ><DialogHeader
          ><DialogTitle>范围保存记录</DialogTitle
          ><DialogDescription
            >只读查看，不覆盖当前编辑。记录编号不是业务版本号。</DialogDescription
          ></DialogHeader
        >
        <div class="history-dialog-layout">
          <div class="space-y-1">
            <Button
              v-for="r in history"
              :key="r.revision"
              variant="ghost"
              class="w-full justify-start"
              @click="selectedHistory = r"
              ><Check
                v-if="selectedHistory?.revision === r.revision"
                class="size-3"
              />保存 #{{ r.revision }}</Button
            ><Button
              v-if="historyMore"
              variant="outline"
              :disabled="busy"
              @click="showHistory(true)"
              >更多记录</Button
            >
          </div>
          <div v-if="selectedHistory">
            <h3>{{ selectedHistory.label }}</h3>
            <p class="muted text-sm my-3">{{ selectedHistory.goal }}</p>
            <pre class="saved-scope-preview">{{
              selectedHistory.content || "该保存记录的范围为空"
            }}</pre>
          </div>
        </div></DialogContent
      ></Dialog
    >
    <Dialog :open="sessionOpen" @update:open="closeSession"
      ><DialogContent
        ><DialogHeader
          ><DialogTitle>新建版本会话</DialogTitle
          ><DialogDescription
            >此会话直接关联
            {{ version?.label }}，不会创建第二个需求版本。</DialogDescription
          ></DialogHeader
        >
        <form class="space-y-4" @submit.prevent="createSession">
          <Input
            v-model="title"
            aria-label="版本会话标题"
            maxlength="120"
            :disabled="busy"
            placeholder="例如 细化导入流程"
          />
          <p v-if="error" class="text-destructive">{{ error }}</p>
          <DialogFooter
            ><Button
              type="button"
              variant="outline"
              @click="closeSession(false)"
              :disabled="busy"
              >取消</Button
            ><Button :disabled="busy || !title.trim()"
              >创建会话</Button
            ></DialogFooter
          >
        </form></DialogContent
      ></Dialog
    >
    <Dialog :open="cancelOpen" @update:open="closeCancel"
      ><DialogContent
        ><DialogHeader
          ><DialogTitle>取消本次交付</DialogTitle
          ><DialogDescription
            >取消后范围只读，未完成的交付不会计为完成。后续继续可以新建版本。</DialogDescription
          ></DialogHeader
        >
        <form class="space-y-4" @submit.prevent="cancel">
          <Textarea
            v-model="reason"
            aria-label="取消原因"
            maxlength="2000"
            placeholder="说明为什么不再推进这个版本"
            :disabled="busy"
          />
          <p v-if="error" class="text-destructive">{{ error }}</p>
          <DialogFooter
            ><Button
              type="button"
              variant="outline"
              :disabled="busy"
              @click="closeCancel(false)"
              >继续交付</Button
            ><Button variant="destructive" :disabled="busy || !reason.trim()"
              >确认取消</Button
            ></DialogFooter
          >
        </form></DialogContent
      ></Dialog
    >
  </div>
</template>
