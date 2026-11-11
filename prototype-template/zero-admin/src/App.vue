<script setup>
/** 使用 Zero 主题的原型壳；静态菜单与演示身份替代上游认证接线。@author Andersen */
import { computed, ref } from 'vue';
import { useRoute } from 'vue-router';
import { User, Fold, Expand, RefreshLeft } from '@element-plus/icons-vue';
import { ElMessageBox } from 'element-plus';
import { profile } from './project/profile.js';
import { pages } from './platform/routes.js';

/** 当前路径用于展示面包屑和菜单，不参与权限判断。 */
const route = useRoute();
/** 折叠状态仅在当前内存保存。 */
const collapsed = ref(false);
/** 平台允许在侧栏显示的入口。 */
const menuPages = pages.filter((page) => page.menu);
/** 当前菜单跟随平台页面关系，详情页继承其父入口。 */
const activeMenuPath = computed(() => route.meta.parentPath || route.path);
/** 原型默认首页来自受控页面清单。 */
const homePage = menuPages[0];
/** 当前业务页面标题。 */
const pageTitle = computed(() => route.meta.title || '人员管理');

/** 显式重置会丢弃本次演示数据和未保存表单，先显示影响说明。 */
async function handleReset() {
  try {
    await ElMessageBox.confirm('重置本次演示新增、修改及未保存的内容，恢复初始模拟数据。', '重置演示数据', { type: 'warning', confirmButtonText: '重置', cancelButtonText: '取消' });
    window.location.reload();
  } catch { /* 用户取消时保留现状。 */ }
}
</script>

<template>
  <div class="prototype-shell">
    <aside class="prototype-sidebar" :class="{ 'is-collapsed': collapsed }">
      <div class="prototype-brand"><span class="prototype-brand-mark">Z</span><span v-if="!collapsed">ZERO <small>管理端原型</small></span></div>
      <div v-if="!collapsed" class="prototype-nav-label">业务工作台</div>
      <el-menu router :collapse="collapsed" :default-active="activeMenuPath" background-color="transparent" text-color="var(--project-alpha-sidebar-text)" active-text-color="white">
        <el-menu-item v-for="page in menuPages" :key="page.id" :index="page.path"><el-icon><User /></el-icon><template #title>{{ page.title }}</template></el-menu-item>
      </el-menu>
      <div v-if="!collapsed" class="prototype-sidebar-note">模板样板<br><span>仅用于产品体验与联调</span></div>
    </aside>
    <div class="prototype-body">
      <header class="prototype-header">
        <div class="prototype-heading"><el-button text circle :aria-label="collapsed ? '展开导航' : '收起导航'" @click="collapsed = !collapsed"><el-icon><Expand v-if="collapsed" /><Fold v-else /></el-icon></el-button><span>{{ profile.title }}</span><span class="prototype-breadcrumb">/ {{ pageTitle }}</span></div>
        <div class="prototype-header-actions"><el-button data-testid="reset-demo" text :icon="RefreshLeft" @click="handleReset">重置演示数据</el-button><span class="prototype-avatar">演</span><span>演示用户</span></div>
      </header>
      <div class="prototype-tabs"><router-link :to="homePage.path" :class="{ active: route.name === homePage.id }">{{ homePage.title }}</router-link><span v-if="route.name !== homePage.id" class="active">{{ pageTitle }}</span></div>
      <div class="prototype-provenance" role="note">{{ profile.provenance }}</div>
      <main class="prototype-main"><router-view /></main>
    </div>
  </div>
</template>
