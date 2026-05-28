<script setup lang="ts">
import { ref, nextTick, type PropType } from 'vue';
import type { ChatMsg, ChatMsgAttachment } from '../../composables/useConversations';
import PredictionMessage from './PredictionMessage.vue';
import BuildSteps from './BuildSteps.vue';

defineProps({
  messages: { type: Array as PropType<ChatMsg[]>, required: true },
  loading:  { type: Boolean, default: false },
});

const emit = defineEmits<{
  (e: 'preview', att: ChatMsgAttachment): void;
  (e: 'focus-node', id: string): void;
  (e: 'abort-prediction'): void;
}>();

const listRef = ref<HTMLElement | null>(null);

/** 暴露给父级:自动滚动到底。 */
const scrollToBottom = () => {
  nextTick(() => {
    if (listRef.value) listRef.value.scrollTop = listRef.value.scrollHeight;
  });
};

const previewable = (a: ChatMsgAttachment) => !a.error;

defineExpose({ scrollToBottom });
</script>

<template>
  <div class="ch-msgs" ref="listRef">
    <template v-for="(m, i) in messages" :key="i">
      <!-- 推演消息(整行宽,带步骤卡片) -->
      <div v-if="m.role === 'prediction' && m.prediction" class="msg msg-prediction">
        <div class="avatar avatar-pred">⚡</div>
        <div class="msg-body">
          <PredictionMessage
            :prediction="m.prediction"
            @focus-node="(id) => emit('focus-node', id)"
            @abort="emit('abort-prediction')"
          />
        </div>
      </div>
      <!-- 常规用户 / AI 消息 -->
      <div v-else :class="['msg', `msg-${m.role === 'u' ? 'user' : 'asst'}`]">
        <div v-if="m.role === 'a'" class="avatar">推</div>
        <div class="msg-body">
          <div v-if="m.atts && m.atts.length > 0" class="att-tags">
            <button
              v-for="(a, j) in m.atts"
              :key="j"
              type="button"
              class="att-sm"
              :class="{ 'att-sm-err': a.error, 'att-sm-clickable': previewable(a) }"
              :title="a.error || (previewable(a) ? '点击查看' : a.name)"
              :disabled="!previewable(a)"
              @click="previewable(a) && emit('preview', a)"
            >
              <span>{{ a.kind === 'image' ? '🖼' : a.kind === 'text' ? '📄' : '📎' }} {{ a.name }}</span>
              <svg v-if="previewable(a)" class="att-sm-eye" viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
            </button>
          </div>
          <BuildSteps
            v-if="m.role === 'a' && m.buildSteps && m.buildSteps.length"
            :steps="m.buildSteps"
            :done="!!m.buildDone"
          />
          <div v-if="m.text || (m.role === 'a' && !m.buildSteps?.length)" class="bubble" :class="{ streaming: m.role === 'a' && !m.text }">
            {{ m.text }}<span v-if="loading && m.role === 'a' && i === messages.length - 1" class="cursor" />
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.att-sm {
  /* override the global .att-sm so the chip behaves as a button */
  border: none;
  font-family: inherit;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.att-sm-clickable {
  cursor: pointer;
  transition: background .15s, color .15s;
}
.att-sm-clickable:hover {
  background: rgba(66, 184, 131, 0.18);
  color: #a7f3d0;
}
.att-sm:disabled { cursor: default; }
.att-sm-eye { opacity: 0.55; }
.att-sm-clickable:hover .att-sm-eye { opacity: 1; }

/* prediction 消息整行宽,头像用金色 */
.msg-prediction { align-self: stretch; max-width: 100%; }
.msg-prediction .msg-body { flex: 1; min-width: 0; max-width: 100%; }
.avatar-pred {
  background: linear-gradient(135deg, #fbbf24, #f59e0b) !important;
  color: #1a1a1a !important;
  font-size: 16px !important;
}
</style>
