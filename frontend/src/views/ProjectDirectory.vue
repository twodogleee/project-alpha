<script setup>
/** 产品项目目录；创建不触发模型，背景用于后续会话上下文。@author Andersen */
import { onMounted, ref, reactive, computed } from "vue";
import { useRouter } from "vue-router";
import { Plus, FolderOpen, ArrowUpRight, MessageSquare } from "@lucide/vue";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from "@/components/ui/dialog";
import { listWorkspaceProjects, newProject } from "@/api/workspace.js";
import { useWorkspace } from "@/lib/workspace.js";
import {
  useWorkspacePage,
  useWorkspaceLeave,
  useDialogClose,
} from "@/composables/useWorkspacePage.js";
import { projectPath } from "@/workspace/state.js";
/** 新项目完成后打开概览。 */ const router = useRouter(),
  shell = useWorkspace();
/** 当前分页与创建副本。 */ const items = ref([]),
  page = ref(1),
  hasMore = ref(false),
  open = ref(false),
  form = reactive({ name: "", background: "" });
/** 独立读取和命令状态。 */ const { busy, loading, error, read, act } =
  useWorkspacePage();
/** 创建输入在路由离开时保护。 */ const dirty = computed(
  () => open.value && Boolean(form.name || form.background),
);
useWorkspaceLeave(dirty, busy);
/** 获取下一页，不把截断结果误称完整目录。 */
async function load(append = false) {
  await read(
    (s) => listWorkspaceProjects(append ? page.value + 1 : 1, s),
    (data) => {
      items.value = append ? [...items.value, ...data.items] : data.items;
      page.value = data.page;
      hasMore.value = data.hasMore;
    },
  );
}
/** 创建容器后同步侧栏。 */
async function create() {
  const result = await act(() =>
    newProject({ name: form.name.trim(), background: form.background.trim() }),
  );
  if (result) {
    open.value = false;
    form.name = "";
    form.background = "";
    await shell.refreshProjects();
    router.push(projectPath(result.id));
  }
}
/** 打开干净副本。 */ function start() {
  form.name = "";
  form.background = "";
  open.value = true;
}
/** 关闭创建表单前保护已输入的产品信息。 */
const closeCreate = useDialogClose(
  busy,
  () => Boolean(form.name || form.background),
  (value) => (open.value = value),
);
onMounted(() => load());
</script>
<template>
  <div class="workspace-page">
    <div class="flex justify-between items-start gap-6">
      <div>
        <p class="eyebrow">YOUR PROJECTS</p>
        <h1 class="page-title">项目</h1>
        <p class="muted mt-2">按产品组织讨论，让每次交付都有明确的需求版本。</p>
      </div>
      <Button @click="start"><Plus class="size-4" />新建项目</Button>
    </div>
    <div v-if="error" class="error-banner mt-6">
      {{ error
      }}<Button variant="outline" size="sm" @click="load()">重试</Button>
    </div>
    <p v-if="loading && !items.length" class="muted py-12">正在读取项目…</p>
    <div v-else-if="!items.length && !error" class="workspace-empty mt-8">
      <FolderOpen class="size-10 text-muted-foreground" />
      <h2>创建你的第一个项目</h2>
      <p>项目是产品或业务系统。先确定背景，再通过会话探索需求。</p>
      <Button @click="start">新建项目</Button>
    </div>
    <div class="project-grid mt-8">
      <RouterLink
        v-for="project in items"
        :key="project.id"
        :to="projectPath(project.id)"
        class="project-card"
        ><div class="flex justify-between">
          <span class="project-icon"><FolderOpen class="size-5" /></span
          ><ArrowUpRight class="size-4 text-muted-foreground" />
        </div>
        <h2 class="mt-6">{{ project.name }}</h2>
        <p class="muted line-clamp-3 mt-2">
          {{ project.background || "尚未填写项目背景" }}
        </p>
        <div
          class="flex items-center justify-between mt-8 text-xs text-muted-foreground"
        >
          <span class="flex gap-2 items-center"
            ><MessageSquare class="size-3.5" />打开项目与会话</span
          ><span>{{
            new Date(project.updatedAt).toLocaleDateString("zh-CN")
          }}</span>
        </div></RouterLink
      >
    </div>
    <Button
      v-if="hasMore"
      variant="outline"
      class="mt-6"
      :disabled="loading"
      @click="load(true)"
      >加载更多项目</Button
    >
    <Dialog :open="open" @update:open="closeCreate"
      ><DialogContent
        ><DialogHeader
          ><DialogTitle>新建项目</DialogTitle
          ><DialogDescription
            >填写长期产品背景。创建项目不会调用模型。</DialogDescription
          ></DialogHeader
        >
        <form class="space-y-5" @submit.prevent="create">
          <div class="space-y-2">
            <label for="project-name">项目名称</label
            ><Input
              id="project-name"
              v-model="form.name"
              maxlength="120"
              placeholder="例如 客户运营系统"
              :disabled="busy"
            />
          </div>
          <div class="space-y-2">
            <label for="project-background">背景与约束</label
            ><Textarea
              id="project-background"
              v-model="form.background"
              maxlength="12000"
              placeholder="产品做什么，面向谁，有哪些已知约束？"
              :disabled="busy"
            />
          </div>
          <p v-if="error" class="text-destructive text-sm">{{ error }}</p>
          <DialogFooter
            ><Button
              variant="outline"
              type="button"
              :disabled="busy"
              @click="closeCreate(false)"
              >取消</Button
            ><Button :disabled="busy || !form.name.trim()">{{
              busy ? "正在创建…" : "创建项目"
            }}</Button></DialogFooter
          >
        </form></DialogContent
      ></Dialog
    >
  </div>
</template>
