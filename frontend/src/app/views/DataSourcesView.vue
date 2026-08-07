<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import {
  Plus, Database, Globe, Trash2, LoaderCircle, PlugZap, Table2, Search,
  Layers, RefreshCw, DatabaseZap, CheckCircle2, AlertTriangle, FilterX, RotateCw,
} from 'lucide-vue-next';
import {
  listDataSources, createDataSource, deleteDataSource, testDataSource,
  listTables, previewTable,
  type DataSource, type DataSourceKind, type TablePreview,
} from '../../api/dataSources';
import { createExperienceFromDdl } from '../../api/experiences';
import { useToastStore } from '../stores/toast';
import { timeAgo } from '../lib/format';
import UiModal from '../components/UiModal.vue';
import UiDrawer from '../components/UiDrawer.vue';
import UiEmpty from '../components/UiEmpty.vue';

const route = useRoute();
const toast = useToastStore();

const DB_KINDS: { value: DataSourceKind; label: string }[] = [
  { value: 'mysql', label: 'MySQL' },
  { value: 'pgsql', label: 'PostgreSQL' },
  { value: 'oracle', label: 'Oracle' },
  { value: 'dm', label: '达梦 DM' },
  { value: 'gbase', label: 'GBase' },
  { value: 'https_api', label: 'HTTP 接口' },
];
const DB_SET = new Set(['mysql', 'pgsql', 'oracle', 'dm', 'gbase']);
const DEFAULT_PORTS: Record<string, string> = { mysql: '3306', pgsql: '5432', oracle: '1521', dm: '5236', gbase: '5258' };
/* 引擎图标配色（内联 rgba，双主题通用，取自原型） */
const KIND_COLORS: Record<string, string> = {
  mysql: '#6366F1', pgsql: '#0EA5E9', oracle: '#EF4444', dm: '#F59E0B', gbase: '#8B5CF6', https_api: '#10B981',
};

const loading = ref(true);
const list = ref<DataSource[]>([]);
const testing = ref<Record<string, boolean>>({});

// 筛选 / 排序
const q = ref('');
const statusF = ref('');
const engineF = ref('');
const sortBy = ref('status');

// 新建
const createOpen = ref(false);
const kind = ref<DataSourceKind>('mysql');
const name = ref('');
const cfg = ref<Record<string, any>>({});
const saving = ref(false);

// 详情
const detail = ref<DataSource | null>(null);
const tables = ref<string[] | null>(null);
const tablesLoading = ref(false);
const preview = ref<{ name: string; data: TablePreview } | null>(null);

const isDb = computed(() => DB_SET.has(kind.value));
const canSave = computed(() => {
  if (!name.value.trim()) return false;
  if (isDb.value) return !!(cfg.value.host && cfg.value.port && cfg.value.username && (kind.value === 'dm' || cfg.value.database));
  return !!cfg.value.url;
});

const statusOf = (ds: DataSource) => ds.status || 'idle';

const filtered = computed(() => {
  const kw = q.value.trim().toLowerCase();
  let arr = list.value
    .filter((ds) => !statusF.value || statusOf(ds) === statusF.value)
    .filter((ds) => !engineF.value || ds.kind === engineF.value)
    .filter((ds) => !kw || ds.name.toLowerCase().includes(kw)
      || String(ds.config?.host || '').toLowerCase().includes(kw)
      || String(ds.config?.database || '').toLowerCase().includes(kw));
  if (sortBy.value === 'name') arr = [...arr].sort((a, b) => a.name.localeCompare(b.name, 'zh'));
  else arr = [...arr].sort((a, b) => ['connected', 'error', 'idle'].indexOf(statusOf(a)) - ['connected', 'error', 'idle'].indexOf(statusOf(b)));
  return arr;
});

