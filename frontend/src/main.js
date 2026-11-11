/**
 * 装配 Alpha 的 Vue 应用、路由与 shadcn-vue 主题。
 * @author Andersen
 */
import { createApp } from 'vue';
import './styles/theme.css';
import App from './App.vue';
import { router } from './router/index.js';

createApp(App).use(router).mount('#app');
