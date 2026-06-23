<script setup lang="ts">
// 统一开关。内部改用 shadcn-vue <Switch>，对外 props/emit 不变。
import { Switch } from '@/components/ui/switch';
import './form.css';

defineProps<{
  modelValue: boolean;
  label?: string;
  disabled?: boolean;
}>();

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void;
}>();
</script>

<template>
  <label class="f-switch-wrap" :class="{ disabled }">
    <Switch
      :model-value="modelValue"
      :disabled="disabled"
      @update:model-value="(v: boolean) => emit('update:modelValue', v)"
    />
    <span v-if="label" class="f-switch-label">{{ label }}</span>
  </label>
</template>
