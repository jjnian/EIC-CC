<script setup lang="ts">
// 统一下拉选择。选项可传 string[] 或 {value,label}[]，也支持默认插槽自定义 <option>。
import { computed } from 'vue';
import './form.css';

interface Option { value: string | number; label: string; }

const props = defineProps<{
  modelValue: string | number | null | undefined;
  options?: (string | Option)[];
  disabled?: boolean;
  invalid?: boolean;
}>();

const emit = defineEmits<{
  (e: 'update:modelValue', v: string): void;
  (e: 'change', v: string): void;
  (e: 'blur'): void;
}>();

const normalized = computed<Option[]>(() =>
  (props.options || []).map((o) =>
    typeof o === 'string' ? { value: o, label: o } : o,
  ),
);

const onChange = (e: Event) => {
  const v = (e.target as HTMLSelectElement).value;
  emit('update:modelValue', v);
  emit('change', v);
};
</script>

<template>
  <select
    class="f-select"
    :class="{ 'f-invalid': invalid }"
    :value="modelValue ?? ''"
    :disabled="disabled"
    @change="onChange"
    @blur="emit('blur')"
  >
    <slot>
      <option v-for="o in normalized" :key="o.value" :value="o.value">{{ o.label }}</option>
    </slot>
  </select>
</template>
