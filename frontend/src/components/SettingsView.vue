<script setup lang="ts">
import { ref, onMounted } from 'vue';

interface ModelConfig {
  id: string;
  name: string;
  baseUrl: string;
  modelName: string;
  enabled: boolean;
}

const models = ref<ModelConfig[]>([]);
const loading = ref(true);
const showAddModal = ref(false);
const editingModel = ref<ModelConfig | null>(null);
const formData = ref({ name: '', baseUrl: '', modelName: '', apiKey: '' });
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

onMounted(loadModels);

const openAdd = () => {
  editingModel.value = null;
  formData.value = { name: '', baseUrl: '', modelName: '', apiKey: '' };
  formErrors.value = {};
  showAddModal.value = true;
};

const openEdit = (model: ModelConfig) => {
  editingModel.value = model;
  formData.value = { name: model.name, baseUrl: model.baseUrl, modelName: model.modelName, apiKey: '' };
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
    const res = await fetch(url, {
      method,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(formData.value)
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
            <h4>{{ m.name }}</h4>
            <span class="model-meta">{{ m.baseUrl }} · {{ m.modelName }}</span>
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
          <div class="form-group" :class="{ error: formErrors.name }">
            <label>模型名称</label>
            <input v-model="formData.name" placeholder="例如：我的自定义模型" />
            <span class="form-error" v-if="formErrors.name">{{ formErrors.name }}</span>
          </div>
          <div class="form-group" :class="{ error: formErrors.baseUrl }">
            <label>Base URL</label>
            <input v-model="formData.baseUrl" placeholder="https://api.example.com/v1" />
            <span class="form-error" v-if="formErrors.baseUrl">{{ formErrors.baseUrl }}</span>
          </div>
          <div class="form-group" :class="{ error: formErrors.modelName }">
            <label>模型名称 (Model Name)</label>
            <input v-model="formData.modelName" placeholder="例如：gpt-4o" />
            <span class="form-error" v-if="formErrors.modelName">{{ formErrors.modelName }}</span>
          </div>
          <div class="form-group">
            <label>API Key</label>
            <input type="password" v-model="formData.apiKey" :placeholder="editingModel ? '留空则不修改' : '输入 API Key'" />
            <span class="sv-help">创建时必填，编辑时留空表示不修改</span>
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
