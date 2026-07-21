<script setup lang="ts">
import { CircleCheck, CircleAlert, Info } from 'lucide-vue-next';
import { useToastStore } from '../stores/toast';

const toast = useToastStore();

const ICONS = { success: CircleCheck, error: CircleAlert, info: Info } as const;
const CLSES = {
  success: 'border-emerald-200 bg-emerald-50 text-emerald-800',
  error: 'border-rose-200 bg-rose-50 text-rose-800',
  info: 'border-sky-200 bg-sky-50 text-sky-800',
} as const;
</script>

<template>
  <teleport to="body">
    <div class="pointer-events-none fixed bottom-5 right-5 z-[80] flex w-80 flex-col gap-2">
      <div
        v-for="t in toast.items"
        :key="t.id"
        class="pointer-events-auto flex items-start gap-2.5 rounded-xl border px-3.5 py-3 text-[13px] shadow-lg"
        :class="CLSES[t.kind]"
      >
        <component :is="ICONS[t.kind]" :size="16" class="mt-0.5 shrink-0" />
        <div class="min-w-0 flex-1 leading-snug break-words">{{ t.text }}</div>
      </div>
    </div>
  </teleport>
</template>
