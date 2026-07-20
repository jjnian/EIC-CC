<script setup lang="ts">
import { computed, ref } from 'vue';
import type { DataSource } from '../../api/dataSources';
import { testDataSource } from '../../api/dataSources';
import { Button } from '@/components/ui/button';
import { toast } from '../../composables/useToast';

const props = defineProps<{ ds: DataSource }>();
const emit = defineEmits<{ (e: 'updated'): void }>();
const testing = ref(false);

const kindLabel = computed(() => ({
  mysql: 'MySQL', pgsql: 'PostgreSQL', oracle: 'Oracle', dm: '达梦 DM', gbase: 'GBase 8a',
} as Record<string, string>)[props.ds.kind] || props.ds.kind);

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
      <dt>类型</dt><dd>{{ kindLabel }}</dd>
      <dt>Host</dt><dd>{{ (ds.config as any)?.host }}:{{ (ds.config as any)?.port }}</dd>
      <dt>{{ ds.kind === 'oracle' ? 'Service Name' : ds.kind === 'dm' ? 'Schema' : 'Database' }}</dt><dd>{{ (ds.config as any)?.database }}</dd>
      <dt>Username</dt><dd>{{ (ds.config as any)?.username }}</dd>
      <dt>状态</dt><dd>
        <span :class="['status', ds.status]">{{ ds.status }}</span>
      </dd>
      <dt>最后测试</dt><dd>{{ fmtTime(ds.lastTestedAt) }}</dd>
      <dt v-if="ds.lastError">最后错误</dt>
      <dd v-if="ds.lastError" class="err">{{ ds.lastError }}</dd>
    </dl>
    <Button :disabled="testing" @click="runTest">
      {{ testing ? '测试中…' : '测试连接' }}
    </Button>
  </div>
</template>

<style scoped>
.overview { padding: 16px; color: var(--text-main, #18181b); }
dl { display: grid; grid-template-columns: 110px 1fr; gap: 6px 12px; margin-bottom: 12px; }
dt { color: var(--text-muted, #a1a1aa); font-size: 13px; }
dd { color: var(--text-main, #18181b); font-size: 13px; margin: 0; }
.status { padding: 2px 8px; border-radius: 4px; font-size: 12px; }
.status.connected { background: rgba(5,150,105,0.10); color: #059669; }
.status.error { background: rgba(220,38,38,0.08); color: #dc2626; }
.status.idle { background: var(--bg-elev, rgba(0,0,0,0.045)); color: var(--text-dim, #52525b); }
.err { color: #dc2626; font-size: 12px; }
.primary { background: var(--accent, #18181b); border: none; color: #fff; padding: 6px 14px; border-radius: 6px; cursor: pointer; }
.primary:disabled { opacity: .5; }
</style>
