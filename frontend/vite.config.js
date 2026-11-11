/**
 * Alpha 工作台开发与构建配置；开发代理保持后端 /api 路径不变。
 * @author Andersen
 */
import { defineConfig, loadEnv } from 'vite';
import vue from '@vitejs/plugin-vue';
import tailwindcss from '@tailwindcss/vite';
import { fileURLToPath } from 'node:url';

export default defineConfig(({ mode }) => {
  const environment = loadEnv(mode, process.cwd(), 'VITE_');
  return {
    plugins: [vue(), tailwindcss()],
    resolve: {
      alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
    },
    server: {
      host: '127.0.0.1',
      port: Number(environment.VITE_DEV_PORT || 5178),
      strictPort: true,
      proxy: {
        '/api': { target: environment.VITE_DEV_API_PROXY_TARGET || 'http://127.0.0.1:18080' },
      },
    },
  };
});
