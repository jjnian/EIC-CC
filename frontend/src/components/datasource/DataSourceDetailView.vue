<script setup lang="ts">
import { ref, watch, computed, onMounted } from 'vue';
import { getDataSource, updateDataSource, buildStructuralGraphStream } from '../../api/dataSources';
import { createExperienceFromDdl } from '../../api/experiences';
import { ApiError } from '../../api/http';
import { toast } from '../../composables/useToast';
import { useSidebarTree } from '../../composables/useSidebarTree';
import { useWorkspaces } from '../../composables/useWorkspaces';
import type { DataSource } from '../../api/dataSources';
import DbOverviewTab from './DbOverviewTab.vue';
import DbTableListTab from './DbTableListTab.vue';
import DbSqlTab from './DbSqlTab.vue';
import HttpExecuteTab from './HttpExecuteTab.vue';
import HttpHistoryTab from './HttpHistoryTab.vue';
import HttpScheduleTab from './HttpScheduleTab.vue';
import DataSourceConfigForm from './DataSourceConfigForm.vue';
import { Button } from '@/components/ui/button';
import BaseInput from '../form/BaseInput.vue';

const props = defineProps<{ dsId: string; hasCurrentModel?: boolean }>();
const tree = useSidebarTree();
const ws = useWorkspaces();

const ds = ref<DataSource | null>(null);
const loading = ref(false);
const tab = ref<string>('overview');

// 导出 DDL 到经验库（仅 mysql/pgsql）
const exportingDdl = ref(false);
const ddlWithSamples = ref(false);       // 是否附带样例数据（含真实数据，默认关）
// 可内省/查表写 SQL 的数据库类型（含国产库 dm/gbase）
const DB_KINDS = ['mysql', 'pgsql', 'oracle', 'dm', 'gbase'];
const isDbKind = (k?: string) => !!k && DB_KINDS.includes(k);
const isDb = computed(() => isDbKind(ds.value?.kind));

const exportDdlToExperience = async () => {
  if (!ds.value || exportingDdl.value) return;
  exportingDdl.value = true;
  try {
    const created = await createExperienceFromDdl(ds.value.id, ddlWithSamples.value ? 10 : 0);
    const wsId = ws.currentId.value;
    if (wsId) tree.upsertExperience(wsId, created);
    toast.success(`已导出到经验库：${created.title}`);
  } catch (e) {
    toast.error(`导出失败：${e instanceof ApiError ? e.message : (e as Error).message}`);
  } finally {
    exportingDdl.value = false;
  }
};

// 确定性结构建图（绕过 LLM，面向千张/万张表）：全库内省 → 表/列/外键 直出为新本体模型。
// 走 SSE 流式，避免大库同步请求超时；进度文案实时显示在按钮上。
const buildingStructural = ref(false);
const structuralProgress = ref('');
const buildStructuralNow = () => {
  if (!ds.value || buildingStructural.value) return;
  buildingStructural.value = true;
  structuralProgress.value = '正在启动…';
  buildStructuralGraphStream(ds.value.id, {
    onStep: (_key, label) => { structuralProgress.value = label; },
    onComplete: async (r) => {
      const wsId = ws.currentId.value;
      if (wsId) await tree.loadOntologies(wsId, true);   // 刷新侧栏，让新模型出现
      toast.success(`结构建图完成：${r.nodeCount} 节点 / ${r.edgeCount} 边（${r.tableCount} 表 · ${r.viewCount} 视图 · ${r.fkCount} 外键）→ 模型「${r.title}」`);
      buildingStructural.value = false;
      structuralProgress.value = '';
    },
    onError: (msg) => {
      toast.error(`结构建图失败：${msg}`);
      buildingStructural.value = false;
      structuralProgress.value = '';
    },
  });
};

const editing = ref(false);
const editName = ref('');
const editCfg = ref<Record<string, any>>({});
const saving = ref(false);

const load = async () => {
  loading.value = true;
  try {
    ds.value = await getDataSource(props.dsId);
    editName.value = ds.value.name;
    editCfg.value = { ...(ds.value.config || {}) };
    tab.value = 'overview';
  } catch (e) {
    toast(`加载失败：${e instanceof ApiError ? e.message : (e as Error).message}`);
  } finally { loading.value = false; }
};

