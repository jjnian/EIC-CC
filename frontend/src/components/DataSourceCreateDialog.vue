<script setup lang="ts">
import { ref } from 'vue';
import { createDataSource, testDataSourceInline } from '../api/dataSources';
import type { DataSourceKind } from '../api/dataSources';
import { ApiError } from '../api/http';
import { toast } from '../composables/useToast';
import DataSourceConfigForm from './datasource/DataSourceConfigForm.vue';
import { Button } from '@/components/ui/button';

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'created', id: string): void;
}>();

const step = ref<'pick' | 'form'>('pick');
const kind = ref<DataSourceKind | null>(null);
const name = ref('');
const cfg = ref<Record<string, any>>({});
const submitting = ref(false);
const testing = ref(false);
const testMsg = ref<string>('');
const testOk = ref<boolean | null>(null);
const formValid = ref(false);

const TYPES: { kind: DataSourceKind; icon: string; label: string; desc: string; tag: string; accent: string }[] = [
  { kind: 'mysql',     icon: '🗄', label: 'MySQL',      desc: '连接 MySQL 数据库，查表写 SQL',  tag: 'RDBMS', accent: 'var(--accent)' },
  { kind: 'pgsql',     icon: '🐘', label: 'PostgreSQL', desc: '连接 PgSQL 数据库，查表写 SQL',  tag: 'RDBMS', accent: 'var(--accent-2)' },
  { kind: 'oracle',    icon: '🔶', label: 'Oracle',     desc: '连接 Oracle 数据库，查表写 SQL', tag: 'RDBMS', accent: 'var(--accent)' },
  { kind: 'dm',        icon: '🏮', label: '达梦 DM',    desc: '国产库，兼容 Oracle 语法',      tag: '国产',  accent: 'var(--accent-2)' },
  { kind: 'gbase',     icon: '🧩', label: 'GBase 8a',   desc: '国产 MPP 库，兼容 MySQL 协议',  tag: '国产',  accent: 'var(--accent-3)' },
  { kind: 'https_api', icon: '🌐', label: 'HTTPS 接口', desc: 'REST API，可定时拉取',           tag: 'API',   accent: 'var(--accent-3)' },
];

const accentOf = (k: DataSourceKind | null) => TYPES.find(t => t.kind === k)?.accent ?? 'var(--accent)';
const labelOf = (k: DataSourceKind | null) => TYPES.find(t => t.kind === k)?.label ?? '';

const pickType = (k: DataSourceKind) => {
  kind.value = k;
  step.value = 'form';
  testMsg.value = ''; testOk.value = null;
  if (k === 'mysql') cfg.value = { host: 'localhost', port: 3306, database: '', username: '', password: '', params: '' };
  if (k === 'pgsql') cfg.value = { host: 'localhost', port: 5432, database: '', username: '', password: '', params: '' };
  if (k === 'oracle') cfg.value = { host: 'localhost', port: 1521, database: '', username: '', password: '' };
  if (k === 'dm') cfg.value = { host: 'localhost', port: 5236, database: '', username: '', password: '' };
  if (k === 'gbase') cfg.value = { host: 'localhost', port: 5258, database: '', username: '', password: '', params: '' };
  if (k === 'https_api') cfg.value = { url: '', method: 'GET', headers: {}, body: '', timeoutMs: 15000, schedule: { enabled: false, intervalSec: 300 } };
};

const runTest = async () => {
  if (!kind.value) return;
  testing.value = true;
  testMsg.value = ''; testOk.value = null;
  try {
    const r = await testDataSourceInline({ kind: kind.value, config: cfg.value });
    testOk.value = r.success;
    testMsg.value = r.success
      ? `连接成功 · ${r.latencyMs ?? '-'}ms`
      : `连接失败：${r.message || '未知错误'}`;
  } catch (e) {
    testOk.value = false;
    testMsg.value = `异常：${(e as Error).message}`;
  } finally {
    testing.value = false;
  }
};

