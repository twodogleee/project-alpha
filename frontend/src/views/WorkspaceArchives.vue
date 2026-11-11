<script setup>
/** 归档只影响目录可见性，恢复不改变版本取消状态。@author Andersen */
import { ref, onMounted } from "vue";
import { useRoute } from "vue-router";
import SessionActions from "@/components/SessionActions.vue";
import { toast } from "vue-sonner";
import { Archive, Undo2, FolderOpen } from "@lucide/vue";
import { Button } from "@/components/ui/button";
import * as api from "@/api/workspace.js";
import { useWorkspace } from "@/lib/workspace.js";
import { useWorkspacePage } from "@/composables/useWorkspacePage.js";
import { projectPath, sessionPath, versionPath } from "@/workspace/state.js";
/** 归档和回收站各自分页，打开链接可直接进入回收站。 */
const tab = ref(useRoute().query.tab === 'trash' ? 'trash' : 'archives'), trash = ref({ items: [], hasMore: false }), trashPage = ref(1);
/** 文档与会话分别展示，恢复文档不会恢复已删除会话。 */
const trashDocuments = ref({ items: [], hasMore: false });
/** 按当前回收页实际归属读取项目，不依赖侧栏仅含活动项目的首屏目录。 */
const trashProjects = ref({});
/** 三类归档独立分页合并。 */ const data = ref({
    projects: { items: [], hasMore: false },
    sessions: { items: [], hasMore: false },
    versions: { items: [], hasMore: false },
  }),
  page = ref(1),
  shell = useWorkspace();
/** 网络与提交状态。 */ const { busy, loading, error, read, act } =
  useWorkspacePage();
