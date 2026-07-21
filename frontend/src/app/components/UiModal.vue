<script setup lang="ts">
import { X } from 'lucide-vue-next';

defineProps<{
  open: boolean;
  title: string;
  width?: string;
}>();

const emit = defineEmits<{ (e: 'close'): void }>();
</script>

<template>
  <teleport to="body">
    <div v-if="open" class="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div class="absolute inset-0 bg-slate-900/40" @click="emit('close')" />
      <div
        class="card relative flex max-h-[85vh] w-full flex-col overflow-hidden"
        :style="{ maxWidth: width || '520px' }"
      >
        <div class="flex shrink-0 items-center justify-between border-b border-slate-100 px-5 py-3.5">
          <h3 class="text-[15px] font-semibold text-slate-900">{{ title }}</h3>
          <button class="rounded-md p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-600" @click="emit('close')">
            <X :size="16" />
          </button>
        </div>
        <div class="min-h-0 flex-1 overflow-y-auto px-5 py-4">
          <slot />
        </div>
        <div v-if="$slots.footer" class="shrink-0 border-t border-slate-100 px-5 py-3.5">
          <slot name="footer" />
        </div>
      </div>
    </div>
  </teleport>
</template>
