<script setup lang="ts">
// 统一文本/数字/密码输入。密码类型自动带显隐切换。
// 内部改用 shadcn-vue <Input>，对外 props/emit 完全不变。
import { computed, ref } from 'vue';
import { Input } from '@/components/ui/input';
import './form.css';

const props = withDefaults(defineProps<{
  modelValue: string | number | null | undefined;
  type?: 'text' | 'number' | 'password';
  placeholder?: string;
  disabled?: boolean;
  invalid?: boolean;
  min?: number;
  max?: number;
  /** 数字输入时是否把值作为 number emit（等价 v-model.number）。 */
  numeric?: boolean;
}>(), {
  type: 'text',
});

const emit = defineEmits<{
  (e: 'update:modelValue', v: string | number): void;
  (e: 'blur'): void;
  (e: 'enter'): void;
}>();

const reveal = ref(false);
const isPassword = computed(() => props.type === 'password');
const inputType = computed(() => (isPassword.value && reveal.value ? 'text' : props.type));

const onVal = (raw: string | number) => {
  if (props.numeric || props.type === 'number') {
    emit('update:modelValue', raw === '' ? ('' as any) : Number(raw));
  } else {
    emit('update:modelValue', raw);
  }
};
</script>

<template>
  <div class="f-input-wrap">
    <Input
      class="f-input-sh"
      :class="{ 'f-invalid': invalid }"
      :type="inputType"
      :model-value="modelValue ?? ''"
      :placeholder="placeholder"
      :disabled="disabled"
      :min="min"
      :max="max"
      :aria-invalid="invalid || undefined"
      @update:model-value="onVal"
      @blur="emit('blur')"
      @keydown.enter="emit('enter')"
    />
    <button
      v-if="isPassword"
      type="button"
      class="f-eye"
      :title="reveal ? '隐藏' : '显示'"
      @click="reveal = !reveal"
    >{{ reveal ? '🙈' : '👁' }}</button>
  </div>
</template>
