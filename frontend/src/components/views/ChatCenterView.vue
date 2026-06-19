<script setup lang="ts">
import { computed, ref, type PropType } from 'vue';
import ChatPanel from '../ChatPanel.vue';
import type { OntologyNode, OntologyEdge, ChainStep } from '../../types';

const props = defineProps({
  nodes:           { type: Array as PropType<OntologyNode[]>, required: true },
  edges:           { type: Array as PropType<OntologyEdge[]>, required: true },
  liveActive:      { type: Boolean, required: true },
  liveLoading:     { type: Boolean, required: true },
  liveSteps:       { type: Array as PropType<ChainStep[]>, required: true },
  liveIntent:      { type: String as PropType<'forward' | 'backward'>, required: true },
  livePruneDetails: { type: Array as PropType<{ nodeId: string; label: string; reason: string }[]>, default: () => [] },
  liveSeeds:       { type: Array as PropType<string[]>, default: () => [] },
  livePrompt:      { type: String, default: '' },
  liveName:        { type: String, default: '' },
  liveBranchId:    { type: String, default: '' },
  liveError:       { type: String, default: '' },
  liveStatus:      { type: Number as PropType<0 | 1 | 2 | 3 | 4>, default: 0 },
  pendingChatSeed: { type: Object as PropType<{ text: string; files: File[] } | null>, default: null },
  modelTitle:      { type: String, default: '' },
  modelId:         { type: String, default: '' },
  ensureModel:     { type: Function as PropType<(titleHint: string) => Promise<void>>, default: null },
});

const livePrediction = computed(() => ({
  status: props.liveStatus,
  intent: props.liveIntent,
  seeds: props.liveSeeds,
  prompt: props.livePrompt,
  name: props.liveName,
  steps: props.liveSteps,
  pruneDetails: props.livePruneDetails,
  branchId: props.liveBranchId,
  error: props.liveError,
}));

const emit = defineEmits<{
  (e: 'update', addNodes: OntologyNode[], addEdges: OntologyEdge[],
     removeNodeIds?: string[], removeEdgeIds?: string[]): void;
  (e: 'clear-graph'): void;
  (e: 'seed-consumed'): void;
  (e: 'abort-prediction'): void;
  (e: 'chat-ref', el: any): void;
  (e: 'view-graph', modelId: string): void;
}>();

const cpRef = ref<InstanceType<typeof ChatPanel> | null>(null);
const hasUserMsg = ref(false);

const examples = [
  '描述一个供应链本体：包含供应商、原料、工厂、产品和客户实体',
  '从企业组织架构提取部门、岗位与人员的关系图谱',
  '基于金融审计场景构建合同、付款、审批与风险节点',
];

// 当前会话还没有用户消息时,显示欢迎横幅(进入新对话或首次启动)。
// 一旦用户发送消息或切换到已有对话,横幅自动隐藏。
const showWelcomeBanner = computed(() => !hasUserMsg.value);

const useExample = (text: string) => {
  (cpRef.value as any)?.setInput?.(text);
};

const bindRef = (el: any) => {
  cpRef.value = el;
  emit('chat-ref', el);
};
</script>

<template>
  <div class="chat-center">
    <div class="cc-inner">
      <div v-if="showWelcomeBanner" class="cc-welcome-banner">
        <div class="cwb-logo-mark">推</div>
        <h1 class="cwb-title">今天要构建什么本体？</h1>
        <p class="cwb-sub">用自然语言描述实体与关系，或上传图片 / Markdown / TXT / JSON / 代码文件，自动提取本体图谱</p>
        <div class="cwb-examples">
          <span class="cwb-ex-label">试试这些：</span>
          <button class="cwb-ex" v-for="(t, i) in examples" :key="i" @click="useExample(t)">{{ t }}</button>
        </div>
      </div>
      <ChatPanel
        :ref="bindRef"
        :nodes="nodes"
        :edges="edges"
        :width="0"
        :seed="pendingChatSeed"
        :live-prediction="livePrediction"
        :model-id="modelId"
        :ensure-model="ensureModel"
        class="cc-chat"
        @update="(addNodes, addEdges, removeNodeIds, removeEdgeIds) => emit('update', addNodes, addEdges, removeNodeIds, removeEdgeIds)"
        @clear-graph="emit('clear-graph')"
        @seed-consumed="emit('seed-consumed')"
        @abort-prediction="emit('abort-prediction')"
        @view-graph="(id) => emit('view-graph', id)"
        @user-msg-changed="(v) => hasUserMsg = v"
      />
    </div>
  </div>
