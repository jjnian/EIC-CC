<script setup lang="ts">
// 修复：computed 未导入导致设置页 setup 抛 ReferenceError、页面空白
import { computed, onMounted, ref } from 'vue';
import {
  LoaderCircle, PlugZap, Cpu, SlidersHorizontal, Info, CircleCheck, CircleAlert,
  HardDrive, Download, BarChart3, RotateCcw, History, LayoutTemplate, Trash2, FileWarning,
  Activity, Layers, Palette,
} from 'lucide-vue-next';
import { listModels, testModel, updateModel } from '../../api/models';
import { getLlmMetrics, resetLlmMetrics, getHealth, type LlmMetrics, type HealthResponse } from '../../api/system';
import { deleteWorkspace, updateWorkspace, type Workspace } from '../../api/workspaces';
import { getConfig, type ConfigResponse } from '../../api/config';
import { getPrefs, savePrefs, type Prefs } from '../../api/prefs';
import { listOntologies, listVersions, restoreVersion, listGraphTemplates, deleteGraphTemplate, saveOntology } from '../../api/ontology';
import type { ModelConfig } from '../../types';
import { useToastStore } from '../stores/toast';
import { useWorkspaceStore } from '../stores/workspace';
import { timeAgo } from '../lib/format';
import { toggleTheme, isDark } from '../lib/theme';
import UiModal from '../components/UiModal.vue';

const toast = useToastStore();

const loading = ref(true);
const config = ref<ConfigResponse | null>(null);
const models = ref<ModelConfig[]>([]);
const prefs = ref<Prefs | null>(null);
const dark = ref(isDark());
const testing = ref<Record<string, string>>({});
const testMsg = ref<Record<string, string>>({});
const metrics = ref<LlmMetrics | null>(null); // LLM 用量统计
const toggling = ref<Record<string, boolean>>({}); // 启停请求进行中

// ── 系统监控 ──
const health = ref<HealthResponse | null>(null);

// ── 工作空间管理 ──
const wsStore = useWorkspaceStore();
const wsList = computed(() => wsStore.list);
const wsDeleteId = ref<string | null>(null);
const wsDeleteTarget = computed(() => wsList.value.find((w) => w.id === wsDeleteId.value));

// ── 版本历史 ──
const ontologies = ref<any[]>([]);
const verModelId = ref('');
const versions = ref<any[]>([]);
const versionsLoading = ref(false);
const restoring = ref(false);

// ── 模板库 ──
const templates = ref<any[]>([]);
const templatesLoading = ref(false);
const templateOpen = ref(false);

async function loadVersions() {
  if (!verModelId.value) { versions.value = []; return; }
  versionsLoading.value = true;
  try { versions.value = await listVersions(verModelId.value); }
  catch { versions.value = []; }
  finally { versionsLoading.value = false; }
}

async function doRestore(ts: number) {
  if (!verModelId.value || restoring.value) return;
  restoring.value = true;
  try {
    await restoreVersion(verModelId.value, ts);
    toast.success('版本已恢复');
    await loadVersions();
  } catch (e) { toast.error((e as Error).message); }
  finally { restoring.value = false; }
}

async function loadTemplates() {
  templateOpen.value = true;
  templatesLoading.value = true;
  try { templates.value = await listGraphTemplates(); }
  catch { templates.value = []; }
  finally { templatesLoading.value = false; }
}

async function removeTemplate(id: string) {
  try {
    await deleteGraphTemplate(id);
    templates.value = templates.value.filter((t: any) => t.id !== id);
    toast.success('模板已删除');
  } catch (e) { toast.error((e as Error).message); }
}

async function createFromTemplate(tpl: any) {
  try {
    const created = await saveOntology({ ...tpl, title: (tpl.title || '未命名') + ' (副本)', id: undefined });
    toast.success(`已创建模型「${created.title || created.id}」`);
    templateOpen.value = false;
  } catch (e) { toast.error((e as Error).message); }
}

// ── 工作空间管理 ──
async function setDefaultWs(w: Workspace) {
  try {
    await updateWorkspace(w.id, { isDefault: true });
    await wsStore.refresh();
    toast.success(`已将「${w.name}」设为默认`);
  } catch (e) { toast.error((e as Error).message); }
}

async function confirmDeleteWs() {
  if (!wsDeleteId.value) return;
  const id = wsDeleteId.value;
  wsDeleteId.value = null;
  try {
    await deleteWorkspace(id);
    await wsStore.refresh();
    toast.success('工作空间已删除');
  } catch (e) { toast.error((e as Error).message); }
}

