<script setup lang="ts">
import { type PropType } from 'vue';
import { CAPABILITY_OPTIONS } from '../../composables/useModelConfigs';
import type { SettingsModelConfig, ProviderInfo } from '../../composables/useModelConfigs';

interface ModelFormData {
  name: string;
  baseUrl: string;
  modelName: string;
  apiKey: string;
  providerCode: string;
  description: string;
  contextWindow: number | null;
  maxOutputTokens: number | null;
  capabilities: string[];
  protocol: string;
}

const props = defineProps({
  show: { type: Boolean, required: true },
  editing: { type: Object as PropType<SettingsModelConfig | null>, default: null },
  providers: { type: Array as PropType<ProviderInfo[]>, required: true },
  formData: { type: Object as PropType<ModelFormData>, required: true },
  formErrors: { type: Object as PropType<Record<string, string>>, required: true },
  selectedProvider: { type: Object as PropType<ProviderInfo | null>, default: null },
  saving: { type: Boolean, default: false },
  saveError: { type: String, default: '' },
});

const emit = defineEmits<{
  (e: 'update:show', value: boolean): void;
  (e: 'submit'): void;
  (e: 'apply-preset', code: string): void;
  (e: 'toggle-capability', code: string): void;
}>();

const close = () => emit('update:show', false);
</script>

<template>
  <div class="modal-overlay" v-if="show" @click.self="close">
    <div class="modal">
      <div class="modal-header">
        <h3>{{ editing ? '编辑模型' : '新增模型' }}</h3>
        <button class="modal-close" @click="close">×</button>
      </div>
      <div class="modal-body">
        <div class="form-group">
          <label>提供商预设 <span class="sv-help-inline">(可选,自动带出 Base URL 与默认模型)</span></label>
          <select class="preset-select" :value="formData.providerCode"
                  @change="emit('apply-preset', ($event.target as HTMLSelectElement).value)">
            <option value="">— 自定义 / 不使用预设 —</option>
            <option v-for="p in providers" :key="p.code" :value="p.code">{{ p.displayName }}</option>
          </select>
          <span v-if="selectedProvider" class="sv-help">
            环境变量:<code>{{ selectedProvider.apiKeyEnvName }}</code>
            <span v-if="selectedProvider.code === 'anthropic'" class="anthropic-badge">使用原生 Messages API</span>
          </span>
        </div>
        <div class="form-group" :class="{ error: formErrors.name }">
          <label>名称(显示用)</label>
          <input v-model="formData.name" placeholder="例如:我的 Claude Opus" />
          <span class="form-error" v-if="formErrors.name">{{ formErrors.name }}</span>
        </div>
        <div class="form-group" :class="{ error: formErrors.baseUrl }">
          <label>Base URL</label>
          <input v-model="formData.baseUrl" placeholder="https://api.example.com/v1" />
          <span class="form-error" v-if="formErrors.baseUrl">{{ formErrors.baseUrl }}</span>
        </div>
        <div class="form-group" :class="{ error: formErrors.modelName }">
          <label>模型名称 (Model Name)</label>
          <input v-model="formData.modelName"
                 :list="selectedProvider ? 'preset-models' : undefined"
                 placeholder="例如:gpt-4.1 / claude-opus-4-7 / deepseek-chat" />
          <datalist v-if="selectedProvider" id="preset-models">
            <option v-for="m in selectedProvider.models" :key="m" :value="m" />
          </datalist>
          <span class="form-error" v-if="formErrors.modelName">{{ formErrors.modelName }}</span>
        </div>
        <div class="form-group">
          <label>API Key</label>
          <input type="password" v-model="formData.apiKey"
                 :placeholder="editing ? '留空则不修改' : '输入 API Key'" />
          <span class="sv-help">创建时必填,编辑时留空表示不修改</span>
        </div>

        <div class="form-divider">大模型基本信息 <span class="sv-help-inline">(可选,仅用于展示与协议选择)</span></div>

        <div class="form-group">
          <label>简介</label>
          <textarea class="form-textarea" v-model="formData.description" rows="2"
                    placeholder="例:擅长长上下文与复杂推理;中文友好" />
        </div>

        <div class="form-row">
          <div class="form-group">
            <label>上下文窗口 (token)</label>
            <input type="number" v-model.number="formData.contextWindow" placeholder="例如 200000" />
          </div>
          <div class="form-group">
            <label>最大输出 (token)</label>
            <input type="number" v-model.number="formData.maxOutputTokens" placeholder="例如 8192" />
          </div>
        </div>

        <div class="form-group">
          <label>能力标签</label>
          <div class="cap-chips">
            <button v-for="c in CAPABILITY_OPTIONS" :key="c.code" type="button"
                    class="cap-chip" :class="{ active: formData.capabilities.includes(c.code) }"
                    @click="emit('toggle-capability', c.code)">
              {{ c.label }}
            </button>
          </div>
        </div>

        <div class="form-group">
          <label>协议族 <span class="sv-help-inline">(空 = 按 URL/模型名自动识别)</span></label>
          <select class="preset-select" v-model="formData.protocol">
            <option value="">— 自动识别 —</option>
            <option value="openai">OpenAI 兼容 (/chat/completions)</option>
            <option value="anthropic">Anthropic Messages API</option>
          </select>
        </div>
      </div>
      <div v-if="saveError" class="modal-error-banner">
        <span>⚠</span> {{ saveError }}
      </div>
      <div class="modal-footer">
        <button class="modal-btn cancel" @click="close" :disabled="saving">取消</button>
        <button class="modal-btn save" @click="emit('submit')" :disabled="saving">
          <span v-if="saving" class="spinner-sm" />
          {{ saving ? '保存中…' : (editing ? '保存修改' : '添加模型') }}
        </button>
      </div>
    </div>
  </div>
</template>