</template>

<style scoped>
.chat-center {
  flex: 1;
  min-height: 0;
  display: flex;
  justify-content: center;
  background: transparent;
  overflow: hidden;
}
.cc-inner {
  width: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 0 24px 0;
}
.cc-chat {
  flex: 1;
  min-height: 0;
  width: 100% !important;
}
.cc-chat.chat-panel {
  background: transparent !important;
  backdrop-filter: none !important;
  -webkit-backdrop-filter: none !important;
  border: none !important;
  box-shadow: none !important;
}
.cc-chat :deep(.ch-head) {
  display: none;
}
/* 去掉对话面板左侧那条渐变竖线(.chat-panel::before 与 cc-chat 同元素) */
.cc-chat::before {
  display: none !important;
}
.cc-chat :deep(.ch-msgs),
.cc-chat :deep(.ch-input-area),
.cc-chat :deep(.att-row) {
  width: min(100%, 1280px);
  margin-left: auto;
  margin-right: auto;
}
.cc-chat :deep(.ch-msgs) {
  padding-left: 32px;
  padding-right: 32px;
}
.cc-chat :deep(.ch-input-area) {
  padding-left: 32px;
  padding-right: 32px;
}
.cc-chat :deep(.msg) {
  max-width: 920px;
}
.cc-chat :deep(.msg-body) {
  max-width: 100%;
}
.cc-chat :deep(.ch-msgs) {
  align-items: center;
}
.cc-chat :deep(.msg-asst .bubble) {
  background: transparent !important;
  border: none;
  box-shadow: none;
  color: #f5f7fb;
  padding: 4px 0;
}
.cc-chat :deep(.msg-asst .bubble strong),
.cc-chat :deep(.msg-asst .bubble em) {
  color: #fff;
}
@media (max-width: 1200px) {
  .cc-chat :deep(.ch-msgs),
  .cc-chat :deep(.ch-input-area),
  .cc-chat :deep(.att-row) {
    width: min(100%, 960px);
  }
  .cc-chat :deep(.msg) {
    max-width: 820px;
  }
}

/* ---------- 欢迎横幅(空会话时出现在 ChatPanel 上方) ---------- */
.cc-welcome-banner {
  width: min(100%, 1280px);
  margin: 24px auto 8px;
  padding: 0 32px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
  text-align: center;
  flex-shrink: 0;
}
.cwb-logo-mark {
  width: 56px;
  height: 56px;
  border-radius: 16px;
  background: linear-gradient(135deg, #5fd4a3 0%, #42b883 50%, #35495e 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  font-weight: 800;
  color: #fff;
  box-shadow:
    0 12px 30px rgba(66, 184, 131, 0.36),
    0 4px 10px rgba(0, 0, 0, 0.28),
    inset 0 1px 0 rgba(255, 255, 255, 0.30),
    inset 0 -1px 0 rgba(0, 0, 0, 0.18);
  letter-spacing: 0.5px;
  font-family: 'Inter', sans-serif;
}
.cwb-title {
  margin: 0;
  font-size: 28px;
  font-weight: 700;
  letter-spacing: 0.4px;
  background: linear-gradient(180deg, #ffffff 0%, rgba(244, 247, 251, 0.78) 100%);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  font-family: 'Inter', sans-serif;
}
.cwb-sub {
  margin: 0;
  color: var(--text-dim);
  font-size: 13.5px;
  letter-spacing: 0.2px;
  line-height: 1.65;
  max-width: 640px;
}
.cwb-examples {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
  margin-top: 4px;
}
.cwb-ex-label {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.45);
  align-self: center;
  margin-right: 4px;
  letter-spacing: 0.3px;
  font-family: 'JetBrains Mono', monospace;
}
.cwb-ex {
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.05), rgba(255, 255, 255, 0.02));
  border: 1px solid rgba(255, 255, 255, 0.10);
  color: var(--text-dim);
  padding: 7px 14px;
  border-radius: 100px;
  font-size: 12.5px;
  cursor: pointer;
  transition: border-color 0.15s ease, color 0.15s ease, background 0.15s ease, transform 0.15s ease;
  font-family: inherit;
  letter-spacing: 0.15px;
}
.cwb-ex:hover {
  border-color: rgba(66, 184, 131, 0.45);
  color: var(--text-main);
  background: linear-gradient(180deg, rgba(66, 184, 131, 0.10), rgba(66, 184, 131, 0.04));
  transform: translateY(-1px);
}
</style>
