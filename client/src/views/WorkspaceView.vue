<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { api } from '../api.js';
import ConfirmDialog from '../components/ConfirmDialog.vue';
import EditableTitle from '../components/EditableTitle.vue';

const route = useRoute();
const router = useRouter();
const mediaId = route.params.mediaId;
const media = ref(null);
const result = ref(null);
const context = ref(null);
const videoSrc = ref('');
const question = ref('');
const chat = ref([]);
const sending = ref(false);
const video = ref(null);
const statusText = ref('');
const showDelete = ref(false);

// 阶段 → 中文提示（进度条 / 状态横幅共用）
const STAGE_LABEL = {
  SUBMITTED: '任务已提交',
  INGEST: '下载解析视频',
  ASR: '语音转写 (ASR)',
  OCR: '画面文字识别 (OCR)',
  ASR_FAILED: '语音转写失败（降级）',
  OCR_FAILED: '画面识别失败（降级）',
  ALIGN: '对齐合并',
  AGENT_PLAN: 'Agent 规划中',
  AGENT_EXECUTE: 'Agent 生成结论中',
  AGENT_CRITIC: 'Agent 评审中',
  AGENT_ROUND: 'Agent 补证中',
  AGENT_COMPLETED: 'Agent 完成',
};
function stageLabel(s) {
  return (s && STAGE_LABEL[s]) || s || '';
}

async function load() {
  try { media.value = await api.get(`/media/${mediaId}`); } catch {}
  try { result.value = await api.get(`/analysis/result?mediaId=${mediaId}`); } catch {}
  try { context.value = await api.get(`/analysis/context?mediaId=${mediaId}`); } catch {}
  // 恢复持久化的追问历史
  try {
    const history = await api.chatHistory(mediaId);
    if (Array.isArray(history) && history.length) {
      chat.value = history.map(h => ({ role: h.role, text: h.content }));
    }
  } catch {}
}
onMounted(() => { load(); loadVideo(); });

// 流接口需鉴权：fetch 带 Authorization 头 → blob → 本地 URL 播放（处理中即可播放，文件已上传）
async function loadVideo() {
  try {
    const res = await fetch('/api/media/' + mediaId + '/stream', {
      headers: { Authorization: 'Bearer ' + api.token() },
    });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const blob = await res.blob();
    videoSrc.value = URL.createObjectURL(blob);
  } catch (e) {
    statusText.value = '视频加载失败：' + e.message;
  }
}

let timer = null;
onMounted(() => {
  timer = setInterval(pollStatus, 4000);
});
onBeforeUnmount(() => { clearInterval(timer); if (videoSrc.value) URL.revokeObjectURL(videoSrc.value); });

// 轮询：状态/进度 + 渐进取回已完成的区域（context/result）
function pollStatus() {
  if (!media.value) return;
  api.get(`/media/${mediaId}`).then(m => {
    media.value = m;
    if (m.status === 'CONTEXT_READY') {
      clearInterval(timer);
      timer = null;
    } else if (m.status === 'FAILED') {
      statusText.value = '处理失败';
    }
  }).catch(() => {});
  // 已完成的区域渐进取回：context（转写就绪后可用）、result（Agent 完成后可用）
  if (!context.value) {
    api.get(`/analysis/context?mediaId=${mediaId}`).then(c => { context.value = c; }).catch(() => {});
  }
  if (!result.value) {
    api.get(`/analysis/result?mediaId=${mediaId}`).then(r => { result.value = r; }).catch(() => {});
  }
}

// 知识库/证据跳转：?t=ms 定位播放
const pendingSeek = ref(null);
watch(() => route.query.t, (t) => {
  pendingSeek.value = t ? Number(t) : null;
  if (t && video.value && video.value.readyState >= 1) {
    video.value.currentTime = Number(t) / 1000;
  }
});
function onLoaded() {
  if (pendingSeek.value != null && video.value) {
    video.value.currentTime = pendingSeek.value / 1000;
    pendingSeek.value = null;
  }
}

function seekTo(ms) {
  if (!video.value) return;
  video.value.currentTime = ms / 1000;
  video.value.play().catch(() => {});
}

function fmt(ms) {
  const s = Math.floor((ms || 0) / 1000);
  const m = Math.floor(s / 60), sec = s % 60;
  return String(m).padStart(2, '0') + ':' + String(sec).padStart(2, '0');
}

// ---- 结论区（标题 / 核心结论 / 建议） ----
const resultData = computed(() => result.value || {});
const title = computed(() => resultData.value.title || media.value?.title || media.value?.filename || '分析结果');
const conclusions = computed(() => resultData.value.conclusions || []);
const suggestions = computed(() => resultData.value.suggestions || []);
const evidenceAll = computed(() => resultData.value.evidence || []);

