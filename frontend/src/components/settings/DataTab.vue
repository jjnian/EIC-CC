<script setup lang="ts">
import { Button } from '@/components/ui/button';

defineProps<{ prefs: any }>();

const exportPrefs = (prefs: any) => {
  const blob = new Blob([JSON.stringify(prefs, null, 2)], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = 'tuiyan-prefs.json';
  a.click();
};
</script>

<template>
  <section>
    <div class="sv-section-head">
      <div>
        <h3>数据管理</h3>
        <p>导出本地偏好设置。</p>
      </div>
    </div>

    <div class="pref-card">
      <div class="pref-row">
        <div class="pref-label">
          <div class="pref-name">导出全部偏好设置</div>
          <div class="pref-desc">把当前偏好（外观等）下载为 JSON 文件</div>
        </div>
        <Button variant="secondary" size="sm" @click="exportPrefs(prefs)">下载 JSON</Button>
      </div>
    </div>
  </section>
</template>
