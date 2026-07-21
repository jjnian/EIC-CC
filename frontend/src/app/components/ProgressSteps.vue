<script setup lang="ts">
import { LoaderCircle, CircleCheck } from 'lucide-vue-next';

export interface StepItem { key: string; label: string }

defineProps<{
  steps: StepItem[];
  running: boolean;
}>();
</script>

<template>
  <div v-if="steps.length" class="space-y-1.5">
    <div
      v-for="(s, i) in steps"
      :key="s.key + i"
      class="flex items-center gap-2.5 text-[13px]"
    >
      <LoaderCircle
        v-if="running && i === steps.length - 1"
        :size="15"
        class="shrink-0 animate-spin text-indigo-600"
      />
      <CircleCheck v-else :size="15" class="shrink-0 text-emerald-500" />
      <span :class="running && i === steps.length - 1 ? 'font-medium text-slate-800' : 'text-slate-500'">
        {{ s.label }}
      </span>
    </div>
  </div>
</template>
