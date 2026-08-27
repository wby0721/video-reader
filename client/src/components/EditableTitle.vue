<script setup>
import { ref } from 'vue';

const props = defineProps({
  title: { type: String, default: '' },
  onAuto: { type: Function, default: null },
  plain: { type: Boolean, default: false }, // 纯文本模式：与相邻主标题同大小同颜色
});
const emit = defineEmits(['save']);

const editing = ref(false);
const draft = ref('');
let esc = false;

function start() {
  draft.value = props.title || '';
  editing.value = true;
  esc = false;
}
function commit() {
  editing.value = false;
  emit('save', draft.value.trim());
}
function onKey(e) {
  if (e.key === 'Enter') commit();
  else if (e.key === 'Escape') { esc = true; editing.value = false; }
}
function onBlur() {
  if (esc) { esc = false; return; }
  commit();
}
</script>

<template>
  <span class="et">
    <template v-if="!editing">
      <span v-if="title" :class="['et-chip', { plain }]" :title="'点击修改标题：' + title" @click="start">
        <template v-if="plain">{{ title }}<span class="et-pen">✏️</span></template>
        <template v-else>📄 {{ title }} ✏️</template>
      </span>
      <span v-else class="et-empty">
        <button v-if="onAuto" class="et-btn" @click="onAuto()">✨ 自动生成标题</button>
        <button class="et-btn" @click="start">＋ 添加标题</button>
      </span>
    </template>
    <span v-else class="et-editbox" @click.stop>
      <input
        v-model="draft"
        maxlength="64"
        placeholder="输入视频标题，回车保存"
        @keydown="onKey"
        @blur="onBlur"
      />
    </span>
  </span>
</template>

<style scoped>
.et { display: inline-flex; align-items: center; vertical-align: middle; max-width: 100%; }
/* 胶囊模式（默认） */
.et-chip {
  cursor: pointer; font-size: 12px; color: var(--primary); background: var(--primary-light);
  border-radius: 8px; padding: 2px 8px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.et-chip:hover { opacity: .85; }
/* 纯文本模式：与主标题（视频文件名）同大小同颜色，hover 显示编辑笔 */
.et-chip.plain {
  background: transparent; color: var(--text); font-size: 14px; font-weight: 700;
  padding: 0 4px; border-radius: 4px; line-height: 1.4;
  max-width: 320px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.et-chip.plain:hover { background: var(--hover); opacity: 1; }
.et-pen { opacity: 0; margin-left: 3px; font-size: 11px; }
.et-chip.plain:hover .et-pen { opacity: 1; }
.et-empty { display: inline-flex; gap: 6px; }
.et-btn {
  font-size: 12px; padding: 2px 8px; border-radius: 8px; background: var(--primary-light);
  color: var(--primary); border: 1px dashed var(--primary); white-space: nowrap;
}
.et-btn:hover { opacity: .85; }
.et-editbox input { width: 220px; font-size: 13px; }
</style>
