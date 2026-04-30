<script setup lang="ts">
import { ref, onMounted, watch, computed } from 'vue';

interface ProviderInfo {
  code: string;
  displayName: string;
  baseUrl: string;
  defaultModel: string;
  models: string[];
  apiKeyEnvName: string;
}

const providers = ref<ProviderInfo[]>([]);
const selectedProvider = ref('qwen');
const baseUrl = ref('');
const modelName = ref('');
const apiKey = ref('');
const saved = ref(false);
const loading = ref(true);
const showProviderDropdown = ref(false);
const showModelDropdown = ref(false);

// 当前选中的提供商信息
const currentProviderInfo = computed(() => {
  return providers.value.find(p => p.code === selectedProvider.value);
});

// 当前提供商的模型列表
const currentModels = computed(() => {
  return currentProviderInfo.value?.models || [];
});

// 当前提供商的 API Key 环境变量名
const currentApiKeyEnvName = computed(() => {
  return currentProviderInfo.value?.apiKeyEnvName || 'LLM_API_KEY';
});

onMounted(async () => {
  try {
    const res = await fetch('/api/config');
    console.log('Config response status:', res.status);
    if (res.ok) {
      const data = await res.json();
      console.log('Config data:', data);
      providers.value = data.providers || [];
      selectedProvider.value = data.provider || 'qwen';
      baseUrl.value = data.baseUrl || '';
      modelName.value = data.modelName || '';
      console.log('Providers loaded:', providers.value.length);
      console.log('Selected provider:', selectedProvider.value);
      console.log('Current provider info:', currentProviderInfo.value);
    }
  } catch (e) {
    console.error("Failed to load config", e);
  } finally {
    loading.value = false;
  }
});

// 点击外部关闭下拉框
const handleClickOutside = (e: MouseEvent) => {
  const target = e.target as HTMLElement;
  if (!target.closest('.custom-select')) {
    showProviderDropdown.value = false;
    showModelDropdown.value = false;
  }
};

onMounted(() => {
  document.addEventListener('click', handleClickOutside);
});

import { onUnmounted } from 'vue';
onUnmounted(() => {
  document.removeEventListener('click', handleClickOutside);
});

// 当选择提供商时，自动填充对应的 baseUrl 和 modelName
watch(selectedProvider, (newProvider) => {
  const provider = providers.value.find(p => p.code === newProvider);
  if (provider && newProvider !== 'custom') {
    baseUrl.value = provider.baseUrl;
    modelName.value = provider.defaultModel;
  }
  // 切换提供商时关闭下拉框
  showProviderDropdown.value = false;
  showModelDropdown.value = false;
});

const selectProvider = (code: string) => {
  selectedProvider.value = code;
  showProviderDropdown.value = false;
};

const selectModel = (model: string) => {
  modelName.value = model;
  showModelDropdown.value = false;
};

