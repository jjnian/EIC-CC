<script setup lang="ts">
import { ref, watch, nextTick, type PropType } from 'vue';
import type { ChatMsg } from '../../composables/useConversations';

defineProps({
  messages: { type: Array as PropType<ChatMsg[]>, required: true },
  loading:  { type: Boolean, default: false },
});

const listRef = ref<HTMLElement | null>(null);

/** 暴露给父级:自动滚动到底。 */
const scrollToBottom = () => {
  nextTick(() => {
    if (listRef.value) listRef.value.scrollTop = listRef.value.scrollHeight;
  });
};

defineExpose({ scrollToBottom });
</script>

<template>
  <div class="ch-msgs" ref="listRef">
    <div v-for="(m, i) in messages" :key="i" :class="['msg', `msg-${m.role === 'u' ? 'user' : 'asst'}`]">
      <div v-if="m.role === 'a'" class="avatar">推</div>
      <div class="msg-body">
        <div v-if="m.atts && m.atts.length > 0" class="att-tags">
          <span
            v-for="(a, j) in m.atts"
            :key="j"
            class="att-sm"
            :class="{ 'att-sm-err': a.error }"
            :title="a.error || a.name"
          >
            {{ a.kind === 'image' ? '🖼' : a.kind === 'text' ? '📄' : '📎' }} {{ a.name }}
          </span>
        </div>
        <div class="bubble" :class="{ streaming: m.role === 'a' && !m.text }">
          {{ m.text }}<span v-if="loading && m.role === 'a'" class="cursor" />
        </div>
      </div>
    </div>
  </div>
</template>
