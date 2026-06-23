<script setup lang="ts">
// 统一勾选框。内部改用 shadcn-vue <Checkbox>，对外 props/emit 不变。
import { Checkbox } from '@/components/ui/checkbox';
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
  <label class="f-check-wrap" :class="{ disabled }">
    <Checkbox
      :model-value="modelValue"
      :disabled="disabled"
      @update:model-value="(v: boolean | 'indeterminate') => emit('update:modelValue', v === true)"
    />
    <span v-if="label" class="f-check-label">{{ label }}</span>
  </label>
</template>
