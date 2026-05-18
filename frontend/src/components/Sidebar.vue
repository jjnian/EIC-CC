<script setup lang="ts">
import { ref } from 'vue';

defineProps<{ expanded: boolean }>();
const emit = defineEmits<{
  (e: 'toggle'): void;
  (e: 'nav', route: string): void;
}>();

const a = ref(0);
const items = [
  ['✦', '新对话', 'welcome'],
  ['◈', '本体模型', 'list']
];
</script>

<template>
  <div :class="['sidebar', { exp: expanded }]">
    <div class="sidebar-logo" @click="emit('toggle')" style="cursor:pointer" :title="expanded ? '收起侧栏' : '展开侧栏'">
      <div class="logo-mark" :style="{ transition: 'transform .22s', transform: expanded ? 'rotate(0deg)' : 'rotate(0deg)' }">推</div>
      <div class="logo-text">推演平台</div>
    </div>
    <button v-for="(item, i) in items" :key="i" :class="['sb-item', { active: a === i }]" @click="a = i; emit('nav', item[2] as string)">
      <span class="sb-icon">{{ item[0] }}</span><span class="sb-item-label">{{ item[1] }}</span>
    </button>
    <div class="sb-spacer" />
    <button :class="['sb-item', { active: a === items.length }]" @click="a = items.length; emit('nav', 'settings')">
      <span class="sb-icon">⚙</span><span class="sb-item-label">设置</span>
    </button>
  </div>
</template>
