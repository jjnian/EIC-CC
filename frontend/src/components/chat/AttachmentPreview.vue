<script setup lang="ts">
import { computed, ref, watch, type PropType } from 'vue';
import type { ChatMsgAttachment } from '../../composables/useConversations';
import { toast } from '../../composables/useToast';
import { renderMarkdown } from '../../utils/markdown';
import { Button } from '@/components/ui/button';

const props = defineProps({
  attachment: { type: Object as PropType<ChatMsgAttachment | null>, default: null },
});

const emit = defineEmits<{ (e: 'close'): void }>();

/** 是否为 Markdown 文本（按文件名后缀或 MIME 判断）。 */
const isMarkdown = computed(() => {
  const v = props.attachment;
  if (!v || v.kind !== 'text' || !v.content) return false;
  const name = (v.name || '').toLowerCase();
  const type = (v.type || '').toLowerCase();
  return name.endsWith('.md') || name.endsWith('.markdown') || type.includes('markdown');
});

/** Markdown 默认以「可读」渲染视图展示，可切换为源码。 */
const showSource = ref(false);
// 切换附件时重置回渲染视图
watch(() => props.attachment, () => { showSource.value = false; });

const renderedMd = computed(() =>
  isMarkdown.value && props.attachment?.content ? renderMarkdown(props.attachment.content) : '');

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
        <Button variant="ghost" size="icon-sm" class="ap-close" @click="emit('close')" title="关闭">×</Button>
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
          <div v-if="isMarkdown && !showSource" class="ap-md" v-html="renderedMd"></div>
          <pre v-else-if="a.content" class="ap-text">{{ a.content }}</pre>
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
        <Button v-if="isMarkdown" variant="outline" size="sm" class="ap-btn" @click="showSource = !showSource">
          {{ showSource ? '阅读视图' : '查看源码' }}
        </Button>
        <span class="ap-foot-spacer"></span>
        <Button v-if="a.content && a.kind === 'text'" variant="outline" size="sm" class="ap-btn" @click="copyText">复制文本</Button>
        <Button v-if="a.content" size="sm" class="ap-btn ap-btn-hi" @click="download">下载</Button>
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
/* 渲染后的 Markdown「阅读视图」 */
.ap-md {
  align-self: flex-start;
  width: 100%; max-height: 64vh; overflow: auto;
  background: rgba(0,0,0,0.2); border: 1px solid rgba(255,255,255,0.06);
  border-radius: 8px; padding: 18px 22px;
  color: #d6e2f0; font-size: 13.5px; line-height: 1.7; overflow-wrap: anywhere;
}
.ap-md :deep(h1) { font-size: 19px; margin: 4px 0 10px; color: #fff; }
.ap-md :deep(h2) { font-size: 16px; margin: 16px 0 8px; color: #fff; }
.ap-md :deep(h3) { font-size: 14px; margin: 14px 0 6px; color: #fff; }
.ap-md :deep(p) { margin: 8px 0; }
.ap-md :deep(ul), .ap-md :deep(ol) { padding-left: 22px; margin: 8px 0; }
.ap-md :deep(li) { margin: 3px 0; }
.ap-md :deep(code) { background: rgba(255,255,255,0.08); padding: 1px 6px; border-radius: 4px;
  font-family: 'JetBrains Mono', monospace; font-size: 12px; }
.ap-md :deep(pre) { background: rgba(0,0,0,0.35); border: 1px solid rgba(255,255,255,0.08);
  border-radius: 8px; padding: 12px 14px; overflow-x: auto; margin: 10px 0; }
.ap-md :deep(pre code) { background: none; padding: 0; }
.ap-md :deep(blockquote) { border-left: 3px solid rgba(47,134,214,0.5); margin: 8px 0;
  padding: 2px 12px; color: rgba(255,255,255,0.6); }
.ap-md :deep(a) { color: #5aa6ee; }
.ap-md :deep(hr) { border: none; border-top: 1px solid rgba(255,255,255,0.12); margin: 14px 0; }

.ap-msg {
  color: rgba(255,255,255,0.55); font-size: 13px; text-align: center;
  padding: 32px 16px;
}
.ap-msg.ap-error { color: #ff8a8a; }
.ap-hint { color: rgba(255,255,255,0.35); font-size: 12px; }

.ap-foot {
  display: flex; align-items: center; gap: 8px;
  padding: 12px 18px; border-top: 1px solid rgba(255,255,255,0.08);
}
.ap-foot-spacer { flex: 1; }
.ap-btn {
  background: rgba(255,255,255,0.08); border: 1px solid rgba(255,255,255,0.1);
  color: var(--text-main); padding: 7px 14px; border-radius: 8px;
  font-size: 13px; cursor: pointer; transition: all .15s; font-family: inherit;
}
.ap-btn:hover { background: rgba(255,255,255,0.16); }
.ap-btn-hi {
  background: var(--accent); color: #fff; border-color: transparent; font-weight: 600;
}
.ap-btn-hi:hover { opacity: 0.9; background: var(--accent); }
</style>
