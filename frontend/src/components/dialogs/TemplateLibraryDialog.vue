<script setup lang="ts">
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
  <div class="modal-mask" @click.self="emit('close')">
    <div class="version-panel">
      <div class="vp-header">
        <h3>模板库</h3>
        <button class="vp-close" @click="emit('close')">&#10005;</button>
      </div>
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
    </div>
  </div>
</template>
