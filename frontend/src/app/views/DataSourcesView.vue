<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import {
  Plus, Database, Globe, Trash2, LoaderCircle, PlugZap, Table2,
} from 'lucide-vue-next';
import {
  listDataSources, createDataSource, deleteDataSource, testDataSource,
  listTables, previewTable,
  type DataSource, type DataSourceKind, type TablePreview,
} from '../../api/dataSources';
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

const loading = ref(true);
const list = ref<DataSource[]>([]);
const testing = ref<Record<string, boolean>>({});

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

onMounted(async () => {
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
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    testing.value[ds.id] = false;
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
</script>

<template>
  <div class="mx-auto max-w-5xl px-6 py-8">
    <div class="mb-5 flex items-center justify-between">
      <p class="text-[13px] text-slate-400">数据源为图节点「供血」：库表结构可沉淀为经验，推断血缘可做值包含检验。</p>
      <button class="btn-primary btn-sm" @click="openCreate"><Plus :size="14" /> 接入数据源</button>
    </div>

    <div v-if="loading" class="flex justify-center py-20 text-slate-400"><LoaderCircle :size="22" class="animate-spin" /></div>

    <UiEmpty
      v-else-if="!list.length"
      :icon="Database"
      title="还没有数据源"
      description="接入数据库或 HTTP 接口后，可以导出库结构参与建图，并为图节点绑定真实数据。"
    >
      <button class="btn-primary" @click="openCreate"><Plus :size="15" /> 接入第一个数据源</button>
    </UiEmpty>

    <div v-else class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      <div v-for="ds in list" :key="ds.id" class="card group flex flex-col p-5 transition hover:border-indigo-300 hover:shadow-md">
        <div class="mb-3 flex items-center gap-2.5">
          <div class="flex h-9 w-9 items-center justify-center rounded-lg" :class="DB_SET.has(ds.kind) ? 'bg-indigo-50 text-indigo-600' : 'bg-emerald-50 text-emerald-600'">
            <Database v-if="DB_SET.has(ds.kind)" :size="16" />
            <Globe v-else :size="16" />
          </div>
          <span class="badge bg-slate-100 text-slate-600">{{ kindLabel(ds.kind) }}</span>
          <span class="ml-auto flex items-center gap-1.5 text-xs" :class="ds.status === 'connected' ? 'text-emerald-600' : ds.status === 'error' ? 'text-rose-500' : 'text-slate-400'">
            <span class="h-1.5 w-1.5 rounded-full" :class="ds.status === 'connected' ? 'bg-emerald-500' : ds.status === 'error' ? 'bg-rose-500' : 'bg-slate-300'" />
            {{ ds.status === 'connected' ? '已连接' : ds.status === 'error' ? '异常' : '未测试' }}
          </span>
        </div>
        <button class="text-left text-[14.5px] font-semibold text-slate-800 hover:text-indigo-700" @click="openDetail(ds)">{{ ds.name }}</button>
        <div class="mt-1 text-xs text-slate-400">接入于 {{ timeAgo(ds.createdAt) }}</div>
        <div class="mt-4 flex items-center gap-1.5">
          <button class="btn-secondary btn-sm" :disabled="testing[ds.id]" @click="test(ds)">
            <LoaderCircle v-if="testing[ds.id]" :size="13" class="animate-spin" /><PlugZap v-else :size="13" /> 测试
          </button>
          <button class="btn-ghost btn-sm" @click="openDetail(ds)">详情</button>
          <button class="btn-ghost btn-sm ml-auto !text-rose-500 hover:!bg-rose-50" @click="remove(ds)"><Trash2 :size="13" /></button>
        </div>
      </div>
    </div>

    <!-- 新建 -->
    <UiModal :open="createOpen" title="接入数据源" width="560px" @close="createOpen = false">
      <div class="space-y-4">
        <div>
          <label class="label">类型</label>
          <div class="flex flex-wrap gap-1.5">
            <button
              v-for="k in DB_KINDS" :key="k.value"
              class="rounded-lg border px-3 py-1.5 text-[13px] transition"
              :class="kind === k.value ? 'border-indigo-500 bg-indigo-50 font-medium text-indigo-700' : 'border-slate-200 text-slate-600 hover:border-slate-300'"
              @click="pickKind(k.value)"
            >{{ k.label }}</button>
          </div>
        </div>
        <div>
          <label class="label">名称 <span class="text-rose-500">*</span></label>
          <input v-model="name" class="input" placeholder="例如：核心信贷库" />
        </div>

        <template v-if="isDb">
          <div class="grid grid-cols-3 gap-3">
            <div class="col-span-2">
              <label class="label">Host <span class="text-rose-500">*</span></label>
              <input v-model="cfg.host" class="input" placeholder="localhost" />
            </div>
            <div>
              <label class="label">端口 <span class="text-rose-500">*</span></label>
              <input v-model="cfg.port" class="input" :placeholder="DEFAULT_PORTS[kind]" />
            </div>
          </div>
          <div>
            <label class="label">{{ kind === 'oracle' ? 'Service Name' : kind === 'dm' ? 'Schema（可空）' : '数据库名' }} <span v-if="kind !== 'dm'" class="text-rose-500">*</span></label>
            <input v-model="cfg.database" class="input" />
          </div>
          <div class="grid grid-cols-2 gap-3">
            <div>
              <label class="label">用户名 <span class="text-rose-500">*</span></label>
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
            <label class="label">URL <span class="text-rose-500">*</span></label>
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
          <span class="badge bg-slate-100 text-slate-600">{{ kindLabel(detail.kind) }}</span>
          <button class="btn-secondary btn-sm ml-auto" :disabled="testing[detail.id]" @click="test(detail)">
            <LoaderCircle v-if="testing[detail.id]" :size="13" class="animate-spin" /><PlugZap v-else :size="13" /> 测试连接
          </button>
        </div>

        <div class="label">连接配置</div>
        <div class="mb-5 space-y-1">
          <div v-for="[k, v] in configEntries(detail)" :key="k" class="flex items-center justify-between rounded-lg bg-slate-50 px-3 py-1.5 text-[12.5px]">
            <span class="text-slate-400">{{ k }}</span>
            <span class="ml-3 max-w-[60%] truncate font-mono text-slate-700">{{ v }}</span>
          </div>
        </div>

        <template v-if="DB_SET.has(detail.kind)">
          <div class="label">库表浏览</div>
          <div v-if="tablesLoading" class="flex justify-center py-8 text-slate-400"><LoaderCircle :size="18" class="animate-spin" /></div>
          <div v-else-if="tables?.length" class="grid grid-cols-1 gap-1">
            <button
              v-for="t in tables.slice(0, 200)" :key="t"
              class="flex items-center gap-2 rounded-lg border border-slate-100 px-3 py-1.5 text-left font-mono text-[12.5px] text-slate-700 hover:border-indigo-200 hover:bg-indigo-50"
              @click="showPreview(t)"
            >
              <Table2 :size="13" class="shrink-0 text-slate-400" /> {{ t }}
            </button>
          </div>
          <p v-else class="text-[13px] text-slate-400">未获取到表列表（请检查连接）。</p>
        </template>
      </template>
    </UiDrawer>

    <!-- 表预览 -->
    <UiModal :open="!!preview" :title="preview ? `预览：${preview.name}` : ''" width="720px" @close="preview = null">
      <div v-if="preview" class="overflow-x-auto">
        <table class="w-full border-collapse text-[12.5px]">
          <thead>
            <tr>
              <th v-for="c in preview.data.columns" :key="c" class="border-b border-slate-200 bg-slate-50 px-3 py-2 text-left font-medium text-slate-500">{{ c }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, i) in preview.data.rows" :key="i">
              <td v-for="(cell, j) in row" :key="j" class="border-b border-slate-100 px-3 py-1.5 text-slate-700">{{ cell }}</td>
            </tr>
          </tbody>
        </table>
        <p class="mt-2 text-xs text-slate-400">共 {{ preview.data.rowCount }} 行{{ preview.data.truncated ? '（已截断）' : '' }}</p>
      </div>
    </UiModal>
  </div>
</template>
