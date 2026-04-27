<script setup lang="ts">
import { ref, onMounted } from 'vue';

const baseUrl = ref('https://dashscope.aliyuncs.com/compatible-mode/v1');
const modelName = ref('qwen-max');
const saved = ref(false);
const loading = ref(true);

onMounted(async () => {
  try {
    const res = await fetch('/api/config');
    if (res.ok) {
      const data = await res.json();
      baseUrl.value = data.baseUrl || baseUrl.value;
      modelName.value = data.modelName || modelName.value;
    }
  } catch (e) {
    console.error("Failed to load config", e);
  } finally {
    loading.value = false;
  }
});

const saveConfig = async () => {
  try {
    saved.value = false;
    await fetch('/api/config', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        baseUrl: baseUrl.value,
        modelName: modelName.value
      })
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
      <p>配置大语言模型的底层接入节点。目前默认对接 OpenAI 兼容格式。</p>
    </div>

    <div class="sv-card" v-if="!loading">
      <h3>模型网络配置</h3>

      <div class="sv-form-group">
        <label>大模型 Base URL</label>
        <input type="text" v-model="baseUrl" placeholder="https://api.openai.com/v1" />
        <span class="sv-help">只需输入到 v1 目录级别，例如通义千问配置：https://dashscope.aliyuncs.com/compatible-mode/v1</span>
      </div>

      <div class="sv-form-group">
        <label>模型名称 (Model Name)</label>
        <input type="text" v-model="modelName" placeholder="gpt-4o / qwen-max" />
        <span class="sv-help">指定调用哪个模型，比如 "qwen-max" 或 "ep-2024..."。</span>
      </div>

      <div class="sv-form-group" style="margin-top: 16px;">
        <label>API Key 设置指引</label>
        <div class="sv-alert">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#ffaa22" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
          <div style="flex:1">
            <strong>为了绝对的安全：</strong>请在画布界面底部的终端或页面左下部的"Settings/环境变数"中手动输入 <code>LLM_API_KEY</code> 密钥。本设置页仅保存公开接口不保存私有密钥。
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
.sv-form-group input {
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
.sv-form-group input:focus {
  border-color: #42b883;
  box-shadow: 0 0 0 2px rgba(66, 184, 131, 0.15);
}
.sv-help {
  font-size: 12px;
  color: rgba(255,255,255,0.4);
}
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
