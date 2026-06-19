<script setup lang="ts">
import { ref } from 'vue';
import type { DataSource } from '../../api/dataSources';
import { testDataSource } from '../../api/dataSources';
import { toast } from '../../composables/useToast';

const props = defineProps<{ ds: DataSource }>();
const emit = defineEmits<{ (e: 'updated'): void }>();
const testing = ref(false);

const fmtTime = (t?: number) => t ? new Date(t).toLocaleString() : '—';

const runTest = async () => {
  testing.value = true;
  try {
    const r = await testDataSource(props.ds.id);
    toast(r.success ? `连接成功 (${r.latencyMs ?? '-'}ms)` : `连接失败：${r.message}`);
    emit('updated');
  } finally { testing.value = false; }
};
</script>

<template>
  <div class="overview">
    <dl>
      <dt>类型</dt><dd>{{ ds.kind === 'mysql' ? 'MySQL' : ds.kind === 'oracle' ? 'Oracle' : 'PostgreSQL' }}</dd>
      <dt>Host</dt><dd>{{ (ds.config as any)?.host }}:{{ (ds.config as any)?.port }}</dd>
      <dt>{{ ds.kind === 'oracle' ? 'Service Name' : 'Database' }}</dt><dd>{{ (ds.config as any)?.database }}</dd>
      <dt>Username</dt><dd>{{ (ds.config as any)?.username }}</dd>
      <dt>状态</dt><dd>
        <span :class="['status', ds.status]">{{ ds.status }}</span>
      </dd>
      <dt>最后测试</dt><dd>{{ fmtTime(ds.lastTestedAt) }}</dd>
      <dt v-if="ds.lastError">最后错误</dt>
      <dd v-if="ds.lastError" class="err">{{ ds.lastError }}</dd>
    </dl>
    <button class="primary" :disabled="testing" @click="runTest">
      {{ testing ? '测试中…' : '测试连接' }}
    </button>
  </div>
</template>

<style scoped>
.overview { padding: 16px; color: #e8eaed; }
dl { display: grid; grid-template-columns: 110px 1fr; gap: 6px 12px; margin-bottom: 12px; }
dt { color: #888; font-size: 13px; }
dd { color: #e8eaed; font-size: 13px; margin: 0; }
.status { padding: 2px 8px; border-radius: 4px; font-size: 12px; }
.status.connected { background: rgba(34,221,136,.2); color: #22dd88; }
.status.error { background: rgba(255,99,71,.2); color: tomato; }
.status.idle { background: rgba(255,255,255,.1); color: #aaa; }
.err { color: tomato; font-size: 12px; }
.primary { background: #4a8df0; border: none; color: #fff; padding: 6px 14px; border-radius: 6px; cursor: pointer; }
.primary:disabled { opacity: .5; }
</style>
