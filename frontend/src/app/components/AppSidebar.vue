<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import {
  LayoutDashboard, Waypoints, Library, MessagesSquare,
  Database, ChartColumn, Settings,
} from 'lucide-vue-next';

const route = useRoute();

const NAV = [
  { name: 'dashboard', label: '工作台', icon: LayoutDashboard },
  { name: 'graph', label: '血缘图谱', icon: Waypoints },
  { name: 'experiences', label: '经验库', icon: Library },
  { name: 'chat', label: 'AI 对话', icon: MessagesSquare },
  { name: 'datasources', label: '数据源', icon: Database },
  { name: 'analysis', label: '血缘分析', icon: ChartColumn },
];

const active = computed(() => route.name);
</script>

<template>
  <aside class="flex h-full w-[220px] shrink-0 flex-col border-r border-slate-200 bg-white">
    <div class="flex h-14 items-center gap-2.5 border-b border-slate-100 px-4">
      <div class="flex h-8 w-8 items-center justify-center rounded-lg bg-indigo-600 text-white">
        <Waypoints :size="17" :stroke-width="2.2" />
      </div>
      <div class="leading-tight">
        <div class="text-[14px] font-semibold text-slate-900">推演平台</div>
        <div class="text-[11px] text-slate-400">EIC-CC · 业务血缘</div>
      </div>
    </div>

    <nav class="flex-1 space-y-0.5 overflow-y-auto p-2.5">
      <router-link
        v-for="item in NAV"
        :key="item.name"
        :to="{ name: item.name }"
        class="flex items-center gap-2.5 rounded-lg px-3 py-2 text-[13.5px] font-medium transition-colors"
        :class="active === item.name
          ? 'bg-indigo-50 text-indigo-700'
          : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900'"
      >
        <component :is="item.icon" :size="16" :stroke-width="2" />
        {{ item.label }}
      </router-link>
    </nav>

    <div class="border-t border-slate-100 p-2.5">
      <router-link
        :to="{ name: 'settings' }"
        class="flex items-center gap-2.5 rounded-lg px-3 py-2 text-[13.5px] font-medium transition-colors"
        :class="active === 'settings'
          ? 'bg-indigo-50 text-indigo-700'
          : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900'"
      >
        <Settings :size="16" :stroke-width="2" />
        设置
      </router-link>
    </div>
  </aside>
</template>
