<script setup lang="ts">
import { Button } from '@/components/ui/button';

defineProps<{ prefs: any }>();
defineEmits<{ (e: 'clear-scenarios'): void }>();

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
        <p>清理本地存储的推演分支与缓存。</p>
      </div>
    </div>

    <div class="pref-card">
      <div class="pref-row">
        <div class="pref-label">
          <div class="pref-name">清空所有推演分支</div>
          <div class="pref-desc">删除 backend/src/main/resources/scenarios/ 下所有快照文件。仅影响推演结果，不影响原始本体模型。</div>
        </div>
        <Button variant="destructive" size="sm" @click="$emit('clear-scenarios')">清空</Button>
      </div>

      <div class="pref-row">
        <div class="pref-label">
          <div class="pref-name">导出全部偏好设置</div>
          <div class="pref-desc">把当前偏好（含推演参数、外观）下载为 JSON 文件</div>
        </div>
        <Button variant="secondary" size="sm" @click="exportPrefs(prefs)">下载 JSON</Button>
      </div>
    </div>
  </section>
</template>
