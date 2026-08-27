<script setup>
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { api } from '../api.js';
import { setUser } from '../store.js';

const router = useRouter();
const mode = ref('login');
const username = ref('');
const password = ref('');
const nickname = ref('');
const error = ref('');
const loading = ref(false);

async function submit() {
  error.value = '';
  loading.value = true;
  try {
    const data = mode.value === 'register'
      ? await api.post('/user/register', { username: username.value, password: password.value, nickname: nickname.value || username.value })
      : await api.post('/user/login', { username: username.value, password: password.value });
    api.setToken(data.token);
    try { setUser(await api.get('/user/me')); } catch {}
    router.push('/');
  } catch (e) {
    error.value = e.message;
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="auth-page">
    <div class="auth-card">
      <div class="logo">🎬</div>
      <h2>Video Agent</h2>
      <p class="muted">可信长视频理解工作台</p>

      <div class="tabs">
        <div class="tab" :class="{ on: mode === 'login' }" @click="mode = 'login'">登录</div>
        <div class="tab" :class="{ on: mode === 'register' }" @click="mode = 'register'">注册</div>
      </div>

      <div class="field">
        <label>用户名</label>
        <input type="text" v-model="username" placeholder="3~32 位字母数字下划线" />
      </div>
      <div class="field">
        <label>密码</label>
        <input type="password" v-model="password" placeholder="至少 6 位" @keyup.enter="submit" />
      </div>
      <div v-if="mode === 'register'" class="field">
        <label>昵称（可选）</label>
        <input type="text" v-model="nickname" placeholder="展示名称" />
      </div>

      <p v-if="error" class="err">{{ error }}</p>
      <button class="btn submit" :disabled="loading" @click="submit">
        {{ loading ? '提交中…' : (mode === 'login' ? '登录' : '注册并进入') }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.auth-page { height: 100vh; display: flex; align-items: center; justify-content: center; background: var(--bg); }
.auth-card { width: 360px; background: var(--panel); border: 1px solid var(--border); border-radius: 14px; padding: 32px; text-align: center; }
.logo { font-size: 40px; }
h2 { margin: 8px 0 2px; }
.tabs { display: flex; background: var(--hover); border-radius: 8px; padding: 4px; margin: 20px 0 16px; }
.tab { flex: 1; padding: 8px; border-radius: 6px; cursor: pointer; color: var(--text-2); }
.tab.on { background: var(--panel); color: var(--text); font-weight: 600; }
.field { text-align: left; margin-bottom: 14px; }
.field label { display: block; margin-bottom: 6px; }
.err { color: var(--danger); font-size: 13px; }
.submit { width: 100%; margin-top: 6px; }
</style>
