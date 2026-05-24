<script setup lang="ts">
import { CAPABILITY_LABELS, fmtTokens } from '../../composables/useModelConfigs';
import type { ModelConfig } from '../../types';

interface TestResult { status: 'idle' | 'testing' | 'ok' | 'error'; latencyMs?: number; error?: string; }

defineProps<{
  models: ModelConfig[];
  loading: boolean;
  testResults: Record<string, TestResult>;
}>();

defineEmits<{
  (e: 'run-test', id: string): void;
  (e: 'test-all'): void;
}>();
</script>

<template>
  <section>
    <div class="sv-section-head">
      <div>
        <h3>大模型管理</h3>
        <p>模型配置来自 application.yml，修改后重启生效。</p>
      </div>
      <button class="test-all-btn" @click="$emit('test-all')">🔌 全部测试</button>
    </div>
    <div class="model-list" v-if="!loading">
      <div v-for="m in models" :key="m.id" class="model-card" :class="{ disabled: !m.enabled }">
        <div class="model-card-header">
          <div class="model-card-info">
            <div class="mc-title-row">
              <h4>{{ m.name }}</h4>
              <span v-if="m.provider" class="mc-prov-chip" :class="'prov-' + m.provider">{{ m.provider }}</span>
              <span v-if="m.protocol === 'anthropic'" class="mc-proto-chip">Messages API</span>
            </div>
            <span class="model-meta">{{ m.baseUrl }} · {{ m.modelName }}</span>
            <p v-if="m.description" class="mc-desc">{{ m.description }}</p>
            <div v-if="m.contextWindow || m.capabilities?.length" class="mc-stats">
              <span v-if="m.contextWindow" class="mc-stat">
                <span class="mc-stat-key">上下文</span>
                <span class="mc-stat-val">{{ fmtTokens(m.contextWindow) }}</span>
              </span>
              <span v-if="m.maxOutputTokens" class="mc-stat">
                <span class="mc-stat-key">输出上限</span>
                <span class="mc-stat-val">{{ fmtTokens(m.maxOutputTokens) }}</span>
              </span>
              <span v-for="c in (m.capabilities || [])" :key="c" class="mc-cap">{{ CAPABILITY_LABELS[c] || c }}</span>
            </div>
          </div>
          <div class="model-actions">
            <button
              v-if="m.enabled"
              class="test-btn"
              :class="testResults[m.id]?.status || 'idle'"
              :disabled="testResults[m.id]?.status === 'testing'"
              @click="$emit('run-test', m.id)"
            >
              <span v-if="!testResults[m.id] || testResults[m.id].status === 'idle'">测试连接</span>
              <span v-else-if="testResults[m.id].status === 'testing'" class="test-spin">⟳</span>
              <span v-else-if="testResults[m.id].status === 'ok'" class="test-ok">✓ {{ testResults[m.id].latencyMs }}ms</span>
              <span v-else class="test-err" :title="testResults[m.id].error">✕ 失败</span>
            </button>
            <span class="status-badge" :class="m.enabled ? 'enabled' : 'disabled'">
              {{ m.enabled ? '已启用' : '已禁用' }}
            </span>
          </div>
        </div>
      </div>

      <div v-if="models.length === 0" class="empty-state">
        <p>暂无模型配置，请在 application.yml 的 app.llm.models 中添加。</p>
      </div>
    </div>
  </section>
</template>
