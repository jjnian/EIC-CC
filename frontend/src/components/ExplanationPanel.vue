<script setup lang="ts">
// P1-7：浮动解释面板。按节点 id 关联到一次推演，请求 LLM 三段式解释（依据/假设/反例）。
// 缓存命中直接渲染；强制重新生成调 LLM。
import { ref, onMounted, onBeforeUnmount, computed } from 'vue';
import { explainStream } from '../api/explanations';
import type { SseHandle } from '../api/http';
import type { NodeExplanation } from '../types';
import { Button } from '@/components/ui/button';

const props = defineProps<{
  scenarioId: string;
  nodeId: string;
  nodeLabel: string;
  nodeType?: string;
  confidence?: number;
  // 用于面板堆叠时错开起始位置
  stackIndex?: number;
}>();

const emit = defineEmits<{ (e: 'close'): void }>();

const evidence = ref('');
const assumptions = ref('');
const counterexamples = ref('');
const status = ref<'idle' | 'loading' | 'done' | 'error'>('idle');
const errMsg = ref('');
const cached = ref(false);
const collapsed = ref(false);

let sseHandle: SseHandle | null = null;

const reset = () => {
  evidence.value = '';
  assumptions.value = '';
  counterexamples.value = '';
  errMsg.value = '';
  cached.value = false;
};

const start = (forceRegenerate = false) => {
  reset();
  status.value = 'loading';
  sseHandle = explainStream(props.scenarioId, {
    nodeId: props.nodeId,
    forceRegenerate,
  }, {
    onChunk: (field, text) => {
      if (field === 'evidence') evidence.value += text;
      else if (field === 'assumptions') assumptions.value += text;
      else if (field === 'counterexamples') counterexamples.value += text;
    },
    onComplete: (explanation: NodeExplanation, isCached: boolean) => {
      cached.value = isCached;
      // 一次性赋值确保最终内容与 chunk 累积一致（防止 chunk 顺序乱）
      evidence.value = explanation.evidence;
      assumptions.value = explanation.assumptions;
      counterexamples.value = explanation.counterexamples;
      status.value = 'done';
    },
    onError: (msg) => {
      errMsg.value = msg;
      status.value = 'error';
    },
  });
};

const abort = () => {
  if (sseHandle) {
    try { sseHandle.abort(); } catch { /* noop */ }
    sseHandle = null;
  }
};

const close = () => {
  abort();
  emit('close');
};

// 拖拽 —— 顶部 header 抓取后跟随鼠标
const x = ref(0);
const y = ref(0);
const dragging = ref(false);
let startX = 0, startY = 0, startLeft = 0, startTop = 0;

const onHeaderMouseDown = (e: MouseEvent) => {
  if ((e.target as HTMLElement).tagName === 'BUTTON') return;
  dragging.value = true;
  startX = e.clientX;
  startY = e.clientY;
  startLeft = x.value;
  startTop = y.value;
  document.addEventListener('mousemove', onMove);
  document.addEventListener('mouseup', onUp);
};
const onMove = (e: MouseEvent) => {
  if (!dragging.value) return;
  x.value = startLeft + (e.clientX - startX);
  y.value = startTop + (e.clientY - startY);
};
const onUp = () => {
  dragging.value = false;
  document.removeEventListener('mousemove', onMove);
  document.removeEventListener('mouseup', onUp);
};

const initialOffset = computed(() => {
  const idx = props.stackIndex ?? 0;
  return { right: 24 + idx * 24, bottom: 24 + idx * 24 };
});

const confidencePct = computed(() =>
  props.confidence == null ? '' : Math.round(props.confidence * 100) + '%'
);

onMounted(() => start(false));
onBeforeUnmount(() => abort());
</script>

