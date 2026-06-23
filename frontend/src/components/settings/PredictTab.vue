<script setup lang="ts">
import { computed } from 'vue';
import type { ModelConfig } from '../../types';
import BaseSelect from '../form/BaseSelect.vue';

const props = defineProps<{
  prefs: any;
  models: ModelConfig[];
}>();

// 选项：系统默认（空值）+ 已启用模型。空值由 BaseSelect 内部哨兵承载。
const modelOptions = computed(() => [
  { value: '', label: '— 使用系统默认 —' },
  ...props.models.filter((x) => x.enabled).map((m) => ({ value: m.id, label: m.name })),
]);

const emit = defineEmits<{
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
          <BaseSelect :modelValue="prefs.defaultModelConfigId" :options="modelOptions" @update:modelValue="prefs.defaultModelConfigId = $event; emit('save')" />
        </div>
      </div>
    </div>
  </section>
</template>