const saveConfig = async () => {
  try {
    saved.value = false;
    const body: any = {
      provider: selectedProvider.value,
      baseUrl: baseUrl.value,
      modelName: modelName.value
    };
    if (apiKey.value) {
      body.apiKey = apiKey.value;
    }
    await fetch('/api/config', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    saved.value = true;
    setTimeout(() => saved.value = false, 2000);
  } catch (e) {
    console.error("Failed to save config", e);
  }
};
</script>

<template>
  <div class="settings-view">
    <div class="sv-header">
      <h2>平台设置</h2>
      <p>配置大语言模型的底层接入节点。支持多种 OpenAI 兼容格式的模型提供商。</p>
    </div>

    <div class="sv-card" v-if="!loading">
      <h3>模型提供商配置</h3>

      <!-- 提供商选择 -->
      <div class="sv-form-group">
        <label>选择模型提供商</label>
        <div class="custom-select" :class="{ open: showProviderDropdown }">
          <div class="custom-select-trigger" @click="showProviderDropdown = !showProviderDropdown">
            <span class="trigger-text">{{ currentProviderInfo?.displayName || '请选择' }}</span>
            <svg class="arrow" :class="{ open: showProviderDropdown }" viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" stroke-width="2" fill="none"><polyline points="6 9 12 15 18 9"/></svg>
          </div>
          <div class="custom-dropdown" v-show="showProviderDropdown">
            <div class="custom-dropdown-item"
                 v-for="p in providers"
                 :key="p.code"
                 :class="{ active: p.code === selectedProvider }"
                 @click.stop="selectProvider(p.code)">
              <span class="provider-name">{{ p.displayName }}</span>
              <span class="provider-url">{{ p.baseUrl }}</span>
              <span class="check" v-if="p.code === selectedProvider">✓</span>
            </div>
          </div>
        </div>
        <span class="sv-help">选择不同的模型提供商，系统会自动填充对应的 Base URL 和默认模型。</span>
      </div>

      <!-- Base URL -->
      <div class="sv-form-group">
        <label>大模型 Base URL</label>
        <input type="text" v-model="baseUrl" placeholder="https://api.openai.com/v1" class="sv-input" />
        <span class="sv-help">只需输入到 v1 目录级别。选择"自定义配置"时需要手动填写。</span>
      </div>

      <!-- 模型选择 -->
      <div class="sv-form-group">
        <label>选择模型</label>
        <div class="custom-select" v-if="currentModels.length > 0" :class="{ open: showModelDropdown }">
          <div class="custom-select-trigger" @click="showModelDropdown = !showModelDropdown">
            <span class="trigger-text">{{ modelName || '请选择模型' }}</span>
            <svg class="arrow" :class="{ open: showModelDropdown }" viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" stroke-width="2" fill="none"><polyline points="6 9 12 15 18 9"/></svg>
          </div>
          <div class="custom-dropdown" v-show="showModelDropdown">
            <div class="custom-dropdown-item model-item"
                 v-for="m in currentModels"
                 :key="m"
                 :class="{ active: m === modelName }"
                 @click.stop="selectModel(m)">
              <span>{{ m }}</span>
              <span class="check" v-if="m === modelName">✓</span>
            </div>
          </div>
        </div>
        <input type="text" v-model="modelName" placeholder="输入模型名称" class="sv-input" v-else />
        <span class="sv-help">指定调用哪个模型。也可在聊天面板中临时切换模型。</span>
      </div>

      <!-- API Key 配置 -->
      <div class="sv-form-group">
        <label>API Key</label>
        <div class="api-key-input-wrapper">
          <input type="password" v-model="apiKey" :placeholder="'输入 ' + currentApiKeyEnvName" class="sv-input api-key-input" />
          <button type="button" class="toggle-visibility" @click.stop>
            <svg viewBox="0 0 24 24" width="18" height="18" stroke="currentColor" stroke-width="2" fill="none"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
          </button>
        </div>
        <span class="sv-help">输入您的 API Key，或设置环境变量 <code>{{ currentApiKeyEnvName }}</code></span>
      </div>

      <!-- API Key 环境变量指引 -->
      <div class="sv-form-group" style="margin-top: 16px;">
        <label>环境变量设置指引</label>
        <div class="sv-alert">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#ffaa22" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
          <div style="flex:1">
            <strong>安全提示：</strong>如果不在上方输入 API Key，请在环境变量中设置：
            <ul class="api-key-list">
              <li><code>DASHSCOPE_API_KEY</code> 或 <code>QWEN_API_KEY</code> — 通义千问</li>
              <li><code>DEEPSEEK_API_KEY</code> — DeepSeek</li>
              <li><code>MOONSHOT_API_KEY</code> 或 <code>KIMI_API_KEY</code> — Kimi</li>
              <li><code>ZHIPU_API_KEY</code> — 智谱清言 (GLM)</li>
              <li><code>MINIMAX_API_KEY</code> — MiniMax</li>
              <li><code>OPENAI_API_KEY</code> — OpenAI</li>
              <li><code>LLM_API_KEY</code> — 通用后备（所有提供商可用）</li>
            </ul>
          </div>
        </div>
      </div>

      <div class="sv-actions">
        <button class="sv-btn-save" @click="saveConfig">
          {{ saved ? '已保存！' : '保存设置' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.settings-view {
  flex: 1;
  padding: 40px;
  overflow-y: auto;
  background: transparent;
}
.sv-header {
  margin-bottom: 32px;
}
.sv-header h2 {
  font-size: 24px;
  font-weight: 600;
  color: var(--text-main);
  margin-bottom: 8px;
  letter-spacing: 0.5px;
}
.sv-header p {
  color: var(--text-dim);
  font-size: 14px;
}
.sv-card {
  background: rgba(14, 25, 41, 0.4);
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 12px;
  padding: 24px;
  max-width: 680px;
}
.sv-card h3 {
  font-size: 16px;
  color: var(--text-main);
  margin-bottom: 24px;
  font-weight: 500;
}
.sv-form-group {
  margin-bottom: 20px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.sv-form-group label {
  font-size: 13px;
  color: rgba(255,255,255,0.8);
  font-weight: 500;
}
.sv-input {
  background: rgba(10, 16, 27, 0.8);
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: white;
  padding: 10px 14px;
  border-radius: 8px;
  font-size: 14px;
  font-family: 'JetBrains Mono', monospace;
  outline: none;
  transition: all 0.2s;
}
.sv-input:focus {
  border-color: #42b883;
  box-shadow: 0 0 0 2px rgba(66, 184, 131, 0.15);
}
.sv-help {
  font-size: 12px;
  color: rgba(255,255,255,0.4);
}
.sv-help code {
  background: rgba(0,0,0,0.3);
  padding: 2px 6px;
  border-radius: 4px;
  font-family: 'JetBrains Mono', monospace;
  color: #42b883;
}

/* 自定义下拉选择器 */
.custom-select {
  position: relative;
  cursor: pointer;
  user-select: none;
}
.custom-select-trigger {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: rgba(10, 16, 27, 0.8);
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: white;
  padding: 10px 14px;
  border-radius: 8px;
  font-size: 14px;
  transition: all 0.2s;
}
.custom-select.open .custom-select-trigger {
  border-color: #42b883;
  box-shadow: 0 0 0 2px rgba(66, 184, 131, 0.15);
}
.trigger-text {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.arrow {
  transition: transform 0.2s;
  flex-shrink: 0;
}
.arrow.open {
  transform: rotate(180deg);
}
.custom-dropdown {
  position: absolute;
  top: calc(100% + 4px);
  left: 0;
  right: 0;
  background: rgba(14, 25, 41, 0.98);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 10px;
  padding: 6px;
  max-height: 280px;
  overflow-y: auto;
  z-index: 1000;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.5);
}
.custom-dropdown-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 6px;
  font-size: 13px;
  color: rgba(255, 255, 255, 0.7);
  transition: all 0.15s;
}
.custom-dropdown-item:hover {
  background: rgba(66, 184, 131, 0.15);
  color: white;
}
.custom-dropdown-item.active {
  background: rgba(66, 184, 131, 0.2);
  color: #42b883;
}
.provider-name {
  flex: 1;
  font-weight: 500;
  font-family: 'Inter', sans-serif;
}
.provider-url {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.3);
  font-family: 'JetBrains Mono', monospace;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.model-item span:first-child {
  flex: 1;
  font-family: 'JetBrains Mono', monospace;
}
.check {
  font-size: 14px;
  color: #42b883;
  flex-shrink: 0;
}

/* API Key 输入框 */
.api-key-input-wrapper {
  position: relative;
  display: flex;
  align-items: center;
}
.api-key-input {
  flex: 1;
  padding-right: 44px;
}
.toggle-visibility {
  position: absolute;
  right: 8px;
  background: none;
  border: none;
  color: rgba(255, 255, 255, 0.4);
  cursor: pointer;
  padding: 4px;
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}
.toggle-visibility:hover {
  color: #42b883;
}

/* 警告框 */
.sv-alert {
  display: flex;
  gap: 12px;
  background: rgba(255, 170, 34, 0.08);
  border: 1px solid rgba(255, 170, 34, 0.2);
  padding: 14px;
  border-radius: 8px;
  font-size: 13px;
  color: rgba(255, 255, 255, 0.85);
  line-height: 1.5;
}
.sv-alert strong {
  color: #ffaa22;
}
.api-key-list {
  margin: 8px 0 0 0;
  padding-left: 16px;
  list-style: disc;
}
.api-key-list li {
  margin: 4px 0;
  font-size: 12px;
}
.api-key-list code {
  background: rgba(0,0,0,0.3);
  padding: 2px 6px;
  border-radius: 4px;
  font-family: 'JetBrains Mono', monospace;
  color: #42b883;
}

.sv-actions {
  margin-top: 32px;
  display: flex;
  justify-content: flex-end;
}
.sv-btn-save {
  background: #42b883;
  color: #002418;
  border: none;
  padding: 10px 24px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}
.sv-btn-save:hover {
  background: #50caa3;
}
</style>
