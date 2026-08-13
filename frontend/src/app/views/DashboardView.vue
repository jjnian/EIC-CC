<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import {
  Box, GitFork, FileText, DatabaseZap, Waypoints, Sparkles, Globe, Upload,
  Database, AlertTriangle, CircleCheck, LoaderCircle, ArrowRight,
} from 'lucide-vue-next';
import { listOntologies } from '../../api/ontology';
import { listExperiences } from '../../api/experiences';
import { listDataSources } from '../../api/dataSources';
import { listExperienceFolders } from '../../api/experienceFolders';
import type { OntologyModel } from '../../types';
import { useWorkspaceStore } from '../stores/workspace';
import { timeAgo } from '../lib/format';
import UiEmpty from '../components/UiEmpty.vue';

const router = useRouter();
const ws = useWorkspaceStore();

const loading = ref(true);
const failed = ref(false);
const models = ref<OntologyModel[]>([]);
const expCount = ref(0);
const folderCount = ref(0);
const dsCount = ref(0);
const dsErrorCount = ref(0);
const unindexedCount = ref(0);
const stats = ref({ nodes: 0, edges: 0 });

onMounted(async () => {
  try {
    const [ms, exps, dss, folders] = await Promise.all([
      listOntologies(),
      listExperiences(),
      listDataSources(),
      listExperienceFolders().catch(() => []),
    ]);
    models.value = [...ms].sort((a, b) => String(b.updated || '').localeCompare(String(a.updated || '')));
    expCount.value = exps.length;
    folderCount.value = folders.length;
    dsCount.value = dss.length;
    dsErrorCount.value = dss.filter((d) => d.status === 'error').length;
    unindexedCount.value = exps.filter((e) => e.indexStatus !== 'indexed').length;
    // 业务实体按节点 id 去重统计
    const nodeIds = new Set<string>();
    let edges = 0;
    for (const m of ms) {
      (m.graphData?.nodes || []).forEach((n) => nodeIds.add(n.id));
      edges += m.graphData?.edges?.length || 0;
    }
    stats.value = { nodes: nodeIds.size, edges };
  } catch {
    failed.value = true;
  } finally {
    loading.value = false;
  }
});

/* 问候语：按时段 */
const greeting = computed(() => {
  const h = new Date().getHours();
  if (h < 6) return '夜深了';
  if (h < 12) return '早上好';
  if (h < 18) return '下午好';
  return '晚上好';
});

const today = computed(() => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
});

/* 待办提醒：真实可算项 */
const todos = computed(() => {
  const items: { icon: any; color: string; bg: string; html: string; to: any }[] = [];
  if (dsErrorCount.value > 0) {
    items.push({
      icon: AlertTriangle, color: '#D97706', bg: 'rgba(217,119,6,.06)',
      html: `<b>${dsErrorCount.value} 个数据源</b>连接异常，建议检查`,
      to: { name: 'datasources' },
    });
  }
  if (unindexedCount.value > 0) {
    items.push({
      icon: FileText, color: '#F43F5E', bg: 'rgba(244,63,94,.06)',
      html: `<b>${unindexedCount.value} 篇经验</b>尚未索引，暂不能参与建图`,
      to: { name: 'experiences' },
    });
  }
  if (!items.length) {
    items.push({
      icon: CircleCheck, color: 'var(--success)', bg: 'var(--nav-hover)',
      html: '一切正常，暂无待办提醒',
      to: null,
    });
  }
  return items;
});

const quickActions = [
  { label: '一键建图', icon: Sparkles, accent: true, to: { name: 'experiences', query: { build: '1' } } },
  { label: '联网调研', icon: Globe, accent: false, to: { name: 'experiences', query: { research: '1' } } },
  { label: '导入文档', icon: Upload, accent: false, to: { name: 'experiences', query: { import: '1' } } },
  { label: '接入数据源', icon: Database, accent: false, to: { name: 'datasources', query: { new: '1' } } },
];
</script>

