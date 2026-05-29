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
  (e: 'select-option', messageIndex: number, option: { label: string; value?: string }): void;
  (e: 'view-graph', modelId: string): void;
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
          <!-- 分析完成后"查看图谱"快捷入口 -->
          <button v-if="m.role === 'a' && m.graphModelId" type="button" class="view-graph-btn" @click="emit('view-graph', m.graphModelId!)">
            <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83"/></svg>
            查看图谱
          </button>
          <!-- LLM 返回的澄清问题 + 可点击选项 -->
          <div v-if="m.role === 'a' && m.question" class="question-card" :class="{ answered: !!m.question.answered }">
            <div class="question-head">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="#fbbf24" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <circle cx="12" cy="12" r="10"/>
                <path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"/>
                <line x1="12" y1="17" x2="12.01" y2="17"/>
              </svg>
              <span class="question-tag">需要你的确认</span>
            </div>
            <div class="question-text">{{ m.question.text }}</div>
            <div class="question-options">
              <button v-for="(opt, oi) in m.question.options" :key="oi"
                      type="button"
                      class="question-option"
                      :class="{ selected: m.question.answered === opt.label }"
                      :disabled="!!m.question.answered"
                      @click="emit('select-option', i, opt)">
                {{ opt.label }}
              </button>
            </div>
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

/* 澄清问题卡片 */
.question-card {
  margin-top: 8px;
  background: rgba(251, 191, 36, 0.06);
  border: 1px solid rgba(251, 191, 36, 0.25);
  border-radius: 10px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.question-card.answered {
  background: rgba(255, 255, 255, 0.03);
  border-color: rgba(255, 255, 255, 0.08);
}
.question-head {
  display: flex;
  align-items: center;
  gap: 6px;
}
.question-tag {
  font-size: 11px;
  font-weight: 600;
  color: #fbbf24;
  letter-spacing: 0.4px;
}
.question-card.answered .question-tag { color: rgba(255, 255, 255, 0.45); }
.question-text {
  color: var(--text-main);
  font-size: 13px;
  line-height: 1.5;
}
.question-options {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.question-option {
  background: rgba(251, 191, 36, 0.1);
  border: 1px solid rgba(251, 191, 36, 0.3);
  color: #fde68a;
  padding: 5px 12px;
  border-radius: 100px;
  font-size: 12px;
  cursor: pointer;
  font-family: inherit;
  transition: all 0.12s;
}
.question-option:hover:not(:disabled) {
  background: rgba(251, 191, 36, 0.22);
  border-color: rgba(251, 191, 36, 0.55);
  color: #fff;
  transform: translateY(-1px);
}
.question-option:disabled {
  cursor: default;
  opacity: 0.5;
}
.question-option.selected {
  background: rgba(66, 184, 131, 0.18);
  border-color: rgba(66, 184, 131, 0.45);
  color: #6dd4a7;
  opacity: 1;
}

.view-graph-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin-top: 8px;
  padding: 5px 12px;
  background: rgba(66, 184, 131, 0.12);
  border: 1px solid rgba(66, 184, 131, 0.35);
  border-radius: 6px;
  color: #6dd4a7;
  font-size: 12px;
  cursor: pointer;
  transition: background .15s, border-color .15s;
}
.view-graph-btn:hover {
  background: rgba(66, 184, 131, 0.22);
  border-color: rgba(66, 184, 131, 0.6);
}

/* prediction 消息整行宽,头像用金色 */
.msg-prediction { align-self: stretch; max-width: 100%; }
.msg-prediction .msg-body { flex: 1; min-width: 0; max-width: 100%; }
.avatar-pred {
  background: linear-gradient(135deg, #fbbf24, #f59e0b) !important;
  color: #1a1a1a !important;
  font-size: 16px !important;
}
</style>
