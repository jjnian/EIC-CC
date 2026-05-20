<script setup lang="ts">
import { type PropType } from 'vue';
import type { Attachment } from '../../composables/useAttachments';

defineProps({
  attachments: { type: Array as PropType<Attachment[]>, required: true },
});

const emit = defineEmits<{
  (e: 'remove', index: number): void;
}>();
</script>

<template>
  <div v-if="attachments.length > 0" class="att-row">
    <div
      v-for="(a, i) in attachments"
      :key="i"
      class="att-chip"
      :class="{ 'att-err': a.error, 'att-img': a.kind === 'image' }"
      :title="a.error || (a.truncated ? '文件较大,已截断' : '')"
    >
      <span class="att-kind">{{ a.kind === 'image' ? '🖼' : a.kind === 'text' ? '📄' : '📎' }}</span>
      <span class="att-name">{{ a.name }}</span>
      <span v-if="a.loading" class="att-spin" />
      <span v-else-if="a.error" class="att-bad">!</span>
      <button type="button" @click="emit('remove', i)">×</button>
    </div>
  </div>
</template>
