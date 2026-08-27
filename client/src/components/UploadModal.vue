<script setup>
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { api } from '../api.js';

const props = defineProps({ open: Boolean });
const emit = defineEmits(['close']);
const router = useRouter();

const file = ref(null);
const goal = ref('');
const uploading = ref(false);
const progress = ref(0);
const error = ref('');

function pick(e) { file.value = e.target.files[0]; error.value = ''; }

async function upload() {
  if (!file.value) { error.value = '请先选择视频文件'; return; }
  error.value = '';
  uploading.value = true;
  progress.value = 5;
  try {
    const up = await api.upload(file.value, p => { progress.value = 5 + Math.round(p * 80); });
    progress.value = 90;
    await api.post('/analysis', {
      mediaId: up.id,
      userGoal: goal.value.trim() || '总结视频内容，提炼核心结论并给出建议',
      mode: 'general',
    });
    progress.value = 100;
    emit('close');
    router.push(`/workspace/${up.id}`);
  } catch (e) {
    error.value = e.message;
  } finally {
    uploading.value = false;
  }
}
</script>

<template>
  <div v-if="open" class="mask" @click.self="emit('close')">
    <div class="modal panel">
      <h3>上传视频</h3>
      <div class="drop" @click="$refs.input.click()">
        <input ref="input" type="file" accept="video/*" hidden @change="pick" />
        <p v-if="!file">点击选择视频文件（mp4 / mov / mkv …）</p>
        <p v-else><b>{{ file.name }}</b>（{{ (file.size / 1048576).toFixed(1) }} MB）</p>
      </div>
      <div class="field">
        <label>分析目标（可选）</label>
        <textarea v-model="goal" rows="3" placeholder="例如：总结本课的核心知识点，并给出可执行的复习建议"></textarea>
      </div>
      <div v-if="uploading" class="bar"><div class="fill" :style="{ width: progress + '%' }"></div></div>
      <p v-if="error" class="err">{{ error }}</p>
      <div class="actions">
        <button class="btn" @click="emit('close')">取消</button>
        <button class="btn primary" :disabled="uploading" @click="upload">
          {{ uploading ? '上传并分析中…' : '上传并开始分析' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mask { position: fixed; inset: 0; background: rgba(0,0,0,.5); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal { width: 460px; padding: 24px; }
.drop { border: 1px dashed var(--border); border-radius: 10px; padding: 28px; text-align: center; cursor: pointer; margin-bottom: 14px; }
.drop:hover { border-color: var(--primary); }
.field label { display: block; margin-bottom: 6px; }
textarea { width: 100%; }
.bar { height: 8px; background: var(--hover); border-radius: 4px; overflow: hidden; margin: 12px 0; }
.fill { height: 100%; background: var(--primary); transition: width .2s; }
.err { color: var(--danger); font-size: 13px; }
.actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 8px; }
</style>