// 每条结论绑定其 claim 命中的时间戳证据
const conclusionRows = computed(() => {
  const evs = evidenceAll.value;
  return conclusions.value.map(c => ({
    text: c,
    evidence: evs.filter(e => !e.claim || e.claim === c || e.claim.includes(c) || c.includes(e.claim)),
  }));
});

// ---- 完整 ASR 转写（右侧顶部） ----
const asrLines = computed(() => {
  const segs = context.value?.segments || [];
  return segs
    .filter(s => s.transcript && s.transcript.trim())
    .map(s => ({ startMs: s.startMs, text: s.transcript.trim() }));
});

// ---- 连续追问（需上下文就绪）：LLM 自然语言回答，历史持久化到后端 ----
async function ask() {
  const q = question.value.trim();
  if (!q || sending.value || !context.value) return;
  chat.value.push({ role: 'user', text: q });
  question.value = '';
  sending.value = true;
  try {
    const history = chat.value.slice(-7, -1).map(c => ({ role: c.role, content: c.text }));
    const data = await api.chat(Number(mediaId), q, history);
    // 后端已持久化并返回完整历史，直接覆盖本地（保证与服务端一致）
    if (data && Array.isArray(data.history) && data.history.length) {
      chat.value = data.history.map(h => ({ role: h.role, text: h.content }));
    } else {
      chat.value.push({ role: 'assistant', text: (data && data.answer) || '抱歉，暂时无法回答。' });
    }
  } catch (e) {
    chat.value.push({ role: 'assistant', text: '请求失败：' + e.message });
  } finally {
    sending.value = false;
  }
}

// ---- 视频标题：保存修改 / 自动生成（与视频库同一字段，自动同步） ----
async function saveTitle(t) {
  try {
    const up = await api.putTitle(Number(mediaId), t);
    if (media.value) media.value.title = up.title;
  } catch (e) {
    alert('保存标题失败：' + e.message);
  }
}
async function autoTitle() {
  try {
    const up = await api.autoTitle(Number(mediaId));
    if (media.value) media.value.title = up.title;
  } catch (e) {
    alert('自动生成标题失败：' + e.message);
  }
}

// ---- 删除当前记录 ----
async function doDelete() {
  showDelete.value = false;
  try {
    await api.post('/media/batch-delete', { ids: [Number(mediaId)] });
    router.push('/');
  } catch (e) {
    alert('删除失败：' + e.message);
  }
}
</script>

