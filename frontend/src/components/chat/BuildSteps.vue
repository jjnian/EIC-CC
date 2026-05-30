<script setup lang="ts">
import { ref, computed, watch, type PropType } from 'vue';
import type { ChatBuildStep } from '../../composables/useConversations';

const props = defineProps({
  steps: { type: Array as PropType<ChatBuildStep[]>, required: true },
  done: { type: Boolean, default: false },
});

const collapsed = ref(false);

const hasError = computed(() => props.steps.some(s => s.status === 'error'));

// 完成后默认折叠，只显示标题；出错时保留展开方便排查
watch(() => props.done, (v) => {
  if (v && !hasError.value) collapsed.value = true;
}, { immediate: true });

const toggleCollapse = () => {
  if (props.done) collapsed.value = !collapsed.value;
};
</script>

<template>
  <div class="bsteps" :class="{ 'bsteps-done': done && !hasError, 'bsteps-error': hasError }">
    <div class="bsteps-head" @click="toggleCollapse">
      <span class="bsteps-icon">
        <svg v-if="hasError" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="#ff6b6b" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
          <line x1="18" y1="6" x2="6" y2="18" />
          <line x1="6" y1="6" x2="18" y2="18" />
        </svg>
        <svg v-else-if="!done" class="bsteps-spin" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
          <path d="M12 2a10 10 0 0 1 10 10" />
        </svg>
        <svg v-else viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="#42b883" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
          <polyline points="20 6 9 17 4 12" />
        </svg>
      </span>
      <span class="bsteps-title">{{ hasError ? '分析已中止' : (done ? '分析完了' : '正在分析…') }}</span>
      <span v-if="steps.length" class="bsteps-count">{{ steps.filter(s => s.status === 'done').length }}/{{ steps.length }}</span>
      <span v-if="done" class="bsteps-toggle">{{ collapsed ? '展开' : '收起' }}</span>
    </div>
    <transition name="bsteps-body">
      <div v-if="!collapsed && steps.length" class="bsteps-list">
        <TransitionGroup name="bstep" tag="div" class="bsteps-items">
          <div v-for="s in steps" :key="s.key" class="bstep" :class="`bstep-${s.status}`">
            <div class="bstep-indicator">
              <span v-if="s.status === 'running'" class="bstep-spin" />
              <svg v-else-if="s.status === 'done'" viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="#42b883" stroke-width="3" stroke-linecap="round" stroke-linejoin="round">
                <polyline points="20 6 9 17 4 12" />
              </svg>
              <svg v-else-if="s.status === 'error'" viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="#ff6b6b" stroke-width="3" stroke-linecap="round" stroke-linejoin="round">
                <line x1="18" y1="6" x2="6" y2="18" />
                <line x1="6" y1="6" x2="18" y2="18" />
              </svg>
              <span v-else class="bstep-dot" />
            </div>
            <span class="bstep-label">{{ s.label }}</span>
          </div>
        </TransitionGroup>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.bsteps {
  background: rgba(66, 184, 131, 0.06);
  border: 1px solid rgba(66, 184, 131, 0.2);
  border-radius: 10px;
  padding: 0;
  font-size: 13px;
  overflow: hidden;
  margin-bottom: 6px;
}
.bsteps-done {
  background: rgba(66, 184, 131, 0.04);
  border-color: rgba(66, 184, 131, 0.12);
}
.bsteps-error {
  background: rgba(255, 107, 107, 0.06);
  border-color: rgba(255, 107, 107, 0.25);
}
.bsteps-error .bsteps-title {
  color: #ff8a8a;
}
.bstep-error .bstep-label {
  color: #ff8a8a;
}
.bsteps-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  cursor: pointer;
  user-select: none;
  transition: background 0.15s;
}
.bsteps-head:hover {
  background: rgba(66, 184, 131, 0.08);
}
.bsteps-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.bsteps-spin {
  animation: bsteps-rotate 1s linear infinite;
  stroke: #42b883;
}
@keyframes bsteps-rotate { to { transform: rotate(360deg); } }
.bsteps-title {
  font-weight: 500;
  color: var(--text-main);
  font-size: 12.5px;
}
.bsteps-count {
  margin-left: auto;
  font-size: 11px;
  color: var(--text-dim);
  font-family: 'JetBrains Mono', 'SF Mono', monospace;
}
.bsteps-toggle {
  font-size: 10.5px;
  color: rgba(66, 184, 131, 0.7);
  padding: 1px 6px;
  border-radius: 4px;
  background: rgba(66, 184, 131, 0.08);
}
.bsteps-list {
  padding: 0 12px 10px;
}
.bsteps-items {
  display: flex;
  flex-direction: column;
  gap: 0;
  position: relative;
}
.bstep {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 5px 0;
  position: relative;
}
.bstep-indicator {
  width: 16px;
  height: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  position: relative;
  z-index: 1;
}
/* vertical connector line between steps */
.bstep:not(:last-child)::before {
  content: '';
  position: absolute;
  left: 7.5px;
  top: 22px;
  bottom: -2px;
  width: 1px;
  background: rgba(66, 184, 131, 0.15);
}
.bstep-spin {
  width: 12px;
  height: 12px;
  border: 2px solid rgba(66, 184, 131, 0.25);
  border-top-color: #42b883;
  border-radius: 50%;
  animation: bstep-spin 0.8s linear infinite;
}
@keyframes bstep-spin { to { transform: rotate(360deg); } }
.bstep-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.15);
}
.bstep-label {
  color: var(--text-dim);
  font-size: 12px;
  transition: color 0.2s;
}
.bstep-running .bstep-label {
  color: var(--text-main);
}
.bstep-done .bstep-label {
  color: rgba(255, 255, 255, 0.5);
}

/* TransitionGroup animations */
.bstep-enter-active {
  transition: all 0.3s ease-out;
}
.bstep-enter-from {
  opacity: 0;
  transform: translateY(-8px);
}
.bstep-enter-to {
  opacity: 1;
  transform: translateY(0);
}

/* body collapse animation */
.bsteps-body-enter-active,
.bsteps-body-leave-active {
  transition: all 0.25s ease;
  overflow: hidden;
}
.bsteps-body-enter-from,
.bsteps-body-leave-to {
  opacity: 0;
  max-height: 0;
  padding-top: 0;
  padding-bottom: 0;
}
.bsteps-body-enter-to,
.bsteps-body-leave-from {
  opacity: 1;
  max-height: 400px;
}
</style>
