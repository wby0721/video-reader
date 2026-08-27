import { reactive } from 'vue';
import { api } from './api.js';

// 轻量全局状态：鉴权 + 主题（无需 Pinia）
export const store = reactive({
  user: null,
  get loggedIn() {
    return !!api.token();
  },
  theme: localStorage.getItem('va_theme') || 'light',
  model: localStorage.getItem('va_model') || 'deepseek-v4-flash',
  // 视频库筛选（左侧边栏共享状态）
  filters: {
    search: '',
    status: 'ALL',       // ALL / UPLOADED / PROCESSING / CONTEXT_READY / FAILED
    source: 'ALL',       // ALL / LOCAL / ONLINE（后端暂无来源字段，本地展示）
  },
});

// 侧边栏过滤统计（任务状态 / 视频来源计数，供筛选项展示）
export const counts = reactive({ status: {}, source: {} });

export async function refreshCounts() {
  try {
    const list = await api.get('/media/list');
    const s = {};
    const src = {};
    for (const m of list) {
      s[m.status] = (s[m.status] || 0) + 1;
      const key = 'LOCAL'; // 后端暂无来源字段，当前全部归本地
      src[key] = (src[key] || 0) + 1;
    }
    counts.status = s;
    counts.source = src;
  } catch {}
}

export function setUser(u) {
  store.user = u;
}

export function toggleTheme() {
  store.theme = store.theme === 'light' ? 'dark' : 'light';
  localStorage.setItem('va_theme', store.theme);
  document.documentElement.setAttribute('data-theme', store.theme);
}

export function applyTheme() {
  document.documentElement.setAttribute('data-theme', store.theme);
}
