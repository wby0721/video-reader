<script setup>
import { ref, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { api } from '../api.js';

const route = useRoute();
const router = useRouter();
const mediaId = route.params.mediaId;
const trace = ref(null);
const chat = ref([]);
const loading = ref(true);
const error = ref('');
const tab = ref('process'); // process | chat

async function load() {
  loading.value = true;
  try {
    trace.value = await api.get(`/analysis/trace?mediaId=${mediaId}`);
  } catch (e) {
    error.value = e.message;
  } finally {
    loading.value = false;
  }
}
async function loadChat() {
  try { chat.value = await api.chatHistory(mediaId); } catch { chat.value = []; }
}
onMounted(() => { load(); loadChat(); });

function fmt(ms) {
  if (ms == null) return '—';
  const s = Math.round(ms / 100) / 10;
  return s < 60 ? s + 's' : Math.floor(s / 60) + '分' + Math.round(s % 60) + 's';
}
function fmtTs(ms) {
  const s = Math.floor((ms || 0) / 1000);
  return String(Math.floor(s / 60)).padStart(2, '0') + ':' + String(s % 60).padStart(2, '0');
}
function pct(x) { return x == null ? '—' : (x * 100).toFixed(1) + '%'; }
const fmtTime = (ts) => (ts ? new Date(ts).toLocaleTimeString('zh-CN', { hour12: false }) : '');

const verdictCls = (s) => (s === 'SUPPORTED' ? 'ok' : s === 'UNSUPPORTED' ? 'bad' : 'warn');
const verdictLabel = (s) => (s === 'SUPPORTED' ? '有支撑' : s === 'UNSUPPORTED' ? '无支撑' : '不可判定');
</script>

<template>
  <div>
    <div class="page-head">
      <button class="btn ghost" @click="router.push(`/workspace/${mediaId}`)">← 返回工作台</button>
      <h2 style="margin:0;">可信度 trace</h2>
      <div class="tabs">
        <button class="tab" :class="{ on: tab === 'process' }" @click="tab = 'process'">基础流程</button>
        <button class="tab" :class="{ on: tab === 'chat' }" @click="tab = 'chat'">问答</button>
      </div>
      <span class="muted" v-if="tab === 'process' && trace">目标：{{ trace.goal }}</span>
    </div>

    <!-- ============ 基础流程 ============ -->
    <template v-if="tab === 'process'">
      <div v-if="loading" class="panel"><p class="muted">加载中…</p></div>
      <div v-else-if="error" class="panel"><p class="err">{{ error }}</p></div>

      <template v-else>
        <!-- 1. 处理流程时间线 -->
        <div class="panel">
          <h3>① 处理流程时间线</h3>
          <div class="chips">
            <span v-for="s in (trace.timeline?.steps || [])" :key="s.key" class="chip">{{ s.label }} {{ fmt(s.durationMs) }}</span>
            <span class="chip total">总耗时 {{ fmt(trace.timeline?.totalMs) }}</span>
          </div>
        </div>

        <!-- 2. Agent 阶段耗时 -->
        <div class="panel">
          <h3>② Agent 阶段耗时（telemetry）</h3>
          <table>
            <thead><tr><th>阶段</th><th>耗时</th><th>LLM 调用</th><th>Token 估算</th></tr></thead>
            <tbody>
              <tr v-for="(t, i) in (trace.telemetry?.stages || [])" :key="i">
                <td>{{ t.stage }}</td><td>{{ fmt(t.durationMs) }}</td><td>{{ t.llmCalls }}</td><td>{{ t.tokensEstimate }}</td>
              </tr>
              <tr v-if="!(trace.telemetry?.stages || []).length"><td colspan="4" class="muted">暂无</td></tr>
            </tbody>
          </table>
        </div>

        <!-- 3. 规划 -->
        <div class="panel" v-if="trace.plan">
          <h3>③ Agent 规划（Planner）</h3>
          <p><b>目标理解：</b>{{ trace.plan.understoodGoal }}</p>
          <ul><li v-for="(t, i) in trace.plan.tasks" :key="i">{{ t }}</li></ul>
        </div>

        <!-- 4. 各轮执行与评审 -->
        <div class="panel" v-if="(trace.rounds || []).length">
          <h3>④ 执行与评审（Executor → Critic）</h3>
          <div v-for="r in trace.rounds" :key="r.round" class="round">
            <h4>第 {{ r.round + 1 }} 轮</h4>
            <div v-if="r.executor">
              <p class="label">Executor 产出</p>
              <p class="title-line">{{ r.executor.title }}</p>
              <ul><li v-for="(c, i) in (r.executor.conclusions || [])" :key="i">{{ c }}</li></ul>
              <p class="muted">建议：{{ (r.executor.suggestions || []).join('；') || '—' }}</p>
            </div>
            <div v-if="r.critic">
              <p class="label">Critic 评审</p>
              <p :class="r.critic.passed ? 'ok-text' : 'bad-text'">{{ r.critic.passed ? '✅ 通过' : '❌ 未通过' }}</p>
              <ul><li v-for="(f, i) in (r.critic.feedback || [])" :key="i" class="muted">{{ f }}</li></ul>
              <p class="muted" v-if="(r.critic.missingRequirements || []).length">未覆盖任务：{{ r.critic.missingRequirements.join('；') }}</p>
              <p class="muted" v-if="(r.critic.unsupportedClaims || []).length">无证据结论：{{ r.critic.unsupportedClaims.join('；') }}</p>
              <p class="muted" v-if="(r.critic.requiredTimestamps || []).length">需补证据时间戳：{{ r.critic.requiredTimestamps.join(', ') }}ms</p>
            </div>
          </div>
        </div>

        <!-- 5. 最终结果 -->
        <div class="panel" v-if="trace.final">
          <h3>⑤ 最终结果</h3>
          <p class="title-line">{{ trace.final.title }}</p>
          <p class="label">核心结论</p>
          <ol><li v-for="(c, i) in (trace.final.conclusions || [])" :key="i">{{ c }}</li></ol>
          <p class="label">建议</p>
          <ul><li v-for="(s, i) in (trace.final.suggestions || [])" :key="i">{{ s }}</li></ul>
          <p class="warn-text" v-if="trace.final.warning">⚠️ {{ trace.final.warning }}</p>
        </div>

        <!-- 6. 支撑验证 -->
        <div class="panel" v-if="trace.verification">
          <h3>⑥ 最终支撑验证</h3>
          <div class="metrics">
            <div class="metric"><b>{{ trace.verification.totalConclusions }}</b><span class="cap">结论数</span></div>
            <div class="metric"><b class="ok">{{ trace.verification.supportedConclusions }}</b><span class="cap">有支撑</span></div>
            <div class="metric"><b class="bad">{{ trace.verification.unsupportedConclusions }}</b><span class="cap">无支撑(幻觉)</span></div>
            <div class="metric"><b>{{ pct(trace.verification.semanticSupportRate) }}</b><span class="cap">语义支撑率</span></div>
            <div class="metric"><b :class="trace.verification.hallucinationRate > 0.2 ? 'bad' : 'ok'">{{ pct(trace.verification.hallucinationRate) }}</b><span class="cap">幻觉率</span></div>
          </div>
          <table>
            <thead><tr><th>结论</th><th>判定</th><th>理由</th></tr></thead>
            <tbody>
              <tr v-for="(v, i) in (trace.verification.verdicts || [])" :key="i">
                <td class="claim">{{ v.claim }}</td>
                <td><span class="badge" :class="verdictCls(v.status)">{{ verdictLabel(v.status) }}</span></td>
                <td class="muted">{{ v.reason }}</td>
              </tr>
              <tr v-if="!(trace.verification.verdicts || []).length"><td colspan="3" class="muted">暂无逐条判定</td></tr>
            </tbody>
          </table>
          <p v-if="trace.verification.l3Review" class="label">L3 独立复核：<span>{{ trace.verification.l3Review }}</span></p>
        </div>

        <!-- 7. 评估指标 -->
        <div class="panel" v-if="trace.evaluation">
          <h3>⑦ 质量评估指标</h3>
          <table>
            <thead><tr><th>指标</th><th>值</th></tr></thead>
            <tbody>
              <tr v-for="(v, k) in trace.evaluation.metrics" :key="k">
                <td>{{ k }}</td><td>{{ typeof v === 'number' ? (Math.abs(v) < 1 && v !== 0 ? pct(v) : v) : v }}</td>
              </tr>
            </tbody>
          </table>
          <p class="muted">成本估算：¥{{ trace.evaluation.costEstimateYuan?.toFixed(4) }} · LLM 调用 {{ trace.evaluation.totalLlmCalls }} 次 · Token {{ trace.evaluation.totalTokensEstimate }}</p>
        </div>
      </template>
    </template>

    <!-- ============ 问答 ============ -->
    <div v-else class="panel">
      <h3>连续追问记录（{{ chat.length }} 条）</h3>
      <p class="muted">每条 assistant 回答下方展示其检索依据的 Top-N 证据片段摘要。</p>
      <div v-for="(c, i) in chat" :key="i" class="chat-item" :class="c.role">
        <div class="chat-head">
          <b>{{ c.role === 'user' ? '你' : 'Agent' }}</b>
          <span class="muted">{{ fmtTime(c.ts) }}</span>
        </div>
        <p class="chat-text">{{ c.content }}</p>
        <div v-if="c.role === 'assistant' && (c.evidence || []).length" class="evid">
          <p class="label">依据证据片段：</p>
          <div v-for="(e, j) in c.evidence" :key="j" class="evid-item">
            <span class="badge info">{{ fmtTs(e.startMs) }}~{{ fmtTs(e.endMs) }}</span>
            <span class="evid-sum">{{ e.summary }}</span>
            <span class="muted">支撑 {{ pct(e.score) }}</span>
          </div>
        </div>
      </div>
      <p v-if="!chat.length" class="muted">暂无追问记录。</p>
    </div>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.tabs { display: flex; gap: 4px; background: var(--hover); border-radius: 8px; padding: 3px; }
.tab { background: transparent; border: none; border-radius: 6px; padding: 5px 14px; font-size: 13px; color: var(--text-2); }
.tab.on { background: var(--panel); color: var(--primary); font-weight: 600; }
h3 { margin: 0 0 12px; }
h4 { margin: 12px 0 6px; color: var(--text-2); }
.chips { display: flex; flex-wrap: wrap; gap: 8px; }
.chip { font-size: 12px; color: var(--text-2); background: var(--hover); border-radius: 6px; padding: 3px 9px; }
.chip.total { color: var(--primary); background: var(--primary-light); font-weight: 600; }
.label { color: var(--text-2); font-size: 12px; margin: 12px 0 4px; }
.title-line { font-weight: 700; }
.round { border-top: 1px dashed var(--border); padding-top: 8px; margin-top: 8px; }
.metrics { display: flex; gap: 18px; flex-wrap: wrap; margin-bottom: 12px; }
.metric { text-align: center; }
.metric b { font-size: 20px; display: block; }
.metric .cap { font-size: 11px; color: var(--text-3); }
.ok { color: var(--ok); }
.bad { color: var(--danger); }
.warn { color: var(--warn); }
.ok-text { color: var(--ok); font-weight: 600; }
.bad-text { color: var(--danger); font-weight: 600; }
.warn-text { color: var(--warn); }
.err { color: var(--danger); }
.claim { max-width: 420px; }
.chat-item { border-top: 1px dashed var(--border); padding: 10px 0; }
.chat-head { display: flex; gap: 10px; align-items: center; }
.chat-head b.user { color: var(--primary); }
.chat-text { margin: 6px 0; white-space: pre-wrap; }
.evid { background: var(--hover); border-radius: 8px; padding: 8px 12px; margin-top: 6px; }
.evid-item { display: flex; gap: 8px; align-items: flex-start; padding: 3px 0; }
.evid-sum { flex: 1; font-size: 13px; }
</style>
