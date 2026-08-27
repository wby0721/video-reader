<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue';
import { useRouter } from 'vue-router';
import { api } from '../api.js';
import { store, refreshCounts } from '../store.js';
import ConfirmDialog from '../components/ConfirmDialog.vue';
import EditableTitle from '../components/EditableTitle.vue';

const router = useRouter();
const list = ref([]);
const loading = ref(true);
const selected = ref(new Set());
const showConfirm = ref(false);
const confirmMsg = ref('');

const STATUS_MAP = {
  UPLOADED: { label: '待处理', cls: 'gray' },
  PROCESSING: { label: '处理中', cls: 'info' },
  CONTEXT_READY: { label: '已完成', cls: 'ok' },
  FAILED: { label: '失败', cls: 'bad' },
};

// 处理阶段 → 中文标签（进度条上显示当前到哪一步）
const STAGE_LABEL = {
  SUBMITTED: '已提交',
  INGEST: '下载解析',
  ASR: '语音转写',
  OCR: '画面识别',
  ASR_FAILED: '转写降级',
  OCR_FAILED: '画面降级',
  ALIGN: '对齐合并',
  AGENT_PLAN: 'Agent 规划',
  AGENT_EXECUTE: 'Agent 生成结论',
  AGENT_CRITIC: 'Agent 评审',
  AGENT_ROUND: 'Agent 补证',
  AGENT_COMPLETED: 'Agent 完成',
};
function stageLabel(s) {
  return (s && STAGE_LABEL[s]) || s || '';
}

async function load() {
  loading.value = true;
  try {
    list.value = await api.get('/media/list');
    refreshCounts();
  } catch {}
  finally { loading.value = false; }
}
onMounted(() => { load(); poll(); });

// 存在处理中记录时轮询刷新进度
let timer = null;
function poll() {
  timer = setInterval(() => {
    if (list.value.some(m => m.status === 'PROCESSING')) load();
  }, 5000);
}
onBeforeUnmount(() => clearInterval(timer));

const filtered = computed(() => list.value.filter(m => {
  // 标题搜索：同时匹配 视频标题 与 视频文件名
  const q = (store.filters.search || '').trim().toLowerCase();
  if (q) {
    const name = (m.filename || '').toLowerCase();
    const title = (m.title || '').toLowerCase();
    if (!name.includes(q) && !title.includes(q)) return false;
  }
  if (store.filters.status !== 'ALL' && m.status !== store.filters.status) return false;
  if (store.filters.source !== 'ALL' && store.filters.source !== 'LOCAL') return false; // 当前全部本地
  return true;
}));

// ---- 文件名展开/收起（有标题时默认收起） ----
const expanded = ref(new Set());
function toggleFile(m) {
  if (expanded.value.has(m.id)) expanded.value.delete(m.id);
  else expanded.value.add(m.id);
  expanded.value = new Set(expanded.value);
}

// ---- 勾选 / 全选 ----
const allChecked = computed(() => filtered.value.length > 0 && filtered.value.every(m => selected.value.has(m.id)));
function toggleAll() {
  if (allChecked.value) {
    filtered.value.forEach(m => selected.value.delete(m.id));
  } else {
    filtered.value.forEach(m => selected.value.add(m.id));
  }
  selected.value = new Set(selected.value);
}
function toggleOne(id) {
  if (selected.value.has(id)) selected.value.delete(id);
  else selected.value.add(id);
  selected.value = new Set(selected.value);
}

function openDeleteConfirm() {
  const n = selected.value.size;
  if (!n) return;
  confirmMsg.value = `确定删除选中的 ${n} 条视频记录吗？将同时删除该视频文件、转写/OCR、分析结果与反馈等全部关联数据，且不可恢复。`;
  showConfirm.value = true;
}
async function doDelete() {
  showConfirm.value = false;
  const ids = [...selected.value];
  try {
    await api.post('/media/batch-delete', { ids });
    selected.value = new Set();
    await load();
  } catch (e) {
    alert('删除失败：' + e.message);
  }
}

// ---- 视频标题：保存修改 / 自动生成 ----
async function saveTitle(m, t) {
  try {
    const up = await api.putTitle(m.id, t);
    m.title = up.title;
  } catch (e) {
    alert('保存标题失败：' + e.message);
  }
}
async function autoTitle(m) {
  try {
    const up = await api.autoTitle(m.id);
    m.title = up.title;
  } catch (e) {
    alert('自动生成标题失败：' + e.message);
  }
}

// ---- 展示辅助 ----
function fmt(ms) {
  if (!ms) return '—';
  const s = Math.floor(ms / 1000);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  return (h ? h + '时' : '') + (m ? m + '分' : '') + sec + '秒';
}
function fmtStep(ms) {
  if (ms == null) return '—';
  const s = Math.round(ms / 100) / 10;
  return s < 60 ? s + 's' : fmt(ms);
}
</script>

