<script setup lang="ts">
import type { OntologyNode } from '../../types';
import { NT } from '../../constants';
import FormField from '../form/FormField.vue';
import BaseInput from '../form/BaseInput.vue';
import BaseCheckbox from '../form/BaseCheckbox.vue';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';

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
  <Dialog :open="true" @update:open="(v: boolean) => { if (!v) emit('cancel'); }">
    <DialogContent class="max-h-[86vh] overflow-y-auto sm:max-w-[520px]">
      <DialogHeader>
        <DialogTitle>编辑关系</DialogTitle>
      </DialogHeader>
      <FormField label="关系名称" required>
        <BaseInput v-model="editingRelation.label" placeholder="输入关系名称…" />
      </FormField>
      <div v-if="editRelGraphNodes.length > 0" class="anp-section">
        <div class="anp-section-title">输入节点 <span class="anp-hint">（关系的起始节点，可多选）</span></div>
        <div class="anp-node-list">
          <div v-for="n in editRelGraphNodes.filter(x => !editingRelation.outputs.includes(x.id))" :key="'ri-'+n.id" class="anp-node-option">
            <div class="anp-check-row">
              <BaseCheckbox
                :modelValue="editingRelation.inputs.includes(n.id)"
                @update:modelValue="emit('toggle-input', n.id)"
              />
              <span class="anp-node-dot" :style="{ background: (NT as any)[n.type]?.color || '#2563eb' }"></span>
              <span class="anp-node-name">{{ n.label }}</span>
            </div>
          </div>
        </div>
      </div>
      <div v-if="editRelGraphNodes.length > 0" class="anp-section">
        <div class="anp-section-title">输出节点 <span class="anp-hint">（关系的目标节点，可多选）</span></div>
        <div class="anp-node-list">
          <div v-for="n in editRelGraphNodes.filter(x => !editingRelation.inputs.includes(x.id))" :key="'ro-'+n.id" class="anp-node-option">
            <div class="anp-check-row">
              <BaseCheckbox
                :modelValue="editingRelation.outputs.includes(n.id)"
                @update:modelValue="emit('toggle-output', n.id)"
              />
              <span class="anp-node-dot" :style="{ background: (NT as any)[n.type]?.color || '#2563eb' }"></span>
              <span class="anp-node-name">{{ n.label }}</span>
            </div>
          </div>
        </div>
      </div>
      <DialogFooter class="sm:justify-between">
        <Button variant="destructive" size="sm" @click="emit('delete')">删除关系</Button>
        <div class="flex gap-2">
          <Button variant="secondary" size="sm" @click="emit('cancel')">取消</Button>
          <Button size="sm" :disabled="editingRelation.inputs.length === 0 || editingRelation.outputs.length === 0" @click="emit('save')">保存</Button>
        </div>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>

<style scoped>
.anp-check-row { display: flex; align-items: center; gap: 8px; padding: 5px 8px; border-radius: 6px; transition: background 0.12s; }
.anp-check-row:hover { background: var(--bg-elev, rgba(0, 0, 0, 0.045)); }
</style>
