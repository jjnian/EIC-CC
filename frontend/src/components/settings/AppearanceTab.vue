<script setup lang="ts">
defineProps<{ prefs: any }>();
defineEmits<{ (e: 'save'): void }>();
</script>

<template>
  <section>
    <div class="sv-section-head">
      <div>
        <h3>图谱外观</h3>
        <p>调整画布渲染细节。</p>
      </div>
    </div>

    <div class="pref-card">
      <div class="pref-row">
        <div class="pref-label">
          <div class="pref-name">显示边标签</div>
          <div class="pref-desc">关闭后画布更清爽，悬停仍可看关系名</div>
        </div>
        <label class="switch">
          <input type="checkbox" v-model="prefs.showEdgeLabels" @change="$emit('save')" />
          <span class="switch-slider" />
        </label>
      </div>

      <div class="pref-row">
        <div class="pref-label">
          <div class="pref-name">打开模型时自动适应屏幕</div>
          <div class="pref-desc">切换模型/分支后自动 fitView</div>
        </div>
        <label class="switch">
          <input type="checkbox" v-model="prefs.autoFit" @change="$emit('save')" />
          <span class="switch-slider" />
        </label>
      </div>

      <div class="pref-row">
        <div class="pref-label">
          <div class="pref-name">节点字号</div>
          <div class="pref-desc">画布上节点标签的字体大小</div>
        </div>
        <div class="pref-control">
          <input type="range" min="10" max="18" step="1" v-model.number="prefs.graphFontSize" @input="$emit('save')" class="pref-slider" />
          <span class="pref-val">{{ prefs.graphFontSize }} px</span>
        </div>
      </div>
    </div>
  </section>
</template>
