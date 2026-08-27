<script setup>
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { api } from '../api.js';

const router = useRouter();
const query = ref('');
const results = ref([]);
const searched = ref(false);
const loading = ref(false);

async function search() {
  const q = query.value.trim();
  if (!q || loading.value) return;
  loading.value = true;
  try {
    results.value = await api.get(`/analysis/global-search?query=${encodeURIComponent(q)}&topK=10`);
  } catch {
    results.value = [];
  } finally {
    loading.value = false;
    searched.value = true;
  }
}

function fmt(ms) {
  if (!ms) return '—';
  const s = Math.floor(ms / 1000), m = Math.floor(s / 60), sec = s % 60;
  return String(m).padStart(2, '0') + ':' + String(sec).padStart(2, '0');
}

function open(r) {
  router.push(`/workspace/${r.mediaId}?t=${r.startMs}`);
}
</script>

<template>
  <div>
    <div class="page-head">
      <h2 style="margin:0;">知识库全局搜索</h2>
      <span class="muted">跨视频项目检索语义证据，点击结果直达对应画面</span>
    </div>

    <div class="searchbar">
      <input type="text" v-model="query" placeholder="输入问题，检索所有已完成视频的片段…" @keyup.enter="search" />
      <button class="btn" :disabled="loading" @click="search">{{ loading ? '检索中…' : '搜索' }}</button>
    </div>

    <div v-if="searched && !results.length" class="empty">未找到匹配的证据片段。</div>
    <div v-else class="results">
      <div v-for="(r, i) in results" :key="i" class="panel item" @click="open(r)">
        <div class="top">
          <span class="badge ok">片段 {{ i + 1 }}</span>
          <span class="badge info">{{ r.filename || ('#' + r.mediaId) }}</span>
          <span class="muted">{{ fmt(r.startMs) }} ~ {{ fmt(r.endMs) }}</span>
          <span class="muted">支撑 {{ (r.score * 100).toFixed(1) }}%</span>
        </div>
        <p>{{ r.summary }}</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: baseline; gap: 12px; margin-bottom: 16px; }
.searchbar { display: flex; gap: 8px; margin-bottom: 20px; }
.searchbar input { flex: 1; }
.results { display: flex; flex-direction: column; gap: 12px; }
.item { cursor: pointer; }
.item:hover { border-color: var(--primary); }
.item .top { display: flex; gap: 12px; align-items: center; margin-bottom: 8px; flex-wrap: wrap; }
.item p { margin: 0; }
</style>
