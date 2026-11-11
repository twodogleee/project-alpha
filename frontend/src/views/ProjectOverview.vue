<script setup>
import SessionActions from '@/components/SessionActions.vue';
import SelectField from '@/components/form/SelectField.vue';
/** 产品背景、会话与交付版本的聚合入口。@author Andersen */
import { ref, reactive, computed, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { toast } from "vue-sonner";
import {
  Plus,
  MessageSquare,
  ArrowUpRight,
  Layers,
  Settings2,
  Archive,
  Trash2,
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
import { sessionPath, versionPath } from "@/workspace/state.js";
import { confirmAction } from "@/lib/feedback.js";
/** 当前产品身份。 */ const route = useRoute(),
  router = useRouter(),
  shell = useWorkspace();
/** 概览与独立编辑副本。 */ const project = ref(null),
  sessions = ref([]),
  versions = ref([]),
  sessionMore = ref(false),
  versionMore = ref(false),
  sessionPage = ref(1),
  versionPage = ref(1),
  editOpen = ref(false),
  sessionOpen = ref(false),
  versionOpen = ref(false);
/** 每次进入项目默认展开，目录刷新不覆盖用户的手动收起。 */
const documentsOpen = ref(true);
/** 项目与会话表单分别维护。 */ const form = reactive({
    name: "",
    background: "",
  }),
  sessionForm = reactive({ title: "", versionId: "" });
/** 请求状态。 */ const { busy, loading, error, read, act } =
  useWorkspacePage();
/** 弹窗路由切换保护。 */ const dirty = computed(
  () => editOpen.value || sessionOpen.value || versionOpen.value,
);
useWorkspaceLeave(dirty, busy, () => {
  editOpen.value = false;
  sessionOpen.value = false;
  versionOpen.value = false;
});
/** 可绑定的活动业务版本。 */ const activeVersions = computed(() =>
  versions.value.filter((v) => v.lifecycle === "ACTIVE" && !v.archivedAt),
);
/** 会话侧栏和列表操作成功后重新读取目录；未提交的项目表单保留。 */
watch(() => shell.sessionChange.value, () => load());
/** 快速切换时只应用最新项目读取。 */
async function load() {
  await read(
    async (signal) => {
      const id = route.params.projectId;
      return await Promise.all([
        api.loadProject(id, signal),
        api.listSessions(id, {}, signal),
        api.listRequirementVersions(id, {}, signal),
      ]);
    },
    ([p, s, v]) => {
      project.value = p;
      sessions.value = s.items;
      versions.value = v.items;
      sessionMore.value = s.hasMore;
      versionMore.value = v.hasMore;
      sessionPage.value = 1;
      versionPage.value = 1;
    },
  );
}
/** 删除文档前说明关联会话只读；命令携带确认时的修订，失败保留原目录。 */
async function removeDocument(document) {
  const result = await act(async () => {
    const preview = await api.versionDeletionPreview(document.id);
    if (!(await confirmAction({
      title: `删除文档「${preview.version.label}」`,
      description: `文档将移入回收站，正文、待确认关联、历史版本和定稿均保留，可恢复。${preview.linkedSessions ? `关联的 ${preview.linkedSessions} 个会话及聊天记录保留，这份 PRD 在恢复前只读。` : '此文档没有未删除的关联会话。'}`,
      action: '移入回收站', destructive: true,
    }))) return;
    return api.deleteVersion(document.id, {
      expectedRevision: preview.version.revision, deleted: true,
      expectedDocumentRevision: preview.documentRevision, expectedLinkedSessions: preview.linkedSessions,
    });
  });
  if (result) { await load(); toast.success('文档已移入回收站，关联会话保留'); }
}
/** 分页获取更多目录。 */ async function more(kind) {
  const result = await act(() =>
    kind === "sessions"
      ? api.listSessions(project.value.id, { page: sessionPage.value + 1 })
      : api.listRequirementVersions(project.value.id, {
          page: versionPage.value + 1,
        }),
  );
  if (!result) return;
  if (kind === "sessions") {
    sessions.value.push(...result.items);
    sessionMore.value = result.hasMore;
    sessionPage.value++;
  } else {
    versions.value.push(...result.items);
    versionMore.value = result.hasMore;
    versionPage.value++;
  }
}
/** 开始背景编辑。 */ function edit() {
  Object.assign(form, {
    name: project.value.name,
    background: project.value.background,
  });
  editOpen.value = true;
}
/** 保存背景只影响后续运行。 */ async function save() {
  const result = await act(() =>
    api.editProject(project.value.id, {
      expectedRevision: project.value.revision,
      ...form,
    }),
  );
  if (result) {
    project.value = result;
    editOpen.value = false;
    await shell.refreshProjects();
    toast.success("项目背景已保存");
  }
}
/** 清空新会话副本。 */ function startSession() {
  sessionForm.title = "";
  sessionForm.versionId = "";
  sessionOpen.value = true;
}
/** 创建会话后进入独立消息区。 */ async function createSession() {
  const result = await act(() =>
    api.newSession(project.value.id, {
      title: sessionForm.title.trim() || "新会话",
      versionId: sessionForm.versionId || null,
    }),
  );
  if (result) {
    sessionOpen.value = false;
    await shell.refreshSessions();
    router.push(sessionPath(result));
  }
}
/** 手工定义交付范围容器。 */ async function createVersion(data) {
  const result = await act(() =>
    api.newVersion(project.value.id, { ...data, content: "" }),
  );
  if (result) {
    versionOpen.value = false;
    router.push(versionPath(result));
  }
}
/** 服务器校验项目归档条件。 */ async function archive() {
  if (
    !(await confirmAction({
      title: "归档项目",
      description:
        "项目将移到归档记录。存在活动需求版本或执行中任务时不能归档。",
      action: "归档项目",
    }))
  )
    return;
  const result = await act(() =>
    api.archiveProject(project.value.id, {
      expectedRevision: project.value.revision,
      archived: true,
    }),
  );
  if (result) {
    await shell.refreshProjects();
    router.push("/archives");
  }
}
/** ESC 与取消共享未提交副本保护。 */
const closeEdit = useDialogClose(
  busy,
  () =>
    form.name !== project.value?.name ||
    form.background !== project.value?.background,
  (value) => (editOpen.value = value),
);
const closeSession = useDialogClose(
  busy,
  () => Boolean(sessionForm.title || sessionForm.versionId),
  (value) => (sessionOpen.value = value),
);
watch(
  () => route.params.projectId,
  () => {
    project.value = null;
    documentsOpen.value = true;
    load();
  },
  { immediate: true },
);
/** 侧栏入口只打开表单，不隐式创建空会话。 */
watch(
  () => route.query.newSession,
  (value) => {
    if (value) startSession();
  },
  { immediate: true },
);
</script>
<template>
  <div class="workspace-page">
    <div v-if="error" class="error-banner mb-6">
      {{ error
      }}<Button variant="outline" size="sm" @click="load">重新载入</Button>
    </div>
    <p v-if="loading && !project" class="muted">正在读取项目…</p>
    <template v-if="project"
      ><div class="flex justify-between items-start gap-6">
        <div>
          <p class="eyebrow">PROJECT OVERVIEW</p>
          <h1 class="page-title">{{ project.name }}</h1>
          <p class="muted mt-2">通过会话逐步形成 PRD，也可以直接编辑文档。</p>
        </div>
        <div class="flex gap-2">
          <Button
            variant="outline"
            :disabled="busy || !!project.archivedAt"
            @click="edit"
            ><Settings2 class="size-4" />项目设置</Button
          ><Button v-if="!project.archivedAt" @click="startSession"
            ><Plus class="size-4" />新建会话</Button
          >
        </div>
      </div>
      <div v-if="project.archivedAt" class="notice-banner mt-6">
        项目已归档，当前内容只读。请在归档记录中恢复项目。
      </div>
      <section class="workspace-panel mt-8">
        <div class="flex items-center justify-between mb-3">
          <h2>项目背景</h2>
          <span class="text-xs text-muted-foreground"
            >背景修订 r{{ project.revision }}</span
          >
        </div>
        <p class="whitespace-pre-wrap text-sm leading-7 text-muted-foreground">
          {{
            project.background || "补充背景与约束，让会话围绕同一产品上下文。"
          }}
        </p>
      </section>
      <section class="mt-8">
        <div class="section-heading">
          <div class="flex gap-2 items-center">
            <MessageSquare class="size-4" />
            <h2>会话</h2>
          </div>
          <span class="muted text-xs">在会话中讨论和编辑同一份 PRD</span>
        </div>
        <div v-if="!sessions.length" class="workspace-empty compact">
          <p>新建会话，输入你想解决的业务问题。</p>
        </div>
        <div class="session-list">
          <div v-for="s in sessions" :key="s.id" class="session-row">
          <RouterLink :to="sessionPath(s)" class="flex items-center gap-3 flex-1 min-w-0"
            ><MessageSquare class="size-4 text-muted-foreground" />
            <div class="flex-1 min-w-0">
              <strong>{{ s.title }}</strong>
              <p class="text-xs text-muted-foreground mt-1">
                {{
                  s.versionId
                    ? versions.find((v) => v.id === s.versionId)?.label ||
                      "已关联需求版本"
                    : "打开后继续完善 PRD"
                }}
              </p>
            </div>
            <span class="text-xs text-muted-foreground">{{
              new Date(s.updatedAt).toLocaleDateString("zh-CN")
            }}</span
            ><ArrowUpRight class="size-4"
          /></RouterLink><SessionActions :session="s" :disabled="busy" /></div>
        </div>
        <Button
          v-if="sessionMore"
          variant="outline"
          class="mt-4"
          :disabled="busy"
          @click="more('sessions')"
          >更多会话</Button
        >
      </section>
      <details class="mt-8" :open="documentsOpen" @toggle="documentsOpen = $event.target.open"><summary class="text-sm text-muted-foreground cursor-pointer">文档与历史迭代</summary>
        <div class="section-heading">
          <div class="flex gap-2 items-center">
            <Layers class="size-4" />
            <h2>关联文档</h2>
            <span class="muted text-xs">{{ versions.length }}</span>
          </div>
        </div>
        <div v-if="!versions.length" class="workspace-empty compact">
          <p>新建会话，直接开始描述需求。</p>
        </div>
        <div class="version-grid">
          <div
            v-for="v in versions"
            :key="v.id"
            class="workspace-panel version-card"
            ><RouterLink :to="versionPath(v)" class="block"><div class="flex justify-between gap-3">
              <h3>{{ v.label }}</h3>
              <Badge variant="secondary">{{
                v.lifecycle === "CANCELED"
                  ? "已取消"
                  : { REVIEW: "历史需求资料", PRD: "PRD 草稿", PROTOTYPE: "原型阶段" }[
                      v.stage
                    ] || v.stage
              }}</Badge>
            </div>
            <p class="muted line-clamp-3 mt-3">{{ v.goal }}</p>
            <div
              class="flex justify-between text-xs text-muted-foreground mt-6"
            >
              <span>保存记录 #{{ v.revision }}</span
              ><ArrowUpRight class="size-4" /></div
          ></RouterLink>
            <div class="flex justify-end items-center gap-3 mt-3"><RouterLink v-if="v.stage !== 'REVIEW'" :to="{ path: versionPath(v), query: { view: 'prototype' } }" class="text-xs text-muted-foreground hover:text-foreground">查看原型 ↗</RouterLink><Button v-if="!project.archivedAt" variant="ghost" size="sm" class="text-destructive hover:text-destructive" :aria-label="`删除文档：${v.label}`" :disabled="busy || loading" @click="removeDocument(v)"><Trash2 class="size-3.5" />删除文档</Button></div>
          </div>
        </div>
        <Button
          v-if="versionMore"
          variant="outline"
          class="mt-4"
          :disabled="busy"
          @click="more('versions')"
          >更多版本</Button
        >
      </details>

      <Button
        v-if="!project.archivedAt"
        variant="ghost"
        size="sm"
        class="text-muted-foreground mt-8"
        :disabled="busy"
        @click="archive"
        ><Archive class="size-4" />归档项目</Button
      >
    </template>
    <VersionDialog
      v-model:open="versionOpen"
      :busy="busy"
      :error="error"
      @submit="createVersion"
    />
    <Dialog :open="editOpen" @update:open="closeEdit"
      ><DialogContent
        ><DialogHeader
          ><DialogTitle>项目背景与约束</DialogTitle
          ><DialogDescription
            >修改只影响后续请求，已有消息和结果保留当次上下文。</DialogDescription
          ></DialogHeader
        >
        <form class="space-y-4" @submit.prevent="save">
          <Input
            v-model="form.name"
            aria-label="项目名称"
            maxlength="120"
            :disabled="busy"
          /><Textarea
            v-model="form.background"
            aria-label="项目背景"
            maxlength="12000"
            class="min-h-40"
            :disabled="busy"
          />
          <p v-if="error" class="text-destructive">{{ error }}</p>
          <DialogFooter
            ><Button
              variant="outline"
              type="button"
              @click="closeEdit(false)"
              :disabled="busy"
              >取消</Button
            ><Button :disabled="busy || !form.name.trim()"
              >保存项目</Button
            ></DialogFooter
          >
        </form></DialogContent
      ></Dialog
    >
    <Dialog :open="sessionOpen" @update:open="closeSession"
      ><DialogContent
        ><DialogHeader
          ><DialogTitle>新建会话</DialogTitle
          ><DialogDescription
            >描述本次要做的事情，也可继续已有文档。不填名称时，从首条需求描述生成标题，可随时修改。</DialogDescription
          ></DialogHeader
        >
        <form class="space-y-4" @submit.prevent="createSession">
          <div class="space-y-2">
            <label for="session-title">会话名称（可选）</label
            ><Input
              id="session-title"
              v-model="sessionForm.title"
              maxlength="120"
              placeholder="例如 客户批量导入"
              :disabled="busy"
            />
          </div>
          <div class="space-y-2">
            <label for="session-version">关联文档</label
            ><SelectField id="session-version" v-model="sessionForm.versionId" :disabled="busy"
              :options="[{ value: '', label: '新建 PRD 草稿' }, ...activeVersions.map(v => ({ value: v.id, label: v.label }))]" />
          </div>
          <p v-if="error" class="text-destructive">{{ error }}</p>
          <DialogFooter
            ><Button
              type="button"
              variant="outline"
              @click="closeSession(false)"
              :disabled="busy"
              >取消</Button
            ><Button :disabled="busy"
              >开始会话</Button
            ></DialogFooter
          >
        </form></DialogContent
      ></Dialog
    >
  </div>
</template>
