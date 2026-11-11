/** Hash 路由显式表达项目归属与会话/版本身份。@author Andersen */
import { createRouter, createWebHashHistory } from "vue-router";
import AppShell from "@/components/AppShell.vue";
import SystemSettings from "@/views/SystemSettings.vue";
import PrdTemplates from "@/views/PrdTemplates.vue";
import ProjectDirectory from "@/views/ProjectDirectory.vue";
import ProjectOverview from "@/views/ProjectOverview.vue";
import UnifiedWorkspace from "@/views/UnifiedWorkspace.vue";
import VersionWorkspace from "@/views/VersionWorkspace.vue";
import WorkspaceArchives from "@/views/WorkspaceArchives.vue";
/** 旧评审工作台退出，不导入旧项目快照。 */
export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    {
      path: "/",
      component: AppShell,
      redirect: "/projects",
      children: [
        { path: "projects", name: "projects", component: ProjectDirectory },
        {
          path: "projects/:projectId",
          name: "project",
          component: ProjectOverview,
        },
        {
          path: "projects/:projectId/sessions/:sessionId",
          name: "session",
          component: UnifiedWorkspace,
        },
        {
          path: "projects/:projectId/versions/:versionId",
          name: "version",
          component: UnifiedWorkspace,
        },
        {
          path: "projects/:projectId/versions/:versionId/manage",
          name: "version-management",
          component: VersionWorkspace,
        },
        { path: "archives", name: "archives", component: WorkspaceArchives },
        {
          path: "settings/platforms",
          name: "platforms",
          component: SystemSettings,
        },
        { path: "settings/models", name: "models", component: SystemSettings },
        { path: "settings/prd-templates", name: "prd-templates", component: PrdTemplates },
        { path: "workspace", redirect: "/projects" },
      ],
    },
    { path: "/:pathMatch(.*)*", redirect: "/projects" },
  ],
});
