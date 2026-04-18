<template>
  <div :class="['sidebar', exp && 'exp']">
    <!-- Logo / toggle -->
    <div class="sidebar-logo" @click="exp = !exp" title="展开 / 收起">
      <div class="logo-mark">推</div>
      <div class="logo-text">推演平台</div>
    </div>

    <!-- Primary nav -->
    <button
      v-for="item in primary" :key="item.path"
      :class="['sb-item', isActive(item) && 'active']"
      @click="go(item)"
      :title="item.label"
    >
      <span class="sb-icon">{{ item.icon }}</span>
      <span class="sb-item-label">{{ item.label }}</span>
    </button>

    <div class="sb-divider" />

    <!-- Secondary nav -->
    <button
      v-for="item in secondary" :key="item.path"
      :class="['sb-item', isActive(item) && 'active']"
      @click="go(item)"
      :title="item.label"
    >
      <span class="sb-icon">{{ item.icon }}</span>
      <span class="sb-item-label">{{ item.label }}</span>
    </button>

    <div class="sb-spacer" />
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'

const router = useRouter()
const route = useRoute()
const exp = ref(true)

const primary = [
  { icon: '◈', label: '图谱', path: '/graphs' },
  { icon: '◉', label: '溯源', path: '/trace' },
  { icon: '⬡', label: '流程', path: '/flow' },
  { icon: '◆', label: '事件', path: '/events' },
  { icon: '▣', label: '数据', path: '/data' },
]

const secondary = [
  { icon: '≡', label: '列表', path: '/graphs' },
  { icon: '◎', label: '搜索', path: '/search' },
]

function isActive(item) {
  // 图谱和编辑器都高亮"图谱"菜单项
  if (item.path === '/graphs') {
    return route.path === '/graphs' || route.path.startsWith('/editor/')
  }
  return route.path === item.path
}

function go(item) {
  router.push(item.path)
}
</script>
