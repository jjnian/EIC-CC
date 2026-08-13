<script setup lang="ts">
import { CircleCheck, CircleAlert, Info } from 'lucide-vue-next';
import { useToastStore } from '../stores/toast';

const toast = useToastStore();

const ICONS = { success: CircleCheck, error: CircleAlert, info: Info } as const;
/* 图标颜色按类型取主题变量，双主题通用 */
const COLORS = {
  success: 'var(--success)',
  error: 'var(--danger)',
  info: 'var(--primary)',
} as const;
</script>

<template>
  <teleport to="body">
    <div class="pointer-events-none fixed right-4 top-4 z-[80] flex w-80 flex-col gap-2">
      <div
        v-for="t in toast.items"
        :key="t.id"
        class="pointer-events-auto flex items-start gap-2.5 rounded-[10px] border px-4 py-3 text-[13px] font-medium shadow-lg"
        style="background:var(--panel);border-color:var(--border);color:var(--text)"
      >
        <component :is="ICONS[t.kind]" :size="16" class="mt-0.5 shrink-0" :style="{ color: COLORS[t.kind] }" />
        <div class="min-w-0 flex-1 leading-snug break-words">{{ t.text }}</div>
      </div>
    </div>
  </teleport>
</template>
