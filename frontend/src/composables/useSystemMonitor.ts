import { ref } from 'vue';
import { request } from '../api/http';

export function useSystemMonitor() {
  const healthData = ref<any>(null);
  const metricsData = ref<any>(null);

  const loadMonitor = async () => {
    try {
      healthData.value = await request('/api/system/health');
      metricsData.value = await request('/api/system/metrics/llm');
    } catch (e) {
      console.error('Failed to load monitor data', e);
    }
  };

  return { healthData, metricsData, loadMonitor };
}
