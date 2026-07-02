<script setup lang="ts">
import { ref, nextTick, type PropType } from 'vue';
import type { ChatMsg, ChatMsgAttachment } from '../../composables/useConversations';
import BuildSteps from './BuildSteps.vue';
import { Button } from '@/components/ui/button';

defineProps({
  messages: { type: Array as PropType<ChatMsg[]>, required: true },
  loading:  { type: Boolean, default: false },
});

const emit = defineEmits<{
  (e: 'preview', att: ChatMsgAttachment): void;
  (e: 'focus-node', id: string): void;
  (e: 'pick-option', messageIndex: number, questionIndex: number, option: { label: string; value?: string }): void;
  (e: 'submit-answers', messageIndex: number): void;
  (e: 'custom-answer', messageIndex: number): void;
  (e: 'view-graph', modelId: string): void;
}>();

/** 这组问题是否每条都已至少选一项(决定「提交回答」是否可点)。 */
const allAnswered = (m: ChatMsg) => (m.questions || []).every(q => (q.selected || []).length > 0);
/** 多问题或含多选时才需要显式「提交回答」;单题单选点选即发送。 */
const showSubmit = (m: ChatMsg) => (m.questions || []).length > 1 || (m.questions || []).some(q => q.multiSelect);
/** 整组已提交且没有任何勾选 → 说明是用自定义文本回答的。 */
const customUsed = (m: ChatMsg) => !!m.questionsDone && (m.questions || []).every(q => (q.selected || []).length === 0);

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
      <!-- 常规用户 / AI 消息 -->
      <div :class="['msg', `msg-${m.role === 'u' ? 'user' : 'asst'}`]">
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
          <Button v-if="m.role === 'a' && m.graphModelId" variant="outline" size="sm" type="button" @click="emit('view-graph', m.graphModelId!)">
            <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83"/></svg>
            查看图谱
          </Button>
          <!-- LLM 返回的澄清问题组(支持一次多个、单题多选) -->
          <div v-if="m.role === 'a' && m.questions && m.questions.length" class="question-card" :class="{ answered: !!m.questionsDone }">
            <div class="question-head">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="#fbbf24" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <circle cx="12" cy="12" r="10"/>
                <path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"/>
                <line x1="12" y1="17" x2="12.01" y2="17"/>
              </svg>
              <span class="question-tag">需要你的确认{{ m.questions.length > 1 ? `（${m.questions.length} 个问题）` : '' }}</span>
            </div>

            <div v-for="(q, qi) in m.questions" :key="qi" class="question-block">
              <div class="question-text">
                <span v-if="q.header" class="question-topic">{{ q.header }}</span>
                {{ q.text }}
                <span v-if="q.multiSelect" class="question-multi-tag">可多选</span>
              </div>
              <div class="question-options">
                <button v-for="(opt, oi) in q.options" :key="oi"
                        type="button"
                        class="question-option"
                        :class="{ selected: (q.selected || []).includes(opt.label) }"
                        :disabled="!!m.questionsDone"
                        @click="emit('pick-option', i, qi, opt)">
                  <span class="question-option-num">{{ oi + 1 }}</span>
                  <span class="question-option-label">{{ opt.label }}</span>
                </button>
                <!-- 手动填写:每个问题最后一行,点了去下方输入框自己写答案 -->
                <button type="button"
                        class="question-option question-option-custom"
                        :class="{ selected: customUsed(m) }"
                        :disabled="!!m.questionsDone"
                        :title="m.questionsDone ? '已回答' : '在下方输入框里写自己的答案'"
                        @click="emit('custom-answer', i)">
                  <span class="question-option-num">{{ q.options.length + 1 }}</span>
                  <span class="question-option-label">
                    <svg viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"/><path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"/></svg>
                    手动填写其它答案…
                  </span>
                </button>
              </div>
            </div>

            <div v-if="showSubmit(m)" class="question-actions">
              <Button type="button"
                      size="sm"
                      class="question-submit"
                      :disabled="!!m.questionsDone || !allAnswered(m)"
                      @click="emit('submit-answers', i)">
                {{ m.questionsDone ? '已提交' : '提交回答' }}
              </Button>
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
  background: rgba(47, 134, 214, 0.18);
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
  flex-direction: column;
  gap: 6px;
}
.question-option {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  text-align: left;
  background: rgba(251, 191, 36, 0.1);
  border: 1px solid rgba(251, 191, 36, 0.3);
  color: #fde68a;
  padding: 8px 12px;
  border-radius: 8px;
  font-size: 12px;
  cursor: pointer;
  font-family: inherit;
  transition: all 0.12s;
}
.question-option:hover:not(:disabled) {
  background: rgba(251, 191, 36, 0.22);
  border-color: rgba(251, 191, 36, 0.55);
  color: #fff;
  transform: translateX(2px);
}
/* 行首的序号徽标:1 2 3 … */
.question-option-num {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border-radius: 5px;
  background: rgba(251, 191, 36, 0.22);
  color: #fde68a;
  font-size: 11px;
  font-weight: 700;
  line-height: 1;
}
.question-option-label {
  flex: 1 1 auto;
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.question-option.selected .question-option-num {
  background: rgba(47, 134, 214, 0.28);
  color: #5aa6ee;
}
.question-option-custom .question-option-num {
  background: rgba(125, 211, 252, 0.2);
  color: #bae6fd;
}
.question-option:disabled {
  cursor: default;
  opacity: 0.5;
}
.question-option.selected {
  background: rgba(47, 134, 214, 0.18);
  border-color: rgba(47, 134, 214, 0.45);
  color: #5aa6ee;
  opacity: 1;
}
.question-option-custom {
  background: rgba(125, 211, 252, 0.08);
  border-color: rgba(125, 211, 252, 0.28);
  color: #bae6fd;
  border-style: dashed;
}
.question-option-custom:hover:not(:disabled) {
  background: rgba(125, 211, 252, 0.18);
  border-color: rgba(125, 211, 252, 0.5);
  color: #fff;
}
.question-option-custom.selected {
  background: rgba(125, 211, 252, 0.2);
  border-color: rgba(125, 211, 252, 0.55);
  border-style: solid;
  color: #e0f2fe;
}
/* 多问题:每条问题成块,块间细分隔线 */
.question-block {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.question-block + .question-block {
  padding-top: 8px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}
.question-topic {
  display: inline-block;
  font-size: 10.5px;
  font-weight: 600;
  color: #fbbf24;
  background: rgba(251, 191, 36, 0.12);
  border-radius: 5px;
  padding: 1px 7px;
  margin-right: 6px;
  vertical-align: middle;
}
.question-card.answered .question-topic { color: rgba(255, 255, 255, 0.45); background: rgba(255, 255, 255, 0.06); }
.question-multi-tag {
  font-size: 10px;
  color: #5aa6ee;
  background: rgba(47, 134, 214, 0.14);
  border-radius: 100px;
  padding: 1px 7px;
  margin-left: 6px;
  vertical-align: middle;
}
.question-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 2px;
}
.question-submit {
  background: var(--accent);
  color: #fff;
  border: none;
  padding: 6px 16px;
  border-radius: 100px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  font-family: inherit;
  transition: all 0.12s;
}
.question-submit:hover:not(:disabled) { background: #5aa6ee; }
.question-submit:disabled { opacity: 0.45; cursor: default; }

.view-graph-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin-top: 8px;
  padding: 5px 12px;
  background: rgba(47, 134, 214, 0.12);
  border: 1px solid rgba(47, 134, 214, 0.35);
  border-radius: 6px;
  color: #5aa6ee;
  font-size: 12px;
  cursor: pointer;
  transition: background .15s, border-color .15s;
}
.view-graph-btn:hover {
  background: rgba(47, 134, 214, 0.22);
  border-color: rgba(47, 134, 214, 0.6);
}

.ch-msgs {
  align-items: center;
  gap: 18px;
}
.msg {
  width: min(100%, 860px);
  max-width: 100%;
  align-self: center;
  gap: 0;
}
.msg-user,
.msg-asst {
  align-self: center;
  flex-direction: row;
}
.avatar {
  display: none !important;
}
.msg-body {
  width: 100%;
  max-width: 100%;
}
.bubble {
  background: transparent;
  border: none;
  box-shadow: none;
  padding: 4px 0;
  border-radius: 0;
}
.msg-user .bubble {
  display: inline-block;
  max-width: min(100%, 620px);
  margin-left: auto;
  background: linear-gradient(135deg, var(--accent-soft), var(--accent));
  border: 1px solid rgba(47, 134, 214, 0.45);
  border-radius: 14px;
  padding: 10px 14px;
  color: #fff;
  font-weight: 600;
  box-shadow: 0 8px 22px rgba(47, 134, 214, 0.24), inset 0 1px 0 rgba(255, 255, 255, 0.28);
}
.msg-asst .bubble {
  max-width: min(100%, 860px);
  padding: 8px 0;
  background: transparent !important;
  border: none !important;
  border-radius: 0;
  color: #f4f7fb;
  font-size: 14px;
  line-height: 1.8;
  box-shadow: none !important;
}
.msg-asst .bubble :deep(p) {
  margin: 0 0 10px;
}
.msg-asst .bubble :deep(ul),
.msg-asst .bubble :deep(ol) {
  margin: 8px 0 8px 18px;
  padding: 0;
}
.msg-asst .bubble :deep(li) {
  margin: 4px 0;
}
.msg-asst .msg-body {
  align-items: flex-start;
}
.msg-user .msg-body {
  align-items: flex-end;
}
.att-tags {
  justify-content: flex-end;
}
</style>
