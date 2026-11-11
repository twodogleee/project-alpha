/** 平台维护路由入口；后续依据已校验页面清单确定性组装。@author Andersen */
import { createRouter, createWebHashHistory } from 'vue-router';
import PersonnelList from '../features/generated/personnel/PersonnelList.vue';
import PersonnelDetail from '../features/generated/personnel/PersonnelDetail.vue';

/** 样板导航及路由身份，不从外部菜单 API 获取。 */
export const pages = Object.freeze([
  { id: 'personnel-list', path: '/personnel', title: '人员管理', menu: true, component: PersonnelList },
  { id: 'personnel-detail', path: '/personnel/:id', title: '人员详情', parentPath: '/personnel', menu: false, component: PersonnelDetail },
]);

/** 独立原型路由，不安装 OAuth 或后端权限守卫。 */
export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/personnel' },
    ...pages.map((page) => ({ path: page.path, name: page.id, component: page.component, meta: { title: page.title, parentPath: page.parentPath } })),
    { path: '/:pathMatch(.*)*', redirect: '/personnel' },
  ],
});
