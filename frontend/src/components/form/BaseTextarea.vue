<script setup lang="ts">
// 统一多行文本输入。
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

const onInput = (e: Event) => emit('update:modelValue', (e.target as HTMLTextAreaElement).value);
</script>

<template>
  <textarea
    class="f-textarea"
    :class="{ 'f-invalid': invalid }"
    :value="modelValue ?? ''"
    :placeholder="placeholder"
    :disabled="disabled"
    :rows="rows"
    @input="onInput"
    @blur="emit('blur')"
  ></textarea>
</template>
