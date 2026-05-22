<script setup lang="ts">
import { computed, type PropType } from 'vue';
import type { ChatMsgAttachment } from '../../composables/useConversations';
import { toast } from '../../composables/useToast';

const props = defineProps({
  attachment: { type: Object as PropType<ChatMsgAttachment | null>, default: null },
});

const emit = defineEmits<{ (e: 'close'): void }>();

const formatSize = (bytes?: number) => {
  if (!bytes && bytes !== 0) return '';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
};

const a = computed(() => props.attachment);

const onBackdrop = (e: MouseEvent) => {
  if ((e.target as HTMLElement).classList.contains('ap-backdrop')) emit('close');
};

const download = () => {
  if (!a.value || !a.value.content) return;
  const link = document.createElement('a');
  if (a.value.kind === 'image') {
    link.href = a.value.content;
  } else {
    const blob = new Blob([a.value.content], { type: 'text/plain;charset=utf-8' });
    link.href = URL.createObjectURL(blob);
  }
  link.download = a.value.name || 'download';
  link.click();
  if (a.value.kind !== 'image') URL.revokeObjectURL(link.href);
};

const copyText = async () => {
  if (!a.value?.content) return;
  try {
    await navigator.clipboard.writeText(a.value.content);
    toast.success('已复制到剪贴板');
  } catch {
    toast.warn('剪贴板不可用');
  }
};
</script>

<template>
  <div v-if="a" class="ap-backdrop" @mousedown="onBackdrop">
    <div class="ap-dialog">
      <div class="ap-head">
        <div class="ap-title">
          <span class="ap-kind">{{ a.kind === 'image' ? '🖼' : a.kind === 'text' ? '📄' : '📎' }}</span>
          <span class="ap-name" :title="a.name">{{ a.name }}</span>
          <span v-if="a.size" class="ap-size">{{ formatSize(a.size) }}</span>
          <span v-if="a.truncated" class="ap-tag">原文已截断</span>
          <span v-if="a.storedTruncated" class="ap-tag ap-tag-warn">未持久化</span>
        </div>
        <button class="ap-close" @click="emit('close')" title="关闭">×</button>
      </div>

      <div class="ap-body">
        <template v-if="a.error">
          <div class="ap-msg ap-error">读取失败:{{ a.error }}</div>
        </template>
        <template v-else-if="a.kind === 'image'">
          <div v-if="a.content" class="ap-img-wrap">
            <img :src="a.content" :alt="a.name" />
          </div>
          <div v-else class="ap-msg">
            图片体积过大,未与对话一起保存。<br/>
            <span class="ap-hint">如需查看,请重新上传原文件。</span>
          </div>
        </template>
        <template v-else-if="a.kind === 'text'">
          <pre v-if="a.content" class="ap-text">{{ a.content }}</pre>
          <div v-else class="ap-msg">
            文本内容未保存。<br/>
            <span class="ap-hint">如需查看,请重新上传原文件。</span>
          </div>
        </template>
        <template v-else>
          <div class="ap-msg">
            二进制文件不显示内容,仅记录了文件元信息。
          </div>
        </template>
      </div>

      <div class="ap-foot">
        <button v-if="a.content && a.kind === 'text'" class="ap-btn" @click="copyText">复制文本</button>
        <button v-if="a.content" class="ap-btn ap-btn-hi" @click="download">下载</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ap-backdrop {
  position: fixed; inset: 0;
  background: rgba(0,0,0,0.55); backdrop-filter: blur(4px);
  display: flex; align-items: center; justify-content: center;
  z-index: 2100; animation: apFade .18s ease-out;
}
@keyframes apFade { from { opacity: 0; } to { opacity: 1; } }

.ap-dialog {
  width: 720px; max-width: 92vw; max-height: 86vh;
  background: rgba(15, 23, 42, 0.97);
  border: 1px solid rgba(255,255,255,0.12);
  border-radius: 16px; display: flex; flex-direction: column;
  box-shadow: 0 24px 64px rgba(0,0,0,0.5); overflow: hidden;
}

.ap-head {
  display: flex; align-items: center; gap: 12px;
  padding: 14px 18px; border-bottom: 1px solid rgba(255,255,255,0.08);
}
.ap-title { display: flex; align-items: center; gap: 8px; flex: 1; min-width: 0; }
.ap-kind { font-size: 16px; }
.ap-name { color: #fff; font-weight: 500; font-size: 14px; max-width: 480px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ap-size { font-size: 12px; color: rgba(255,255,255,0.4); font-family: 'JetBrains Mono', monospace; }
.ap-tag {
  font-size: 11px; padding: 2px 6px; border-radius: 4px;
  background: rgba(99, 179, 237, 0.15); color: #63b3ed;
  border: 1px solid rgba(99, 179, 237, 0.3);
}
.ap-tag-warn {
  background: rgba(251, 191, 36, 0.15); color: #fbbf24;
  border-color: rgba(251, 191, 36, 0.3);
}
.ap-close {
  background: none; border: none; color: rgba(255,255,255,0.5);
  font-size: 24px; line-height: 1; cursor: pointer; padding: 0;
  width: 28px; height: 28px; display: flex; align-items: center; justify-content: center;
  border-radius: 6px;
}
.ap-close:hover { background: rgba(255,255,255,0.08); color: #fff; }

.ap-body {
  flex: 1; overflow: auto; padding: 18px;
  display: flex; align-items: center; justify-content: center;
}
.ap-img-wrap {
  width: 100%; max-height: 60vh; display: flex; align-items: center; justify-content: center;
}
.ap-img-wrap img { max-width: 100%; max-height: 60vh; object-fit: contain;
  border-radius: 8px; background: rgba(0,0,0,0.3); }
.ap-text {
  width: 100%; max-height: 60vh; overflow: auto;
  background: rgba(0,0,0,0.3); border: 1px solid rgba(255,255,255,0.06);
  border-radius: 8px; padding: 14px 16px; margin: 0;
  font-family: 'JetBrains Mono', ui-monospace, monospace; font-size: 12.5px;
  color: #d6e2f0; line-height: 1.55;
  white-space: pre-wrap; word-break: break-word;
}
.ap-msg {
  color: rgba(255,255,255,0.55); font-size: 13px; text-align: center;
  padding: 32px 16px;
}
.ap-msg.ap-error { color: #ff8a8a; }
.ap-hint { color: rgba(255,255,255,0.35); font-size: 12px; }

.ap-foot {
  display: flex; justify-content: flex-end; gap: 8px;
  padding: 12px 18px; border-top: 1px solid rgba(255,255,255,0.08);
}
.ap-btn {
  background: rgba(255,255,255,0.08); border: 1px solid rgba(255,255,255,0.1);
  color: var(--text-main); padding: 7px 14px; border-radius: 8px;
  font-size: 13px; cursor: pointer; transition: all .15s; font-family: inherit;
}
.ap-btn:hover { background: rgba(255,255,255,0.16); }
.ap-btn-hi {
  background: var(--accent); color: #002418; border-color: transparent; font-weight: 600;
}
.ap-btn-hi:hover { opacity: 0.9; background: var(--accent); }
</style>
