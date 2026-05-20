import { ref, computed } from 'vue';
import { confirm as uiConfirm } from './useConfirm';
import { listModels, createModel, updateModel, deleteModel as apiDeleteModel, toggleModel as apiToggleModel } from '../api/models';
import { getConfig } from '../api/config';
import { ApiError } from '../api/http';

export interface SettingsModelConfig {
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

export interface ProviderInfo {
  code: string;
  displayName: string;
  baseUrl: string;
  defaultModel: string;
  models: string[];
  apiKeyEnvName: string;
}

export const CAPABILITY_OPTIONS = [
  { code: 'streaming', label: '流式' },
  { code: 'vision', label: '图像' },
  { code: 'json', label: 'JSON 模式' },
  { code: 'tool-use', label: '工具调用' },
  { code: 'reasoning', label: '推理链' },
];

export const CAPABILITY_LABELS: Record<string, string> = Object.fromEntries(
  CAPABILITY_OPTIONS.map(o => [o.code, o.label])
);

export const fmtTokens = (n?: number) => {
  if (!n) return '';
  if (n >= 1000000) return (n / 1000000).toFixed(n % 1000000 === 0 ? 0 : 1) + 'M';
  if (n >= 1000) return (n / 1000).toFixed(n % 1000 === 0 ? 0 : 1) + 'K';
  return String(n);
};

export interface ModelConfigsCtx {
  showToast: (msg: string, kind: 'success' | 'error') => void;
}

/**
 * 模型管理:列表 + CRUD + 表单状态 + provider 推断。
 */
export function useModelConfigs(ctx: ModelConfigsCtx) {
  const models = ref<SettingsModelConfig[]>([]);
  const providers = ref<ProviderInfo[]>([]);
  const loading = ref(true);
  const showAddModal = ref(false);
  const editingModel = ref<SettingsModelConfig | null>(null);
  const formData = ref({
    name: '', baseUrl: '', modelName: '', apiKey: '', providerCode: '',
    description: '', contextWindow: null as number | null, maxOutputTokens: null as number | null,
    capabilities: [] as string[], protocol: 'openai',
  });
  const emptyForm = () => ({
    name: '', baseUrl: '', modelName: '', apiKey: '', providerCode: '',
    description: '', contextWindow: null as number | null, maxOutputTokens: null as number | null,
    capabilities: [] as string[], protocol: 'openai',
  });
  const formErrors = ref<Record<string, string>>({});
  const saved = ref(false);
  const saveError = ref('');
  const saving = ref(false);

  const selectedProvider = computed(() =>
    providers.value.find(p => p.code === formData.value.providerCode) || null
  );

  const loadModelList = async () => {
    try {
      models.value = await listModels();
    } catch (e) {
      console.error('Failed to load models', e);
    } finally {
      loading.value = false;
    }
  };

  const loadProviders = async () => {
    try {
      const cfg = await getConfig();
      providers.value = (cfg.providers || []).filter((p: ProviderInfo) => p.code !== 'custom');
    } catch (e) {
      console.error('Failed to load providers', e);
    }
  };

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

  const toggleCapability = (code: string) => {
    const idx = formData.value.capabilities.indexOf(code);
    if (idx >= 0) formData.value.capabilities.splice(idx, 1);
    else formData.value.capabilities.push(code);
  };

  const detectProviderCode = (baseUrl: string): string => {
    if (!baseUrl) return '';
    try {
      const host = new URL(baseUrl).hostname.toLowerCase();
      if (host.includes('dashscope')) return 'qwen';
      if (host.includes('openai')) return 'openai';
      if (host.includes('anthropic')) return 'anthropic';
      if (host.includes('deepseek')) return 'deepseek';
      if (host.includes('moonshot')) return 'moonshot';
    } catch { /* noop */ }
    return '';
  };

  const openAdd = () => {
    editingModel.value = null;
    formData.value = emptyForm();
    formErrors.value = {};
    saveError.value = '';
    showAddModal.value = true;
  };

  const openEdit = (model: SettingsModelConfig) => {
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
      protocol: model.protocol || 'openai',
    };
    formErrors.value = {};
    saveError.value = '';
    showAddModal.value = true;
  };

  const validateForm = (): boolean => {
    formErrors.value = {};
    if (!formData.value.name.trim()) formErrors.value.name = '必填';
    if (!formData.value.baseUrl.trim()) formErrors.value.baseUrl = '必填';
    if (!formData.value.modelName.trim()) formErrors.value.modelName = '必填';
    return Object.keys(formErrors.value).length === 0;
  };

  const saveModel = async () => {
    saveError.value = '';
    if (!validateForm()) { saveError.value = '请检查必填项'; return; }
    if (saving.value) return;
    saving.value = true;
    try {
      const payload = {
        name: formData.value.name,
        baseUrl: formData.value.baseUrl,
        modelName: formData.value.modelName,
        apiKey: formData.value.apiKey,
        provider: formData.value.providerCode || undefined,
        description: formData.value.description || undefined,
        contextWindow: formData.value.contextWindow ?? undefined,
        maxOutputTokens: formData.value.maxOutputTokens ?? undefined,
        capabilities: formData.value.capabilities,
        protocol: formData.value.protocol || undefined,
      };
      if (editingModel.value) {
        await updateModel(editingModel.value.id, payload);
      } else {
        await createModel(payload);
      }
      const wasEdit = !!editingModel.value;
      showAddModal.value = false;
      await loadModelList();
      ctx.showToast(wasEdit ? '修改已保存' : '模型已添加', 'success');
    } catch (e: any) {
      if (e instanceof ApiError) saveError.value = e.message;
      else saveError.value = '网络错误:' + (e?.message || e);
      ctx.showToast(saveError.value, 'error');
    } finally {
      saving.value = false;
    }
  };

  const deleteModelConfig = async (id: string) => {
    const ok = await uiConfirm({
      title: '删除模型配置',
      message: '确定要删除这个模型配置吗?',
      confirmLabel: '删除',
      danger: true,
    });
    if (!ok) return;
    try {
      await apiDeleteModel(id);
      await loadModelList();
    } catch (e) {
      console.error('Failed to delete model', e);
      if (e instanceof ApiError) ctx.showToast('删除失败 (HTTP ' + e.status + ')', 'error');
      else ctx.showToast('删除请求失败', 'error');
    }
  };

  const toggleModelEnabled = async (model: SettingsModelConfig) => {
    try {
      await apiToggleModel(model.id);
      await loadModelList();
    } catch (e) {
      console.error('Failed to toggle model', e);
    }
  };

  return {
    models, providers, loading, showAddModal, editingModel,
    formData, formErrors, saved, saveError, saving,
    selectedProvider,
    loadModelList, loadProviders, applyPreset, toggleCapability,
    detectProviderCode, openAdd, openEdit, validateForm,
    saveModel, deleteModelConfig, toggleModelEnabled,
    CAPABILITY_OPTIONS, CAPABILITY_LABELS, fmtTokens,
  };
}
