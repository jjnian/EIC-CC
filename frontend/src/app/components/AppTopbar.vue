<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Search, Sparkles, Globe, Moon, Sun } from 'lucide-vue-next';
import { useWorkspaceStore } from '../stores/workspace';
import { useToastStore } from '../stores/toast';
import { toggleTheme, isDark } from '../lib/theme';
import { wsColorForIndex } from '../lib/workspaceTheme';

const route = useRoute();
const router = useRouter();
const ws = useWorkspaceStore();
const toast = useToastStore();

const title = computed(() => (route.meta.title as string) || '');
const query = ref('');
const searchRef = ref<HTMLInputElement | null>(null);
const dark = ref(isDark());

/* Ctrl+K 聚焦全局搜索 */
function onKeydown(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault();
    searchRef.value?.focus();
  }
}
onMounted(() => window.addEventListener('keydown', onKeydown));
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown));

/* 回车：列表页带词跳转过滤，图谱/对话页提示用页内搜索 */
function onEnter() {
  const q = query.value.trim();
  if (!q) return;
  if (route.name === 'experiences' || route.name === 'datasources') {
    router.push({ name: route.name, query: { q } });
  } else if (route.name === 'graph' || route.name === 'chat') {
    toast.info('本页请使用页内搜索');
  } else {
    router.push({ name: 'experiences', query: { q } });
  }
}

function onThemeToggle() {
  toggleTheme();
  dark.value = isDark();
}

const avatarIdx = computed(() => ws.list.findIndex((w) => w.id === ws.currentId));
</script>

<template>
  <header
    class="flex h-14 shrink-0 items-center gap-3 border-b px-5 backdrop-blur"
    style="background:var(--topbar);border-color:var(--border)"
  >
    <h1 class="text-[15px] font-semibold" style="color:var(--text)">{{ title }}</h1>

    <!-- 全局搜索 -->
    <div class="relative ml-4 w-64">
      <Search class="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2" style="color:var(--text3)" />
      <input
        ref="searchRef"
        v-model="query"
        class="h-8 w-full rounded-lg border pl-9 pr-12 text-[12.5px] outline-none transition"
        style="background:var(--panel);border-color:var(--border);color:var(--text)"
        placeholder="搜索模型、节点、经验文档…"
        @keyup.enter="onEnter"
        @keyup.esc="query = ''"
      />
      <kbd
        class="absolute right-2.5 top-1/2 -translate-y-1/2 rounded border px-1.5 py-0.5 text-[10px]"
        style="border-color:var(--border);color:var(--text3)"
      >Ctrl K</kbd>
    </div>

    <div class="ml-auto flex items-center gap-2">
      <button class="chip" @click="router.push({ name: 'experiences', query: { build: '1' } })">
        <Sparkles class="h-3.5 w-3.5" style="color:var(--primary)" />一键建图
      </button>
      <button class="chip" @click="router.push({ name: 'experiences', query: { research: '1' } })">
        <Globe class="h-3.5 w-3.5" />联网调研
      </button>
      <div class="mx-1 h-5 w-px" style="background:var(--border)" />
      <!-- 主题切换 -->
      <button
        class="flex h-8 w-8 items-center justify-center rounded-lg border transition"
        style="background:var(--btn-ghost-bg);border-color:var(--btn-ghost-border);color:var(--btn-ghost-text)"
        title="切换亮色/暗色主题"
        @click="onThemeToggle"
      >
        <Moon v-if="!dark" class="h-4 w-4" />
        <Sun v-else class="h-4 w-4" />
      </button>
      <!-- 当前工作空间头像 -->
      <div
        class="flex h-7 w-7 items-center justify-center rounded-full text-[11px] font-bold text-white"
        :style="{ background: wsColorForIndex(avatarIdx).bg }"
        :title="ws.current?.name || ''"
      >{{ ws.current?.name?.charAt(0) || '?' }}</div>
    </div>
  </header>
</template>
