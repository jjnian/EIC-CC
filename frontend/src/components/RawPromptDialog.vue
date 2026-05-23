<script setup lang="ts">
// P1-8：原始 prompt 查看弹窗。展示推演时发给 LLM 的完整 system + user 文本。
import { ref, watch } from 'vue';
import { getRawPrompt } from '../api/explanations';
import { toast } from '../composables/useToast';

const props = defineProps<{
  open: boolean;
  scenarioId: string;
}>();

const emit = defineEmits<{ (e: 'close'): void }>();

const loading = ref(false);
const rawText = ref('');
const errMsg = ref('');

const load = async () => {
  if (!props.scenarioId) return;
  loading.value = true;
  rawText.value = '';
  errMsg.value = '';
  try {
    const r = await getRawPrompt(props.scenarioId);
    if (!r.rawPrompt) {
      errMsg.value = '该分支创建于早期版本，未保存原始 prompt。';
    } else {
      rawText.value = r.rawPrompt;
    }
  } catch (e) {
    errMsg.value = '无法加载原始 prompt：' + ((e as Error).message || '未知错误');
  } finally {
    loading.value = false;
  }
};

const copyAll = async () => {
  if (!rawText.value) return;
  try {
    await navigator.clipboard.writeText(rawText.value);
    toast.info('已复制到剪贴板');
  } catch {
    toast.error('复制失败，请手动选择');
  }
};

const onBackdrop = (e: MouseEvent) => {
  if ((e.target as HTMLElement).classList.contains('rp-backdrop')) emit('close');
};

watch(() => props.open, (v) => { if (v) load(); }, { immediate: true });
</script>

<template>
  <div v-if="open" class="rp-backdrop" @mousedown="onBackdrop">
    <div class="rp-dialog">
      <div class="rp-head">
        <span class="rp-title"><span class="rp-icon">📜</span> 原始 prompt</span>
        <button class="rp-close" @click="emit('close')" type="button">×</button>
      </div>
      <div class="rp-body">
        <div v-if="loading" class="rp-loading">加载中…</div>
        <div v-else-if="errMsg" class="rp-err">{{ errMsg }}</div>
        <pre v-else class="rp-pre">{{ rawText }}</pre>
      </div>
      <div class="rp-foot">
        <button class="rp-btn rp-btn-cancel" @click="emit('close')" type="button">关闭</button>
        <button class="rp-btn rp-btn-go" @click="copyAll" :disabled="!rawText" type="button">复制全文</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.rp-backdrop {
  position: fixed; inset: 0;
  background: rgba(0, 0, 0, 0.5);
  backdrop-filter: blur(4px);
  display: flex; align-items: center; justify-content: center;
  z-index: 2100;
}
.rp-dialog {
  width: 720px; max-width: 92vw;
  max-height: 86vh;
  background: rgba(15, 23, 42, 0.96);
  border: 1px solid rgba(99, 179, 237, 0.3);
  border-radius: 14px;
  display: flex; flex-direction: column;
  overflow: hidden;
  box-shadow: 0 24px 64px rgba(0, 0, 0, 0.5);
}
.rp-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 14px 18px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}
.rp-title { display: flex; align-items: center; gap: 8px; font-size: 14px; font-weight: 600; color: var(--text-main); }
.rp-icon { font-size: 16px; }
.rp-close {
  background: rgba(255,255,255,0.06); border: none; color: var(--text-dim);
  width: 26px; height: 26px; border-radius: 50%; cursor: pointer; font-size: 16px;
}
.rp-close:hover { background: rgba(255, 99, 99, 0.2); color: #ff8a8a; }
.rp-body {
  flex: 1; overflow: auto; padding: 14px 18px;
}
.rp-loading, .rp-err {
  font-size: 12px; color: var(--text-dim); padding: 24px 12px; text-align: center;
}
.rp-err { color: #ffb86c; }
.rp-pre {
  font-family: 'JetBrains Mono', 'Consolas', monospace;
  font-size: 12px;
  line-height: 1.55;
  color: var(--text-main);
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0;
  background: rgba(10, 16, 27, 0.6);
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 8px;
  padding: 12px 14px;
}
.rp-foot {
  display: flex; gap: 10px; justify-content: flex-end;
  padding: 12px 18px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}
.rp-btn {
  padding: 8px 16px; border-radius: 8px; border: none;
  font-size: 12px; font-weight: 600; cursor: pointer; font-family: inherit;
}
.rp-btn-cancel { background: rgba(255,255,255,0.06); color: var(--text-dim); }
.rp-btn-cancel:hover { background: rgba(255,255,255,0.12); color: var(--text-main); }
.rp-btn-go { background: #63b3ed; color: #1a1a1a; }
.rp-btn-go:hover:not(:disabled) { background: #93c5fd; }
.rp-btn-go:disabled { opacity: 0.4; cursor: not-allowed; }
</style>
