/** 固定原型构建入口；业务生成不能修改插件、依赖或服务代理。@author Andersen */
import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import AutoImport from 'unplugin-auto-import/vite';

export default defineConfig({
  // 静态预览可挂载在任意受限会话路径下。
  base: './',
  plugins: [vue(), AutoImport({ imports: ['vue'], dts: false })],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  build: { sourcemap: false, outDir: 'dist', emptyOutDir: true },
});
