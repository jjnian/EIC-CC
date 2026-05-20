import { ref, computed } from 'vue';
import { getConfig } from '../api/config';
import { getPrefs } from '../api/prefs';

export interface ModelOption {
  id: string;
  name: string;
  type: 'preset' | 'custom';
  configId?: string;
}

/**
 * 聊天面板的模型选择器:加载预置 + 自定义模型,优先选择 prefs.defaultModelConfigId。
 */
export function useChatModels() {
  const currentModel = ref<ModelOption | null>(null);
  const availableModels = ref<ModelOption[]>([]);
  const showModelPicker = ref(false);

  const presetModels = computed(() => availableModels.value.filter(x => x.type === 'preset'));
  const customModels = computed(() => availableModels.value.filter(x => x.type === 'custom'));

  const loadModels = async () => {
    try {
      const data = await getConfig();
      const models: ModelOption[] = [];

      if (data.provider && data.modelName) {
        const provider = (data.providers || []).find(p => p.code === data.provider);
        const modelName = data.modelName;
        if (provider && provider.models) {
          provider.models.forEach(m => {
            models.push({ id: m, name: m, type: 'preset' });
          });
        } else {
          models.push({ id: modelName, name: modelName, type: 'preset' });
        }
      }

      if (data.customModels) {
        data.customModels
          .filter(m => m.enabled)
          .forEach(m => {
            models.push({ id: m.id, name: m.name, type: 'custom', configId: m.id });
          });
      }

      availableModels.value = models;
      if (models.length > 0) {
        let defaultId: string | null = null;
        try {
          const prefs = await getPrefs();
          if (prefs.defaultModelConfigId) defaultId = prefs.defaultModelConfigId as string;
        } catch { /* prefs 不可读时退化用第一个 */ }
        const preferred = defaultId
          ? models.find(m => m.configId === defaultId || m.id === defaultId)
          : null;
        currentModel.value = preferred || models[0];
      }
    } catch (e) {
      console.error('Failed to load models', e);
    }
  };

  const selectModel = (model: ModelOption) => {
    currentModel.value = model;
    showModelPicker.value = false;
  };

  return {
    currentModel,
    availableModels,
    showModelPicker,
    presetModels,
    customModels,
    loadModels,
    selectModel,
  };
}
