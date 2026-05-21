import { ref } from 'vue';
import { listModels } from '../api/models';
import { getConfig } from '../api/config';
import type { ProviderInfo } from '../api/config';
import type { ModelConfig } from '../types';

export type SettingsModelConfig = ModelConfig;
export type { ProviderInfo };

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
 * 模型管理：只读列表（配置来自 application.yml）。
 */
export function useModelConfigs(_ctx: ModelConfigsCtx) {
  const models = ref<SettingsModelConfig[]>([]);
  const providers = ref<ProviderInfo[]>([]);
  const loading = ref(true);

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

  return {
    models, providers, loading,
    loadModelList, loadProviders,
    CAPABILITY_OPTIONS, CAPABILITY_LABELS, fmtTokens,
  };
}
