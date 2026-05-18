<script setup lang="ts">
import { ref, onMounted, computed } from 'vue';

interface ModelConfig {
  id: string;
  name: string;
  baseUrl: string;
  modelName: string;
  enabled: boolean;
  provider?: string;
  description?: string;
  contextWindow?: number;
  maxOutputTokens?: number;
  capabilities?: string[];
  protocol?: string;
}

const CAPABILITY_OPTIONS = [
  { code: 'streaming', label: '流式' },
  { code: 'vision', label: '图像' },
  { code: 'json', label: 'JSON 模式' },
  { code: 'tool-use', label: '工具调用' },
  { code: 'reasoning', label: '推理链' }
];

const CAPABILITY_LABELS: Record<string, string> = Object.fromEntries(
  CAPABILITY_OPTIONS.map(o => [o.code, o.label])
);

const fmtTokens = (n?: number) => {
  if (!n) return '';
  if (n >= 1000000) return (n / 1000000).toFixed(n % 1000000 === 0 ? 0 : 1) + 'M';
  if (n >= 1000) return (n / 1000).toFixed(n % 1000 === 0 ? 0 : 1) + 'K';
  return String(n);
};

interface ProviderInfo {
  code: string;
  displayName: string;
  baseUrl: string;
  defaultModel: string;
  models: string[];
  apiKeyEnvName: string;
}

const models = ref<ModelConfig[]>([]);
const providers = ref<ProviderInfo[]>([]);
const loading = ref(true);
const showAddModal = ref(false);
const editingModel = ref<ModelConfig | null>(null);
const formData = ref({
  name: '', baseUrl: '', modelName: '', apiKey: '', providerCode: '',
  description: '', contextWindow: null as number | null, maxOutputTokens: null as number | null,
  capabilities: [] as string[], protocol: ''
});
const emptyForm = () => ({
  name: '', baseUrl: '', modelName: '', apiKey: '', providerCode: '',
  description: '', contextWindow: null as number | null, maxOutputTokens: null as number | null,
  capabilities: [] as string[], protocol: ''
});
const formErrors = ref<Record<string, string>>({});
const saved = ref(false);

const loadModels = async () => {
  try {
    const res = await fetch('/api/models');
    if (res.ok) {
      models.value = await res.json();
    }
  } catch (e) {
    console.error("Failed to load models", e);
  } finally {
    loading.value = false;
  }
};

const loadProviders = async () => {
  try {
    const res = await fetch('/api/config');
    if (res.ok) {
      const cfg = await res.json();
      providers.value = (cfg.providers || []).filter((p: ProviderInfo) => p.code !== 'custom');
    }
  } catch (e) {
    console.error("Failed to load providers", e);
  }
};

const selectedProvider = computed(() =>
  providers.value.find(p => p.code === formData.value.providerCode) || null
);

const applyPreset = (code: string) => {
  formData.value.providerCode = code;
  if (!code) return;
  const p = providers.value.find(x => x.code === code);
  if (!p) return;
  formData.value.baseUrl = p.baseUrl;
  formData.value.modelName = p.defaultModel;
  if (!formData.value.name.trim()) {
    formData.value.name = p.displayName;
  }
  formData.value.protocol = code === 'anthropic' ? 'anthropic' : 'openai';
};

onMounted(() => {
  loadModels();
  loadProviders();
});

const openAdd = () => {
  editingModel.value = null;
  formData.value = emptyForm();
  formErrors.value = {};
  showAddModal.value = true;
};

const toggleCapability = (code: string) => {
  const idx = formData.value.capabilities.indexOf(code);
  if (idx >= 0) formData.value.capabilities.splice(idx, 1);
  else formData.value.capabilities.push(code);
};

const detectProviderCode = (baseUrl: string): string => {
  if (!baseUrl) return '';
  const m = providers.value.find(p => p.baseUrl && baseUrl.includes(new URL(p.baseUrl).hostname));
  return m ? m.code : '';
};

const openEdit = (model: ModelConfig) => {
  editingModel.value = model;
  formData.value = {
    name: model.name,
    baseUrl: model.baseUrl,
    modelName: model.modelName,
    apiKey: '',
    providerCode: model.provider || detectProviderCode(model.baseUrl),
    description: model.description || '',
    contextWindow: model.contextWindow ?? null,
    maxOutputTokens: model.maxOutputTokens ?? null,
    capabilities: [...(model.capabilities || [])],
    protocol: model.protocol || ''
  };
  formErrors.value = {};
  showAddModal.value = true;
};

const validateForm = (): boolean => {
  formErrors.value = {};
  if (!formData.value.name.trim()) formErrors.value.name = '名称不能为空';
  if (!formData.value.baseUrl.trim()) formErrors.value.baseUrl = 'Base URL 不能为空';
  if (!formData.value.modelName.trim()) formErrors.value.modelName = '模型名称不能为空';
  return Object.keys(formErrors.value).length === 0;
};