<template>
  <div>
    <div class="page-head">
      <button class="btn ghost" @click="router.push('/')">← 返回主页</button>
      <h2 style="margin:0;">解析工作台</h2>
      <span class="muted">{{ media?.filename || '#' + mediaId }}</span>
      <span v-if="media" class="title-edit">
        <EditableTitle :title="media.title" :on-auto="autoTitle" @save="saveTitle" />
      </span>
      <button class="btn ghost head-trace" @click="router.push(`/workspace/${mediaId}/trace`)">可信度 trace</button>
      <button class="btn danger head-del" @click="showDelete = true">删除该记录</button>
    </div>

    <!-- 处理中 / 失败 状态横幅 -->
    <div v-if="media && media.status !== 'CONTEXT_READY'" class="panel banner" :class="{ bad: media.status === 'FAILED' }">
      <template v-if="media.status === 'FAILED'">
        <span class="b-ico">⚠️</span> 处理失败{{ statusText ? '：' + statusText : '' }}
      </template>
      <template v-else>
        <span class="spinner"></span>
        处理中（{{ media.progress ?? 0 }}%）：{{ stageLabel(media.stage) || '排队中' }}
        —— 已完成的区域会实时显示在下方
      </template>
    </div>

    <div v-if="!media" class="panel"><p class="muted">加载中…</p></div>

    <template v-else>
      <div class="grid">
        <!-- 左列：播放器 + 连续追问 -->
        <div class="left">
          <div class="panel">
            <video ref="video" :src="videoSrc" controls class="player" @loadedmetadata="onLoaded"></video>
          </div>
          <div class="panel chat">
            <div class="chat-box">
              <div v-for="(c, i) in chat" :key="i" class="msg" :class="c.role">
                <b>{{ c.role === 'user' ? '你' : 'Agent' }}</b>
                <span>{{ c.text }}</span>
              </div>
              <div v-if="!chat.length" class="muted">
                {{ context ? '对视频内容连续追问，例如「XX 定理的前提是什么？」' : '上下文尚未生成，转写完成后即可追问…' }}
              </div>
            </div>
            <div class="chat-input">
              <input type="text" v-model="question" :disabled="!context" placeholder="输入追问…" @keyup.enter="ask" />
              <button class="btn" :disabled="sending || !context" @click="ask">发送</button>
            </div>
          </div>
        </div>

        <!-- 右列：顶部完整 ASR 记录；下方标题/核心结论/建议（未完成区域显示加载中） -->
        <div class="right">
          <div class="panel asr">
            <div class="panel-head">
              <span class="plabel">完整 ASR 转写记录</span>
              <span class="muted">{{ context ? `共 ${asrLines.length} 条（点击定位画面）` : '生成中…' }}</span>
            </div>
            <div v-if="context" class="asr-box">
              <div v-for="(l, i) in asrLines" :key="i" class="asr-line" @click="seekTo(l.startMs)">
                <span class="ts">{{ fmt(l.startMs) }}</span>
                <span class="txt">{{ l.text }}</span>
              </div>
              <p v-if="!asrLines.length" class="muted">暂无转写记录。</p>
            </div>
            <div v-else class="loading">
              <span class="spinner"></span> 语音转写生成中，完成后自动显示…
            </div>
          </div>

          <div class="panel">
            <h3>{{ title }}</h3>
            <template v-if="result">
              <p class="label">核心结论</p>
              <div v-for="(c, i) in conclusions" :key="i" class="conclusion">
                <p class="ctitle">{{ i + 1 }}. {{ c }}</p>
                <div v-if="conclusionRows[i] && conclusionRows[i].evidence.length" class="tags">
                  <button
                    v-for="(e, j) in conclusionRows[i].evidence" :key="j"
                    class="tag" @click="seekTo(e.timestampMs)">
                    {{ fmt(e.timestampMs) }}{{ e.source ? ' · ' + e.source : '' }}
                  </button>
                </div>
              </div>
              <p v-if="!conclusions.length" class="muted">暂无结构化结论。</p>

              <p class="label">建议</p>
              <ul v-if="suggestions.length">
                <li v-for="(s, i) in suggestions" :key="i">{{ s }}</li>
              </ul>
              <p v-else class="muted">暂无建议。</p>
            </template>
            <div v-else class="loading">
              <span class="spinner"></span> Agent 分析中，结论与建议生成后自动显示…
            </div>
          </div>
        </div>
      </div>
    </template>

    <ConfirmDialog
      :open="showDelete"
      title="删除该视频记录"
      message="确定删除这条视频记录吗？将同时删除视频文件、转写/OCR、分析结果、反馈等全部关联数据，且不可恢复。"
      danger
      @confirm="doDelete"
      @cancel="showDelete = false"
    />
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.head-del { margin-left: auto; }
.head-trace { white-space: nowrap; }
.banner { display: flex; align-items: center; gap: 10px; color: var(--text-2); font-size: 13px; }
.banner.bad { color: var(--danger); }
.b-ico { font-size: 15px; }
.spinner {
  width: 14px; height: 14px; border: 2px solid var(--border); border-top-color: var(--primary);
  border-radius: 50%; display: inline-block; animation: rot 0.8s linear infinite; flex-shrink: 0;
}
@keyframes rot { to { transform: rotate(360deg); } }
.grid { display: grid; grid-template-columns: 1.4fr 1fr; gap: 16px; }
.left, .right { display: flex; flex-direction: column; gap: 16px; min-width: 0; }
.player { width: 100%; max-height: 420px; background: #000; border-radius: 8px; }
.chat { display: flex; flex-direction: column; height: 340px; }
.chat-box { flex: 1; overflow-y: auto; display: flex; flex-direction: column; gap: 10px; margin-bottom: 12px; }
.msg { display: flex; flex-direction: column; gap: 4px; white-space: pre-wrap; }
.msg b { color: var(--text-2); font-size: 12px; }
.msg.user b { color: var(--primary); }
.chat-input { display: flex; gap: 8px; }
.chat-input input { flex: 1; }
h3 { margin: 0 0 12px; }
.label { color: var(--text-2); font-size: 12px; margin: 14px 0 4px; }
.conclusion { margin-bottom: 14px; }
.ctitle { margin: 0 0 6px; font-weight: 600; }
.tags { display: flex; flex-wrap: wrap; gap: 6px; }
.loading { display: flex; align-items: center; gap: 8px; color: var(--text-3); font-size: 13px; padding: 24px 0; }

/* ASR 面板 */
.panel-head { display: flex; align-items: baseline; gap: 10px; margin-bottom: 10px; }
.plabel { font-weight: 600; }
.asr { display: flex; flex-direction: column; }
.asr-box { max-height: 300px; overflow-y: auto; display: flex; flex-direction: column; }
.asr-line { display: flex; gap: 10px; padding: 5px 8px; border-radius: 6px; cursor: pointer; font-size: 13px; line-height: 1.6; }
.asr-line:hover { background: var(--hover); }
.asr-line .ts { color: var(--primary); font-weight: 600; flex-shrink: 0; font-size: 12px; padding-top: 2px; }
.asr-line .txt { color: var(--text); }
</style>
