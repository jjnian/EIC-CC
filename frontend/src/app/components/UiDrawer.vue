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
    <div v-if="open" class="fixed inset-0 z-50">
      <div class="absolute inset-0 bg-slate-900/30" @click="emit('close')" />
      <div
        class="absolute inset-y-0 right-0 flex w-full flex-col bg-white shadow-2xl"
        :style="{ maxWidth: width || '460px' }"
      >
        <div class="flex shrink-0 items-center justify-between border-b border-slate-100 px-5 py-3.5">
          <h3 class="truncate text-[15px] font-semibold text-slate-900">{{ title }}</h3>
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
