<script setup lang="ts">
// 统一勾选框。
import './form.css';

const props = defineProps<{
  modelValue: boolean;
  label?: string;
  disabled?: boolean;
}>();

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void;
}>();

const toggle = () => {
  if (props.disabled) return;
  emit('update:modelValue', !props.modelValue);
};
</script>

<template>
  <span
    class="f-check"
    :class="{ on: modelValue, disabled }"
    role="checkbox"
    :aria-checked="modelValue"
    tabindex="0"
    @click="toggle"
    @keydown.enter.prevent="toggle"
    @keydown.space.prevent="toggle"
  >
    <span class="f-check-box"><span v-if="modelValue" class="f-check-mark">✓</span></span>
    <span v-if="label">{{ label }}</span>
  </span>
</template>
