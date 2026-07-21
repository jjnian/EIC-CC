<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import {
  Waypoints, Library, Database, Sparkles, Upload, MessagesSquare,
  Plug, ArrowRight, LoaderCircle,
} from 'lucide-vue-next';
import { listOntologies } from '../../api/ontology';
import { listExperiences } from '../../api/experiences';
import { listDataSources } from '../../api/dataSources';
import type { OntologyModel } from '../../types';
import { formatNumber, timeAgo } from '../lib/format';
import UiEmpty from '../components/UiEmpty.vue';

const router = useRouter();

const loading = ref(true);
const failed = ref(false);
const models = ref<OntologyModel[]>([]);
const expCount = ref(0);
const dsCount = ref(0);
const stats = ref({ nodes: 0, edges: 0 });

onMounted(async () => {
  try {
    const [ms, exps, dss] = await Promise.all([
      listOntologies(),
      listExperiences(),
      listDataSources(),
    ]);
    models.value = [...ms].sort((a, b) => String(b.updated || '').localeCompare(String(a.updated || '')));
    expCount.value = exps.length;
    dsCount.value = dss.length;
    stats.value = ms.reduce(
      (acc, m) => ({
        nodes: acc.nodes + (m.graphData?.nodes?.length || 0),
        edges: acc.edges + (m.graphData?.edges?.length || 0),
      }),
      { nodes: 0, edges: 0 },
    );
  } catch {
    failed.value = true;
  } finally {
    loading.value = false;
  }
});

const quickActions = [
  { label: '开始建图', desc: '从经验库构建血缘图', icon: Sparkles, to: { name: 'experiences', query: { build: '1' } }, cls: 'bg-indigo-50 text-indigo-600' },
  { label: '导入文档', desc: 'PDF / DOCX / 图片 / 音频', icon: Upload, to: { name: 'experiences', query: { import: '1' } }, cls: 'bg-emerald-50 text-emerald-600' },
  { label: 'AI 对话', desc: '对话式建模与问答', icon: MessagesSquare, to: { name: 'chat' }, cls: 'bg-amber-50 text-amber-600' },
  { label: '接入数据源', desc: '库表 / HTTP 接口供血', icon: Plug, to: { name: 'datasources', query: { new: '1' } }, cls: 'bg-violet-50 text-violet-600' },
];
</script>

<template>
  <div class="mx-auto max-w-5xl px-6 py-8">
    <div v-if="loading" class="flex items-center justify-center py-24 text-slate-400">
      <LoaderCircle :size="22" class="animate-spin" />
    </div>

    <UiEmpty
      v-else-if="failed"
      :icon="Database"
      title="后端服务暂不可达"
      description="请确认 Spring Boot 后端已在 8000 端口启动，然后刷新页面。"
    />

    <template v-else>
      <!-- 统计卡片 -->
      <div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <div class="card p-5">
          <div class="flex items-center gap-3">
            <div class="flex h-10 w-10 items-center justify-center rounded-xl bg-indigo-50 text-indigo-600"><Waypoints :size="19" /></div>
            <div>
              <div class="text-2xl font-bold text-slate-900">{{ models.length }}</div>
              <div class="text-xs text-slate-400">血缘模型</div>
            </div>
          </div>
        </div>
        <div class="card p-5">
          <div class="flex items-center gap-3">
            <div class="flex h-10 w-10 items-center justify-center rounded-xl bg-sky-50 text-sky-600"><Sparkles :size="19" /></div>
            <div>
              <div class="text-2xl font-bold text-slate-900">{{ formatNumber(stats.nodes) }}<span class="mx-0.5 text-sm font-normal text-slate-300">/</span>{{ formatNumber(stats.edges) }}</div>
              <div class="text-xs text-slate-400">节点 / 血缘边</div>
            </div>
          </div>
        </div>
        <div class="card p-5">
          <div class="flex items-center gap-3">
            <div class="flex h-10 w-10 items-center justify-center rounded-xl bg-emerald-50 text-emerald-600"><Library :size="19" /></div>
            <div>
              <div class="text-2xl font-bold text-slate-900">{{ expCount }}</div>
              <div class="text-xs text-slate-400">经验文档</div>
            </div>
          </div>
        </div>
        <div class="card p-5">
          <div class="flex items-center gap-3">
            <div class="flex h-10 w-10 items-center justify-center rounded-xl bg-violet-50 text-violet-600"><Database :size="19" /></div>
            <div>
              <div class="text-2xl font-bold text-slate-900">{{ dsCount }}</div>
              <div class="text-xs text-slate-400">数据源</div>
            </div>
          </div>
        </div>
      </div>

      <!-- 快速操作 -->
      <h2 class="mb-3 mt-8 text-[14px] font-semibold text-slate-700">快速开始</h2>
      <div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <button
          v-for="a in quickActions" :key="a.label"
          class="card flex flex-col items-start gap-3 p-5 text-left transition hover:border-indigo-300 hover:shadow-md"
          @click="router.push(a.to)"
        >
          <div class="flex h-10 w-10 items-center justify-center rounded-xl" :class="a.cls">
            <component :is="a.icon" :size="19" />
          </div>
          <div>
            <div class="text-[14px] font-semibold text-slate-800">{{ a.label }}</div>
            <div class="mt-0.5 text-[12px] text-slate-400">{{ a.desc }}</div>
          </div>
        </button>
      </div>

      <!-- 最近模型 -->
      <h2 class="mb-3 mt-8 text-[14px] font-semibold text-slate-700">最近的血缘模型</h2>
      <div v-if="models.length" class="card divide-y divide-slate-100">
        <button
          v-for="m in models.slice(0, 5)" :key="m.id"
          class="flex w-full items-center gap-4 px-5 py-3.5 text-left transition hover:bg-slate-50"
          @click="router.push({ name: 'graph', query: { model: m.id } })"
        >
          <div class="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-indigo-50 text-indigo-600">
            <Waypoints :size="16" />
          </div>
          <div class="min-w-0 flex-1">
            <div class="truncate text-[14px] font-medium text-slate-800">{{ m.title || m.name || '未命名模型' }}</div>
            <div class="mt-0.5 text-xs text-slate-400">
              {{ m.graphData?.nodes?.length || 0 }} 节点 · {{ m.graphData?.edges?.length || 0 }} 边 · 更新于 {{ timeAgo(m.updated) }}
            </div>
          </div>
          <ArrowRight :size="15" class="shrink-0 text-slate-300" />
        </button>
      </div>
      <div v-else class="card">
        <UiEmpty
          :icon="Waypoints"
          title="还没有血缘模型"
          description="先把访谈、文档、库结构沉淀进经验库，再一键构建业务血缘图。"
        >
          <button class="btn-primary" @click="router.push({ name: 'experiences', query: { import: '1' } })">
            <Upload :size="15" /> 去导入经验
          </button>
        </UiEmpty>
      </div>
    </template>
  </div>
</template>
