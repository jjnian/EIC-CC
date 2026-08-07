<script setup lang="ts">
import type { Component } from 'vue';

defineProps<{
  icon: Component;
  title: string;
  description?: string;
}>();
</script>

<template>
  <div class="flex flex-col items-center justify-center px-6 py-14 text-center">
    <div
      class="mb-3.5 flex h-12 w-12 items-center justify-center rounded-2xl"
      style="background:var(--nav-hover);color:var(--text3)"
    >
      <component :is="icon" :size="22" :stroke-width="1.8" />
    </div>
    <div class="text-[14px] font-medium" style="color:var(--text)">{{ title }}</div>
    <div v-if="description" class="mt-1 max-w-sm text-[13px] leading-relaxed" style="color:var(--text3)">{{ description }}</div>
    <div v-if="$slots.default" class="mt-4">
      <slot />
    </div>
  </div>
</template>
