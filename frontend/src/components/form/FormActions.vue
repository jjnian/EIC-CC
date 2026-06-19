<script setup lang="ts">
// 底部按钮区容器。提供命名插槽，并统一主/次/危险按钮样式（通过 .f-btn 类）。
// 用法：
//   <FormActions>
//     <template #left><button class="f-btn f-btn--danger">删除</button></template>
//     <button class="f-btn f-btn--ghost" @click="cancel">取消</button>
//     <button class="f-btn f-btn--primary" :disabled="!isValid" @click="save">保存</button>
//   </FormActions>
import './form.css';
</script>

<template>
  <div class="f-actions">
    <slot name="left" />
    <span class="f-spacer" />
    <slot />
  </div>
</template>
