<script setup lang="ts">
import { ref, watch, computed } from 'vue';
import { updateSchedule } from '../../api/dataSources';
import { ApiError } from '../../api/http';
import { Button } from '@/components/ui/button';
import { toast } from '../../composables/useToast';
import type { DataSource } from '../../api/dataSources';

const props = defineProps<{ ds: DataSource }>();
const emit = defineEmits<{ (e: 'updated'): void }>();

const enabled = ref<boolean>(!!(props.ds.config as any)?.schedule?.enabled);
const intervalSec = ref<number>(((props.ds.config as any)?.schedule?.intervalSec) || 300);
const saving = ref(false);

watch(() => props.ds.id, () => {
  enabled.value = !!(props.ds.config as any)?.schedule?.enabled;
  intervalSec.value = ((props.ds.config as any)?.schedule?.intervalSec) || 300;
});

const nextRun = computed(() => enabled.value
  ? new Date(Date.now() + intervalSec.value * 1000).toLocaleTimeString()
  : '—');

const save = async () => {
  saving.value = true;
  try {
    await updateSchedule(props.ds.id, enabled.value, intervalSec.value);
    toast(enabled.value ? '定时任务已启用' : '定时任务已停止');
    emit('updated');
  } catch (e) {
    toast(`保存失败：${e instanceof ApiError ? e.message : (e as Error).message}`);
  } finally { saving.value = false; }
};
</script>

<template>
  <div class="tab">
    <label class="toggle">
      <input type="checkbox" v-model="enabled" />
      <span>启用定时拉取</span>
    </label>
    <label class="row">
      <span>间隔 (秒)</span>
      <input type="number" v-model.number="intervalSec" min="60" />
      <small class="hint">最小 60 秒</small>
    </label>
    <div class="row">
      <span>下次执行</span>
      <span>{{ nextRun }}</span>
    </div>
    <Button class="primary" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</Button>
  </div>
</template>

<style scoped>
.tab { padding: 16px; display: flex; flex-direction: column; gap: 12px; color: #e8eaed; }
.toggle { display: flex; gap: 8px; align-items: center; }
.row { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.row > span:first-child { min-width: 88px; color: #c0c4cf; }
.row input[type=number] { background: rgba(255,255,255,.04); border: 1px solid rgba(255,255,255,.12); border-radius: 6px; padding: 4px 8px; color: #e8eaed; width: 100px; }
.hint { color: #888; font-size: 12px; }
.primary { background: #4a8df0; border: none; color: #fff; padding: 6px 14px; border-radius: 6px; cursor: pointer; align-self: flex-start; }
.primary:disabled { opacity: .5; }
</style>
