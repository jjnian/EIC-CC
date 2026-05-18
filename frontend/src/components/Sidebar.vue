<script setup lang="ts">
import { ref, computed } from 'vue';

const props = defineProps<{
  expanded: boolean;
  models: any[];
  currentModelId: string;
  view: string;
}>();

const emit = defineEmits<{
  (e: 'toggle'): void;
  (e: 'nav', route: string): void;
  (e: 'open-model', id: string): void;
}>();

const a = ref(0);
const items = [
  ['✦', '新对话', 'welcome'],
  ['◈', '本体模型', 'list']
];

const historyModels = computed(() => {
  return [...props.models].slice(0, 30);
});

const onPick = (i: number, route: string) => {
  a.value = i;
  emit('nav', route);
};

const isActiveModel = (id: string) => props.view === 'graph' && props.currentModelId === id;
</script>

<template>
  <div :class="['sidebar', { exp: expanded }]">
    <div class="sidebar-logo" @click="emit('toggle')" style="cursor:pointer" :title="expanded ? '收起侧栏' : '展开侧栏'">
      <div class="logo-mark" :style="{ transition: 'transform .22s', transform: expanded ? 'rotate(0deg)' : 'rotate(0deg)' }">推</div>
      <div class="logo-text">推演平台</div>
    </div>
    <button v-for="(item, i) in items" :key="i"
            :class="['sb-item', { active: (item[2] === 'welcome' && view === 'welcome') || (item[2] === 'list' && view === 'list') }]"
            @click="onPick(i, item[2] as string)">
      <span class="sb-icon">{{ item[0] }}</span><span class="sb-item-label">{{ item[1] }}</span>
    </button>

    <div class="sb-history" v-if="expanded && historyModels.length">
      <div class="sb-section-label">历史对话</div>
      <div class="sb-history-list">
        <button v-for="m in historyModels" :key="m.id"
                :class="['sb-history-item', { active: isActiveModel(m.id) }]"
                :title="m.title"
                @click="emit('open-model', m.id)">
          <span class="sb-history-dot" />
          <span class="sb-history-title">{{ m.title }}</span>
          <span class="sb-history-time">{{ m.updated }}</span>
        </button>
      </div>
    </div>

    <div v-if="!expanded || !historyModels.length" class="sb-spacer" />
    <button :class="['sb-item', { active: view === 'settings' }]" @click="emit('nav', 'settings')">
      <span class="sb-icon">⚙</span><span class="sb-item-label">设置</span>
    </button>
  </div>
</template>

<style scoped>
.sb-history {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
  display: flex;
  flex-direction: column;
  min-height: 0;
  flex: 1;
  overflow: hidden;
}
.sb-section-label {
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 1.2px;
  color: rgba(255, 255, 255, 0.35);
  padding: 0 14px 8px;
  font-family: 'Inter', sans-serif;
  font-weight: 500;
}
.sb-history-list {
  flex: 1;
  overflow-y: auto;
  padding: 0 6px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.sb-history-list::-webkit-scrollbar { width: 4px; }
.sb-history-list::-webkit-scrollbar-thumb { background: rgba(255,255,255,0.1); border-radius: 4px; }
.sb-history-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  background: none;
  border: none;
  color: var(--text-dim);
  font-size: 12px;
  font-family: inherit;
  cursor: pointer;
  border-radius: 8px;
  text-align: left;
  transition: all 0.12s;
  width: 100%;
  overflow: hidden;
}
.sb-history-item:hover {
  background: rgba(255, 255, 255, 0.05);
  color: var(--text-main);
}
.sb-history-item.active {
  background: rgba(66, 184, 131, 0.12);
  color: #42b883;
}
.sb-history-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
  flex-shrink: 0;
  opacity: 0.6;
}
.sb-history-title {
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.sb-history-time {
  font-size: 9px;
  color: rgba(255, 255, 255, 0.3);
  font-family: 'JetBrains Mono', monospace;
  flex-shrink: 0;
}
.sb-history-item.active .sb-history-time {
  color: rgba(66, 184, 131, 0.6);
}
</style>

