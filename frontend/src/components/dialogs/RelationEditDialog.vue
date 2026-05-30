<script setup lang="ts">
import type { OntologyNode } from '../../types';
import { NT } from '../../constants';

interface EditingRelation {
  label: string;
  originalLabel: string;
  inputs: string[];
  outputs: string[];
}

defineProps<{
  editingRelation: EditingRelation;
  editRelGraphNodes: OntologyNode[];
}>();

const emit = defineEmits<{
  (e: 'save'): void;
  (e: 'cancel'): void;
  (e: 'delete'): void;
  (e: 'toggle-input', id: string): void;
  (e: 'toggle-output', id: string): void;
}>();
</script>
<template>
  <div class="modal-mask" @click.self="emit('cancel')">
    <div class="add-node-dialog" @click.stop>
      <h3>编辑关系</h3>
      <label class="anp-label">关系名称
        <input v-model="editingRelation.label" class="edit-input" placeholder="输入关系名称…" @keydown.escape="emit('cancel')" />
      </label>
      <div v-if="editRelGraphNodes.length > 0" class="anp-section">
        <div class="anp-section-title">输入节点 <span class="anp-hint">（关系的起始节点，可多选）</span></div>
        <div class="anp-node-list">
          <div v-for="n in editRelGraphNodes.filter(x => !editingRelation.outputs.includes(x.id))" :key="'ri-'+n.id" class="anp-node-option">
            <label class="anp-check-label" @click.prevent="emit('toggle-input', n.id)">
              <span :class="['anp-checkbox', { checked: editingRelation.inputs.includes(n.id) }]">
                <span v-if="editingRelation.inputs.includes(n.id)" class="anp-check-mark">✓</span>
              </span>
              <span class="anp-node-dot" :style="{ background: (NT as any)[n.type]?.color || '#3d9bff' }"></span>
              <span class="anp-node-name">{{ n.label }}</span>
            </label>
          </div>
        </div>
      </div>
      <div v-if="editRelGraphNodes.length > 0" class="anp-section">
        <div class="anp-section-title">输出节点 <span class="anp-hint">（关系的目标节点，可多选）</span></div>
        <div class="anp-node-list">
          <div v-for="n in editRelGraphNodes.filter(x => !editingRelation.inputs.includes(x.id))" :key="'ro-'+n.id" class="anp-node-option">
            <label class="anp-check-label" @click.prevent="emit('toggle-output', n.id)">
              <span :class="['anp-checkbox', { checked: editingRelation.outputs.includes(n.id) }]">
                <span v-if="editingRelation.outputs.includes(n.id)" class="anp-check-mark">✓</span>
              </span>
              <span class="anp-node-dot" :style="{ background: (NT as any)[n.type]?.color || '#3d9bff' }"></span>
              <span class="anp-node-name">{{ n.label }}</span>
            </label>
          </div>
        </div>
      </div>
      <div class="edit-actions">
        <button class="edit-delete" @click="emit('delete')">删除关系</button>
        <button class="edit-cancel" @click="emit('cancel')">取消</button>
        <button class="edit-save" @click="emit('save')" :disabled="editingRelation.inputs.length === 0 || editingRelation.outputs.length === 0">保存</button>
      </div>
    </div>
  </div>
</template>
