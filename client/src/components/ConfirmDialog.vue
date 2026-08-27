<script setup>
defineProps({
  open: Boolean,
  title: { type: String, default: '确认操作' },
  message: { type: String, default: '' },
  danger: { type: Boolean, default: false },
  confirmText: { type: String, default: '确认删除' },
});
const emit = defineEmits(['confirm', 'cancel']);
</script>

<template>
  <div v-if="open" class="mask" @click.self="emit('cancel')">
    <div class="panel dlg">
      <h3>{{ title }}</h3>
      <p class="msg">{{ message }}</p>
      <div class="actions">
        <button class="btn" @click="emit('cancel')">取消</button>
        <button class="btn" :class="danger ? 'danger' : ''" @click="emit('confirm')">{{ confirmText }}</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mask { position: fixed; inset: 0; background: rgba(0,0,0,.5); display: flex; align-items: center; justify-content: center; z-index: 200; }
.dlg { width: 380px; padding: 24px; margin: 0; }
.msg { color: var(--text-2); margin: 0 0 18px; line-height: 1.6; }
.actions { display: flex; justify-content: flex-end; gap: 8px; }
</style>