const saveModel = async () => {
  if (!validateForm()) return;
  try {
    const url = editingModel.value ? `/api/models/${editingModel.value.id}` : '/api/models';
    const method = editingModel.value ? 'PUT' : 'POST';
    const payload = {
      name: formData.value.name,
      baseUrl: formData.value.baseUrl,
      modelName: formData.value.modelName,
      apiKey: formData.value.apiKey,
      provider: formData.value.providerCode || null,
      description: formData.value.description || null,
      contextWindow: formData.value.contextWindow,
      maxOutputTokens: formData.value.maxOutputTokens,
      capabilities: formData.value.capabilities,
      protocol: formData.value.protocol || null
    };
    const res = await fetch(url, {
      method,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
    if (res.ok) {
      showAddModal.value = false;
      saved.value = true;
      setTimeout(() => saved.value = false, 2000);
      await loadModels();
    } else {
      const err = await res.json();
      console.error('Save failed:', err);
    }
  } catch (e) {
    console.error("Failed to save model", e);
  }
};

const deleteModel = async (id: string) => {
  if (!confirm('确定要删除这个模型配置吗？')) return;
  try {
    const res = await fetch(`/api/models/${id}`, { method: 'DELETE' });
    if (res.ok) await loadModels();
  } catch (e) {
    console.error("Failed to delete model", e);
  }
};

const toggleModel = async (model: ModelConfig) => {
  try {
    const res = await fetch(`/api/models/${model.id}/toggle`, { method: 'PATCH' });
    if (res.ok) await loadModels();
  } catch (e) {
    console.error("Failed to toggle model", e);
  }
};
</script>

<template>
  <div class="settings-view">
    <div class="sv-header">
      <div>
        <h2>模型管理</h2>
        <p>管理所有可用的大语言模型配置。预设提供商来自系统内置，自定义模型可自行添加。</p>
      </div>
      <button class="add-btn" @click="openAdd">
        <svg viewBox="0 0 24 24" width="18" height="18" stroke="currentColor" stroke-width="2" fill="none"><line x1="12" y1="5" x2="12" y2="19"></line><line x1="5" y1="12" x2="19" y2="12"></line></svg>
        新增模型
      </button>
    </div>

    <!-- 模型卡片列表 -->
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
            <span class="status-badge" :class="m.enabled ? 'enabled' : 'disabled'">
              {{ m.enabled ? '已启用' : '已禁用' }}
            </span>
            <button class="action-btn toggle" @click="toggleModel(m)" :title="m.enabled ? '禁用' : '启用'">
              {{ m.enabled ? '禁用' : '启用' }}
            </button>
            <button class="action-btn edit" @click="openEdit(m)" title="编辑">
              <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" stroke-width="2" fill="none"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
            </button>
            <button class="action-btn delete" @click="deleteModel(m.id)" title="删除">
              <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" stroke-width="2" fill="none"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
            </button>
          </div>
        </div>
      </div>

      <div v-if="models.length === 0" class="empty-state">
        <p>暂无模型配置，点击"新增模型"开始添加。</p>
      </div>
    </div>

    <!-- 新增/编辑模态框 -->
    <div class="modal-overlay" v-if="showAddModal" @click.self="showAddModal = false">
      <div class="modal">
        <div class="modal-header">
          <h3>{{ editingModel ? '编辑模型' : '新增模型' }}</h3>
          <button class="modal-close" @click="showAddModal = false">×</button>
        </div>
        <div class="modal-body">
          <div class="form-group">
            <label>提供商预设 <span class="sv-help-inline">(可选，自动带出 Base URL 与默认模型)</span></label>
            <select class="preset-select" :value="formData.providerCode" @change="applyPreset(($event.target as HTMLSelectElement).value)">
              <option value="">— 自定义 / 不使用预设 —</option>
              <option v-for="p in providers" :key="p.code" :value="p.code">{{ p.displayName }}</option>
            </select>
            <span v-if="selectedProvider" class="sv-help">
              环境变量：<code>{{ selectedProvider.apiKeyEnvName }}</code>
              <span v-if="selectedProvider.code === 'anthropic'" class="anthropic-badge">使用原生 Messages API</span>
            </span>
          </div>
          <div class="form-group" :class="{ error: formErrors.name }">
            <label>名称（显示用）</label>
            <input v-model="formData.name" placeholder="例如：我的 Claude Opus" />
            <span class="form-error" v-if="formErrors.name">{{ formErrors.name }}</span>
          </div>
          <div class="form-group" :class="{ error: formErrors.baseUrl }">
            <label>Base URL</label>
            <input v-model="formData.baseUrl" placeholder="https://api.example.com/v1" />
            <span class="form-error" v-if="formErrors.baseUrl">{{ formErrors.baseUrl }}</span>
          </div>
          <div class="form-group" :class="{ error: formErrors.modelName }">
            <label>模型名称 (Model Name)</label>
            <input v-model="formData.modelName" :list="selectedProvider ? 'preset-models' : undefined" placeholder="例如：gpt-4.1 / claude-opus-4-7 / deepseek-chat" />
            <datalist v-if="selectedProvider" id="preset-models">
              <option v-for="m in selectedProvider.models" :key="m" :value="m" />
            </datalist>
            <span class="form-error" v-if="formErrors.modelName">{{ formErrors.modelName }}</span>
          </div>
          <div class="form-group">
            <label>API Key</label>
            <input type="password" v-model="formData.apiKey" :placeholder="editingModel ? '留空则不修改' : '输入 API Key'" />
            <span class="sv-help">创建时必填，编辑时留空表示不修改</span>
          </div>

          <div class="form-divider">大模型基本信息 <span class="sv-help-inline">(可选，仅用于展示与协议选择)</span></div>

          <div class="form-group">
            <label>简介</label>
            <textarea class="form-textarea" v-model="formData.description" rows="2" placeholder="例：擅长长上下文与复杂推理；中文友好" />
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
                      @click="toggleCapability(c.code)">
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
        <div class="modal-footer">
          <button class="modal-btn cancel" @click="showAddModal = false">取消</button>
          <button class="modal-btn save" @click="saveModel">
            {{ saved ? '已保存！' : (editingModel ? '保存修改' : '添加模型') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.settings-view {
  flex: 1;
  padding: 32px;
  overflow-y: auto;
  background: transparent;
}

.sv-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 24px;
}

.sv-header h2 {
  font-size: 24px;
  font-weight: 600;
  color: var(--text-main);
  margin-bottom: 4px;
}

.sv-header p {
  color: var(--text-dim);
  font-size: 13px;
}

.add-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  background: #42b883;
  color: #002418;
  border: none;
  padding: 10px 20px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
  flex-shrink: 0;
}

.add-btn:hover {
  background: #50caa3;
  transform: translateY(-1px);
}

/* 模型卡片列表 */
.model-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.model-card {
  background: rgba(14, 25, 41, 0.6);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 12px;
  padding: 16px 20px;
  transition: all 0.2s;
}

.model-card:hover {
  border-color: rgba(255, 255, 255, 0.15);
}

.model-card.disabled {
  opacity: 0.5;
}

.model-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.model-card-info h4 {
  font-size: 15px;
  color: var(--text-main);
  margin: 0 0 4px 0;
  font-weight: 500;
}

.model-meta {
  font-size: 12px;
  color: var(--text-dim);
  font-family: 'JetBrains Mono', monospace;
}
.mc-title-row { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; flex-wrap: wrap; }
.mc-prov-chip {
  font-size: 10px;
  padding: 2px 8px;
  border-radius: 100px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  background: rgba(255, 255, 255, 0.08);
  color: rgba(255, 255, 255, 0.6);
}
.mc-prov-chip.prov-anthropic { background: rgba(217, 119, 87, 0.18); color: #f1a684; }
.mc-prov-chip.prov-openai { background: rgba(16, 163, 127, 0.18); color: #4ec9b0; }
.mc-prov-chip.prov-deepseek { background: rgba(99, 102, 241, 0.18); color: #a5a8f0; }
.mc-prov-chip.prov-qwen { background: rgba(61, 155, 255, 0.18); color: #7abaff; }
.mc-prov-chip.prov-kimi { background: rgba(168, 85, 247, 0.18); color: #c4a5fc; }
.mc-prov-chip.prov-glm { background: rgba(34, 197, 94, 0.18); color: #80e0a0; }
.mc-proto-chip {
  font-size: 10px;
  padding: 2px 8px;
  border-radius: 100px;
  background: rgba(251, 191, 36, 0.15);
  color: #fbbf24;
  border: 1px solid rgba(251, 191, 36, 0.3);
}
.mc-desc {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.55);
  margin: 6px 0 0 0;
  line-height: 1.55;
}
.mc-stats {
  display: flex; flex-wrap: wrap; gap: 6px;
  margin-top: 10px;
}
.mc-stat {
  display: inline-flex; align-items: center; gap: 4px;
  font-size: 11px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.06);
  padding: 2px 8px;
  border-radius: 6px;
}
.mc-stat-key { color: rgba(255, 255, 255, 0.4); }
.mc-stat-val { color: var(--text-main); font-family: 'JetBrains Mono', monospace; font-weight: 500; }
.mc-cap {
  font-size: 10px;
  padding: 2px 8px;
  border-radius: 6px;
  background: rgba(66, 184, 131, 0.1);
  color: #6dd4a7;
  border: 1px solid rgba(66, 184, 131, 0.2);
}
.form-divider {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.5);
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 1px;
  margin-top: 8px;
  padding-top: 16px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}
.form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.form-textarea {
  background: rgba(10, 16, 27, 0.8);
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: white;
  padding: 10px 14px;
  border-radius: 8px;
  font-size: 13px;
  outline: none;
  resize: vertical;
  font-family: inherit;
}
.form-textarea:focus { border-color: #42b883; }
.cap-chips { display: flex; flex-wrap: wrap; gap: 6px; }
.cap-chip {
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.08);
  color: var(--text-dim);
  padding: 5px 12px;
  border-radius: 100px;
  font-size: 12px;
  cursor: pointer;
  font-family: inherit;
  transition: all 0.15s;
}
.cap-chip:hover { border-color: rgba(66, 184, 131, 0.4); color: var(--text-main); }
.cap-chip.active {
  background: rgba(66, 184, 131, 0.15);
  border-color: rgba(66, 184, 131, 0.4);
  color: #42b883;
}

.model-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.status-badge {
  font-size: 11px;
  padding: 3px 10px;
  border-radius: 12px;
  font-weight: 500;
}

.status-badge.enabled {
  background: rgba(66, 184, 131, 0.15);
  color: #42b883;
}

.status-badge.disabled {
  background: rgba(255, 255, 255, 0.06);
  color: var(--text-dim);
}

.action-btn {
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.08);
  color: var(--text-dim);
  padding: 6px 10px;
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
  display: flex;
  align-items: center;
  justify-content: center;
}

.action-btn:hover {
  border-color: rgba(255, 255, 255, 0.2);
  color: var(--text-main);
}

.action-btn.toggle:hover {
  border-color: #42b883;
  color: #42b883;
}

.action-btn.edit:hover {
  border-color: #3d9bff;
  color: #3d9bff;
}

.action-btn.delete:hover {
  border-color: #ff6644;
  color: #ff6644;
}

.empty-state {
  text-align: center;
  padding: 48px;
  color: var(--text-dim);
}

/* 模态框 */
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.modal {
  background: #141e30;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 16px;
  width: 480px;
  max-width: 90vw;
  max-height: 90vh;
  overflow-y: auto;
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 24px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}

.modal-header h3 {
  font-size: 18px;
  color: var(--text-main);
  margin: 0;
}

.modal-close {
  background: none;
  border: none;
  color: var(--text-dim);
  font-size: 24px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 4px;
  transition: all 0.2s;
}

.modal-close:hover {
  color: var(--text-main);
  background: rgba(255, 255, 255, 0.06);
}

.modal-body {
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-group label {
  font-size: 13px;
  color: rgba(255, 255, 255, 0.7);
  font-weight: 500;
}

.form-group input {
  background: rgba(10, 16, 27, 0.8);
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: white;
  padding: 10px 14px;
  border-radius: 8px;
  font-size: 14px;
  outline: none;
  transition: all 0.2s;
}

.form-group input:focus {
  border-color: #42b883;
  box-shadow: 0 0 0 2px rgba(66, 184, 131, 0.15);
}

.form-group.error input {
  border-color: #ff6644;
}

.form-error {
  font-size: 12px;
  color: #ff6644;
}

.sv-help {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.4);
}
.sv-help code {
  background: rgba(255, 255, 255, 0.06);
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 11px;
  color: #42b883;
}
.sv-help-inline {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.3);
  font-weight: normal;
  margin-left: 4px;
}
.preset-select {
  background: rgba(10, 16, 27, 0.8);
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: white;
  padding: 10px 14px;
  border-radius: 8px;
  font-size: 14px;
  outline: none;
  cursor: pointer;
  font-family: inherit;
}
.preset-select:focus {
  border-color: #42b883;
}
.preset-select option {
  background: #141e30;
  color: white;
}
.anthropic-badge {
  display: inline-block;
  margin-left: 8px;
  background: rgba(217, 119, 87, 0.15);
  color: #f1a684;
  padding: 1px 8px;
  border-radius: 100px;
  font-size: 10px;
  font-weight: 500;
  border: 1px solid rgba(217, 119, 87, 0.3);
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding: 16px 24px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}

.modal-btn {
  padding: 10px 24px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
  border: none;
}

.modal-btn.cancel {
  background: rgba(255, 255, 255, 0.06);
  color: var(--text-dim);
}

.modal-btn.cancel:hover {
  background: rgba(255, 255, 255, 0.1);
  color: var(--text-main);
}

.modal-btn.save {
  background: #42b883;
  color: #002418;
}

.modal-btn.save:hover {
  background: #50caa3;
}
</style>