/** 同页同时读取三类历史目录，不删除旧项。 */ async function load(
  more = false,
) {
  if (tab.value === 'trash') {
    await read(async signal => {
      const [sessions, documents] = await Promise.all([
        api.loadDeletedSessions(more ? trashPage.value + 1 : 1, signal),
        api.loadDeletedVersions(more ? trashPage.value + 1 : 1, signal),
      ]);
      const ids = [...new Set([...sessions.items, ...documents.items].map(row => row.projectId))];
      const projects = await Promise.all(ids.map(async id => {
        try { return await api.loadProject(id, signal); }
        catch (cause) {
          if (signal.aborted) throw cause;
          // 项目信息暂时失败不隐藏可恢复内容，保留身份供区分并可重新载入。
          return { id, name: `项目 ${id}`, unavailable: true };
        }
      }));
      return [sessions, documents, Object.fromEntries(projects.map(project => [project.id, project]))];
    }, ([sessions, documents, projects]) => {
      if (more) {
        sessions.items = [...trash.value.items, ...sessions.items];
        documents.items = [...trashDocuments.value.items, ...documents.items];
      }
      trashProjects.value = more ? { ...trashProjects.value, ...projects } : projects;
      trash.value = sessions; trashDocuments.value = documents; trashPage.value = more ? trashPage.value + 1 : 1;
    });
    return;
  }
  await read(
    (s) => api.loadArchives(more ? page.value + 1 : 1, s),
    (value) => {
      for (const type of ["projects", "sessions", "versions"]) {
        if (more)
          value[type].items = [...data.value[type].items, ...value[type].items];
      }
      data.value = value;
      page.value = more ? page.value + 1 : 1;
    },
  );
}
/** 切换目录取消旧读取，命令期间保持当前页。 */
function selectTab(value) { if (busy.value) return; tab.value = value; load(); }
/** 回收站恢复不改变原归档或定稿状态。 */
async function restoreDeleted(row) {
  const result = await act(() => api.deleteSession(row.id, { expectedRevision: row.revision, deleted: false, includesPrd: false }));
  if (result) {
    shell.sessionChanged(result); await load(); await shell.refreshSessions();
    toast.success(result.archivedAt ? '会话已恢复，仍保留在归档中' : '会话已恢复；单独删除的文档需另行恢复');
  }
}
/** 恢复原文档身份和状态，已归档或取消的文档不因此变为可编辑。 */
async function restoreDocument(row) {
  const result = await act(() => api.deleteVersion(row.id, { expectedRevision: row.revision, deleted: false }));
  if (result) {
    await load();
    toast.success(result.archivedAt ? '文档已恢复，仍保留在归档中' : '文档已恢复，原会话关联和定稿状态保留');
  }
}
/** 恢复目录可见性；父项目已归档时需先恢复父项目。 */ async function restore(
  type,
  row,
) {
  const method = {
    projects: api.archiveProject,
    sessions: api.archiveSession,
    versions: api.archiveVersion,
  }[type];
  const result = await act(() =>
    method(row.id, { expectedRevision: row.revision, archived: false }),
  );
  if (result) {
    data.value[type].items = data.value[type].items.filter(
      (r) => r.id !== row.id,
    );
    await shell.refreshProjects();
    await shell.refreshSessions();
    toast.success("已恢复到目录；生命周期状态保持原样");
  }
}
/** 归档对象打开仍是只读详情。 */ function path(type, row) {
  return type === "projects"
    ? projectPath(row.id)
    : type === "sessions"
      ? sessionPath(row)
      : versionPath(row);
}
onMounted(() => load());
</script>
<template>
  <div class="workspace-page">
    <p class="eyebrow">ARCHIVES</p>
    <h1 class="page-title">归档与回收站</h1>
    <p class="muted mt-2">
      历史仍可查看。恢复会话不结束版本，恢复已取消版本不重新开启交付。
    </p>
    <div class="flex gap-2 mt-5" aria-label="历史目录分类"><Button :variant="tab === 'archives' ? 'secondary' : 'ghost'" :aria-pressed="tab === 'archives'" :disabled="busy" @click="selectTab('archives')">归档记录</Button><Button :variant="tab === 'trash' ? 'secondary' : 'ghost'" :aria-pressed="tab === 'trash'" :disabled="busy" @click="selectTab('trash')">回收站</Button></div>
    <div v-if="error" class="error-banner mt-6">
      {{ error
      }}<Button variant="outline" size="sm" @click="load()">重新载入</Button>
    </div>
    <p v-if="loading" class="muted py-6">正在读取归档…</p>
    <section v-if="tab === 'trash'" class="mt-6">
      <p class="muted text-sm mb-5">删除文档的最后一个关联会话时，文档会一并移入回收站；恢复该会话可一起恢复文档。单独删除的文档需单独恢复，恢复文档不会恢复已删除的会话。</p>
      <p v-if="Object.values(trashProjects).some(project => project.unavailable)" class="text-sm text-muted-foreground mb-4" role="status">部分项目名称读取失败，暂显示项目编号。<Button variant="link" size="sm" :disabled="busy || loading" @click="load()">重新载入</Button></p>
      <h2 class="mb-4">已删除文档</h2>
      <div v-if="!loading && !trashDocuments.items.length" class="workspace-empty compact">暂无已删除文档</div>
      <div class="session-list">
        <div v-for="row in trashDocuments.items" :key="row.id" class="session-row">
          <div class="flex-1 min-w-0">
            <RouterLink :to="versionPath(row)" class="font-semibold break-words hover:underline">{{ row.label }}</RouterLink>
            <div class="flex flex-wrap items-center gap-x-2 gap-y-1 mt-1 text-xs text-muted-foreground">
              <RouterLink :to="projectPath(row.projectId)" class="inline-flex items-center gap-1 min-w-0 hover:underline" :title="trashProjects[row.projectId]?.name"><FolderOpen class="size-3 shrink-0" /><span class="break-all">所属项目：{{ trashProjects[row.projectId]?.name || row.projectId }}</span></RouterLink>
              <span v-if="trashProjects[row.projectId]?.archivedAt">已归档 · 请先恢复项目</span>
            </div>
            <p class="text-xs text-muted-foreground mt-1">{{ row.archivedAt ? '恢复后仍在归档中' : '正文、待确认关联及历史均保留' }}</p>
          </div>
          <span class="text-xs text-muted-foreground shrink-0">{{ new Date(row.deletedAt).toLocaleDateString('zh-CN') }}</span>
          <Button variant="outline" size="sm" :disabled="busy || loading" @click="restoreDocument(row)"><Undo2 class="size-3.5" />恢复文档</Button>
        </div>
      </div>
      <h2 class="mb-4 mt-8">已删除会话</h2>
      <div v-if="!loading && !trash.items.length" class="workspace-empty compact">暂无已删除会话</div>
      <div class="session-list">
        <div v-for="row in trash.items" :key="row.id" class="session-row">
          <div class="flex-1 min-w-0">
            <RouterLink :to="sessionPath(row)" class="font-semibold break-words hover:underline">{{ row.title }}</RouterLink>
            <div class="flex flex-wrap items-center gap-x-2 gap-y-1 mt-1 text-xs text-muted-foreground">
              <RouterLink :to="projectPath(row.projectId)" class="inline-flex items-center gap-1 min-w-0 hover:underline" :title="trashProjects[row.projectId]?.name"><FolderOpen class="size-3 shrink-0" /><span class="break-all">所属项目：{{ trashProjects[row.projectId]?.name || row.projectId }}</span></RouterLink>
              <span v-if="trashProjects[row.projectId]?.archivedAt">已归档 · 请先恢复项目</span>
            </div>
            <p class="text-xs text-muted-foreground mt-1">{{ row.archivedAt ? '恢复后仍在归档中' : '恢复后回到会话列表' }}</p>
          </div>
          <span class="text-xs text-muted-foreground shrink-0">{{ new Date(row.deletedAt).toLocaleDateString('zh-CN') }}</span>
          <Button variant="outline" size="sm" :disabled="busy || loading" @click="restoreDeleted(row)"><Undo2 class="size-3.5" />恢复会话</Button>
        </div>
      </div>
      <Button v-if="trash.hasMore || trashDocuments.hasMore" variant="outline" class="mt-4" :disabled="busy || loading" @click="load(true)">加载更多已删除内容</Button>
    </section>
    <template v-else>
    <section
      v-for="type in ['projects', 'sessions', 'versions']"
      :key="type"
      class="mt-8"
    >
      <h2 class="mb-4">
        {{ { projects: "项目", sessions: "会话", versions: "需求版本" }[type] }}
      </h2>
      <div v-if="!data[type].items.length" class="workspace-empty compact">
        <Archive class="size-5 text-muted-foreground" />
        <p>
          暂无归档{{
            { projects: "项目", sessions: "会话", versions: "版本" }[type]
          }}
        </p>
      </div>
      <div class="session-list">
        <div v-for="row in data[type].items" :key="row.id" class="session-row">
          <RouterLink :to="path(type, row)" class="flex-1 font-medium">{{
            row.name || row.title || row.label
          }}</RouterLink
          ><span class="text-xs text-muted-foreground">{{
            new Date(row.archivedAt).toLocaleDateString("zh-CN")
          }}</span
          ><Button
            variant="outline"
            size="sm"
            :disabled="busy"
            @click="restore(type, row)"
            ><Undo2 class="size-3.5" />恢复</Button
          ><SessionActions v-if="type === 'sessions'" :session="row" :disabled="busy || loading" @changed="load()" />
        </div>
      </div>
    </section>
    <Button
      v-if="Object.values(data).some((p) => p.hasMore)"
      class="mt-6"
      variant="outline"
      :disabled="loading || busy"
      @click="load(true)"
      >加载更多归档</Button
    >
    </template>
  </div>
</template>