const submit = async () => {
  if (!kind.value) return;
  if (!name.value.trim()) { toast.warn('请填写名称'); return; }
  submitting.value = true;
  try {
    const created = await createDataSource({ name: name.value.trim(), kind: kind.value, config: cfg.value });
    toast.success('已创建');
    emit('created', created.id);
    emit('close');
  } catch (e) {
    const msg = e instanceof ApiError ? e.message : (e as Error).message;
    toast.error(`创建失败：${msg}`);
  } finally {
    submitting.value = false;
  }
};
</script>

<template>
  <div class="dlg-mask" @click.self="emit('close')">
    <div class="dlg" :style="{ '--c': accentOf(kind) }">
      <span class="dlg-corner tl" /><span class="dlg-corner tr" />
      <span class="dlg-corner bl" /><span class="dlg-corner br" />

      <div class="dlg-head">
        <div class="dlg-head-text">
          <div class="dlg-kicker">{{ step === 'pick' ? 'NEW · DATA SOURCE' : 'CONFIGURE · ' + (kind || '').toUpperCase() }}</div>
          <h3>{{ step === 'pick' ? '添加数据源' : labelOf(kind) + ' 连接' }}</h3>
        </div>
        <Button variant="ghost" size="icon-sm" class="dlg-x" @click="emit('close')">×</Button>
      </div>

      <!-- 步骤指示 -->
      <div class="dlg-steps">
        <span class="dlg-step" :class="{ on: true }"><i>1</i> 选择类型</span>
        <span class="dlg-step-line" :class="{ on: step === 'form' }" />
        <span class="dlg-step" :class="{ on: step === 'form' }"><i>2</i> 填写连接</span>
      </div>

      <div class="dlg-body">
        <div v-if="step === 'pick'" class="picker">
          <button
            v-for="t in TYPES"
            :key="t.kind"
            class="type-card"
            :style="{ '--c': t.accent }"
            @click="pickType(t.kind)"
          >
            <span class="tc-glow" />
            <span class="ic">{{ t.icon }}</span>
            <span class="tc-main">
              <strong>{{ t.label }}<small class="tc-tag">{{ t.tag }}</small></strong>
              <small>{{ t.desc }}</small>
            </span>
            <span class="tc-go">→</span>
          </button>
        </div>
        <div v-else class="form">
          <DataSourceConfigForm
            v-if="kind"
            :kind="kind"
            v-model="cfg"
            v-model:name-value="name"
            @validity="formValid = $event"
          />
          <div v-if="testMsg" class="test-msg" :class="{ ok: testOk === true, bad: testOk === false }">
            <span class="test-dot" />{{ testMsg }}
          </div>
        </div>
      </div>

      <div class="dlg-foot">
        <Button v-if="step === 'form'" variant="ghost" size="sm" @click="step = 'pick'">‹ 返回</Button>
        <span class="spacer" />
        <Button
          v-if="step === 'form' && kind"
          variant="secondary" size="sm" :disabled="testing || !formValid" @click="runTest"
        >{{ testing ? '测试中…' : '⚡ 测试连接' }}</Button>
        <Button size="sm" :disabled="step === 'pick' || submitting || !formValid || !name.trim()" @click="submit">
          {{ submitting ? '提交中…' : '保存连接' }}
        </Button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dlg-mask {
  position: fixed; inset: 0; z-index: 1000;
  background: radial-gradient(120% 120% at 50% 0%, rgba(61,155,255,0.06), transparent 50%), rgba(3,6,12,0.66);
  backdrop-filter: blur(6px);
  display: flex; align-items: center; justify-content: center;
  animation: dlgFade .2s var(--ease-out) both;
}
@keyframes dlgFade { from { opacity: 0; } to { opacity: 1; } }

