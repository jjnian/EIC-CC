<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import {
  Search, Sparkles, Globe, Moon, Sun, LoaderCircle, Waypoints, FileText, Database, CircleDot,
} from 'lucide-vue-next';
import { useWorkspaceStore } from '../stores/workspace';
import { useToastStore } from '../stores/toast';
import { toggleTheme, isDark } from '../lib/theme';
import { wsColorForIndex } from '../lib/workspaceTheme';
import { listOntologies } from '../../api/ontology';
import { listDataSources, type DataSource } from '../../api/dataSources';
import { listExperiences, type Experience } from '../../api/experiences';

const route = useRoute();
const router = useRouter();
const ws = useWorkspaceStore();
const toast = useToastStore();

const title = computed(() => (route.meta.title as string) || '');
const query = ref('');
const searchRef = ref<HTMLInputElement | null>(null);
const dark = ref(isDark());

// ── 全局搜索下拉 ──
interface Hit { group: string; icon: 'model' | 'node' | 'ds' | 'exp'; title: string; sub: string; go: () => void; }
const dropOpen = ref(false);
const dropLoading = ref(false);
const hits = ref<Hit[]>([]);
let searchTimer: ReturnType<typeof setTimeout> | null = null;

/* Ctrl+K 聚焦全局搜索 */
function onKeydown(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault();
    searchRef.value?.focus();
  }
}
onMounted(() => {
  window.addEventListener('keydown', onKeydown);
  document.addEventListener('mousedown', onDocClick);
});
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown);
  document.removeEventListener('mousedown', onDocClick);
});

/* 点击外部关闭下拉 */
function onDocClick(e: MouseEvent) {
  const el = e.target as HTMLElement;
  if (!el.closest('[data-global-search]')) dropOpen.value = false;
}

/* 输入防抖 250ms 后聚合搜索：模型/节点/数据源/经验 */
watch(query, (v) => {
  if (searchTimer) clearTimeout(searchTimer);
  const kw = v.trim();
  if (!kw) { hits.value = []; dropOpen.value = false; return; }
  dropLoading.value = true;
  dropOpen.value = true;
  searchTimer = setTimeout(() => void runSearch(kw), 250);
});

async function runSearch(kw: string) {
  const found: Hit[] = [];
  const match = (s: string | undefined) => Boolean(s && s.toLowerCase().includes(kw.toLowerCase()));
  const [onts, dss, exps] = await Promise.all([
    listOntologies().catch(() => []),
    listDataSources().catch(() => []),
    listExperiences().catch(() => []),
  ]);
  for (const m of onts) {
    if (match(m.name) || match(m.title)) {
      const label = m.name || m.title || m.id;
      found.push({ group: '业务模型', icon: 'model', title: label, sub: `${m.graphData?.nodes?.length || 0} 节点 · ${m.graphData?.edges?.length || 0} 边`, go: () => { router.push({ name: 'graph', query: { model: m.id } }); } });
    }
    // 节点级命中：跳转图谱页后用页内搜索高亮
    for (const n of m.graphData?.nodes || []) {
      if (match(n.name) && found.length < 15) {
        const label = m.name || m.title || m.id;
        found.push({ group: '节点', icon: 'node', title: n.name, sub: `模型：${label}`, go: () => { router.push({ name: 'graph', query: { model: m.id } }); toast.info('请在图谱页搜索框输入关键词定位节点'); } });
      }
    }
  }
  for (const d of dss) {
    if (found.length >= 15) break;
    if (match(d.name)) found.push({ group: '数据源', icon: 'ds', title: d.name, sub: String(d.kind || ''), go: () => { router.push({ name: 'datasources', query: { q: kw } }); } });
  }
  for (const x of exps) {
    if (found.length >= 15) break;
    if (match(x.title)) found.push({ group: '经验文档', icon: 'exp', title: x.title, sub: x.tags || '经验文档', go: () => { router.push({ name: 'experiences', query: { q: kw } }); } });
  }
  hits.value = found.slice(0, 15);
  dropLoading.value = false;
}

function pick(h: Hit) {
  dropOpen.value = false;
  query.value = '';
  h.go();
}

/* 回车：列表页带词跳转过滤，图谱/对话页提示用页内搜索 */
function onEnter() {
  const kw = query.value.trim();
  if (!kw) return;
  dropOpen.value = false;
  if (route.name === 'experiences' || route.name === 'datasources') {
    router.push({ name: route.name, query: { q: kw } });
  } else if (route.name === 'graph' || route.name === 'chat') {
    toast.info('本页请使用页内搜索');
  } else {
    router.push({ name: 'experiences', query: { q: kw } });
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
    <div class="relative ml-4 w-64" data-global-search>
      <Search class="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2" style="color:var(--text3)" />
      <input
        ref="searchRef"
        v-model="query"
        class="h-8 w-full rounded-lg border pl-9 pr-12 text-[12.5px] outline-none transition"
        style="background:var(--panel);border-color:var(--border);color:var(--text)"
        placeholder="搜索模型、节点、经验文档…"
        @focus="query.trim() && (dropOpen = true)"
        @keyup.enter="onEnter"
        @keyup.esc="query = ''; dropOpen = false"
      />
      <kbd
        class="absolute right-2.5 top-1/2 -translate-y-1/2 rounded border px-1.5 py-0.5 text-[10px]"
        style="border-color:var(--border);color:var(--text3)"
      >Ctrl K</kbd>

      <!-- 搜索结果下拉 -->
      <div
        v-if="dropOpen"
        class="panel absolute left-0 top-9 z-40 w-80 overflow-hidden shadow-xl"
        style="max-height:340px;overflow-y:auto"
      >
        <div v-if="dropLoading" class="flex items-center gap-2 px-4 py-3 text-[12.5px]" style="color:var(--text3)">
          <LoaderCircle :size="14" class="animate-spin" />搜索中…
        </div>
        <template v-else-if="hits.length">
          <div v-for="(h, i) in hits" :key="i">
            <div v-if="i === 0 || hits[i - 1].group !== h.group" class="px-3 pb-1 pt-2.5 text-[10.5px] font-medium" style="color:var(--text3)">
              {{ h.group }}
            </div>
            <button
              class="flex w-full items-center gap-2.5 px-3 pb-2 text-left transition hover:brightness-95"
              @click="pick(h)"
            >
              <Waypoints v-if="h.icon === 'model'" class="h-3.5 w-3.5 shrink-0" style="color:var(--primary)" />
              <CircleDot v-else-if="h.icon === 'node'" class="h-3.5 w-3.5 shrink-0" style="color:var(--text3)" />
              <Database v-else-if="h.icon === 'ds'" class="h-3.5 w-3.5 shrink-0" style="color:#10B981" />
              <FileText v-else class="h-3.5 w-3.5 shrink-0" style="color:#F59E0B" />
              <span class="truncate text-[13px]" style="color:var(--text)">{{ h.title }}</span>
              <span class="ml-auto shrink-0 truncate text-[11px]" style="color:var(--text3);max-width:110px">{{ h.sub }}</span>
            </button>
          </div>
        </template>
        <div v-else class="px-4 py-4 text-center text-[12.5px]" style="color:var(--text3)">
          无匹配结果，回车进入「{{ route.name === 'datasources' ? '数据源' : '经验库' }}」页搜索
        </div>
      </div>
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
