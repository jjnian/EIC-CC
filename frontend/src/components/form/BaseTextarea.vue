<script setup lang="ts">
// 统一多行文本输入。内部改用 shadcn-vue <Textarea>，对外 props/emit 不变。
import { Textarea } from '@/components/ui/textarea';
import './form.css';

withDefaults(defineProps<{
  modelValue: string | null | undefined;
  placeholder?: string;
  disabled?: boolean;
  invalid?: boolean;
  rows?: number;
}>(), {
  rows: 4,
});

const emit = defineEmits<{
  (e: 'update:modelValue', v: string): void;
  (e: 'blur'): void;
}>();

const onVal = (v: string | number) => emit('update:modelValue', String(v));
</script>

<template>
  <Textarea
    class="f-textarea-sh"
    :class="{ 'f-invalid': invalid }"
    :model-value="modelValue ?? ''"
    :placeholder="placeholder"
    :disabled="disabled"
    :rows="rows"
    :aria-invalid="invalid || undefined"
    @update:model-value="onVal"
    @blur="emit('blur')"
  />
</template>