.dlg {
  position: relative; width: 600px; max-height: 82vh;
  color: var(--text-main); border-radius: 16px;
  display: flex; flex-direction: column;
  background:
    radial-gradient(100% 60% at 50% 0%, color-mix(in srgb, var(--c) 9%, transparent), transparent 60%),
    linear-gradient(180deg, rgba(18,26,42,0.97), rgba(10,15,26,0.98));
  border: 1px solid var(--glass-border);
  box-shadow: var(--shadow-panel), 0 0 60px color-mix(in srgb, var(--c) 14%, transparent);
  backdrop-filter: blur(22px) saturate(1.2);
  overflow: hidden;
  animation: dlgPop .26s var(--ease-spring) both;
}
@keyframes dlgPop { from { opacity: 0; transform: translateY(10px) scale(0.97); } to { opacity: 1; transform: none; } }
.dlg::before {
  content: ''; position: absolute; inset: 0 0 auto 0; height: 1px;
  background: linear-gradient(90deg, transparent, var(--c), transparent); opacity: 0.85;
}
/* 角标 */
.dlg-corner { position: absolute; width: 13px; height: 13px; border: 1px solid color-mix(in srgb, var(--c) 55%, transparent); opacity: 0.6; pointer-events: none; }
.dlg-corner.tl { top: 9px; left: 9px; border-right: 0; border-bottom: 0; }
.dlg-corner.tr { top: 9px; right: 9px; border-left: 0; border-bottom: 0; }
.dlg-corner.bl { bottom: 9px; left: 9px; border-right: 0; border-top: 0; }
.dlg-corner.br { bottom: 9px; right: 9px; border-left: 0; border-top: 0; }

.dlg-head { display: flex; align-items: flex-start; padding: 18px 20px 12px; }
.dlg-head-text { flex: 1; }
.dlg-kicker { font-family: var(--font-mono); font-size: 9.5px; letter-spacing: 2px; color: var(--c); opacity: 0.9; margin-bottom: 5px; }
.dlg-head h3 { margin: 0; font-size: 17px; font-weight: 650; letter-spacing: 0.2px; }
.dlg-x { background: rgba(255,255,255,0.04); border: 1px solid var(--glass-border); color: var(--text-dim); width: 28px; height: 28px; border-radius: 8px; font-size: 18px; line-height: 1; cursor: pointer; transition: all .15s var(--ease-out); }
.dlg-x:hover { color: var(--text-main); background: rgba(255,255,255,0.08); border-color: var(--glass-border-strong); }

/* 步骤指示 */
.dlg-steps { display: flex; align-items: center; gap: 10px; padding: 0 20px 14px; }
.dlg-step { display: flex; align-items: center; gap: 7px; font-size: 11.5px; color: var(--text-muted); font-weight: 500; transition: color .2s; }
.dlg-step.on { color: var(--text-main); }
.dlg-step i { width: 19px; height: 19px; border-radius: 50%; border: 1px solid var(--glass-border); display: flex; align-items: center; justify-content: center; font-size: 10px; font-style: normal; font-family: var(--font-mono); color: var(--text-muted); }
.dlg-step.on i { border-color: var(--c); color: var(--c); background: color-mix(in srgb, var(--c) 14%, transparent); box-shadow: 0 0 12px color-mix(in srgb, var(--c) 35%, transparent); }
.dlg-step-line { flex: 1; height: 1px; background: var(--glass-border); position: relative; overflow: hidden; }
.dlg-step-line.on::after { content: ''; position: absolute; inset: 0; background: linear-gradient(90deg, transparent, var(--c)); }

.dlg-body { padding: 4px 20px 18px; overflow-y: auto; flex: 1; }

