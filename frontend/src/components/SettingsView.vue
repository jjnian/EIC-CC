<script setup lang="ts">
import { ref, onMounted, watch } from 'vue';
import { useModelConfigs } from '../composables/useModelConfigs';
import { useSettingsPrefs } from '../composables/useSettingsPrefs';
import { useSystemMonitor } from '../composables/useSystemMonitor';
import './settings/settings-view.css';
import ModelsTab from './settings/ModelsTab.vue';
import AppearanceTab from './settings/AppearanceTab.vue';
import DataTab from './settings/DataTab.vue';
import MonitorTab from './settings/MonitorTab.vue';
import AboutTab from './settings/AboutTab.vue';
import WorkspacesTab from './settings/WorkspacesTab.vue';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';

const emit = defineEmits<{
  (e: 'switch-workspace', id: string): void;
}>();

const localToast = ref<{ msg: string; kind: 'success' | 'error' } | null>(null);
const showToast = (msg: string, kind: 'success' | 'error' = 'success') => {
  localToast.value = { msg, kind };
  setTimeout(() => { localToast.value = null; }, 2500);
};

const mc = useModelConfigs({ showToast });
const sp = useSettingsPrefs();

const TABS = [
  { id: 'workspaces', label: '工作空间', icon: '▣' },
  { id: 'models',     label: '模型管理', icon: '◈' },
  { id: 'appearance', label: '图谱外观', icon: '✦' },
  { id: 'data',       label: '数据管理', icon: '◐' },
  { id: 'monitor',    label: '系统监控', icon: '📊' },
  { id: 'about',      label: '关于',     icon: 'ⓘ' }
];
const activeTab = ref('workspaces');
const APP_VERSION = '0.5.0';

onMounted(() => {
  mc.loadModelList();
  mc.loadProviders();
  sp.loadPrefs();
});

// 系统监控数据
const { healthData, metricsData, loadMonitor } = useSystemMonitor();

watch(activeTab, (tab) => {
  if (tab === 'monitor') loadMonitor();
});
</script>

<template>
  <div class="settings-view">
    <div class="sv-top">
      <div>
        <h2>平台设置</h2>
        <p>统一管理大模型、画布外观与数据。</p>
      </div>
      <span v-if="sp.prefsSaved.value" class="prefs-saved">已自动保存</span>
    </div>

    <Tabs v-model="activeTab" orientation="vertical" class="sv-layout">
      <TabsList class="sv-tabs">
        <TabsTrigger v-for="t in TABS" :key="t.id" :value="t.id" class="sv-tab">
          <span class="sv-tab-icon">{{ t.icon }}</span>
          <span>{{ t.label }}</span>
        </TabsTrigger>
      </TabsList>

      <div class="sv-pane">
        <WorkspacesTab v-if="activeTab === 'workspaces'"
                       @switch="(id) => emit('switch-workspace', id)" />

        <ModelsTab v-else-if="activeTab === 'models'"
                   :models="mc.models.value"
                   :loading="mc.loading.value"
                   :test-results="mc.testResults"
                   @run-test="mc.runTest"
                   @test-all="mc.testAllModels" />

        <AppearanceTab v-else-if="activeTab === 'appearance'"
                       :prefs="sp.prefs"
                       @save="sp.savePrefs" />

        <DataTab v-else-if="activeTab === 'data'"
                 :prefs="sp.prefs" />

        <MonitorTab v-else-if="activeTab === 'monitor'"
                    :health-data="healthData"
                    :metrics-data="metricsData"
                    @refresh="loadMonitor" />

        <AboutTab v-else-if="activeTab === 'about'" :version="APP_VERSION" />
      </div>
    </Tabs>

    <div v-if="localToast" class="sv-toast" :class="'sv-toast-' + localToast.kind">
      <span class="sv-toast-icon">{{ localToast.kind === 'success' ? '✓' : '✗' }}</span>
      <span>{{ localToast.msg }}</span>
    </div>
  </div>
</template>
