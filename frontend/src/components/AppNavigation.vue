<script setup>
/** 项目组织会话的桌面导航；正式交付范围从项目版本目录进入。@author Andersen */
import { useRoute, useRouter } from "vue-router";
import {
  Plus,
  FolderOpen,
  MessageSquare,
  Archive,
  Network,
  SlidersHorizontal,
  FileText,
  ChevronRight,
} from "@lucide/vue";
import SessionActions from "@/components/SessionActions.vue";
import { Button } from "@/components/ui/button";
import { projectProfile } from "@/project/profile.js";
import { projectPath, sessionPath } from "@/workspace/state.js";
/** 外壳公开目录只读，编辑副本不进入导航。 */
defineProps({
  projects: { type: Array, default: () => [] },
  sessions: { type: Array, default: () => [] },
  listError: String,
  sessionError: String,
});
/** 目录失败请求重读。 */ defineEmits(["retry"]);
/** 选中态来自路由稳定标识。 */ const route = useRoute(),
  router = useRouter();
/** 每次侧栏命令打开新建表单，不隐式创建对象。 */
function newConversation() {
  router.push(
    route.params.projectId
      ? {
          path: projectPath(route.params.projectId),
          query: { newSession: Date.now().toString() },
        }
      : "/projects",
  );
}
</script>
<template>
  <nav class="app-navigation" aria-label="主导航">
    <RouterLink class="brand" to="/projects"
      ><span class="brand-mark">α</span
      ><span>{{ projectProfile.name }}<small>从讨论到交付</small></span></RouterLink
    >
    <Button class="w-full justify-start" @click="newConversation"
      ><Plus class="size-4" />新建会话</Button
    >
    <RouterLink
      to="/projects"
      :class="['nav-link mt-4', { 'is-active': route.name === 'projects' }]"
      ><FolderOpen />全部项目</RouterLink
    >
    <div class="nav-section-label">项目与会话</div>
    <div class="nav-projects space-y-1">
      <p v-if="listError" class="text-xs text-muted-foreground px-3">
        {{ listError
        }}<Button variant="ghost" size="sm" @click="$emit('retry')"
          >重试</Button
        >
      </p>
      <div v-for="project in projects" :key="project.id">
        <RouterLink
          :to="projectPath(project.id)"
          :class="[
            'nav-link',
            {
              'is-active':
                route.name === 'project' &&
                route.params.projectId === project.id,
            },
          ]"
          ><FolderOpen class="size-4" /><span class="truncate flex-1">{{
            project.name
          }}</span
          ><ChevronRight class="size-3"
        /></RouterLink>
        <div
          v-if="route.params.projectId === project.id"
          class="ml-3 pl-2 border-l space-y-1"
        >
          <div v-for="session in sessions.slice(0, 12)" :key="session.id" class="flex items-center min-w-0">
          <RouterLink
            class="flex-1 min-w-0"

            :to="sessionPath(session)"
            :class="[
              'nav-link text-xs',
              { 'is-active': route.params.sessionId === session.id },
            ]"
            ><MessageSquare class="size-3.5" /><span class="truncate">{{
              session.title
            }}</span></RouterLink>
            <SessionActions :session="session" />
          </div>
          <p
            v-if="!sessions.length"
            class="text-xs text-muted-foreground py-3 px-2"
          >
            {{ sessionError || "还没有会话" }}
          </p>
          <RouterLink
            :to="projectPath(project.id)"
            class="block text-xs text-muted-foreground px-3 py-2"
            >全部会话 →</RouterLink
          >
        </div>
      </div>
      <p
        v-if="!projects.length && !listError"
        class="text-xs text-muted-foreground px-3 py-3"
      >
        先创建项目，再开始讨论。
      </p>
    </div>
    <RouterLink
      to="/archives"
      :class="['nav-link', { 'is-active': route.name === 'archives' }]"
      ><Archive />归档与回收站</RouterLink
    >
    <div class="nav-section-label">系统设置</div>
    <RouterLink
      to="/settings/platforms"
      :class="['nav-link', { 'is-active': route.name === 'platforms' }]"
      ><Network />接入平台</RouterLink
    >
    <RouterLink
      to="/settings/models"
      :class="['nav-link', { 'is-active': route.name === 'models' }]"
      ><SlidersHorizontal />模型配置</RouterLink
    >
    <RouterLink
      to="/settings/prd-templates"
      :class="['nav-link', { 'is-active': route.name === 'prd-templates' }]"
      ><FileText />PRD 模板</RouterLink
    >
    <div class="nav-footer">会话 · 需求 · PRD</div>
  </nav>
</template>