const statusCounts = computed(() => ({
  all: list.value.length,
  connected: list.value.filter((d) => statusOf(d) === 'connected').length,
  error: list.value.filter((d) => statusOf(d) === 'error').length,
  idle: list.value.filter((d) => statusOf(d) === 'idle').length,
}));
const engineCounts = computed(() => {
  const m: Record<string, number> = {};
  for (const k of DB_KINDS.map((x) => x.value)) m[k] = list.value.filter((d) => d.kind === k).length;
  return m;
});

onMounted(async () => {
  q.value = (route.query.q as string) || '';
  await reload();
  if (route.query.new) openCreate();
});

async function reload() {
  loading.value = true;
  try {
    list.value = await listDataSources();
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  kind.value = 'mysql';
  name.value = '';
  cfg.value = { port: DEFAULT_PORTS.mysql };
  createOpen.value = true;
}

function pickKind(k: DataSourceKind) {
  kind.value = k;
  cfg.value = DB_SET.has(k) ? { port: DEFAULT_PORTS[k] } : { method: 'GET', timeoutMs: 15000 };
}

async function submitCreate() {
  saving.value = true;
  try {
    await createDataSource({ name: name.value.trim(), kind: kind.value, config: { ...cfg.value } });
    createOpen.value = false;
    toast.success('数据源已创建');
    reload();
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    saving.value = false;
  }
}

async function test(ds: DataSource) {
  testing.value[ds.id] = true;
  try {
    const r = await testDataSource(ds.id);
    if (r.success) toast.success(`「${ds.name}」连接成功${r.latencyMs != null ? `（${r.latencyMs}ms）` : ''}`);
    else toast.error(`「${ds.name}」连接失败：${r.message || '未知错误'}`);
    reload();
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    testing.value[ds.id] = false;
  }
}

/* 全部测试：逐个探测并汇总 */
async function testAll() {
  if (!list.value.length) return;
  let ok = 0; let bad = 0;
  for (const ds of list.value) {
    try {
      const r = await testDataSource(ds.id);
      if (r.success) ok++; else bad++;
    } catch { bad++; }
  }
  toast.info(`已测试 ${list.value.length} 个数据源：${ok} 正常，${bad} 异常`);
  reload();
}

/* 同步 DDL 到经验库 */
async function syncDdl(ds: DataSource) {
  try {
    await createExperienceFromDdl(ds.id);
    toast.success(`「${ds.name}」DDL 已同步到经验库`);
  } catch (e) {
    toast.error((e as Error).message);
  }
}

async function remove(ds: DataSource) {
  if (!window.confirm(`确定删除数据源「${ds.name}」？`)) return;
  try {
    await deleteDataSource(ds.id);
    if (detail.value?.id === ds.id) detail.value = null;
    toast.success('已删除');
    reload();
  } catch (e) {
    toast.error((e as Error).message);
  }
}

async function openDetail(ds: DataSource) {
  detail.value = ds;
  tables.value = null;
  if (DB_SET.has(ds.kind)) {
    tablesLoading.value = true;
    try {
      tables.value = await listTables(ds.id);
    } catch {
      tables.value = null;
    } finally {
      tablesLoading.value = false;
    }
  }
}

async function showPreview(t: string) {
  if (!detail.value) return;
  try {
    const data = await previewTable(detail.value.id, t, 50);
    preview.value = { name: t, data };
  } catch (e) {
    toast.error((e as Error).message);
  }
}

function kindLabel(k: string) {
  return DB_KINDS.find((x) => x.value === k)?.label || k;
}

/* 卡片 host 行 */
function hostLine(ds: DataSource) {
  const c = ds.config || {};
  if (DB_SET.has(ds.kind)) return `${c.host || ''}:${c.port || ''}${c.database ? ' / ' + c.database : ''}`;
  return String(c.url || '');
}

function configEntries(ds: DataSource): [string, string][] {
  const c = ds.config || {};
  return Object.entries(c)
    .filter(([k]) => k !== 'schedule')
    .map(([k, v]) => [
      k,
      k.toLowerCase().includes('password') || k.toLowerCase().includes('key')
        ? '••••••'
        : typeof v === 'object' ? JSON.stringify(v) : String(v),
    ]);
}

function statusColor(s: string) {
  return s === 'connected' ? '#10B981' : s === 'error' ? '#F43F5E' : '#F59E0B';
}
function statusLabel(s: string) {
  return s === 'connected' ? '正常' : s === 'error' ? '异常' : '待探测';
}
</script>

<template>
  <div class="flex h-full flex-col">
    <!-- 顶部工具栏 -->
    <div class="mb-4 flex items-center gap-3">
      <div class="relative w-64">
        <Search class="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2" style="color:var(--text3)" />
        <input v-model="q" class="input !pl-9" placeholder="搜索名称、Host、数据库…" />
      </div>
      <select v-model="sortBy" class="input !h-[34px] !w-auto">
        <option value="status">按状态排序</option>
        <option value="name">按名称排序</option>
      </select>
      <div class="ml-auto flex items-center gap-2">
        <button class="btn-ghost" @click="testAll"><PlugZap class="h-3.5 w-3.5" />全部测试</button>
        <button class="btn-primary" @click="openCreate"><Plus class="h-3.5 w-3.5" />接入数据源</button>
      </div>
    </div>

    <div class="flex min-h-0 flex-1 gap-0">
      <!-- 左侧筛选栏 -->
      <aside class="w-[200px] shrink-0 overflow-y-auto border-r p-3" style="border-color:var(--border)">
        <div class="ds-nav" :class="{ active: !statusF && !engineF }" @click="statusF = ''; engineF = ''">
          <Layers class="h-4 w-4" />全部数据源<span class="ds-nav-count">{{ statusCounts.all }}</span>
        </div>

        <div class="ds-nav-group">连接状态</div>
        <div class="ds-nav" :class="{ active: statusF === 'connected' }" @click="statusF = statusF === 'connected' ? '' : 'connected'; engineF = ''">
          <span class="ds-dot" style="background:#10B981" />正常<span class="ds-nav-count">{{ statusCounts.connected }}</span>
        </div>
        <div class="ds-nav" :class="{ active: statusF === 'error' }" @click="statusF = statusF === 'error' ? '' : 'error'; engineF = ''">
          <span class="ds-dot" style="background:#F43F5E" />异常<span class="ds-nav-count">{{ statusCounts.error }}</span>
        </div>
        <div class="ds-nav" :class="{ active: statusF === 'idle' }" @click="statusF = statusF === 'idle' ? '' : 'idle'; engineF = ''">
          <span class="ds-dot" style="background:#F59E0B" />待探测<span class="ds-nav-count">{{ statusCounts.idle }}</span>
        </div>

        <div class="ds-nav-group">数据源类型</div>
        <div
          v-for="k in DB_KINDS"
          :key="k.value"
          class="ds-nav"
          :class="{ active: engineF === k.value }"
          @click="engineF = engineF === k.value ? '' : k.value; statusF = ''"
        >
          <Database v-if="DB_SET.has(k.value)" class="h-4 w-4" :style="{ color: KIND_COLORS[k.value] }" />
          <Globe v-else class="h-4 w-4" :style="{ color: KIND_COLORS[k.value] }" />
          {{ k.label }}<span class="ds-nav-count">{{ engineCounts[k.value] || 0 }}</span>
        </div>

        <div class="mt-4 border-t pt-3" style="border-color:var(--border)">
          <div class="ds-nav" @click="statusF = ''; engineF = ''"><FilterX class="h-4 w-4" />清除筛选</div>
        </div>
      </aside>

      <!-- 右侧内容区 -->
      <div class="min-h-0 flex-1 overflow-y-auto p-5">
        <!-- 统计概览 -->
        <div class="mb-4 grid grid-cols-4 gap-2.5">
          <div class="ds-stat">
            <span class="ds-stat-icon" style="background:var(--accent-bg);color:var(--accent-text)"><DatabaseZap class="h-4 w-4" /></span>
            <div><div class="ds-stat-num">{{ statusCounts.all }}</div><div class="ds-stat-label">数据源总数</div></div>
          </div>
          <div class="ds-stat">
            <span class="ds-stat-icon" style="background:rgba(16,185,129,.1);color:#10B981"><CheckCircle2 class="h-4 w-4" /></span>
            <div><div class="ds-stat-num" style="color:#10B981">{{ statusCounts.connected }}</div><div class="ds-stat-label">连接正常</div></div>
          </div>
          <div class="ds-stat">
            <span class="ds-stat-icon" style="background:rgba(244,63,94,.1);color:#F43F5E"><AlertTriangle class="h-4 w-4" /></span>
            <div><div class="ds-stat-num" style="color:#F43F5E">{{ statusCounts.error }}</div><div class="ds-stat-label">连接异常</div></div>
          </div>
          <div class="ds-stat">
            <span class="ds-stat-icon" style="background:rgba(245,158,11,.1);color:#F59E0B"><PlugZap class="h-4 w-4" /></span>
            <div><div class="ds-stat-num">{{ statusCounts.idle }}</div><div class="ds-stat-label">待探测</div></div>
          </div>
        </div>

        <!-- 结果计数 -->
        <div class="mb-3 flex items-center gap-2 text-[11.5px]" style="color:var(--text3)">
          <span>共 {{ filtered.length }} 个数据源</span>
          <span v-if="statusF || engineF" class="flex items-center gap-1 rounded px-2 py-0.5" style="background:var(--accent-bg);color:var(--accent-text)">
            已筛选：{{ statusF ? statusLabel(statusF) : kindLabel(engineF) }}
          </span>
        </div>

        <div v-if="loading" class="flex justify-center py-20" style="color:var(--text3)"><LoaderCircle :size="22" class="animate-spin" /></div>

        <UiEmpty
          v-else-if="!list.length"
          :icon="Database"
          title="还没有数据源"
          description="接入数据库或 HTTP 接口后，可以导出库结构参与建图，并为图节点绑定真实数据。"
        >
          <button class="btn-primary" @click="openCreate"><Plus :size="15" /> 接入第一个数据源</button>
        </UiEmpty>

        <!-- 卡片网格 -->
        <div v-else class="grid gap-3 sm:grid-cols-2 2xl:grid-cols-3">
          <div
            v-for="ds in filtered"
            :key="ds.id"
            class="panel ds-card group flex flex-col p-4"
            :style="{ borderLeft: `3px solid ${statusColor(statusOf(ds))}` }"
            @click="openDetail(ds)"
          >
            <div class="mb-3 flex items-start gap-3">
              <div
                class="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg"
                :style="{ background: `${KIND_COLORS[ds.kind] || '#64748b'}1a`, color: KIND_COLORS[ds.kind] || '#64748b' }"
              >
                <Database v-if="DB_SET.has(ds.kind)" class="h-4 w-4" />
                <Globe v-else class="h-4 w-4" />
              </div>
              <div class="min-w-0 flex-1">
                <div class="flex items-center gap-2">
                  <span class="truncate text-[14px] font-semibold" style="color:var(--text)">{{ ds.name }}</span>
                  <span
                    class="shrink-0 rounded-full px-2 py-px text-[10px] font-medium"
                    :style="{ background: `${statusColor(statusOf(ds))}1a`, color: statusColor(statusOf(ds)) }"
                  >{{ statusLabel(statusOf(ds)) }}</span>
                </div>
                <div class="mt-1 truncate font-mono text-[11px]" style="color:var(--text3)">{{ hostLine(ds) }}</div>
              </div>
              <!-- 悬浮操作 -->
              <div class="flex shrink-0 items-center gap-0.5 opacity-0 transition-opacity group-hover:opacity-100">
                <button class="ds-icon-btn" title="测试连接" @click.stop="test(ds)">
                  <LoaderCircle v-if="testing[ds.id]" class="h-3.5 w-3.5 animate-spin" />
                  <RotateCw v-else-if="statusOf(ds) === 'error'" class="h-3.5 w-3.5" />
                  <PlugZap v-else class="h-3.5 w-3.5" />
                </button>
                <button v-if="DB_SET.has(ds.kind)" class="ds-icon-btn" title="同步 DDL 到经验库" @click.stop="syncDdl(ds)">
                  <RefreshCw class="h-3.5 w-3.5" />
                </button>
                <button class="ds-icon-btn danger" title="移除" @click.stop="remove(ds)"><Trash2 class="h-3.5 w-3.5" /></button>
              </div>
            </div>
            <div class="ds-meta mb-3">
              <span>{{ kindLabel(ds.kind) }}</span>
              <span v-if="ds.lastTestedAt" :style="{ color: statusColor(statusOf(ds)) }">{{ timeAgo(ds.lastTestedAt) }}测试</span>
              <span v-if="ds.lastError" style="color:#F43F5E">{{ ds.lastError }}</span>
            </div>
            <div class="mt-auto flex items-center gap-2 border-t pt-2.5 text-[11px]" style="border-color:var(--border);color:var(--text3)">
              接入于 {{ timeAgo(ds.createdAt) }}
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 新建弹窗 -->
    <UiModal :open="createOpen" title="接入数据源" width="560px" @close="createOpen = false">
      <div class="space-y-4">
        <div>
          <label class="label">类型</label>
          <div class="flex flex-wrap gap-1.5">
            <button
              v-for="k in DB_KINDS"
              :key="k.value"
              class="rounded-lg border px-3 py-1.5 text-[13px] transition"
              :style="kind === k.value
                ? 'border-color:var(--primary);background:var(--accent-bg);color:var(--accent-text);font-weight:500'
                : 'border-color:var(--border);color:var(--text2)'"
              @click="pickKind(k.value)"
            >{{ k.label }}</button>
          </div>
        </div>
        <div>
          <label class="label">名称 <span style="color:var(--danger)">*</span></label>
          <input v-model="name" class="input" placeholder="例如：核心信贷库" />
        </div>

        <template v-if="isDb">
          <div class="grid grid-cols-3 gap-3">
            <div class="col-span-2">
              <label class="label">Host <span style="color:var(--danger)">*</span></label>
              <input v-model="cfg.host" class="input" placeholder="localhost" />
            </div>
            <div>
              <label class="label">端口 <span style="color:var(--danger)">*</span></label>
              <input v-model="cfg.port" class="input" :placeholder="DEFAULT_PORTS[kind]" />
            </div>
          </div>
          <div>
            <label class="label">{{ kind === 'oracle' ? 'Service Name' : kind === 'dm' ? 'Schema（可空）' : '数据库名' }} <span v-if="kind !== 'dm'" style="color:var(--danger)">*</span></label>
            <input v-model="cfg.database" class="input" />
          </div>
          <div class="grid grid-cols-2 gap-3">
            <div>
              <label class="label">用户名 <span style="color:var(--danger)">*</span></label>
              <input v-model="cfg.username" class="input" />
            </div>
            <div>
              <label class="label">密码</label>
              <input v-model="cfg.password" type="password" class="input" />
            </div>
          </div>
          <div v-if="kind !== 'oracle' && kind !== 'dm'">
            <label class="label">额外参数</label>
            <input v-model="cfg.params" class="input" placeholder="useSSL=false&serverTimezone=UTC" />
          </div>
        </template>

        <template v-else>
          <div>
            <label class="label">URL <span style="color:var(--danger)">*</span></label>
            <input v-model="cfg.url" class="input" placeholder="https://api.example.com/data" />
          </div>
          <div class="grid grid-cols-2 gap-3">
            <div>
              <label class="label">方法</label>
              <select v-model="cfg.method" class="input">
                <option>GET</option><option>POST</option><option>PUT</option><option>DELETE</option>
              </select>
            </div>
            <div>
              <label class="label">超时（ms）</label>
              <input v-model="cfg.timeoutMs" type="number" class="input" placeholder="15000" />
            </div>
          </div>
        </template>
      </div>
      <template #footer>
        <div class="flex justify-end gap-2">
          <button class="btn-secondary" @click="createOpen = false">取消</button>
          <button class="btn-primary" :disabled="!canSave || saving" @click="submitCreate">
            <LoaderCircle v-if="saving" :size="15" class="animate-spin" /> 保存
          </button>
        </div>
      </template>
    </UiModal>

    <!-- 详情抽屉 -->
    <UiDrawer :open="!!detail" :title="detail?.name || ''" width="520px" @close="detail = null">
      <template v-if="detail">
        <div class="mb-4 flex items-center gap-2">
          <span class="badge" style="background:var(--nav-hover);color:var(--text2)">{{ kindLabel(detail.kind) }}</span>
          <span class="badge" :style="{ background: `${statusColor(statusOf(detail))}1a`, color: statusColor(statusOf(detail)) }">
            {{ statusLabel(statusOf(detail)) }}
          </span>
          <button class="btn-secondary ml-auto" :disabled="testing[detail.id]" @click="test(detail)">
            <LoaderCircle v-if="testing[detail.id]" :size="13" class="animate-spin" /><PlugZap v-else :size="13" /> 测试连接
          </button>
        </div>

        <div class="label">连接配置</div>
        <div class="mb-5 space-y-1">
          <div
            v-for="[k, v] in configEntries(detail)"
            :key="k"
            class="flex items-center justify-between rounded-lg px-3 py-1.5 text-[12.5px]"
            style="background:var(--nav-hover)"
          >
            <span style="color:var(--text3)">{{ k }}</span>
            <span class="ml-3 max-w-[60%] truncate font-mono" style="color:var(--text)">{{ v }}</span>
          </div>
        </div>

        <template v-if="DB_SET.has(detail.kind)">
          <div class="label">库表浏览</div>
          <div v-if="tablesLoading" class="flex justify-center py-8" style="color:var(--text3)"><LoaderCircle :size="18" class="animate-spin" /></div>
          <div v-else-if="tables?.length" class="grid grid-cols-1 gap-1">
            <button
              v-for="t in tables.slice(0, 200)"
              :key="t"
              class="flex items-center gap-2 rounded-lg border px-3 py-1.5 text-left font-mono text-[12.5px] transition hover:brightness-95"
              style="border-color:var(--border);color:var(--text)"
              @click="showPreview(t)"
            >
              <Table2 :size="13" class="shrink-0" style="color:var(--text3)" /> {{ t }}
            </button>
          </div>
          <p v-else class="text-[13px]" style="color:var(--text3)">未获取到表列表（请检查连接）。</p>
        </template>
      </template>
    </UiDrawer>

    <!-- 表预览 -->
    <UiModal :open="!!preview" :title="preview ? `预览：${preview.name}` : ''" width="720px" @close="preview = null">
      <div v-if="preview" class="overflow-x-auto">
        <table class="tbl">
          <thead>
            <tr>
              <th v-for="c in preview.data.columns" :key="c">{{ c }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, i) in preview.data.rows" :key="i">
              <td v-for="(cell, j) in row" :key="j">{{ cell }}</td>
            </tr>
          </tbody>
        </table>
        <p class="mt-2 text-xs" style="color:var(--text3)">共 {{ preview.data.rowCount }} 行{{ preview.data.truncated ? '（已截断）' : '' }}</p>
      </div>
    </UiModal>
  </div>
</template>
