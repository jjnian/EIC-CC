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
    <div class="mb-3.5 flex h-12 w-12 items-center justify-center rounded-2xl bg-slate-100 text-slate-400">
      <component :is="icon" :size="22" :stroke-width="1.8" />
    </div>
    <div class="text-[14px] font-medium text-slate-700">{{ title }}</div>
    <div v-if="description" class="mt-1 max-w-sm text-[13px] leading-relaxed text-slate-400">{{ description }}</div>
    <div v-if="$slots.default" class="mt-4">
      <slot />
    </div>
  </div>
</template>