/* 类型选择卡 */
.picker { display: flex; flex-direction: column; gap: 10px; }
.type-card {
  position: relative; display: flex; align-items: center; gap: 14px; text-align: left;
  background: rgba(255,255,255,0.02); border: 1px solid var(--glass-border); border-radius: 12px;
  padding: 14px 16px; cursor: pointer; color: var(--text-main); overflow: hidden;
  transition: border-color .18s var(--ease-out), background .18s var(--ease-out), transform .18s var(--ease-out);
}
.type-card:hover { border-color: color-mix(in srgb, var(--c) 50%, transparent); background: color-mix(in srgb, var(--c) 7%, transparent); transform: translateY(-2px); }
.tc-glow { position: absolute; left: -40%; top: 0; width: 40%; height: 100%; background: linear-gradient(90deg, transparent, color-mix(in srgb, var(--c) 16%, transparent), transparent); transition: left .5s var(--ease-out); pointer-events: none; }
.type-card:hover .tc-glow { left: 130%; }
.type-card .ic {
  flex-shrink: 0; width: 46px; height: 46px; border-radius: 12px; font-size: 22px;
  display: flex; align-items: center; justify-content: center;
  background: color-mix(in srgb, var(--c) 15%, rgba(255,255,255,0.02));
  border: 1px solid color-mix(in srgb, var(--c) 32%, transparent);
  box-shadow: inset 0 1px 0 rgba(255,255,255,0.08);
  transition: box-shadow .18s var(--ease-out);
}
.type-card:hover .ic { box-shadow: 0 0 22px color-mix(in srgb, var(--c) 40%, transparent), inset 0 1px 0 rgba(255,255,255,0.12); }
.tc-main { display: flex; flex-direction: column; gap: 4px; flex: 1; min-width: 0; }
.tc-main strong { font-size: 15px; font-weight: 600; display: flex; align-items: center; gap: 9px; }
.tc-tag { font-family: var(--font-mono); font-size: 9px; letter-spacing: 1px; color: var(--c); border: 1px solid color-mix(in srgb, var(--c) 40%, transparent); border-radius: 5px; padding: 1px 6px; font-weight: 500; }
.tc-main small { font-size: 12px; color: var(--text-dim); }
.tc-go { font-family: var(--font-mono); font-size: 17px; color: var(--c); opacity: 0; transform: translateX(-6px); transition: all .18s var(--ease-out); }
.type-card:hover .tc-go { opacity: 0.9; transform: translateX(0); }

.form { display: flex; flex-direction: column; gap: 12px; }
.test-msg { display: flex; align-items: center; gap: 9px; font-size: 12.5px; color: var(--text-dim); padding: 9px 12px; background: rgba(255,255,255,0.03); border: 1px solid var(--glass-border); border-radius: 9px; font-family: var(--font-mono); }
.test-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--text-muted); flex-shrink: 0; }
.test-msg.ok { color: var(--accent-soft); border-color: rgba(47,134,214,0.4); background: var(--accent-tint); }
.test-msg.ok .test-dot { background: var(--accent); box-shadow: 0 0 9px var(--accent-glow); }
.test-msg.bad { color: #ff8d8d; border-color: rgba(255,120,120,0.4); background: rgba(255,120,120,0.08); }
.test-msg.bad .test-dot { background: #ff6b6b; box-shadow: 0 0 9px rgba(255,107,107,0.5); }

.dlg-foot { display: flex; align-items: center; gap: 9px; padding: 14px 20px; border-top: 1px solid var(--glass-border); background: rgba(0,0,0,0.2); }
.spacer { flex: 1; }
.primary {
  background: linear-gradient(135deg, var(--accent-soft), var(--accent)); color: #fff; border: none;
  padding: 9px 18px; border-radius: 9px; font-size: 13px; font-weight: 650; cursor: pointer; font-family: inherit;
  box-shadow: 0 6px 16px rgba(47,134,214,0.3), inset 0 1px 0 rgba(255,255,255,0.26);
  transition: transform .15s var(--ease-out), box-shadow .15s var(--ease-out);
}
.primary:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 9px 22px rgba(47,134,214,0.42), inset 0 1px 0 rgba(255,255,255,0.3); }
.primary:disabled { opacity: 0.4; cursor: not-allowed; }
.ghost { background: rgba(255,255,255,0.04); border: 1px solid var(--glass-border); border-radius: 9px; padding: 9px 14px; color: var(--text-dim); cursor: pointer; font-family: inherit; font-size: 13px; transition: all .15s var(--ease-out); }
.ghost:hover:not(:disabled) { color: var(--text-main); background: rgba(255,255,255,0.08); border-color: var(--glass-border-strong); }
.ghost:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
