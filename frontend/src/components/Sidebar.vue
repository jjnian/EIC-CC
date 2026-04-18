<template>
  <div :class="['sidebar', expanded && 'exp']">
    <div class="sidebar-logo" @click="$emit('toggle')" title="切换侧栏">
      <div class="logo-mark">推</div>
      <div class="logo-text">推演平台</div>
    </div>
    <button
      v-for="(item, i) in items.slice(0, 5)" :key="i"
      :class="['sb-item', active === i && 'active']"
      @click="active = i"
    >
      <span class="sb-icon">{{ item[0] }}</span>
      <span class="sb-item-label">{{ item[1] }}</span>
    </button>
    <div class="sb-divider" />
    <button
      v-for="(item, i) in items.slice(5)" :key="i + 5"
      :class="['sb-item', active === i + 5 && 'active']"
      @click="active = i + 5"
    >
      <span class="sb-icon">{{ item[0] }}</span>
      <span class="sb-item-label">{{ item[1] }}</span>
    </button>
    <div class="sb-spacer" />
  </div>
</template>

<script setup>
import { ref } from 'vue'
defineProps({ expanded: Boolean })
defineEmits(['toggle'])
const active = ref(0)
const items = [
  ['◈', '图谱'], ['◉', '溯源'], ['⬡', '流程'], ['◆', '事件'], ['▣', '数据'],
  ['≡', '列表'], ['◎', '搜索'],
]
</script>
