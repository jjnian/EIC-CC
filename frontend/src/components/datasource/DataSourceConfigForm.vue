<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { DataSourceKind } from '../../api/dataSources';
import FormField from '../form/FormField.vue';
import BaseInput from '../form/BaseInput.vue';
import BaseSelect from '../form/BaseSelect.vue';
import BaseTextarea from '../form/BaseTextarea.vue';
import BaseSwitch from '../form/BaseSwitch.vue';
import { Button } from '@/components/ui/button';
import { useFormValidation, rules } from '../../composables/useFormValidation';

const props = defineProps<{
  kind: DataSourceKind;
  modelValue: Record<string, any>;
  nameValue: string;
}>();

const emit = defineEmits<{
  (e: 'update:modelValue', v: Record<string, any>): void;
  (e: 'update:nameValue', v: string): void;
  (e: 'validity', ok: boolean): void;
}>();

const cfg = ref<Record<string, any>>({ ...props.modelValue });
const name = ref<string>(props.nameValue || '');

watch(() => props.modelValue, (v) => { cfg.value = { ...v }; }, { deep: true });
watch(() => props.nameValue, (v) => { name.value = v || ''; });

const emitConfig = () => emit('update:modelValue', { ...cfg.value });
const emitName = () => emit('update:nameValue', name.value);

// 各数据库类型的连接字段提示（端口默认值 / 库字段名称与占位）
const dbPortHint = computed(() => ({
  mysql: '3306', pgsql: '5432', oracle: '1521', dm: '5236', gbase: '5258',
} as Record<string, string>)[props.kind] || '');
const dbNameLabel = computed(() =>
  props.kind === 'oracle' ? 'Service Name' : props.kind === 'dm' ? 'Schema' : 'Database');
const dbNameHint = computed(() =>
  props.kind === 'oracle' ? '服务名或 SID，如 ORCLPDB1'
    : props.kind === 'dm' ? '模式名，可空（默认取登录用户）'
    : '');

// headers 走 [{key,value}] 列表方便编辑
const headerList = ref<{ k: string; v: string }[]>(
  Object.entries(cfg.value.headers || {}).map(([k, v]) => ({ k, v: String(v) })),
);
const syncHeaders = () => {
  const out: Record<string, string> = {};
  for (const it of headerList.value) {
    if (it.k.trim()) out[it.k.trim()] = it.v;
  }
  cfg.value.headers = out;
  emitConfig();
};
const addHeader = () => { headerList.value.push({ k: '', v: '' }); };
const removeHeader = (i: number) => { headerList.value.splice(i, 1); syncHeaders(); };

const scheduleEnabled = computed({
  get: () => !!cfg.value.schedule?.enabled,
  set: (v: boolean) => {
    cfg.value.schedule = { ...(cfg.value.schedule || {}), enabled: v };
    emitConfig();
  },
});
const scheduleInterval = computed({
  get: () => cfg.value.schedule?.intervalSec || 300,
  set: (v: number) => {
    cfg.value.schedule = { ...(cfg.value.schedule || {}), intervalSec: Number(v) || 300 };
    emitConfig();
  },
});

// ── 校验：按数据源类型组装规则 ──
const isDb = computed(() => ['mysql', 'pgsql', 'oracle', 'dm', 'gbase'].includes(props.kind));
const ruleMap = computed<Record<string, any[]>>(() => {
  if (isDb.value) {
    const map: Record<string, any[]> = {
      host: [rules.required('请填写 Host')],
      port: [rules.required('请填写端口'), rules.port()],
      username: [rules.required('请填写用户名')],
    };
    // 达梦的「Schema」可空（默认取登录用户）；其余库的库名/服务名必填
    if (props.kind !== 'dm') {
      map.database = [rules.required(props.kind === 'oracle' ? '请填写服务名' : '请填写数据库名')];
    }
    return map;
  }
  return {
    url: [rules.required('请填写 URL'), rules.url()],
    timeoutMs: [rules.min(1, '超时需大于 0')],
  };
});

const { fieldError, validateField, isValid } = useFormValidation(
  () => ({
    host: cfg.value.host,
    port: cfg.value.port,
    database: cfg.value.database,
    username: cfg.value.username,
    url: cfg.value.url,
    timeoutMs: cfg.value.timeoutMs,
  }),
  // useFormValidation 接收静态规则映射；用 computed 包一层，切换类型时整体重建。
  ruleMap.value,
);

// 类型变化或值变化时，把整体有效性抛给父级（控制“测试连接/保存”按钮）。
watch([isValid, () => props.kind], () => emit('validity', isValid.value), { immediate: true });
</script>

