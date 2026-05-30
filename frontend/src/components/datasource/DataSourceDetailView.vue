<script setup lang="ts">
import { ref, watch, computed, onMounted } from 'vue';
import { getDataSource, updateDataSource } from '../../api/dataSources';
import { ApiError } from '../../api/http';
import { toast } from '../../composables/useToast';
import type { DataSource } from '../../api/dataSources';
import DbOverviewTab from './DbOverviewTab.vue';
import DbTableListTab from './DbTableListTab.vue';
import DbSqlTab from './DbSqlTab.vue';
import FileContentTab from './FileContentTab.vue';
import HttpExecuteTab from './HttpExecuteTab.vue';
import HttpHistoryTab from './HttpHistoryTab.vue';
import HttpScheduleTab from './HttpScheduleTab.vue';
import DataSourceConfigForm from './DataSourceConfigForm.vue';
import type { OntologyNode, OntologyEdge } from '../../types';

const props = defineProps<{ dsId: string; hasCurrentModel?: boolean }>();
const emit = defineEmits<{
  (e: 'ontology-extracted', payload: {
    mode: 'merge' | 'new';
    name: string;
    nodes: OntologyNode[];
    edges: OntologyEdge[];
  }): void;
}>();
const ds = ref<DataSource | null>(null);
const loading = ref(false);
const tab = ref<string>('overview');

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
    // 默认进入"概览"或文件的"内容预览"
    tab.value = ds.value.kind === 'file_stored' ? 'content' : 'overview';
  } catch (e) {
    toast(`加载失败：${e instanceof ApiError ? e.message : (e as Error).message}`);
  } finally { loading.value = false; }
};

const tabs = computed(() => {
  if (!ds.value) return [];
  if (ds.value.kind === 'mysql' || ds.value.kind === 'pgsql') {
    return [
      { id: 'overview', label: 'ℹ 概览' },
      { id: 'tables',   label: '📋 表列表' },
      { id: 'sql',      label: '📝 SQL 查询' },
      { id: 'config',   label: '⚙ 配置' },
    ];
  }
  if (ds.value.kind === 'file_stored') {
    return [
      { id: 'overview', label: 'ℹ 概览' },
      { id: 'content',  label: '📖 内容预览' },
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
    toast('已保存');
    await load();
    editing.value = false;
  } catch (e) {
    toast(`保存失败：${e instanceof ApiError ? e.message : (e as Error).message}`);
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
      </header>
      <nav class="tabs">
        <button v-for="t in tabs" :key="t.id" :class="{ active: tab === t.id }" @click="tab = t.id">{{ t.label }}</button>
      </nav>
      <section class="content">
        <DbOverviewTab v-if="tab === 'overview' && (ds.kind === 'mysql' || ds.kind === 'pgsql')" :ds="ds" @updated="load" />
        <DbTableListTab v-if="tab === 'tables'"
                        :ds-id="ds.id"
                        :ds-name="ds.name"
                        :has-current-model="!!hasCurrentModel"
                        @ontology-extracted="(p) => emit('ontology-extracted', p)" />
        <DbSqlTab v-if="tab === 'sql'" :ds-id="ds.id" />
        <FileContentTab v-if="tab === 'content' && ds.kind === 'file_stored'" :ds-id="ds.id" :total-chars="Number((ds.config as any)?.chars || 0)" />
        <div v-if="tab === 'overview' && ds.kind === 'file_stored'" class="overview">
          <dl>
            <dt>文件名</dt><dd>{{ (ds.config as any)?.originalName }}</dd>
            <dt>大小</dt><dd>{{ ((ds.config as any)?.sizeBytes / 1024).toFixed(1) }} KB</dd>
            <dt v-if="(ds.config as any)?.pages">页数</dt>
            <dd v-if="(ds.config as any)?.pages">{{ (ds.config as any).pages }}</dd>
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
            <input v-model="editName" />
          </label>
          <button class="primary" :disabled="saving" @click="saveEdit">{{ saving ? '保存中…' : '保存' }}</button>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.detail { display: flex; flex-direction: column; height: 100%; color: #e8eaed; }
header { display: flex; align-items: center; gap: 12px; padding: 12px 16px; border-bottom: 1px solid rgba(255,255,255,.06); }
header h2 { margin: 0; font-size: 16px; }
.kind-tag { padding: 2px 8px; background: rgba(255,255,255,.06); border-radius: 4px; font-size: 11px; color: #aaa; }
.tabs { display: flex; gap: 4px; padding: 0 12px; border-bottom: 1px solid rgba(255,255,255,.06); }
.tabs button { background: none; border: none; padding: 8px 12px; color: #aaa; cursor: pointer; font-size: 13px; border-bottom: 2px solid transparent; }
.tabs button.active { color: #fff; border-bottom-color: #4a8df0; }
.content { flex: 1; overflow: hidden; display: flex; }
.content > * { flex: 1; }
.overview { padding: 16px; }
.overview dl { display: grid; grid-template-columns: 110px 1fr; gap: 6px 12px; }
.overview dt { color: #888; font-size: 13px; }
.overview dd { color: #e8eaed; font-size: 13px; margin: 0; }
.status { padding: 2px 8px; border-radius: 4px; font-size: 12px; }
.status.connected { background: rgba(34,221,136,.2); color: #22dd88; }
.status.error { background: rgba(255,99,71,.2); color: tomato; }
.status.idle { background: rgba(255,255,255,.1); color: #aaa; }
.err { color: tomato; }
.config-pane { padding: 16px; display: flex; flex-direction: column; gap: 12px; max-width: 520px; }
.row { display: flex; align-items: center; gap: 8px; }
.row > span { min-width: 88px; font-size: 13px; color: #c0c4cf; }
.row > input { flex: 1; background: rgba(255,255,255,.04); border: 1px solid rgba(255,255,255,.12); border-radius: 6px; padding: 6px 8px; color: #e8eaed; }
.primary { background: #4a8df0; border: none; color: #fff; padding: 6px 14px; border-radius: 6px; cursor: pointer; align-self: flex-start; }
.primary:disabled { opacity: .5; }
.msg { color: #888; padding: 24px; }
</style>
