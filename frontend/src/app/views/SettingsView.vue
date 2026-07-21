<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { LoaderCircle, PlugZap, Cpu, SlidersHorizontal, Info, CircleCheck, CircleAlert } from 'lucide-vue-next';
import { listModels, testModel } from '../../api/models';
import { getConfig, type ConfigResponse } from '../../api/config';
import { getPrefs, savePrefs, type Prefs } from '../../api/prefs';
import type { ModelConfig } from '../../types';
import { useToastStore } from '../stores/toast';

const toast = useToastStore();

const loading = ref(true);
const config = ref<ConfigResponse | null>(null);
const models = ref<ModelConfig[]>([]);
const prefs = ref<Prefs | null>(null);
const testing = ref<Record<string, string>>({});
const testMsg = ref<Record<string, string>>({});

onMounted(async () => {
  try {
    const [c, m, p] = await Promise.all([
      getConfig().catch(() => null),
      listModels().catch(() => []),
      getPrefs().catch(() => null),
    ]);
    config.value = c;
    models.value = m;
    prefs.value = p;
  } finally {
    loading.value = false;
  }
});

async function test(m: ModelConfig) {
  testing.value[m.id] = 'loading';
  try {
    const r = await testModel(m.id);
    testing.value[m.id] = r.status === 'ok' ? 'ok' : 'error';
    testMsg.value[m.id] = r.status === 'ok' ? `${r.latencyMs}ms` : (r.error || '连接失败');
  } catch (e) {
    testing.value[m.id] = 'error';
    testMsg.value[m.id] = (e as Error).message;
  }
}

let prefsTimer: ReturnType<typeof setTimeout> | null = null;
function patchPrefs(patch: Partial<Prefs>) {
  if (!prefs.value) return;
  prefs.value = { ...prefs.value, ...patch };
  if (prefsTimer) clearTimeout(prefsTimer);
  prefsTimer = setTimeout(() => {
    savePrefs(patch).then(() => toast.success('偏好已保存')).catch((e) => toast.error((e as Error).message));
  }, 500);
}

function onCheck(key: 'showEdgeLabels' | 'autoFit', ev: Event) {
  patchPrefs({ [key]: (ev.target as HTMLInputElement).checked } as Partial<Prefs>);
}

function onFont(ev: Event) {
  patchPrefs({ graphFontSize: Number((ev.target as HTMLInputElement).value) });
}
</script>

<template>
  <div class="mx-auto max-w-3xl px-6 py-8">
    <div v-if="loading" class="flex justify-center py-20 text-slate-400"><LoaderCircle :size="22" class="animate-spin" /></div>

    <template v-else>
      <!-- LLM 模型 -->
      <section class="mb-8">
        <div class="mb-3 flex items-center gap-2">
          <Cpu :size="16" class="text-slate-400" />
          <h2 class="text-[15px] font-semibold text-slate-800">LLM 模型</h2>
        </div>

        <div v-if="config" class="card mb-3 flex items-center gap-3 px-5 py-3.5">
          <span class="badge bg-indigo-50 text-indigo-700">当前默认</span>
          <span class="text-[13.5px] text-slate-700">{{ config.provider }} / {{ config.modelName }}</span>
          <span class="ml-auto truncate text-xs text-slate-400">{{ config.baseUrl }}</span>
        </div>

        <div v-if="models.length" class="space-y-3">
          <div v-for="m in models" :key="m.id" class="card p-5">
            <div class="flex items-center gap-2.5">
              <span class="text-[14.5px] font-semibold text-slate-800">{{ m.name }}</span>
              <span v-if="m.enabled" class="badge bg-emerald-50 text-emerald-700">启用</span>
              <span v-else class="badge bg-slate-100 text-slate-500">停用</span>
              <button class="btn-secondary btn-sm ml-auto" :disabled="testing[m.id] === 'loading'" @click="test(m)">
                <LoaderCircle v-if="testing[m.id] === 'loading'" :size="13" class="animate-spin" />
                <PlugZap v-else :size="13" /> 测试连接
              </button>
            </div>
            <div class="mt-2 text-[12.5px] text-slate-400">{{ m.provider || 'custom' }} · {{ m.modelName }} · {{ m.baseUrl }}</div>
            <div v-if="m.capabilities?.length" class="mt-2 flex flex-wrap gap-1.5">
              <span v-for="c in m.capabilities" :key="c" class="badge bg-slate-100 text-slate-500">{{ c }}</span>
            </div>
            <div v-if="testing[m.id] && testing[m.id] !== 'loading'" class="mt-2.5 flex items-center gap-1.5 text-[12.5px]" :class="testing[m.id] === 'ok' ? 'text-emerald-600' : 'text-rose-500'">
              <CircleCheck v-if="testing[m.id] === 'ok'" :size="14" />
              <CircleAlert v-else :size="14" />
              {{ testing[m.id] === 'ok' ? `连接成功（${testMsg[m.id]}）` : `连接失败：${testMsg[m.id]}` }}
            </div>
          </div>
        </div>
        <p v-else class="card px-5 py-4 text-[13px] text-slate-400">暂无自定义模型配置，当前使用环境变量注入的默认模型。</p>
      </section>

      <!-- 偏好 -->
      <section v-if="prefs" class="mb-8">
        <div class="mb-3 flex items-center gap-2">
          <SlidersHorizontal :size="16" class="text-slate-400" />
          <h2 class="text-[15px] font-semibold text-slate-800">图谱偏好</h2>
        </div>
        <div class="card divide-y divide-slate-100">
          <label class="flex cursor-pointer items-center justify-between px-5 py-3.5">
            <div>
              <div class="text-[13.5px] font-medium text-slate-700">显示边标签</div>
              <div class="text-xs text-slate-400">在图谱画布上展示关系名称</div>
            </div>
            <input type="checkbox" class="h-4 w-4 accent-indigo-600" :checked="prefs.showEdgeLabels !== false" @change="onCheck('showEdgeLabels', $event)" />
          </label>
          <label class="flex cursor-pointer items-center justify-between px-5 py-3.5">
            <div>
              <div class="text-[13.5px] font-medium text-slate-700">打开模型自动适应视图</div>
              <div class="text-xs text-slate-400">加载图谱后自动缩放至全图可见</div>
            </div>
            <input type="checkbox" class="h-4 w-4 accent-indigo-600" :checked="prefs.autoFit !== false" @change="onCheck('autoFit', $event)" />
          </label>
          <div class="flex items-center justify-between px-5 py-3.5">
            <div>
              <div class="text-[13.5px] font-medium text-slate-700">图谱字号</div>
              <div class="text-xs text-slate-400">{{ prefs.graphFontSize || 12 }}px</div>
            </div>
            <input type="range" min="10" max="18" step="1" class="w-40 accent-indigo-600" :value="prefs.graphFontSize || 12" @input="onFont" />
          </div>
        </div>
      </section>

      <!-- 关于 -->
      <section>
        <div class="mb-3 flex items-center gap-2">
          <Info :size="16" class="text-slate-400" />
          <h2 class="text-[15px] font-semibold text-slate-800">关于</h2>
        </div>
        <div class="card px-5 py-4 text-[13px] leading-relaxed text-slate-500">
          推演平台（EIC-CC）· 基于本体图谱的 AI 业务血缘建模系统。<br />
          前端：Vue 3 + Vite + TypeScript + Tailwind CSS · 后端：Spring Boot 3 + PostgreSQL + MinIO。
        </div>
      </section>
    </template>
  </div>
</template>
