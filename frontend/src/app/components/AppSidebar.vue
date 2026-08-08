<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import {
  LayoutDashboard, Database, Library, Waypoints, MessageSquareText,
  Settings2, ChevronsUpDown, BarChart3,
} from 'lucide-vue-next';
import { useWorkspaceStore } from '../stores/workspace';
import { wsColorForIndex } from '../lib/workspaceTheme';
import { listExperiences } from '../../api/experiences';
import { listOntologies } from '../../api/ontology';

const route = useRoute();
const router = useRouter();
const ws = useWorkspaceStore();

/* 工作空间分组导航（顺序对齐原型） */
const NAV = [
  { name: 'dashboard', label: '工作台', icon: LayoutDashboard },
  { name: 'datasources', label: '数据源', icon: Database },
  { name: 'experiences', label: '经验库', icon: Library },
  { name: 'graph', label: '血缘图谱', icon: Waypoints },
  { name: 'analysis', label: '分析中心', icon: BarChart3 },
  { name: 'chat', label: 'AI 对话', icon: MessageSquareText },
];

const active = computed(() => route.name);

// 经验库计数徽章（真实数据）
const expCount = ref<number | null>(null);
// 底部切换卡 meta：N 个模型 · M 节点
const wsMeta = ref('');

// 工作空间切换下拉
const dropOpen = ref(false);

onMounted(async () => {
  try {
    const [exps, models] = await Promise.all([listExperiences(), listOntologies()]);
    expCount.value = exps.length;
    const nodes = models.reduce((n, m) => n + (m.graphData?.nodes?.length || 0), 0);
    wsMeta.value = `${models.length} 个模型 · ${nodes} 节点`;
  } catch {
    wsMeta.value = '';
  }
});

const currentIdx = computed(() => ws.list.findIndex((w) => w.id === ws.currentId));

function switchTo(id: string) {
  dropOpen.value = false;
  if (id === ws.currentId) return;
  ws.select(id);
  router.push({ name: 'dashboard' }).then(() => window.location.reload());
}

function manage() {
  dropOpen.value = false;
  router.push({ name: 'workspaces' });
}
</script>

<template>
  <aside class="flex w-[232px] shrink-0 flex-col border-r" style="background:var(--sidebar);border-color:var(--border)">
    <!-- Logo -->
    <div class="flex h-14 items-center gap-2.5 border-b px-4" style="border-color:var(--border)">
      <div
        class="flex h-7 w-7 items-center justify-center rounded-lg text-[13px] font-bold text-white"
        style="background:linear-gradient(135deg, var(--primary), var(--primary2))"
      >推</div>
      <div class="leading-tight">
        <div class="text-[13.5px] font-semibold" style="color:var(--text)">推演平台</div>
        <div class="text-[10.5px] tracking-wide" style="color:var(--text3)">EIC-CC · 业务血缘建模</div>
      </div>
    </div>

    <!-- 导航 -->
    <nav class="flex-1 space-y-0.5 overflow-y-auto p-3">
      <div class="px-3 pb-1.5 pt-1 text-[10.5px] font-medium uppercase tracking-widest" style="color:var(--text3)">工作空间</div>
      <div class="mb-1 px-3 text-[10.5px]" style="color:var(--text3)">以下数据按工作空间隔离</div>
      <router-link
        v-for="item in NAV"
        :key="item.name"
        :to="{ name: item.name }"
        class="nav-item"
        :class="{ active: active === item.name }"
      >
        <component :is="item.icon" class="h-4 w-4" />
        {{ item.label }}
        <span
          v-if="item.name === 'experiences' && expCount != null"
          class="ml-auto rounded px-1.5 py-0.5 text-[10.5px]"
          style="background:var(--nav-hover);color:var(--text3)"
        >{{ expCount }}</span>
      </router-link>

      <div class="px-3 pb-1.5 pt-4 text-[10.5px] font-medium uppercase tracking-widest" style="color:var(--text3)">系统</div>
      <router-link :to="{ name: 'settings' }" class="nav-item" :class="{ active: active === 'settings' }">
        <Settings2 class="h-4 w-4" />
        设置
      </router-link>
    </nav>

    <!-- 底部工作空间切换 -->
    <div class="border-t p-3" style="border-color:var(--border)">
      <div class="relative">
        <button
          class="flex w-full cursor-pointer items-center gap-2.5 rounded-lg p-2.5 text-left transition"
          style="background:var(--nav-hover)"
          @click="dropOpen = !dropOpen"
        >
          <div
            class="flex h-7 w-7 shrink-0 items-center justify-center rounded-md text-[11px] font-bold text-white"
            :style="{ background: wsColorForIndex(currentIdx).bg }"
          >{{ ws.current?.name?.charAt(0) || '?' }}</div>
          <div class="min-w-0 flex-1 leading-tight">
            <div class="truncate text-[12.5px] font-medium" style="color:var(--text)">{{ ws.current?.name || '选择工作空间' }}</div>
            <div class="text-[10.5px]" style="color:var(--text3)">{{ wsMeta || '工作空间' }}</div>
          </div>
          <ChevronsUpDown class="h-3.5 w-3.5 shrink-0" style="color:var(--text3)" />
        </button>

        <!-- 点击遮罩关闭 -->
        <div v-if="dropOpen" class="fixed inset-0 z-40" @click="dropOpen = false" />

        <!-- 向上弹出下拉 -->
        <div
          v-if="dropOpen"
          class="absolute bottom-full left-0 right-0 z-50 mb-1 overflow-hidden rounded-xl border py-1 shadow-lg"
          style="background:var(--panel);border-color:var(--border)"
        >
          <div class="px-3 py-1.5 text-[10.5px] font-medium uppercase tracking-wider" style="color:var(--text3)">切换工作空间</div>
          <button
            v-for="(w, i) in ws.list"
            :key="w.id"
            class="flex w-full items-center gap-2 rounded-none border-none bg-transparent px-3 py-2 text-left text-[12.5px] transition"
            style="color:var(--text)"
            @click="switchTo(w.id)"
          >
            <span
              class="flex h-6 w-6 shrink-0 items-center justify-center rounded-md text-[10px] font-bold text-white"
              :style="{ background: wsColorForIndex(i).bg }"
            >{{ w.name.charAt(0) }}</span>
            <span class="min-w-0 flex-1">
              <span class="block truncate" :style="w.id === ws.currentId ? 'color:var(--accent-text);font-weight:600' : ''">{{ w.name }}</span>
            </span>
          </button>
          <div class="mt-1 border-t pt-1" style="border-color:var(--border)">
            <button
              class="flex w-full items-center gap-2 bg-transparent px-3 py-2 text-left text-[12.5px] font-medium transition"
              style="color:var(--primary)"
              @click="manage"
            >
              <Settings2 class="h-3.5 w-3.5" />
              管理 / 新建工作空间
            </button>
          </div>
        </div>
      </div>
    </div>
  </aside>
</template>
