import { ref } from 'vue';
import { confirm as uiConfirm } from './useConfirm';
import { toast } from './useToast';
import { getPrefs, savePrefs as apiSavePrefs, clearAllScenarios as apiClearAllScenarios } from '../api/prefs';
import { ApiError } from '../api/http';

export interface SettingsPrefs {
  predictDefaultSteps: number;
  predictMinConfidence: number;
  predictStepDelayMs: number;
  showEdgeLabels: boolean;
  autoFit: boolean;
  graphFontSize: number;
  defaultModelConfigId: string;
  [k: string]: unknown;
}

/**
 * 推演偏好 + 图谱外观 + 数据管理(清空 scenarios)。
 */
export function useSettingsPrefs() {
  const prefs = ref<SettingsPrefs>({
    predictDefaultSteps: 4,
    predictMinConfidence: 0.3,
    predictStepDelayMs: 220,
    showEdgeLabels: true,
    autoFit: true,
    graphFontSize: 13,
    defaultModelConfigId: '',
  });
  const prefsSaved = ref(false);
  let prefsTimer: number | null = null;

  const loadPrefs = async () => {
    try {
      const data = await getPrefs();
      prefs.value = { ...prefs.value, ...data };
    } catch (e) { console.error(e); }
  };

  const savePrefs = () => {
    if (prefsTimer) clearTimeout(prefsTimer);
    prefsTimer = window.setTimeout(async () => {
      try {
        await apiSavePrefs(prefs.value);
        prefsSaved.value = true;
        setTimeout(() => prefsSaved.value = false, 1500);
      } catch (e) { console.error(e); }
    }, 400);
  };

  const clearAllScenarios = async () => {
    const ok = await uiConfirm({
      title: '清空所有推演分支',
      message: '此操作不可撤销,确定要删除所有推演分支数据?',
      confirmLabel: '全部清空',
      danger: true,
    });
    if (!ok) return;
    try {
      const data = await apiClearAllScenarios();
      toast.success(`已删除 ${data.count ?? 0} 个推演分支`);
    } catch (e) {
      console.error(e);
      if (e instanceof ApiError) toast.error('清空失败 (HTTP ' + e.status + ')');
      else toast.error('请求异常');
    }
  };

  return { prefs, prefsSaved, loadPrefs, savePrefs, clearAllScenarios };
}
