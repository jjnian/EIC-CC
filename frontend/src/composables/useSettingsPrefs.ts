import { ref } from 'vue';
import { getPrefs, savePrefs as apiSavePrefs } from '../api/prefs';

export interface SettingsPrefs {
  showEdgeLabels: boolean;
  autoFit: boolean;
  graphFontSize: number;
  defaultModelConfigId: string;
  [k: string]: unknown;
}

/**
 * 图谱外观等用户偏好。
 */
export function useSettingsPrefs() {
  const prefs = ref<SettingsPrefs>({
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

  return { prefs, prefsSaved, loadPrefs, savePrefs };
}
