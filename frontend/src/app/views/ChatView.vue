<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue';
import { Send, Bot, User, LoaderCircle, Plus, MessagesSquare } from 'lucide-vue-next';
import { chatStream, type ChatQuestion } from '../../api/chat';
import type { SseHandle } from '../../api/http';
import { useWorkspaceStore } from '../stores/workspace';
import { useToastStore } from '../stores/toast';
import { renderMarkdown } from '../lib/markdown';
import UiEmpty from '../components/UiEmpty.vue';

interface Msg {
  role: 'user' | 'assistant';
  content: string;
  steps?: string[];
  questions?: ChatQuestion[];
  pending?: boolean;
}

const ws = useWorkspaceStore();
const toast = useToastStore();

const messages = ref<Msg[]>([]);
const input = ref('');
const sending = ref(false);
const scrollBox = ref<HTMLDivElement | null>(null);
let handle: SseHandle | null = null;

const storageKey = () => `tuiyan.chat.${ws.currentId || 'default'}`;

onMounted(() => {
  try {
    const raw = localStorage.getItem(storageKey());
    if (raw) messages.value = JSON.parse(raw);
  } catch { /* noop */ }
});

watch(messages, () => {
  try { localStorage.setItem(storageKey(), JSON.stringify(messages.value.slice(-50))); } catch { /* noop */ }
  nextTick(() => {
    if (scrollBox.value) scrollBox.value.scrollTop = scrollBox.value.scrollHeight;
  });
}, { deep: true });

function newChat() {
  handle?.abort();
  messages.value = [];
  sending.value = false;
}

function send(text?: string) {
  const content = (text ?? input.value).trim();
  if (!content || sending.value) return;
  input.value = '';
  messages.value.push({ role: 'user', content });
  const assistant: Msg = { role: 'assistant', content: '', steps: [], pending: true };
  messages.value.push(assistant);
  sending.value = true;

  handle = chatStream(
    {
      message: content,
      history: messages.value.slice(-12, -2).map((m) => ({ role: m.role, content: m.content })),
    },
    {
      onText: (chunk) => { assistant.content += chunk; },
      onStep: (s) => { assistant.steps!.push(s.label); },
      onComplete: (result) => {
        if (!assistant.content && result.reply) assistant.content = result.reply;
        assistant.questions = result.questions || (result.question ? [result.question] : undefined);
        assistant.pending = false;
        sending.value = false;
      },
      onError: (msg) => {
        assistant.content = assistant.content || `出错了：${msg}`;
        assistant.pending = false;
        sending.value = false;
        toast.error(msg);
      },
      onClose: () => { assistant.pending = false; sending.value = false; },
    },
  );
}

function answer(q: ChatQuestion, label: string) {
  send(`${q.text}：${label}`);
}
</script>

<template>
  <div class="mx-auto flex h-full max-w-3xl flex-col">
    <div class="flex shrink-0 items-center justify-between px-6 py-3">
      <div class="text-[13px] text-slate-400">对话内容仅保存在当前浏览器</div>
      <button class="btn-secondary btn-sm" @click="newChat"><Plus :size="14" /> 新对话</button>
    </div>

    <div ref="scrollBox" class="min-h-0 flex-1 overflow-y-auto px-6 pb-4">
      <UiEmpty
        v-if="!messages.length"
        :icon="MessagesSquare"
        title="和业务血缘助手聊聊"
        description="可以问业务概念、让它基于经验库梳理关系，或对图谱提出修改建议。"
      >
        <div class="flex flex-wrap justify-center gap-2">
          <button class="btn-secondary btn-sm" @click="send('当前经验库里有哪些业务领域？')">经验库有哪些领域？</button>
          <button class="btn-secondary btn-sm" @click="send('帮我梳理一下核心业务流程')">梳理核心流程</button>
        </div>
      </UiEmpty>

      <div v-for="(m, i) in messages" :key="i" class="mb-5 flex gap-3" :class="m.role === 'user' ? 'flex-row-reverse' : ''">
        <div
          class="flex h-8 w-8 shrink-0 items-center justify-center rounded-full"
          :class="m.role === 'user' ? 'bg-indigo-600 text-white' : 'border border-slate-200 bg-white text-indigo-600'"
        >
          <User v-if="m.role === 'user'" :size="15" />
          <Bot v-else :size="15" />
        </div>
        <div class="min-w-0 max-w-[80%]">
          <div
            class="rounded-2xl px-4 py-2.5 text-[13.5px] leading-relaxed"
            :class="m.role === 'user'
              ? 'rounded-tr-sm bg-indigo-600 text-white'
              : 'card rounded-tl-sm text-slate-700'"
          >
            <div v-if="m.role === 'user'" class="whitespace-pre-wrap">{{ m.content }}</div>
            <div v-else class="md-body" v-html="renderMarkdown(m.content || (m.pending ? '…' : ''))" />
          </div>

          <div v-if="m.steps?.length && m.pending" class="mt-1.5 flex items-center gap-1.5 text-xs text-slate-400">
            <LoaderCircle :size="12" class="animate-spin" /> {{ m.steps[m.steps.length - 1] }}
          </div>

          <div v-if="m.questions?.length" class="mt-2 space-y-2">
            <div v-for="(q, qi) in m.questions" :key="qi" class="card p-3">
              <div class="mb-2 text-[13px] font-medium text-slate-700">{{ q.text }}</div>
              <div class="flex flex-wrap gap-1.5">
                <button
                  v-for="opt in q.options || []" :key="opt.label"
                  class="btn-secondary btn-sm"
                  @click="answer(q, opt.label)"
                >{{ opt.label }}</button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="shrink-0 border-t border-slate-200 bg-white px-6 py-4">
      <div class="flex items-end gap-2">
        <textarea
          v-model="input"
          class="textarea max-h-36 flex-1"
          rows="1"
          placeholder="输入消息，Enter 发送，Shift+Enter 换行…"
          @keydown.enter.exact.prevent="send()"
        />
        <button class="btn-primary h-10 w-10 !px-0" :disabled="!input.trim() || sending" @click="send()">
          <LoaderCircle v-if="sending" :size="16" class="animate-spin" />
          <Send v-else :size="16" />
        </button>
      </div>
    </div>
  </div>
</template>

<style>
.md-body .md-p { margin: 0 0 8px; }
.md-body .md-p:last-child { margin-bottom: 0; }
.md-body .md-h { font-weight: 600; margin: 10px 0 4px; }
.md-body .md-ul { margin: 0 0 8px; padding-left: 18px; list-style: disc; }
.md-body .md-code { background: #f1f5f9; border-radius: 4px; padding: 1px 5px; font-size: 12px; }
.md-body .md-pre { background: #0f172a; color: #e2e8f0; border-radius: 10px; padding: 10px 12px; overflow-x: auto; font-size: 12px; margin: 0 0 8px; }
.md-body .md-link { color: #4f46e5; text-decoration: underline; }
</style>
