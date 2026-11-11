/** 原型专用启动；只安装 UI、内存 Store 与静态路由。@author Andersen */
import { createApp } from 'vue';
import { createPinia } from 'pinia';
import ElementPlus from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import 'element-plus/dist/index.css';
import './tailwind.css';
import './styles/zero-theme.css';
import './styles/prototype.css';
import App from './App.vue';
import { router } from './platform/routes.js';

/** 每次预览独立的应用实例，刷新即重置演示状态。 */
const app = createApp(App);
app.use(createPinia());
app.use(ElementPlus, { locale: zhCn });
app.use(router);
app.mount('#app');
