<script setup lang="ts">
// UI 改用 shadcn-vue Dialog；props/emit/逻辑不变（父组件 v-if 挂载，故恒 open）。
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';

defineProps<{
  templates: any[];
  templatesLoading: boolean;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'create-from', tpl: any): void;
  (e: 'remove', id: string): void;
}>();
</script>
<template>
  <Dialog :open="true" @update:open="(v: boolean) => { if (!v) emit('close'); }">
    <DialogContent class="max-h-[86vh] overflow-y-auto sm:max-w-[560px]">
      <DialogHeader>
        <DialogTitle>模板库</DialogTitle>
      </DialogHeader>
      <div v-if="templatesLoading" class="vp-loading">加载中…</div>
      <div v-else-if="templates.length === 0" class="vp-empty">暂无模板，可在图谱视图中点击「存为模板」保存当前模型为模板</div>
      <div v-else class="vp-list">
        <div v-for="t in templates" :key="t.id" class="vp-item" style="display:flex;justify-content:space-between;align-items:center">
          <div @click="emit('create-from', t)" style="flex:1;cursor:pointer">
            <div class="vp-time">{{ t.title || t.name || '未命名' }}</div>
            <div class="vp-meta">
              {{ t.graphData?.nodes?.length || 0 }} 节点 · {{ t.graphData?.edges?.length || 0 }} 关系
              {{ t.desc ? ' · ' + t.desc : '' }}
            </div>
          </div>
          <button class="ml-del" @click.stop="emit('remove', t.id)" title="删除模板">×</button>
        </div>
      </div>
    </DialogContent>
  </Dialog>
</template>
