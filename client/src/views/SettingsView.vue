<script setup>
import { ref, onMounted } from 'vue';
import { api } from '../api.js';
import { store } from '../store.js';

const profile = ref(null);
const llmConfig = ref(null);
const asr = ref({ engine: 'local', remainingHours: 5.0, channels: 1 });
const asrSaving = ref(false);

// 用户自带密钥表单
const llmForm = ref({ apiKey: '', baseUrl: 'https://api.deepseek.com', model: 'deepseek-v4-flash' });
const llmSaving = ref(false);
const asrKeyForm = ref({ appId: '', apiKey: '', apiSecret: '' });
const asrKeySaving = ref(false);

async function load() {
  try { profile.value = await api.get('/user/me'); } catch {}
  try { llmConfig.value = await api.get('/user/llm-config'); } catch {}
  try { asr.value = await api.get('/settings/asr'); } catch {}
  try {
    const c = await api.get('/user/asr-config');
    if (c?.configured) {
      asrKeyForm.value = { appId: '', apiKey: '', apiSecret: '' };
      Object.assign(asrKeyForm.value, { configured: true, masked: c });
    }
  } catch {}
}
onMounted(load);

async function saveAsr() {
  asrSaving.value = true;
  try {
    asr.value = await api.put('/settings/asr', {
      engine: asr.value.engine,
      remainingHours: Number(asr.value.remainingHours) || 0,
    });
    alert('ASR 识别设置已保存');
  } catch (e) {
    alert('保存失败：' + e.message);
  } finally {
    asrSaving.value = false;
  }
}

// 保存用户自带 LLM Key（AES-GCM 加密落库，不存明文）
async function saveLlmKey() {
  if (!llmForm.value.apiKey.trim()) { alert('请填写 API Key'); return; }
  llmSaving.value = true;
  try {
    await api.post('/user/llm-config', {
      apiKey: llmForm.value.apiKey.trim(),
      baseUrl: llmForm.value.baseUrl.trim() || 'https://api.deepseek.com',
      model: llmForm.value.model,
    });
    llmForm.value.apiKey = '';
    llmConfig.value = await api.get('/user/llm-config');
    alert('LLM Key 已保存（加密存储，仅显示脱敏值）');
  } catch (e) {
    alert('保存失败：' + e.message);
  } finally {
    llmSaving.value = false;
  }
}

// 保存用户自带讯飞 ASR 凭据（AES-GCM 加密落库）
async function saveAsrKey() {
  const f = asrKeyForm.value;
  if (!f.appId.trim() || !f.apiKey.trim() || !f.apiSecret.trim()) { alert('请完整填写讯飞 APPID / APIKey / APISecret'); return; }
  asrKeySaving.value = true;
  try {
    await api.post('/user/asr-config', {
      appId: f.appId.trim(),
      apiKey: f.apiKey.trim(),
      apiSecret: f.apiSecret.trim(),
    });
    f.appId = ''; f.apiKey = ''; f.apiSecret = '';
    const c = await api.get('/user/asr-config');
    if (c?.configured) Object.assign(f, { configured: true, masked: c });
    alert('讯飞凭据已保存（加密存储，仅显示脱敏值）');
  } catch (e) {
    alert('保存失败：' + e.message);
  } finally {
    asrKeySaving.value = false;
  }
}

const MODELS = [
  { name: 'deepseek-v4-flash', label: 'DeepSeek V4 Flash（默认，经济）' },
  { name: 'deepseek-v4', label: 'DeepSeek V4（更强，更贵）' },
];

function onModel(e) {
  store.model = e.target.value;
  localStorage.setItem('va_model', store.model);
}
</script>