<template>
  <div>
    <div class="page-head">
      <h2 style="margin:0;">视频库</h2>
      <span class="muted">共 {{ filtered.length }} 条任务记录</span>
      <div class="toolbar">
        <label class="sel-all">
          <input type="checkbox" :checked="allChecked" @change="toggleAll" />
          全选
        </label>
        <button class="btn danger" :disabled="!selected.size" @click="openDeleteConfirm">
          删除（{{ selected.size }}）
        </button>
      </div>
    </div>

    <div v-if="loading" class="muted">加载中…</div>
    <div v-else-if="!filtered.length" class="empty">暂无记录，点击右上角「上传视频」开始。</div>
    <div v-else class="rows">
      <div v-for="(m, i) in filtered" :key="m.id" class="row">
        <input type="checkbox" class="pick" :checked="selected.has(m.id)" @change="toggleOne(m.id)" @click.stop />
        <span class="idx">{{ i + 1 }}</span>
        <span class="badge" :class="(STATUS_MAP[m.status] || {}).cls">{{ (STATUS_MAP[m.status] || {}).label || m.status }}</span>
        <span class="dur muted">{{ fmt(m.durationMs) }}</span>

        <div class="main" @click="router.push(`/workspace/${m.id}`)">
          <div class="title-line">
            <!-- 有标题：标题作为主标签（与文件名同大小同颜色），文件名默认收起可展开 -->
            <template v-if="m.title">
              <span class="title-edit" @click.stop>
                <EditableTitle plain :title="m.title" :on-auto="() => autoTitle(m)" @save="(t) => saveTitle(m, t)" />
              </span>
              <span class="file-toggle" @click.stop>
                <button class="file-btn" @click="toggleFile(m)">{{ expanded.has(m.id) ? '⏷ 收起文件名' : '⏵ 展开文件名' }}</button>
                <span v-if="expanded.has(m.id)" class="file-full" :title="m.filename">{{ m.filename }}</span>
              </span>
            </template>
            <!-- 无标题：显示文件名 + 添加/自动生成标题 -->
            <template v-else>
              <span class="title">{{ m.filename }}</span>
              <span class="title-edit" @click.stop>
                <EditableTitle :title="m.title" :on-auto="() => autoTitle(m)" @save="(t) => saveTitle(m, t)" />
              </span>
            </template>
            <!-- 已完成：展示完整处理时间 + 各步骤耗时 -->
            <template v-if="m.status === 'CONTEXT_READY'">
              <span class="chip total">总耗时 {{ fmtStep(m.totalProcessMs) }}</span>
              <span v-for="s in (m.steps || [])" :key="s.key" class="chip">{{ s.label }} {{ fmtStep(s.durationMs) }}</span>
            </template>
          </div>
          <!-- 处理中：进度条（含当前步骤） -->
          <div v-if="m.status === 'PROCESSING'" class="progress">
            <div class="fill" :style="{ width: (m.progress ?? 0) + '%' }"></div>
            <span class="pct">{{ m.progress ?? 0 }}%</span>
            <span class="stage">{{ stageLabel(m.stage) || '排队中' }}</span>
          </div>
        </div>

        <button class="btn primary-outline detail-btn" @click="router.push(`/workspace/${m.id}`)">查看详情 →</button>
      </div>
    </div>

    <ConfirmDialog
      :open="showConfirm"
      title="删除视频记录"
      :message="confirmMsg"
      danger
      @confirm="doDelete"
      @cancel="showConfirm = false"
    />
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.toolbar { margin-left: auto; display: flex; align-items: center; gap: 10px; }
.sel-all { display: flex; align-items: center; gap: 6px; font-size: 13px; color: var(--text-2); cursor: pointer; }
.empty { color: var(--text-3); padding: 60px 0; text-align: center; }
.rows { display: flex; flex-direction: column; gap: 8px; }
.row {
  display: flex; align-items: center; gap: 14px; padding: 12px 16px;
  background: var(--panel); border: 1px solid var(--border); border-radius: 10px;
}
.row:hover { border-color: var(--primary); }
.pick { flex-shrink: 0; width: 16px; height: 16px; cursor: pointer; }
.idx { color: var(--text-3); width: 22px; text-align: center; flex-shrink: 0; }
.dur { width: 88px; flex-shrink: 0; }
.main { flex: 1; min-width: 0; cursor: pointer; }
.title-line { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.title { font-weight: 700; color: var(--text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 320px; }
.file-toggle { display: inline-flex; align-items: center; gap: 6px; }
.file-btn {
  font-size: 11px; padding: 2px 8px; border-radius: 6px; background: transparent;
  color: var(--text-2); border: 1px solid var(--border); white-space: nowrap;
}
.file-btn:hover { color: var(--primary); border-color: var(--primary); }
.file-full {
  font-size: 12px; color: var(--text-2); background: var(--hover); border-radius: 6px;
  padding: 2px 8px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 260px;
}
.chip {
  font-size: 11px; color: var(--text-2); background: var(--hover);
  border-radius: 6px; padding: 2px 7px; white-space: nowrap;
}
.chip.total { color: var(--primary); background: var(--primary-light); font-weight: 600; }
.progress { display: flex; align-items: center; gap: 8px; margin-top: 8px; height: 8px; }
.progress .fill { height: 100%; background: var(--primary); border-radius: 4px; transition: width .4s; }
.progress .pct { font-size: 11px; color: var(--text-2); }
.progress .stage { font-size: 11px; color: var(--primary); background: var(--primary-light); border-radius: 6px; padding: 0 6px; }
.detail-btn { white-space: nowrap; }
</style>