const tabs = computed(() => {
  if (!ds.value) return [];
  if (isDbKind(ds.value.kind)) {
    return [
      { id: 'overview', label: 'ℹ 概览' },
      { id: 'tables',   label: '📋 表列表' },
      { id: 'sql',      label: '📝 SQL 查询' },
      { id: 'config',   label: '⚙ 配置' },
    ];
  }
  if (ds.value.kind === 'file_stored') {
    // 遗留文件数据源：仅保留只读概览（上传已迁移至经验库，内容/下载端点已移除）
    return [
      { id: 'overview', label: 'ℹ 概览' },
      { id: 'config',   label: '⚙ 配置' },
    ];
  }
  if (ds.value.kind === 'https_api') {
    return [
      { id: 'overview', label: 'ℹ 概览' },
      { id: 'execute',  label: '▶ 执行' },
      { id: 'history',  label: '📜 历史' },
      { id: 'schedule', label: '⏰ 定时' },
      { id: 'config',   label: '⚙ 配置' },
    ];
  }
  return [];
});

const saveEdit = async () => {
  saving.value = true;
  try {
    await updateDataSource(props.dsId, { name: editName.value, config: editCfg.value });
    toast.success('已保存');
    await load();
    editing.value = false;
  } catch (e) {
    toast.error(`保存失败：${e instanceof ApiError ? e.message : (e as Error).message}`);
  } finally { saving.value = false; }
};

onMounted(load);
watch(() => props.dsId, load);
</script>