<template>
  <div class="ds-form">
    <FormField label="名称" required>
      <BaseInput v-model="name" placeholder="数据源名称" @update:modelValue="emitName" />
    </FormField>

    <template v-if="isDb">
      <div class="ds-grid">
        <FormField label="Host" required :error="fieldError('host')">
          <BaseInput v-model="cfg.host" placeholder="localhost"
            @update:modelValue="emitConfig" @blur="validateField('host')" />
        </FormField>
        <FormField label="端口" required :error="fieldError('port')">
          <BaseInput v-model="cfg.port" numeric :placeholder="dbPortHint"
            @update:modelValue="emitConfig" @blur="validateField('port')" />
        </FormField>
      </div>
      <FormField :label="dbNameLabel" :required="kind !== 'dm'" :hint="dbNameHint || undefined" :error="fieldError('database')">
        <BaseInput v-model="cfg.database" :placeholder="dbNameHint"
          @update:modelValue="emitConfig" @blur="validateField('database')" />
      </FormField>
      <div class="ds-grid">
        <FormField label="用户名" required :error="fieldError('username')">
          <BaseInput v-model="cfg.username"
            @update:modelValue="emitConfig" @blur="validateField('username')" />
        </FormField>
        <FormField label="密码">
          <BaseInput v-model="cfg.password" type="password" @update:modelValue="emitConfig" />
        </FormField>
      </div>
      <FormField v-if="kind !== 'oracle' && kind !== 'dm'" label="额外参数" hint="可选">
        <BaseInput v-model="cfg.params" placeholder="如 useSSL=false&serverTimezone=UTC"
          @update:modelValue="emitConfig" />
      </FormField>
    </template>

    <template v-if="kind === 'https_api'">
      <FormField label="URL" required :error="fieldError('url')">
        <BaseInput v-model="cfg.url" placeholder="https://api.example.com/..."
          @update:modelValue="emitConfig" @blur="validateField('url')" />
      </FormField>
      <FormField label="方法">
        <BaseSelect v-model="cfg.method" :options="['GET', 'POST', 'PUT', 'DELETE']"
          @update:modelValue="emitConfig" />
      </FormField>
      <FormField label="Headers" hint="可选">
        <div class="kv-list">
          <div v-for="(h, i) in headerList" :key="i" class="kv">
            <BaseInput v-model="h.k" placeholder="Header" @update:modelValue="syncHeaders" />
            <BaseInput v-model="h.v" placeholder="Value" @update:modelValue="syncHeaders" />
            <Button variant="ghost" size="icon-sm" type="button" class="kv-del" @click="removeHeader(i)">×</Button>
          </div>
          <Button variant="ghost" size="sm" type="button" class="kv-add" @click="addHeader">+ 添加 Header</Button>
        </div>
      </FormField>
      <FormField v-if="cfg.method === 'POST' || cfg.method === 'PUT'" label="Body">
        <BaseTextarea v-model="cfg.body" :rows="4" placeholder='{"key":"value"}'
          @update:modelValue="emitConfig" />
      </FormField>
      <FormField label="超时(ms)" :error="fieldError('timeoutMs')">
        <BaseInput v-model="cfg.timeoutMs" numeric placeholder="15000"
          @update:modelValue="emitConfig" @blur="validateField('timeoutMs')" />
      </FormField>
      <FormField label="定时拉取">
        <div class="schedule">
          <BaseSwitch v-model="scheduleEnabled" :label="scheduleEnabled ? '已启用' : '未启用'" />
          <BaseInput v-if="scheduleEnabled" v-model="scheduleInterval" numeric :min="60" placeholder="秒，≥60" />
        </div>
      </FormField>
    </template>
  </div>
</template>

<style scoped>
.ds-form { display: flex; flex-direction: column; gap: 14px; }
.ds-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.kv-list { display: flex; flex-direction: column; gap: 8px; }
.kv { display: flex; gap: 8px; align-items: center; }
.kv-del {
  background: rgba(255, 255, 255, 0.04); border: 1px solid var(--glass-border);
  border-radius: 8px; width: 32px; height: 36px; flex-shrink: 0;
  color: var(--text-dim); cursor: pointer; font-size: 16px;
  transition: color 0.15s var(--ease-out), background 0.15s var(--ease-out);
}
.kv-del:hover { color: #ff8a8a; background: rgba(255, 107, 107, 0.12); }
.kv-add {
  align-self: flex-start;
  background: transparent; border: 1px dashed var(--glass-border);
  border-radius: 8px; padding: 7px 14px; color: var(--text-dim);
  cursor: pointer; font-size: 12.5px; font-family: inherit;
  transition: color 0.15s var(--ease-out), border-color 0.15s var(--ease-out);
}
.kv-add:hover { color: var(--accent); border-color: var(--accent); }
.schedule { display: flex; align-items: center; gap: 12px; }
.schedule :deep(.f-input-wrap) { width: 140px; }
</style>
