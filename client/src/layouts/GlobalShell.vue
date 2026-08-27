<script setup>
import { ref, onMounted } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { api } from '../api.js';
import { store, setUser, toggleTheme, counts, refreshCounts } from '../store.js';
import UploadModal from '../components/UploadModal.vue';

const router = useRouter();
const route = useRoute();
const showUpload = ref(false);

onMounted(async () => {
  try { setUser(await api.get('/user/me')); } catch {}
  refreshCounts();
});

const totalCount = () => Object.values(counts.status).reduce((a, b) => a + b, 0);

function logout() {
  api.clearToken();
  router.push('/login');
}

// 侧边栏筛选：不在视频库页时先回到视频库主页，再应用筛选
function pick(setter) {
  if (route.name !== 'library') router.push('/');
  if (setter) setter();
}
</script>

<template>
  <div class="shell">
    <!-- 顶部横向导航 -->
    <header class="topbar">
      <div class="brand">🎬 Video Agent</div>
      <nav class="nav-center">
        <router-link to="/" class="nav-item" :class="{ active: $route.name === 'library' || $route.name === 'workspace' }">视频库</router-link>
        <router-link to="/knowledge" class="nav-item" :class="{ active: $route.name === 'knowledge' }">知识库</router-link>
        <router-link to="/settings" class="nav-item" :class="{ active: $route.name === 'settings' }">设置</router-link>
      </nav>
      <div class="nav-right">
        <button class="icon-btn" @click="toggleTheme">{{ store.theme === 'light' ? '🌙' : '☀️' }}</button>
        <button class="btn upload-btn" @click="showUpload = true">上传视频</button>
        <span class="username">{{ store.user?.username || '' }}</span>
        <button class="btn ghost" @click="logout">退出</button>
      </div>
    </header>

    <div class="body">
      <!-- 左侧筛选侧边栏（视频库/工作台展示） -->
      <aside v-if="$route.name === 'library' || $route.name === 'workspace'" class="sidebar">
        <div class="side-block">
          <label>视频标题搜索</label>
          <input type="text" v-model="store.filters.search" placeholder="搜索视频标题…" @click="pick()" />
        </div>
        <div class="side-block">
          <label>任务状态</label>
          <div class="filter-item" :class="{ on: store.filters.status === 'ALL' }" @click="pick(() => { store.filters.status = 'ALL' })">
            <span>全部</span><span class="cnt">{{ totalCount() }}</span>
          </div>
          <div class="filter-item" :class="{ on: store.filters.status === 'PROCESSING' }" @click="pick(() => { store.filters.status = 'PROCESSING' })">
            <span>处理中</span><span class="cnt">{{ counts.status.PROCESSING || 0 }}</span>
          </div>
          <div class="filter-item" :class="{ on: store.filters.status === 'CONTEXT_READY' }" @click="pick(() => { store.filters.status = 'CONTEXT_READY' })">
            <span>已完成</span><span class="cnt">{{ counts.status.CONTEXT_READY || 0 }}</span>
          </div>
          <div class="filter-item" :class="{ on: store.filters.status === 'FAILED' }" @click="pick(() => { store.filters.status = 'FAILED' })">
            <span>失败</span><span class="cnt">{{ counts.status.FAILED || 0 }}</span>
          </div>
        </div>
        <div class="side-block">
          <label>视频来源</label>
          <div class="filter-item" :class="{ on: store.filters.source === 'ALL' }" @click="pick(() => { store.filters.source = 'ALL' })">
            <span>全部</span><span class="cnt">{{ totalCount() }}</span>
          </div>
          <div class="filter-item" :class="{ on: store.filters.source === 'LOCAL' }" @click="pick(() => { store.filters.source = 'LOCAL' })">
            <span>本地</span><span class="cnt">{{ counts.source.LOCAL || 0 }}</span>
          </div>
          <div class="filter-item" :class="{ on: store.filters.source === 'ONLINE' }" @click="pick(() => { store.filters.source = 'ONLINE' })">
            <span>在线</span><span class="cnt">{{ counts.source.ONLINE || 0 }}</span>
          </div>
        </div>
      </aside>
      <!-- 右侧内容视图 -->
      <main class="content" :class="{ full: $route.name === 'knowledge' || $route.name === 'settings' }">
        <router-view />
      </main>
    </div>

    <UploadModal :open="showUpload" @close="showUpload = false" />
  </div>
</template>

<style scoped>
.shell { display: flex; flex-direction: column; height: 100vh; }
.topbar {
  display: flex; align-items: center; gap: 20px; padding: 0 24px; height: 56px;
  background: var(--panel); border-bottom: 1px solid var(--border);
}
.brand { font-weight: 700; font-size: 17px; white-space: nowrap; }
.nav-center { display: flex; gap: 4px; flex: 1; justify-content: center; }
.nav-item { padding: 6px 18px; border-radius: 6px; color: var(--text-2); font-size: 14px; }
.nav-item.active, .nav-item:hover { background: var(--primary-light); color: var(--primary); }
.nav-right { display: flex; align-items: center; gap: 12px; }
.icon-btn { background: transparent; border: 1px solid var(--border); border-radius: 6px; width: 34px; height: 34px; }
.upload-btn { background: var(--primary); color: #fff; font-weight: 600; }
.username { color: var(--text-2); font-size: 14px; }
.body { display: flex; flex: 1; overflow: hidden; }
.sidebar { width: 260px; border-right: 1px solid var(--border); background: var(--sidebar-bg); overflow-y: auto; }
.side-block { padding: 16px 16px 8px; }
.side-block label { display: block; margin-bottom: 8px; }
.filter-item { display: flex; justify-content: space-between; align-items: center; padding: 6px 10px; border-radius: 6px; color: var(--text-2); font-size: 13px; cursor: pointer; }
.filter-item:hover { background: var(--hover); }
.filter-item.on { background: var(--primary-light); color: var(--primary); font-weight: 600; }
.cnt { font-size: 12px; background: var(--hover); border-radius: 8px; padding: 0 7px; color: var(--text-3); }
.filter-item.on .cnt { background: var(--primary-light); color: var(--primary); }
.content { flex: 1; overflow-y: auto; padding: 20px; }
.content.full { max-width: 960px; margin: 0 auto; }
</style>
