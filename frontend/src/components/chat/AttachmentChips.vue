<script setup lang="ts">
import { type PropType } from 'vue';
import type { Attachment } from '../../composables/useAttachments';

defineProps({
  attachments: { type: Array as PropType<Attachment[]>, required: true },
});

const emit = defineEmits<{
  (e: 'remove', index: number): void;
  (e: 'preview', att: Attachment): void;
}>();

const previewable = (a: Attachment) => !a.loading && !a.error && !!a.content;
</script>

<template>
  <div v-if="attachments.length > 0" class="att-row">
    <div
      v-for="(a, i) in attachments"
      :key="i"
      class="att-chip"
      :class="{ 'att-err': a.error, 'att-img': a.kind === 'image', 'att-clickable': previewable(a) }"
      :title="a.error || (previewable(a) ? '点击查看' : (a.truncated ? '文件较大,已截断' : a.name))"
      @click="previewable(a) && emit('preview', a)"
    >
      <span class="att-kind">{{ a.kind === 'image' ? '🖼' : a.kind === 'text' ? '📄' : '📎' }}</span>
      <span class="att-name">{{ a.name }}</span>
      <span v-if="a.loading" class="att-spin" />
      <span v-else-if="a.error" class="att-bad">!</span>
      <button type="button" class="att-x" @click.stop="emit('remove', i)" title="移除">×</button>
    </div>
  </div>
</template>

<style scoped>
.att-chip { transition: background .15s, border-color .15s; }
.att-clickable { cursor: pointer; }
.att-clickable:hover { background: rgba(47, 134, 214, 0.14); border-color: rgba(47, 134, 214, 0.4); }
.att-x {
  background: none; border: none; color: inherit;
  cursor: pointer; padding: 0 2px; margin-left: 2px;
  font-size: 14px; line-height: 1; opacity: 0.6;
}
.att-x:hover { opacity: 1; }
</style>
