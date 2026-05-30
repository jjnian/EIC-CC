<script setup lang="ts">
import type { OntologyNode } from '../../types';
import { NT } from '../../constants';

defineProps<{
  editingNode: OntologyNode;
  editableGraphNodes: OntologyNode[];
  editNodeInputs: string[];
  editNodeOutputs: string[];
  editInputLabels: Record<string, string>;
  editOutputLabels: Record<string, string>;
}>();

const emit = defineEmits<{
  (e: 'save'): void;
  (e: 'cancel'): void;
  (e: 'toggle-input', id: string): void;
  (e: 'toggle-output', id: string): void;
}>();
</script>
<template>
  <div class="modal-mask" @click.self="emit('cancel')">
    <div class="add-node-dialog">
      <h3>编辑节点</h3>
      <label class="anp-label">名称
        <input v-model="editingNode.label" class="edit-input" @keydown.enter="emit('save')" />
      </label>
      <label class="anp-label">类型
        <select v-model="editingNode.type" class="edit-input">
          <option v-for="(t, k) in NT" :key="k" :value="k">{{ t.label }}</option>
        </select>
      </label>
      <div v-if="editableGraphNodes.length > 0" class="anp-section">
        <div class="anp-section-title">输入连接 <span class="anp-hint">（从哪些节点连入）</span></div>
        <div class="anp-node-list">
          <div v-for="n in editableGraphNodes" :key="'ein-'+n.id" class="anp-node-option">
            <label class="anp-check-label" @click.prevent="emit('toggle-input', n.id)">
              <span :class="['anp-checkbox', { checked: editNodeInputs.includes(n.id) }]">
                <span v-if="editNodeInputs.includes(n.id)" class="anp-check-mark">✓</span>
              </span>
              <span class="anp-node-dot" :style="{ background: (NT as any)[n.type]?.color || '#3d9bff' }"></span>
              <span class="anp-node-name">{{ n.label }}</span>
            </label>
            <input v-if="editNodeInputs.includes(n.id)" v-model="editInputLabels[n.id]" class="anp-edge-label" placeholder="关系名称" @click.stop />
          </div>
        </div>
      </div>
      <div v-if="editableGraphNodes.length > 0" class="anp-section">
        <div class="anp-section-title">输出连接 <span class="anp-hint">（连向哪些节点）</span></div>
        <div class="anp-node-list">
          <div v-for="n in editableGraphNodes" :key="'eout-'+n.id" class="anp-node-option">
            <label class="anp-check-label" @click.prevent="emit('toggle-output', n.id)">
              <span :class="['anp-checkbox', { checked: editNodeOutputs.includes(n.id) }]">
                <span v-if="editNodeOutputs.includes(n.id)" class="anp-check-mark">✓</span>
              </span>
              <span class="anp-node-dot" :style="{ background: (NT as any)[n.type]?.color || '#3d9bff' }"></span>
              <span class="anp-node-name">{{ n.label }}</span>
            </label>
            <input v-if="editNodeOutputs.includes(n.id)" v-model="editOutputLabels[n.id]" class="anp-edge-label" placeholder="关系名称" @click.stop />
          </div>
        </div>
      </div>
      <div class="edit-actions">
        <button class="edit-cancel" @click="emit('cancel')">取消</button>
        <button class="edit-save" @click="emit('save')">保存</button>
      </div>
    </div>
  </div>
</template>
