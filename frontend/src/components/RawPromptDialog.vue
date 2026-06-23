<script setup lang="ts">
// P1-8：原始 prompt 查看弹窗。展示推演时发给 LLM 的完整 system + user 文本。
// UI 改用 shadcn-vue（Dialog + Button），逻辑/props/emit 保持不变。
import { ref, watch } from 'vue';
import { getRawPrompt } from '../api/explanations';
import { toast } from '../composables/useToast';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';

const props = defineProps<{
  open: boolean;
  scenarioId: string;
}>();

const emit = defineEmits<{ (e: 'close'): void }>();

const loading = ref(false);
const rawText = ref('');
const errMsg = ref('');

const load = async () => {
  if (!props.scenarioId) return;
  loading.value = true;
  rawText.value = '';
  errMsg.value = '';
  try {
    const r = await getRawPrompt(props.scenarioId);
    if (!r.rawPrompt) {
      errMsg.value = '该分支创建于早期版本，未保存原始 prompt。';
    } else {
      rawText.value = r.rawPrompt;
    }
  } catch (e) {
    errMsg.value = '无法加载原始 prompt：' + ((e as Error).message || '未知错误');
  } finally {
    loading.value = false;
  }
};

const copyAll = async () => {
  if (!rawText.value) return;
  try {
    await navigator.clipboard.writeText(rawText.value);
    toast.info('已复制到剪贴板');
  } catch {
    toast.error('复制失败，请手动选择');
  }
};

watch(() => props.open, (v) => { if (v) load(); }, { immediate: true });
</script>

<template>
  <Dialog :open="open" @update:open="(v: boolean) => { if (!v) emit('close'); }">
    <DialogContent class="flex max-h-[86vh] flex-col gap-0 overflow-hidden p-0 sm:max-w-[720px]">
      <DialogHeader class="border-b border-border px-[18px] py-[14px]">
        <DialogTitle class="flex items-center gap-2 text-sm font-semibold">
          <span class="text-base">📜</span> 原始 prompt
        </DialogTitle>
      </DialogHeader>

      <div class="flex-1 overflow-auto px-[18px] py-[14px]">
        <div v-if="loading" class="px-3 py-6 text-center text-xs text-muted-foreground">加载中…</div>
        <div v-else-if="errMsg" class="px-3 py-6 text-center text-xs text-[#ffb86c]">{{ errMsg }}</div>
        <pre v-else class="rp-pre">{{ rawText }}</pre>
      </div>

      <DialogFooter class="border-t border-border px-[18px] py-3">
        <Button variant="secondary" size="sm" @click="emit('close')">关闭</Button>
        <Button size="sm" :disabled="!rawText" @click="copyAll">复制全文</Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>

<style scoped>
.rp-pre {
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.55;
  color: var(--text-main);
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0;
  background: rgba(10, 16, 27, 0.6);
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 8px;
  padding: 12px 14px;
}
</style>
