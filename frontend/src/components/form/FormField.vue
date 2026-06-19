<script setup lang="ts">
// 字段容器：标签 + 必填星 + 提示 + 控件插槽 + 校验错误行。
// 用法：
//   <FormField label="名称" required :error="fieldError('name')">
//     <BaseInput v-model="name" @blur="validateField('name')" />
//   </FormField>
import './form.css';

defineProps<{
  label?: string;
  required?: boolean;
  hint?: string;
  error?: string;
  /** 横向布局：标签在左、控件在右（用于紧凑表单）。 */
  inline?: boolean;
}>();
</script>

<template>
  <div class="f-field" :class="{ 'f-field--row': inline }">
    <label v-if="label" class="f-label">
      {{ label }}
      <span v-if="required" class="f-required">*</span>
      <span v-if="hint" class="f-hint">{{ hint }}</span>
    </label>
    <div class="f-control">
      <slot />
      <div v-if="error" class="f-error">{{ error }}</div>
    </div>
  </div>
</template>