<template>
  <div class="detail">
    <div v-if="loading" class="msg">加载中…</div>
    <div v-else-if="!ds" class="msg">数据源不存在</div>
    <template v-else>
      <header>
        <h2>{{ ds.name }}</h2>
        <span class="kind-tag">{{ ds.kind }}</span>
        <span class="head-spacer" />
        <label v-if="isDb" class="ddl-sample-opt" title="为每张表附带前 10 行真实数据作样例（密码/手机/邮箱/证件/卡号等敏感字段已自动脱敏）">
          <input type="checkbox" v-model="ddlWithSamples" :disabled="exportingDdl" />
          <span>附带样例数据（10 行/表）</span>
        </label>
        <Button
          v-if="isDb"
          variant="secondary"
          size="sm"
          :disabled="exportingDdl"
          :title="ddlWithSamples ? '把库表结构（DDL）+ 每表前 10 行样例数据导出为一条经验' : '把库表结构（DDL）导出为一条经验，供对话建模召回'"
          @click="exportDdlToExperience"
        >{{ exportingDdl ? '导出中…' : '⤴ 导出 DDL 到经验库' }}</Button>
        <Button
          v-if="isDb"
          variant="secondary"
          size="sm"
          :disabled="buildingStructural"
          title="从全库内省，把 表→节点、列→属性、外键→血缘边 确定性直出为一个新本体模型（绕过 LLM，面向千张/万张表）。大库内省+落库可能耗时较长。"
          @click="buildStructuralNow"
        >{{ buildingStructural ? (structuralProgress || '结构建图中…') : '🏗 结构建图（全库）' }}</Button>
      </header>
      <nav class="tabs">
        <button v-for="t in tabs" :key="t.id" :class="{ active: tab === t.id }" @click="tab = t.id">{{ t.label }}</button>
      </nav>
      <section class="content">
        <DbOverviewTab v-if="tab === 'overview' && isDbKind(ds.kind)" :ds="ds" @updated="load" />
        <DbTableListTab v-if="tab === 'tables'"
                        :ds-id="ds.id"
                        :ds-name="ds.name"
                        :has-current-model="!!hasCurrentModel" />
        <DbSqlTab v-if="tab === 'sql'" :ds-id="ds.id" />
        <div v-if="tab === 'overview' && ds.kind === 'file_stored'" class="overview">
          <dl>
            <dt>文件名</dt><dd>{{ (ds.config as any)?.originalName }}</dd>
            <dt>大小</dt><dd>{{ ((ds.config as any)?.sizeBytes / 1024).toFixed(1) }} KB</dd>
            <dt v-if="(ds.config as any)?.pages">页数</dt>
            <dd v-if="(ds.config as any)?.pages">{{ (ds.config as any).pages }}</dd>
            <dt v-if="(ds.config as any)?.paragraphs">段落数</dt>
            <dd v-if="(ds.config as any)?.paragraphs">{{ (ds.config as any).paragraphs }}</dd>
            <dt v-if="(ds.config as any)?.tables">表格数</dt>
            <dd v-if="(ds.config as any)?.tables">{{ (ds.config as any).tables }}</dd>
            <dt>字符数</dt><dd>{{ (ds.config as any)?.chars }}</dd>
            <dt>上传时间</dt><dd>{{ new Date(ds.createdAt).toLocaleString() }}</dd>
          </dl>
        </div>
        <HttpExecuteTab v-if="tab === 'execute' && ds.kind === 'https_api'" :ds="ds" />
        <HttpHistoryTab v-if="tab === 'history' && ds.kind === 'https_api'" :ds-id="ds.id" />
        <HttpScheduleTab v-if="tab === 'schedule' && ds.kind === 'https_api'" :ds="ds" @updated="load" />
        <div v-if="tab === 'overview' && ds.kind === 'https_api'" class="overview">
          <dl>
            <dt>方法</dt><dd>{{ (ds.config as any)?.method }}</dd>
            <dt>URL</dt><dd>{{ (ds.config as any)?.url }}</dd>
            <dt>状态</dt><dd><span :class="['status', ds.status]">{{ ds.status }}</span></dd>
            <dt>最后测试</dt><dd>{{ ds.lastTestedAt ? new Date(ds.lastTestedAt).toLocaleString() : '—' }}</dd>
            <dt v-if="ds.lastError">最后错误</dt>
            <dd v-if="ds.lastError" class="err">{{ ds.lastError }}</dd>
          </dl>
        </div>
        <div v-if="tab === 'config'" class="config-pane">
          <DataSourceConfigForm
            v-if="ds.kind !== 'file_stored'"
            :kind="ds.kind"
            v-model="editCfg"
            v-model:name-value="editName"
          />
          <label v-else class="row">
            <span>名称</span>
            <BaseInput v-model="editName" />
          </label>
          <Button :disabled="saving" @click="saveEdit">{{ saving ? '保存中…' : '保存' }}</Button>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.detail { display: flex; flex-direction: column; height: 100%; color: var(--text-main, #18181b); }
header { display: flex; align-items: center; gap: 12px; padding: 12px 16px; border-bottom: 1px solid var(--hairline, rgba(0,0,0,0.07)); }
header h2 { margin: 0; font-size: 16px; }
.kind-tag { padding: 2px 8px; background: var(--bg-elev, rgba(0,0,0,0.045)); border-radius: 4px; font-size: 11px; color: var(--text-dim, #52525b); }
.head-spacer { flex: 1; }
.ddl-sample-opt { display: flex; align-items: center; gap: 6px; margin-right: 10px; font-size: 12px; color: var(--text-dim, #52525b); cursor: pointer; user-select: none; }
.ddl-sample-opt input { accent-color: var(--accent, #18181b); width: 14px; height: 14px; }
.ddl-export {
  background: transparent; color: #2563eb; border: 1px solid rgba(37,99,235,0.35);
  padding: 5px 12px; border-radius: 6px; font-size: 12px; cursor: pointer; font-family: inherit;
}
.ddl-export:hover { background: rgba(37,99,235,0.08); }
.ddl-export:disabled { opacity: .6; cursor: default; }
.tabs { display: flex; gap: 4px; padding: 0 12px; border-bottom: 1px solid var(--hairline, rgba(0,0,0,0.07)); }
.tabs button { background: none; border: none; padding: 8px 12px; color: var(--text-muted, #a1a1aa); cursor: pointer; font-size: 13px; border-bottom: 2px solid transparent; }
.tabs button.active { color: var(--text-main, #18181b); border-bottom-color: #18181b; }
.content { flex: 1; overflow: hidden; display: flex; }
.content > * { flex: 1; }
.overview { padding: 16px; }
.overview dl { display: grid; grid-template-columns: 110px 1fr; gap: 6px 12px; }
.overview dt { color: var(--text-muted, #a1a1aa); font-size: 13px; }
.overview dd { color: var(--text-main, #18181b); font-size: 13px; margin: 0; }
.status { padding: 2px 8px; border-radius: 4px; font-size: 12px; }
.status.connected { background: rgba(5,150,105,0.10); color: #059669; }
.status.error { background: rgba(220,38,38,0.08); color: #dc2626; }
.status.idle { background: var(--bg-elev, rgba(0,0,0,0.045)); color: var(--text-dim, #52525b); }
.err { color: #dc2626; }
.config-pane { padding: 16px; display: flex; flex-direction: column; gap: 12px; max-width: 520px; }
.row { display: flex; align-items: center; gap: 8px; }
.row > span { min-width: 88px; font-size: 13px; color: var(--text-dim, #52525b); }
.row > input { flex: 1; background: #fff; border: 1px solid var(--glass-border, rgba(0,0,0,0.09)); border-radius: 6px; padding: 6px 8px; color: var(--text-main, #18181b); }
.primary { background: var(--accent, #18181b); border: none; color: #fff; padding: 6px 14px; border-radius: 6px; cursor: pointer; align-self: flex-start; }
.primary:disabled { opacity: .5; }
.msg { color: var(--text-muted, #a1a1aa); padding: 24px; }
</style>
