<script setup lang="ts">
import type { ModelConfig } from '../../types';

defineProps<{
  prefs: any;
  models: ModelConfig[];
}>();

defineEmits<{
  (e: 'save'): void;
}>();
</script>

<template>
  <section>
    <div class="sv-section-head">
      <div>
        <h3>推演偏好</h3>
        <p>设置场景推演（Forward Simulation）的默认参数。</p>
      </div>
    </div>

    <div class="pref-card">
      <div class="pref-row">
        <div class="pref-label">
          <div class="pref-name">默认推演步数</div>
          <div class="pref-desc">右键节点 → 从此推演 时弹窗的默认值</div>
        </div>
        <div class="pref-control">
          <input type="range" min="1" max="8" step="1" v-model.number="prefs.predictDefaultSteps" @input="$emit('save')" class="pref-slider" />
          <span class="pref-val">{{ prefs.predictDefaultSteps }} 步</span>
        </div>
      </div>

      <div class="pref-row">
        <div class="pref-label">
          <div class="pref-name">最低置信度阈值</div>
          <div class="pref-desc">小于此置信度的预测节点将以更低透明度显示</div>
        </div>
        <div class="pref-control">
          <input type="range" min="0" max="1" step="0.05" v-model.number="prefs.predictMinConfidence" @input="$emit('save')" class="pref-slider" />
          <span class="pref-val">{{ Math.round(prefs.predictMinConfidence * 100) }}%</span>
        </div>
      </div>

      <div class="pref-row">
        <div class="pref-label">
          <div class="pref-name">分步动画延迟</div>
          <div class="pref-desc">每个推演节点在画布上淡入的间隔（毫秒）</div>
        </div>
        <div class="pref-control">
          <input type="range" min="0" max="800" step="20" v-model.number="prefs.predictStepDelayMs" @input="$emit('save')" class="pref-slider" />
          <span class="pref-val">{{ prefs.predictStepDelayMs }} ms</span>
        </div>
      </div>

      <div class="pref-row">
        <div class="pref-label">
          <div class="pref-name">默认推演模型</div>
          <div class="pref-desc">不指定时使用此模型驱动推演与聊天</div>
        </div>
        <div class="pref-control">
          <select class="preset-select" v-model="prefs.defaultModelConfigId" @change="$emit('save')">
            <option value="">— 使用系统默认 —</option>
            <option v-for="m in models.filter(x => x.enabled)" :key="m.id" :value="m.id">{{ m.name }}</option>
          </select>
        </div>
      </div>
    </div>
  </section>
</template>