onMounted(async () => {
  try {
    const [c, m, p, mt, onts, h] = await Promise.all([
      getConfig().catch(() => null),
      listModels().catch(() => []),
      getPrefs().catch(() => null),
      getLlmMetrics().catch(() => null),
      listOntologies().catch(() => []),
      getHealth().catch(() => null),
    ]);
    config.value = c;
    models.value = m;
    prefs.value = p;
    metrics.value = mt;
    ontologies.value = onts;
    health.value = h;
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

/* 运行时启停模型（内存生效，重启回落配置文件） */
async function toggleModel(m: ModelConfig) {
  if (toggling.value[m.id]) return;
  toggling.value[m.id] = true;
  try {
    await updateModel(m.id, { enabled: !m.enabled });
    models.value = models.value.map((x) => (x.id === m.id ? { ...x, enabled: !m.enabled } : x));
    toast.success(m.enabled ? `已停用 ${m.name}` : `已启用 ${m.name}`);
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    toggling.value[m.id] = false;
  }
}

/* 清零 LLM 用量统计 */
async function resetMetrics() {
  try {
    await resetLlmMetrics();
    metrics.value = { totalCalls: 0, totalErrors: 0, avgLatencyMs: 0, byModel: [] };
    toast.success('用量统计已清零');
  } catch (e) {
    toast.error((e as Error).message);
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
              <span class="ml-auto" />
              <span
                class="switch" :class="{ on: m.enabled }" :style="toggling[m.id] ? 'opacity:.5;pointer-events:none' : ''"
                title="运行时启停（重启后回落配置）" @click="toggleModel(m)"
              ><span class="knob" /></span>
              <button class="chip" :disabled="testing[m.id] === 'loading'" @click="test(m)">
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

      <!-- LLM 用量统计 -->
      <section>
        <h2 class="mb-4 text-[14.5px] font-semibold" style="color:var(--text)">
          <BarChart3 class="mr-1.5 inline h-4 w-4" style="color:var(--text3)" />LLM 用量统计
        </h2>
        <div class="panel p-5">
          <div v-if="metrics" class="grid grid-cols-3 gap-3">
            <div class="rounded-lg px-4 py-3" style="background:var(--nav-hover)">
              <div class="text-[18px] font-semibold" style="color:var(--text)">{{ metrics.totalCalls }}</div>
              <div class="mt-0.5 text-[12px]" style="color:var(--text3)">累计调用次数</div>
            </div>
            <div class="rounded-lg px-4 py-3" style="background:var(--nav-hover)">
              <div class="text-[18px] font-semibold" :style="{ color: metrics.totalErrors ? 'var(--danger)' : 'var(--text)' }">{{ metrics.totalErrors }}</div>
              <div class="mt-0.5 text-[12px]" style="color:var(--text3)">失败次数</div>
            </div>
            <div class="rounded-lg px-4 py-3" style="background:var(--nav-hover)">
              <div class="text-[18px] font-semibold" style="color:var(--text)">{{ metrics.avgLatencyMs }}<span class="text-[12px] font-normal" style="color:var(--text3)"> ms</span></div>
              <div class="mt-0.5 text-[12px]" style="color:var(--text3)">平均耗时</div>
            </div>
          </div>
          <table v-if="metrics && metrics.byModel.length" class="tbl mt-3 w-full">
            <thead>
              <tr><th>模型</th><th class="text-right">调用</th><th class="text-right">失败</th></tr>
            </thead>
            <tbody>
              <tr v-for="s in metrics.byModel" :key="s.model">
                <td style="font-family:var(--font-mono,monospace)">{{ s.model }}</td>
                <td class="text-right">{{ s.calls }}</td>
                <td class="text-right" :style="{ color: s.errors ? 'var(--danger)' : 'var(--text3)' }">{{ s.errors }}</td>
              </tr>
            </tbody>
          </table>
          <p v-else-if="metrics" class="mt-2 text-[12px]" style="color:var(--text3)">暂无调用记录（进程重启后重新计数）。</p>
          <p v-else class="mt-2 text-[12px]" style="color:var(--text3)">用量统计接口暂不可用。</p>
          <div class="mt-4 flex justify-end border-t pt-3" style="border-color:var(--border)">
            <button class="btn-ghost" :disabled="!metrics || metrics.totalCalls === 0" @click="resetMetrics">
              <RotateCcw class="h-3.5 w-3.5" />清零统计
            </button>
          </div>
        </div>
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

      <!-- 版本历史 -->
      <section>
        <h2 class="mb-4 text-[14.5px] font-semibold" style="color:var(--text)">
          <History class="mr-1.5 inline h-4 w-4" style="color:var(--text3)" />版本历史
        </h2>
        <div class="panel p-5">
          <div class="mb-3 flex items-center gap-2">
            <select v-model="verModelId" class="input !h-8 !w-52 !text-[12.5px]" @change="loadVersions">
              <option value="">选择本体模型…</option>
              <option v-for="o in ontologies" :key="o.id" :value="o.id">{{ o.title || o.name || o.id }}</option>
            </select>
          </div>
          <div v-if="!verModelId" class="text-[13px]" style="color:var(--text3)">选择模型后查看历史版本快照。</div>
          <div v-else-if="versionsLoading" class="flex justify-center py-4" style="color:var(--text3)"><LoaderCircle :size="16" class="animate-spin" /></div>
          <div v-else-if="!versions.length" class="text-[13px]" style="color:var(--text3)">暂无历史版本。</div>
          <div v-else class="space-y-2">
            <div v-for="v in versions" :key="v.timestamp" class="flex items-center justify-between rounded-lg px-3 py-2" style="background:var(--nav-hover)">
              <div>
                <div class="text-[13px]" style="color:var(--text)">{{ new Date(v.timestamp).toLocaleString() }}</div>
                <div class="text-[11px]" style="color:var(--text3)">{{ v.nodeCount }} 节点 · {{ v.edgeCount }} 边 · {{ ((v.fileSize || 0) / 1024).toFixed(1) }} KB</div>
              </div>
              <button class="btn-secondary" :disabled="restoring" @click="doRestore(v.timestamp)">恢复</button>
            </div>
          </div>
        </div>
      </section>

      <!-- 模板库 -->
      <section>
        <h2 class="mb-4 text-[14.5px] font-semibold" style="color:var(--text)">
          <LayoutTemplate class="mr-1.5 inline h-4 w-4" style="color:var(--text3)" />模板库
        </h2>
        <div class="panel p-5">
          <p class="text-[13px]" style="color:var(--text3)">保存常用本体模型为模板，快速复用创建新模型。</p>
          <button class="btn-ghost mt-2" @click="loadTemplates">浏览模板库</button>
        </div>
      </section>

      <!-- 模板库弹窗 -->
      <UiModal :open="templateOpen" title="模板库" width="540px" @close="templateOpen = false">
        <div v-if="templatesLoading" class="flex justify-center py-8" style="color:var(--text3)"><LoaderCircle :size="18" class="animate-spin" /></div>
        <div v-else-if="!templates.length" class="py-6 text-center text-[13px]" style="color:var(--text3)">暂无模板。在图谱页可将当前模型「存为模板」。</div>
        <div v-else class="space-y-2">
          <div v-for="t in templates" :key="t.id" class="flex items-center justify-between rounded-lg px-3 py-2.5" style="background:var(--nav-hover)">
            <div class="flex-1 cursor-pointer" @click="createFromTemplate(t)">
              <div class="text-[13.5px] font-medium" style="color:var(--text)">{{ t.title || t.name || '未命名' }}</div>
              <div class="text-[11px]" style="color:var(--text3)">
                {{ t.graphData?.nodes?.length || 0 }} 节点 · {{ t.graphData?.edges?.length || 0 }} 边
                <span v-if="t.desc"> · {{ t.desc }}</span>
              </div>
            </div>
            <button class="rounded-md p-1.5 transition hover:brightness-95" style="color:var(--text3)" title="删除模板" @click.stop="removeTemplate(t.id)">
              <Trash2 :size="14" />
            </button>
          </div>
        </div>
      </UiModal>

      <!-- 系统监控 -->
      <section>
        <h2 class="mb-4 text-[14.5px] font-semibold" style="color:var(--text)">
          <Activity class="mr-1.5 inline h-4 w-4" style="color:var(--text3)" />系统监控
        </h2>
        <div class="panel p-5">
          <div v-if="health" class="grid grid-cols-4 gap-3">
            <div class="rounded-lg px-3 py-2.5 text-center" style="background:var(--nav-hover)">
              <div class="text-[15px] font-semibold" :style="{ color: health.status === 'UP' ? 'var(--success)' : 'var(--warning)' }">{{ health.status === 'UP' ? '正常' : '降级' }}</div>
              <div class="text-[11px]" style="color:var(--text3)">服务状态</div>
            </div>
            <div class="rounded-lg px-3 py-2.5 text-center" style="background:var(--nav-hover)">
              <div class="text-[15px] font-semibold" style="color:var(--text)">{{ health.freeMemMb }}<span class="text-[11px] font-normal" style="color:var(--text3)"> MB</span></div>
              <div class="text-[11px]" style="color:var(--text3)">空闲内存</div>
            </div>
            <div class="rounded-lg px-3 py-2.5 text-center" style="background:var(--nav-hover)">
              <div class="text-[15px] font-semibold" style="color:var(--text)">{{ health.totalMemMb }}<span class="text-[11px] font-normal" style="color:var(--text3)"> MB</span></div>
              <div class="text-[11px]" style="color:var(--text3)">JVM 堆</div>
            </div>
            <div class="rounded-lg px-3 py-2.5 text-center" style="background:var(--nav-hover)">
              <div class="text-[15px] font-semibold" style="color:var(--text)">{{ (health.uptimeMs / 3600000).toFixed(1) }}<span class="text-[11px] font-normal" style="color:var(--text3)"> h</span></div>
              <div class="text-[11px]" style="color:var(--text3)">运行时长</div>
            </div>
          </div>
          <p v-else class="text-[13px]" style="color:var(--text3)">系统监控接口暂不可用。</p>
        </div>
      </section>

      <!-- 工作空间管理 -->
      <section>
        <h2 class="mb-4 text-[14.5px] font-semibold" style="color:var(--text)">
          <Layers class="mr-1.5 inline h-4 w-4" style="color:var(--text3)" />工作空间管理
        </h2>
        <div class="panel">
          <div v-if="!wsList.length" class="px-5 py-4 text-[13px]" style="color:var(--text3)">暂无工作空间。</div>
          <div v-for="w in wsList" :key="w.id" class="flex items-center gap-3 border-b px-5 py-3.5 last:border-0" style="border-color:var(--border)">
            <div class="flex-1 min-w-0">
              <div class="flex items-center gap-2">
                <span class="text-[13.5px] font-medium truncate" style="color:var(--text)">{{ w.name }}</span>
                <span v-if="w.isDefault" class="badge" style="background:var(--accent-bg);color:var(--accent-text)">默认</span>
              </div>
              <div class="text-[11.5px] truncate" style="color:var(--text3)">{{ w.description || '—' }}</div>
            </div>
            <button v-if="!w.isDefault" class="btn-ghost text-[12px]" @click="setDefaultWs(w)">设为默认</button>
            <span v-else class="text-[11px]" style="color:var(--text3)">当前默认</span>
            <button class="rounded-md p-1.5 transition hover:brightness-95" style="color:var(--text3)" title="删除" :disabled="w.isDefault" @click="wsDeleteId = w.id">
              <Trash2 :size="14" />
            </button>
          </div>
        </div>
      </section>

      <!-- 外观设置 -->
      <section>
        <h2 class="mb-4 text-[14.5px] font-semibold" style="color:var(--text)">
          <Palette class="mr-1.5 inline h-4 w-4" style="color:var(--text3)" />外观设置
        </h2>
        <div class="panel">
          <div class="flex items-center justify-between border-b px-5 py-4" style="border-color:var(--border)">
            <div>
              <div class="text-[13.5px] font-medium" style="color:var(--text)">主题模式</div>
              <div class="text-[12px]" style="color:var(--text3)">切换亮色 / 暗色主题（顶栏按钮亦可切换）</div>
            </div>
            <button class="btn-ghost" @click="toggleTheme(); dark = isDark()">
              {{ dark ? '☀ 亮色模式' : '🌙 暗色模式' }}
            </button>
          </div>
          <div v-if="prefs" class="flex items-center justify-between px-5 py-4">
            <div>
              <div class="text-[13.5px] font-medium" style="color:var(--text)">图谱字号</div>
              <div class="text-[12px]" style="color:var(--text3)">当前 · <span style="color:var(--primary)">{{ prefs.graphFontSize || 12 }}px</span></div>
            </div>
            <input type="range" min="10" max="18" step="1" class="w-32 accent-[var(--primary)]" :value="prefs.graphFontSize || 12" @input="onFont" />
          </div>
        </div>
      </section>

      <!-- 工作空间删除确认弹窗 -->
      <UiModal :open="!!wsDeleteId" title="删除工作空间" width="420px" @close="wsDeleteId = null">
        <p class="text-[13px] leading-relaxed" style="color:var(--danger)">
          确定删除「{{ wsDeleteTarget?.name }}」吗？其中的模型、经验引用都会被清理，此操作不可恢复。
        </p>
        <template #footer>
          <div class="flex justify-end gap-2">
            <button class="btn-secondary" @click="wsDeleteId = null">取消</button>
            <button class="btn-danger" @click="confirmDeleteWs">确认删除</button>
          </div>
        </template>
      </UiModal>

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
