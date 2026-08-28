import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

// Vite 开发服务器：/api → 后端 8081（SSE 用 fetch/EventSource 直连同源代理）
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    // 允许 Cloudflare Tunnel 域名访问（trycloudflare 每次重启换随机子域名，用通配符）
    allowedHosts: ['.trycloudflare.com'],
    proxy: {
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
});