<template>
  <div
    class="ep-panel"
    :class="{ 'ep-collapsed': collapsed }"
    :style="{
      transform: `translate(${x}px, ${y}px)`,
      right: initialOffset.right + 'px',
      bottom: initialOffset.bottom + 'px',
      zIndex: 1000 + (stackIndex ?? 0),
    }"
  >
    <div class="ep-head" @mousedown="onHeaderMouseDown">
      <span class="ep-icon">🔍</span>
      <div class="ep-title">
        <span class="ep-node-label">{{ nodeLabel }}</span>
        <span class="ep-meta">
          <span v-if="nodeType" class="ep-type">{{ nodeType }}</span>
          <span v-if="confidencePct" class="ep-conf">{{ confidencePct }}</span>
          <span v-if="cached && status === 'done'" class="ep-cached" title="来自缓存">缓存</span>
        </span>
      </div>
      <Button variant="ghost" size="icon-sm" @click="collapsed = !collapsed" :title="collapsed ? '展开' : '折叠'" type="button">
        {{ collapsed ? '▽' : '△' }}
      </Button>
      <Button variant="ghost" size="icon-sm" @click="close" title="关闭" type="button">×</Button>
    </div>
    <div v-if="!collapsed" class="ep-body">
      <div v-if="status === 'error'" class="ep-error">
        生成失败：{{ errMsg }}
        <Button variant="outline" size="sm" @click="start(true)" type="button">点击重试</Button>
      </div>
      <template v-else>
        <div class="ep-section">
          <div class="ep-section-title">📌 依据</div>
          <div class="ep-section-body" :class="{ 'ep-body-empty': !evidence }">
            {{ evidence || (status === 'loading' ? '生成中…' : '—') }}
          </div>
        </div>
        <div class="ep-section">
          <div class="ep-section-title">💭 假设</div>
          <div class="ep-section-body" :class="{ 'ep-body-empty': !assumptions }">
            {{ assumptions || (status === 'loading' ? '生成中…' : '—') }}
          </div>
        </div>
        <div class="ep-section">
          <div class="ep-section-title">⚠ 反例</div>
          <div class="ep-section-body" :class="{ 'ep-body-empty': !counterexamples }">
            {{ counterexamples || (status === 'loading' ? '生成中…' : '—') }}
          </div>
        </div>
        <div class="ep-foot">
          <Button
            variant="secondary"
            size="sm"
            :disabled="status === 'loading'"
            @click="start(true)"
            type="button"
            title="忽略缓存重新生成"
          >🔄 重新生成</Button>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.ep-panel {
  position: fixed;
  width: 360px;
  max-height: 70vh;
  display: flex;
  flex-direction: column;
  background: rgba(15, 23, 42, 0.96);
  border: 1px solid rgba(187, 119, 255, 0.32);
  border-radius: 12px;
  box-shadow: 0 12px 36px rgba(0, 0, 0, 0.5), 0 0 0 1px rgba(187, 119, 255, 0.08);
  font-size: 12.5px;
  color: var(--text-main);
  overflow: hidden;
}
.ep-collapsed { max-height: none; }
.ep-head {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 12px;
  background: rgba(187, 119, 255, 0.08);
  border-bottom: 1px solid rgba(187, 119, 255, 0.18);
  cursor: move;
  user-select: none;
}
.ep-icon { font-size: 14px; flex-shrink: 0; }
.ep-title { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.ep-node-label {
  font-size: 13px; font-weight: 600; color: var(--text-main);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.ep-meta { display: flex; gap: 6px; align-items: center; font-size: 10px; }
.ep-type {
  background: rgba(255, 255, 255, 0.08); color: var(--text-dim);
  padding: 1px 6px; border-radius: 4px; text-transform: lowercase;
}
.ep-conf {
  color: #fbbf24; font-family: 'JetBrains Mono', monospace;
}
.ep-cached {
  background: rgba(47, 134, 214, 0.18); color: #5aa6ee;
  padding: 1px 6px; border-radius: 4px;
}
.ep-icon-btn {
  background: rgba(255, 255, 255, 0.06);
  border: none;
  color: var(--text-dim);
  width: 22px; height: 22px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 11px;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
}
.ep-icon-btn:hover { background: rgba(255, 255, 255, 0.14); color: var(--text-main); }
.ep-body { padding: 12px; overflow-y: auto; display: flex; flex-direction: column; gap: 10px; }
.ep-section { display: flex; flex-direction: column; gap: 4px; }
.ep-section-title {
  font-size: 11px; font-weight: 600; color: #d4a8ff;
  letter-spacing: 0.5px;
}
.ep-section-body {
  font-size: 12px; line-height: 1.55; color: var(--text-main);
  background: rgba(0, 0, 0, 0.25);
  border: 1px solid rgba(255, 255, 255, 0.05);
  border-radius: 8px;
  padding: 8px 10px;
  white-space: pre-wrap;
  word-break: break-word;
  min-height: 20px;
}
.ep-body-empty { color: rgba(255, 255, 255, 0.35); font-style: italic; }
.ep-error {
  font-size: 12px; color: #ff8a8a;
  background: rgba(255, 99, 99, 0.08);
  border: 1px solid rgba(255, 99, 99, 0.25);
  border-radius: 8px;
  padding: 10px 12px;
}
.ep-retry {
  display: block; margin-top: 6px;
  background: rgba(255, 99, 99, 0.15); color: #ffb3b3;
  border: 1px solid rgba(255, 99, 99, 0.3);
  padding: 4px 10px; border-radius: 6px; font-size: 11px;
  cursor: pointer; font-family: inherit;
}
.ep-foot { display: flex; justify-content: flex-end; }
.ep-btn {
  background: rgba(187, 119, 255, 0.12);
  border: 1px solid rgba(187, 119, 255, 0.28);
  color: #d4a8ff;
  padding: 5px 12px;
  border-radius: 6px;
  font-size: 11px;
  cursor: pointer;
  font-family: inherit;
}
.ep-btn:hover:not(:disabled) { background: rgba(187, 119, 255, 0.2); }
.ep-btn:disabled { opacity: 0.4; cursor: not-allowed; }
</style>
