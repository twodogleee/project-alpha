<script setup>
/** 应用外壳维护公开运行信息与项目导航，业务编辑状态归各页面所有。@author Andersen */
import {
  computed,
  onBeforeUnmount,
  onMounted,
  provide,
  reactive,
  ref,
  watch,
} from "vue";
import { useRoute } from "vue-router";
import { ChevronRight, PanelLeft } from "@lucide/vue";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import AppNavigation from "./AppNavigation.vue";
import { getRuntimeInfo } from "@/api/agent.js";
import { listWorkspaceProjects, listSessions } from "@/api/workspace.js";
import { workspaceKey } from "@/lib/workspace.js";
/** 顶部路径与侧栏选中状态来自当前路由。 */
const route = useRoute();
/** 收起目录为文档提供更多空间，只影响当前页面会话。 */
const navigationPreference = ref(null);
/** 会话默认让出全局侧栏宽度，用户手动展开或收起后沿用其选择。 */
const navigationCollapsed = computed(() => navigationPreference.value ?? ['session', 'version'].includes(route.name));
/** 当前会话详情提供准确标题，不依赖分页侧栏是否包含该会话。 */
const conversationContext = ref(null);
/** 切换页面立即清空上一会话路径。 */
watch(() => route.path, () => { conversationContext.value = null; });
/** 仅接受当前路由对应的详情，阻止迟到响应改写导航。 */
function setConversationContext(value) {
  if (value.project.id === route.params.projectId &&
      (value.session?.id === route.params.sessionId || value.version?.id === route.params.versionId)) {
    conversationContext.value = { project: value.project.name, sessionId: value.session?.id, title: value.session?.title || value.version?.label };
  }
}
/** 会话路径显示项目名，其余页面沿用目录层级。 */
const parentLabel = computed(() => route.path.startsWith('/settings') ? '系统设置' :
  conversationContext.value?.project || state.projects.find(project => project.id === route.params.projectId)?.name || '项目');