<template>
  <div>
    <div class="page-head"><h2 style="margin:0;">个人设置</h2></div>

    <div class="panel">
      <h3>用户资料</h3>
      <div class="row"><span class="k">用户名</span><span>{{ profile?.username || '—' }}</span></div>
      <div class="row"><span class="k">昵称</span><span>{{ profile?.nickname || '—' }}</span></div>
      <div class="row"><span class="k">角色</span><span>{{ profile?.role || '—' }}</span></div>
    </div>

    <div class="panel">
      <h3>我的 LLM API Key（可选）</h3>
      <p class="muted">不配置时使用系统默认模型；配置后分析/问答/标题生成优先使用你的 Key，<b>AES-GCM 加密存储，仅你本人可见脱敏值</b>。</p>
      <div class="row">
        <span class="k">状态</span>
        <span>{{ llmConfig?.configured ? '已配置（' + (llmConfig.apiKeyMasked || '') + '）' : '未配置（使用系统默认模型）' }}</span>
      </div>
      <div class="form-grid">
        <label>API Key（sk-…，提交后不再明文回显）<input type="password" v-model="llmForm.apiKey" placeholder="sk-xxx" autocomplete="off" /></label>
        <label>Base URL <input v-model="llmForm.baseUrl" /></label>
        <label>模型
          <select v-model="llmForm.model">
            <option v-for="m in MODELS" :key="m.name" :value="m.name">{{ m.label }}</option>
          </select>
        </label>
      </div>
      <button class="btn" :disabled="llmSaving" @click="saveLlmKey">{{ llmSaving ? '保存中…' : '保存 LLM Key' }}</button>
    </div>

    <div class="panel">
      <h3>ASR 语音识别引擎</h3>
      <label class="asr-opt">
        <input type="radio" value="local" v-model="asr.engine" />
        本地模型（Qwen3-ASR-0.6B，GPU，免费离线）
      </label>
      <label class="asr-opt">
        <input type="radio" value="xfyun" v-model="asr.engine" />
        在线 API（科大讯飞极速录音转写）
      </label>
      <div v-if="asr.engine === 'xfyun'" class="xf-info">
        <div class="row">
          <span class="k">剩余时长</span>
          <input type="number" min="0" step="0.5" v-model.number="asr.remainingHours" style="width: 110px;" /> 小时
        </div>
        <div class="row"><span class="k">并发路数</span><span>{{ asr.channels || 1 }} 路</span></div>
        <p class="muted" style="margin-top:6px;">
          配额按讯飞控制台「极速录音转写」维护，请定期手动更新剩余时长；单路并发，多人同时用在线引擎会排队。
        </p>
      </div>
      <button class="btn" :disabled="asrSaving" @click="saveAsr">{{ asrSaving ? '保存中…' : '保存 ASR 设置' }}</button>
    </div>

    <div class="panel">
      <h3>我的讯飞 ASR 凭据（可选）</h3>
      <p class="muted">在线转写优先使用你的讯飞账号（APPID/APIKey/APISecret），<b>AES-GCM 加密存储，仅你本人可见脱敏值</b>；不配置时回退服务端账号。</p>
      <div class="row">
        <span class="k">状态</span>
        <span v-if="asrKeyForm.configured">已配置（APPID: {{ asrKeyForm.masked.appIdMasked }} / Key: {{ asrKeyForm.masked.apiKeyMasked }}）</span>
        <span v-else>未配置（使用系统账号）</span>
      </div>
      <div class="form-grid">
        <label>讯飞 APPID<input type="text" v-model="asrKeyForm.appId" placeholder="6 位数字" autocomplete="off" /></label>
        <label>讯飞 APIKey<input type="password" v-model="asrKeyForm.apiKey" autocomplete="off" /></label>
        <label>讯飞 APISecret<input type="password" v-model="asrKeyForm.apiSecret" autocomplete="off" /></label>
      </div>
      <button class="btn" :disabled="asrKeySaving" @click="saveAsrKey">{{ asrKeySaving ? '保存中…' : '保存讯飞凭据' }}</button>
    </div>

    <div class="panel">
      <h3>AI 预算与限额</h3>
      <div class="row"><span class="k">限流策略</span><span>用户级 + 全局级令牌桶（每秒请求数），超限返回 429</span></div>
      <div class="row"><span class="k">用量监控</span><span>每次分析产生 telemetry：阶段耗时 / LLM 调用次数 / Token / 成本估算</span></div>
    </div>
  </div>
</template>

<style scoped>
.page-head { margin-bottom: 16px; }
h3 { margin: 0 0 12px; }
.row { display: flex; align-items: center; gap: 20px; padding: 6px 0; }
.k { color: var(--text-2); width: 150px; flex-shrink: 0; }
.asr-opt { display: block; padding: 6px 0; cursor: pointer; }
.asr-opt input { margin-right: 8px; }
.xf-info {
  background: var(--hover); border-radius: 8px; padding: 10px 14px; margin: 8px 0 12px;
}
.form-grid { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 12px; margin: 10px 0 12px; }
.form-grid label { display: flex; flex-direction: column; gap: 4px; font-size: 13px; color: var(--text-2); }
.form-grid input, .form-grid select { padding: 6px 8px; border: 1px solid var(--border, #ddd); border-radius: 6px; background: var(--bg); color: var(--text-1); }
.muted { color: var(--text-2); font-size: 13px; }
</style>
