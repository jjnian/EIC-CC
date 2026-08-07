<script setup lang="ts">
import { onMounted, ref } from 'vue';
import {
  LoaderCircle, PlugZap, Cpu, SlidersHorizontal, Info, CircleCheck, CircleAlert,
  HardDrive, Download,
} from 'lucide-vue-next';
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

/* switch 开关切换（对齐原型 .switch.on） */
function togglePref(key: 'showEdgeLabels' | 'autoFit') {
  if (!prefs.value) return;
  const cur = prefs.value[key] !== false;
  patchPrefs({ [key]: !cur } as Partial<Prefs>);
}

function onFont(ev: Event) {
  patchPrefs({ graphFontSize: Number((ev.target as HTMLInputElement).value) });
}

/* 导出偏好为 JSON 文件（纯前端下载） */
async function exportPrefs() {
  try {
    const p = await getPrefs();
    const blob = new Blob([JSON.stringify(p, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'eic-cc-prefs.json';
    a.click();
    URL.revokeObjectURL(url);
    toast.success('偏好设置 JSON 已导出');
  } catch (e) {
    toast.error((e as Error).message);
  }
}
</script>

<template>
  <div class="mx-auto max-w-3xl space-y-8">
    <div v-if="loading" class="flex justify-center py-20" style="color:var(--text3)"><LoaderCircle :size="22" class="animate-spin" /></div>

    <template v-else>
      <!-- LLM 模型 -->
      <section>
        <h2 class="mb-4 text-[14.5px] font-semibold" style="color:var(--text)">
          <Cpu class="mr-1.5 inline h-4 w-4" style="color:var(--text3)" />LLM 模型
        </h2>

        <div v-if="config" class="panel mb-4 flex items-center gap-3 px-5 py-3.5">
          <span class="badge" style="background:var(--accent-bg);color:var(--accent-text)">当前默认</span>
          <span class="text-[13.5px] font-medium" style="color:var(--text)">{{ config.provider }} / {{ config.modelName }}</span>
          <span class="ml-auto text-[12px]" style="color:var(--text3)">{{ config.baseUrl }}</span>
        </div>

        <div v-if="models.length" class="space-y-4">
          <div v-for="m in models" :key="m.id" class="panel p-5">
            <div class="flex items-center gap-2.5">
              <span class="text-[14px] font-semibold" :style="{ color: m.enabled ? 'var(--text)' : 'var(--text3)' }">{{ m.name }}</span>
              <span v-if="m.enabled" class="badge" style="background:rgba(16,185,129,.1);color:#10B981">启用</span>
              <span v-else class="badge" style="background:var(--nav-hover);color:var(--text3)">停用</span>
              <button class="chip ml-auto" :disabled="testing[m.id] === 'loading'" @click="test(m)">
                <LoaderCircle v-if="testing[m.id] === 'loading'" :size="13" class="animate-spin" />
                <PlugZap v-else class="h-3.5 w-3.5" style="color:var(--primary)" /> 测试连接
              </button>
            </div>
            <div class="mt-1.5 text-[12.5px]" style="color:var(--text3)">{{ m.provider || 'custom' }} · {{ m.modelName }} · {{ m.baseUrl }}</div>
            <div v-if="m.capabilities?.length" class="mt-2 flex flex-wrap gap-1.5">
              <span v-for="c in m.capabilities" :key="c" class="badge" style="background:var(--nav-hover);color:var(--text2)">{{ c }}</span>
            </div>
            <div
              v-if="testing[m.id] && testing[m.id] !== 'loading'"
              class="mt-2 flex items-center gap-1.5 text-[12.5px]"
              :style="{ color: testing[m.id] === 'ok' ? 'var(--success)' : 'var(--danger)' }"
            >
              <CircleCheck v-if="testing[m.id] === 'ok'" class="h-3.5 w-3.5" />
              <CircleAlert v-else class="h-3.5 w-3.5" />
              {{ testing[m.id] === 'ok' ? `连接成功（${testMsg[m.id]}）` : `连接失败：${testMsg[m.id]}` }}
            </div>
          </div>
        </div>
        <p v-else class="panel px-5 py-4 text-[13px]" style="color:var(--text3)">暂无自定义模型配置，当前使用环境变量注入的默认模型。</p>
      </section>

      <!-- 图谱偏好 -->
      <section v-if="prefs">
        <h2 class="mb-4 text-[14.5px] font-semibold" style="color:var(--text)">
          <SlidersHorizontal class="mr-1.5 inline h-4 w-4" style="color:var(--text3)" />图谱偏好
        </h2>
        <div class="panel">
          <div class="flex items-center justify-between border-b px-5 py-4" style="border-color:var(--border)">
            <div>
              <div class="text-[13.5px] font-medium" style="color:var(--text)">显示边标签</div>
              <div class="text-[12px]" style="color:var(--text3)">在图谱画布上展示关系名称</div>
            </div>
            <span class="switch" :class="{ on: prefs.showEdgeLabels !== false }" @click="togglePref('showEdgeLabels')"><span class="knob" /></span>
          </div>
          <div class="flex items-center justify-between border-b px-5 py-4" style="border-color:var(--border)">
            <div>
              <div class="text-[13.5px] font-medium" style="color:var(--text)">自动适应视图</div>
              <div class="text-[12px]" style="color:var(--text3)">加载图谱后自动缩放至全图可见</div>
            </div>
            <span class="switch" :class="{ on: prefs.autoFit !== false }" @click="togglePref('autoFit')"><span class="knob" /></span>
          </div>
          <div class="flex items-center justify-between px-5 py-4">
            <div>
              <div class="text-[13.5px] font-medium" style="color:var(--text)">图谱字号</div>
              <div class="text-[12px]" style="color:var(--text3)">节点文字大小 · <span style="color:var(--primary)">{{ prefs.graphFontSize || 12 }}px</span></div>
            </div>
            <input
              type="range" min="10" max="18" step="1" class="w-32 accent-[var(--primary)]"
              :value="prefs.graphFontSize || 12" @input="onFont"
            />
          </div>
        </div>
      </section>

      <!-- 数据管理 -->
      <section>
        <h2 class="mb-4 text-[14.5px] font-semibold" style="color:var(--text)">
          <HardDrive class="mr-1.5 inline h-4 w-4" style="color:var(--text3)" />数据管理
        </h2>
        <div class="panel p-5">
          <div class="flex items-center justify-between">
            <div>
              <div class="text-[13.5px] font-medium" style="color:var(--text)">导出全部偏好设置</div>
              <div class="text-[12px]" style="color:var(--text3)">把当前偏好（图谱等）下载为 JSON 文件</div>
            </div>
            <button class="btn-ghost" @click="exportPrefs"><Download class="h-3.5 w-3.5" />下载 JSON</button>
          </div>
        </div>
      </section>

      <!-- 关于 -->
      <section>
        <h2 class="mb-4 text-[14.5px] font-semibold" style="color:var(--text)">
          <Info class="mr-1.5 inline h-4 w-4" style="color:var(--text3)" />关于
        </h2>
        <div class="panel px-5 py-4 text-[13px] leading-relaxed" style="color:var(--text2)">
          推演平台（EIC-CC）· 基于本体图谱的 AI 业务血缘建模系统。<br />
          前端：Vue 3 + Vite + TypeScript + Tailwind CSS · 后端：Spring Boot 3 + PostgreSQL + MinIO。
        </div>
      </section>
    </template>
  </div>
</template>