/** 公开信息由独立请求读取，某个请求失败不会清空另一个目录。 */
const state = reactive({
  runtime: null,
  connectionError: "",
  projects: [],
  sessions: [],
  sessionError: "",
  listError: "",
});
/** 每类请求保留独立取消句柄，防止旧响应回写。 */
let runtimeController, listController, sessionController;
/** 卸载后忽略异步响应。 */
let disposed = false;
/** 顶部当前位置来自当前路由。 */
const pageLabel = computed(() =>
  route.path.startsWith("/settings")
    ? route.path.endsWith("platforms")
      ? "接入平台"
      : route.name === "prd-templates"
        ? "PRD 模板"
        : "模型配置"
    : route.name === "session"
      ? conversationContext.value?.title || "会话"
      : route.name === "version"
        ? conversationContext.value?.title || "会话"
        : route.name === "archives"
          ? "归档与回收站"
          : route.name === "project"
            ? "项目概览"
            : "项目",
);
/** 服务当前模式与项目最初生成来源分别显示。 */
const modeLabel = computed(() =>
  !state.runtime
    ? "服务未连接"
    : state.runtime.aiMode === "demo"
      ? "本地模板联调"
      : state.runtime.model,
);
/** 读取公开模式，连接失败提供重试且不显示凭证。 */
async function loadRuntime() {
  runtimeController?.abort();
  const controller = new AbortController();
  runtimeController = controller;
  try {
    const runtime = await getRuntimeInfo(controller.signal);
    if (!disposed && !controller.signal.aborted) {
      state.runtime = runtime;
      state.connectionError = "";
    }
  } catch (error) {
    if (!disposed && !controller.signal.aborted) {
      state.runtime = null;
      state.connectionError = error.message;
    }
  }
}
/** 保存或创建成功后同步侧栏，列表失败不会影响业务编辑。 */
async function refreshProjects() {
  listController?.abort();
  const controller = new AbortController();
  listController = controller;
  try {
    const projects = await listWorkspaceProjects(1, controller.signal);
    if (!disposed && !controller.signal.aborted) {
      state.projects = projects.items;
      state.listError = "";
    }
  } catch (error) {
    if (!disposed && !controller.signal.aborted)
      state.listError = error.message;
  }
}
/** 当前项目下的近期会话，路由切换取消旧列表请求。 */
async function refreshSessions() {
  sessionController?.abort();
  const projectId = route.params.projectId;
  state.sessions = [];
  state.sessionError = "";
  if (!projectId) return;
  const controller = new AbortController();
  sessionController = controller;
  try {
    const page = await listSessions(projectId, {}, controller.signal);
    if (!disposed && !controller.signal.aborted) state.sessions = page.items;
  } catch (error) {
    if (!disposed && !controller.signal.aborted)
      state.sessionError = error.message;
  }
}
watch(() => route.params.projectId, refreshSessions, { immediate: true });
/** 当前页的保存保护只在该页挂载期间存在。 */
let sessionGuard;
/** 会话变更通知让列表与正在打开的文档共享同一状态。 */
const sessionChange = ref(null);
/** 页面注册保存保护，返回卸载清理函数。 */
function registerSessionGuard(guard) { sessionGuard = guard; return () => { if (sessionGuard === guard) sessionGuard = null; }; }
/** 侧栏操作也必须等待当前文档保存，不能绕过失败状态。 */
async function prepareSessionAction(id, kind) { return sessionGuard ? sessionGuard(id, kind) : undefined; }
/** 更新公开通知，不把正文放进外壳。 */
function sessionChanged(record) { sessionChange.value = record; }
/** 当前文档可能由版本路由进入，不能只依赖 URL 的 sessionId。 */
function currentSessionId() { return conversationContext.value?.sessionId; }
provide(workspaceKey, { state, loadRuntime, refreshProjects, refreshSessions, setConversationContext, sessionChange, sessionChanged, registerSessionGuard, prepareSessionAction, currentSessionId });
onMounted(() => {
  loadRuntime();
  refreshProjects();
});
onBeforeUnmount(() => {
  disposed = true;
  runtimeController?.abort();
  listController?.abort();
  sessionController?.abort();
});
</script>
<template>
  <div :class="{ 'navigation-collapsed': navigationCollapsed }">
    <aside class="app-sidebar">
      <AppNavigation
        :projects="state.projects"
        :list-error="state.listError"
        :sessions="state.sessions"
        :session-error="state.sessionError"
        @retry="refreshProjects"
      />
    </aside>
    <div class="app-main">
      <header class="app-topbar">
        <div class="flex items-center gap-3 min-w-0">
          <Button variant="ghost" size="icon" :aria-label="navigationCollapsed ? '展开导航' : '收起导航'" @click="navigationPreference = !navigationCollapsed"><PanelLeft class="size-4" /></Button>
          <span class="text-xs text-muted-foreground truncate">{{
            parentLabel
          }}</span
          ><ChevronRight class="size-3 text-muted-foreground" /><span
            class="text-sm font-medium truncate"
            >{{ pageLabel }}</span
          >
        </div>
        <Badge variant="outline" class="gap-2 max-w-[50%] truncate"
          ><span
            :class="[
              'size-1.5 rounded-full shrink-0',
              state.runtime ? 'bg-emerald-500' : 'bg-amber-500',
            ]"
          />{{ modeLabel }}</Badge
        >
      </header>
      <!-- 路径隔离页面实例，旧自动保存和轮询响应不能写入另一工作空间。 -->
      <main class="app-content"><RouterView v-slot="{ Component }"><component :is="Component" :key="route.path" /></RouterView></main>
    </div>
  </div>
</template>
<style scoped>
/* 收起导航不卸载项目目录状态，展开后仍保留当前项目。 */
.navigation-collapsed .app-sidebar { display: none; }
.navigation-collapsed .app-main { margin-left: 0; }
</style>
