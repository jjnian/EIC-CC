<script setup lang="ts">
// 统一下拉选择。内部改用 shadcn-vue <Select>（reka-ui），对外 props/emit 完全不变。
// 选项可传 string[] 或 {value,label}[]；空值（''/null/undefined）用哨兵内部承载，
// 因为 reka-ui SelectItem 不允许空字符串 value。
import { computed } from 'vue';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import './form.css';

interface Option { value: string | number; label: string; }

// 空值哨兵：把 ''/null/undefined 统一映射成该值在 reka-ui 内部流转。
const EMPTY = '__none__';

const props = withDefaults(defineProps<{
  modelValue: string | number | null | undefined;
  options?: (string | Option)[];
  placeholder?: string;
  disabled?: boolean;
  invalid?: boolean;
  /** 触发器尺寸，紧凑表格场景用 'sm'。 */
  size?: 'default' | 'sm';
}>(), { size: 'default' });

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

// reka-ui 用字符串值；空值映射成哨兵。
const innerValue = computed(() => {
  const v = props.modelValue;
  return v === '' || v === null || v === undefined ? EMPTY : String(v);
});

const onUpdate = (raw: unknown) => {
  const v = raw === EMPTY ? '' : String(raw ?? '');
  emit('update:modelValue', v);
  emit('change', v);
};

const itemKey = (v: string | number) => (v === '' ? EMPTY : String(v));
</script>

<template>
  <Select
    :model-value="innerValue"
    :disabled="disabled"
    @update:model-value="onUpdate"
  >
    <SelectTrigger
      class="f-select-sh"
      :class="{ 'f-invalid': invalid }"
      :size="size"
      :aria-invalid="invalid || undefined"
    >
      <SelectValue :placeholder="placeholder || '请选择'" />
    </SelectTrigger>
    <SelectContent>
      <SelectItem
        v-for="o in normalized"
        :key="itemKey(o.value)"
        :value="itemKey(o.value)"
      >
        {{ o.label }}
      </SelectItem>
    </SelectContent>
  </Select>
</template>