<template>
  <div>
    <div v-if="loading" class="flex items-center justify-center py-24" style="color:var(--text3)">
      <LoaderCircle :size="22" class="animate-spin" />
    </div>

    <UiEmpty
      v-else-if="failed"
      :icon="Database"
      title="后端服务暂不可达"
      description="请确认 Spring Boot 后端已在 8000 端口启动，然后刷新页面。"
    />

    <template v-else>
      <!-- 问候块 -->
      <div class="mb-6 flex items-end justify-between">
        <div>
          <h2 class="text-xl font-bold" style="color:var(--text)">{{ greeting }} 👋</h2>
          <p class="mt-1 text-[13px]" style="color:var(--text2)">
            「{{ ws.current?.name || '工作空间' }}」共 {{ models.length }} 个模型 · {{ expCount }} 篇经验文档。
          </p>
        </div>
        <span class="text-xs" style="color:var(--text3)">{{ today }}</span>
      </div>

      <!-- 统计卡 -->
      <div class="mb-6 grid grid-cols-4 gap-4">
        <div class="panel p-4">
          <div class="flex items-center justify-between"><span class="text-[12px]" style="color:var(--text2)">业务实体</span><Box class="h-4 w-4" style="color:var(--primary)" /></div>
          <div class="mt-2 text-[26px] font-bold leading-none" style="color:var(--text)">{{ stats.nodes }}</div>
          <div class="mt-1.5 text-[11.5px]" style="color:var(--text3)">跨模型去重</div>
        </div>
        <div class="panel p-4">
          <div class="flex items-center justify-between"><span class="text-[12px]" style="color:var(--text2)">血缘边</span><GitFork class="h-4 w-4" style="color:var(--primary)" /></div>
          <div class="mt-2 text-[26px] font-bold leading-none" style="color:var(--text)">{{ stats.edges }}</div>
          <div class="mt-1.5 text-[11.5px]" style="color:var(--text3)">{{ models.length }} 个模型</div>
        </div>
        <div class="panel p-4">
          <div class="flex items-center justify-between"><span class="text-[12px]" style="color:var(--text2)">经验文档</span><FileText class="h-4 w-4" style="color:var(--primary)" /></div>
          <div class="mt-2 text-[26px] font-bold leading-none" style="color:var(--text)">{{ expCount }}</div>
          <div class="mt-1.5 text-[11.5px]" style="color:var(--text3)">{{ folderCount }} 个领域文件夹</div>
        </div>
        <div class="panel p-4">
          <div class="flex items-center justify-between"><span class="text-[12px]" style="color:var(--text2)">数据源</span><DatabaseZap class="h-4 w-4" style="color:var(--primary)" /></div>
          <div class="mt-2 text-[26px] font-bold leading-none" style="color:var(--text)">{{ dsCount }}</div>
          <div class="mt-1.5 text-[11.5px]" :style="{ color: dsErrorCount ? 'var(--warning)' : 'var(--success)' }">
            {{ dsErrorCount ? `${dsErrorCount} 个连接异常` : '全部连接正常' }}
          </div>
        </div>
      </div>

      <div class="grid grid-cols-3 gap-4">
        <!-- 最近模型 -->
        <div class="panel col-span-2">
          <div class="flex items-center justify-between border-b px-5 py-3.5" style="border-color:var(--border)">
            <h3 class="text-[13.5px] font-semibold" style="color:var(--text)">最近模型</h3>
            <button class="cursor-pointer text-[12px]" style="color:var(--primary);background:none;border:none" @click="router.push({ name: 'graph' })">查看全部 →</button>
          </div>
          <div v-if="models.length">
            <button
              v-for="(m, i) in models.slice(0, 5)"
              :key="m.id"
              class="card-hover flex w-full items-center gap-3 px-5 py-3.5 text-left"
              :class="i > 0 ? 'border-t' : ''"
              :style="i > 0 ? 'border-color:var(--border)' : ''"
              @click="router.push({ name: 'graph', query: { model: m.id } })"
            >
              <div class="flex h-9 w-9 items-center justify-center rounded-lg" style="background:var(--accent-bg);color:var(--accent-text)">
                <Waypoints class="h-4 w-4" />
              </div>
              <div class="min-w-0 flex-1">
                <div class="text-[13.5px] font-medium" style="color:var(--text)">{{ m.title || m.name || '未命名模型' }}</div>
                <div class="text-[11.5px]" style="color:var(--text3)">
                  {{ m.graphData?.nodes?.length || 0 }} 节点 · {{ m.graphData?.edges?.length || 0 }} 边
                </div>
              </div>
              <span class="text-[11.5px]" style="color:var(--text3)">{{ m.updated ? timeAgo(m.updated) : '' }}</span>
              <ArrowRight class="h-3.5 w-3.5 shrink-0" style="color:var(--text3)" />
            </button>
          </div>
          <UiEmpty
            v-else
            :icon="Waypoints"
            title="还没有血缘模型"
            description="先把访谈、文档、库结构沉淀进经验库，再一键构建业务血缘图。"
          >
            <button class="btn-primary" @click="router.push({ name: 'experiences', query: { import: '1' } })">
              <Upload :size="15" /> 去导入经验
            </button>
          </UiEmpty>
        </div>

        <!-- 待办 & 快速操作 -->
        <div class="space-y-4">
          <div class="panel p-5">
            <h3 class="mb-3 text-[13.5px] font-semibold" style="color:var(--text)">待办提醒</h3>
            <div class="space-y-2.5">
              <div
                v-for="(t, i) in todos"
                :key="i"
                class="flex items-start gap-2.5 rounded-lg p-3 transition hover:brightness-95"
                :class="t.to ? 'cursor-pointer' : ''"
                :style="{ background: t.bg }"
                @click="t.to && router.push(t.to)"
              >
                <component :is="t.icon" class="mt-0.5 h-3.5 w-3.5 shrink-0" :style="{ color: t.color }" />
                <div class="text-[12px] leading-relaxed" style="color:var(--text2)" v-html="t.html.replace(/<b>/g, `<b style='color:${t.color};font-weight:500'>`)" />
              </div>
            </div>
          </div>
          <div class="panel p-5">
            <h3 class="mb-3 text-[13.5px] font-semibold" style="color:var(--text)">快速操作</h3>
            <div class="grid grid-cols-2 gap-2">
              <button
                v-for="a in quickActions"
                :key="a.label"
                class="cursor-pointer rounded-lg border-none p-3 text-center transition hover:brightness-95"
                :style="a.accent ? 'background:var(--accent-bg)' : 'background:var(--nav-hover)'"
                @click="router.push(a.to)"
              >
                <component :is="a.icon" class="mx-auto mb-1.5 h-4 w-4" :style="a.accent ? 'color:var(--primary)' : 'color:var(--text2)'" />
                <div class="text-[12px]" :style="a.accent ? 'color:var(--accent-text)' : 'color:var(--text2)'">{{ a.label }}</div>
              </button>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
