import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

// Vite 开发服务器：/api → 后端 8081（SSE 用 fetch/EventSource 直连同源代理）
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
});
