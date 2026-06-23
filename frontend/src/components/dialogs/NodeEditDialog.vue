<script setup lang="ts">
import type { OntologyNode } from '../../types';
import { NT } from '../../constants';
import FormField from '../form/FormField.vue';
import BaseInput from '../form/BaseInput.vue';
import BaseSelect from '../form/BaseSelect.vue';
import BaseCheckbox from '../form/BaseCheckbox.vue';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';

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

const typeOptions = Object.entries(NT).map(([k, t]) => ({ value: k, label: (t as any).label }));
</script>
<template>
  <Dialog :open="true" @update:open="(v: boolean) => { if (!v) emit('cancel'); }">
    <DialogContent class="max-h-[86vh] overflow-y-auto sm:max-w-[520px]">
      <DialogHeader>
        <DialogTitle>编辑节点</DialogTitle>
      </DialogHeader>
      <FormField label="名称" required>
        <BaseInput v-model="editingNode.label" @enter="emit('save')" />
      </FormField>
      <FormField label="类型">
        <BaseSelect v-model="editingNode.type" :options="typeOptions" />
      </FormField>
      <div v-if="editableGraphNodes.length > 0" class="anp-section">
        <div class="anp-section-title">输入连接 <span class="anp-hint">（从哪些节点连入）</span></div>
        <div class="anp-node-list">
          <div v-for="n in editableGraphNodes" :key="'ein-'+n.id" class="anp-node-option">
            <div class="anp-check-row">
              <BaseCheckbox
                :modelValue="editNodeInputs.includes(n.id)"
                @update:modelValue="emit('toggle-input', n.id)"
              />
              <span class="anp-node-dot" :style="{ background: (NT as any)[n.type]?.color || '#3d9bff' }"></span>
              <span class="anp-node-name">{{ n.label }}</span>
            </div>
            <input v-if="editNodeInputs.includes(n.id)" v-model="editInputLabels[n.id]" class="anp-edge-label" placeholder="关系名称" @click.stop />
          </div>
        </div>
      </div>
      <div v-if="editableGraphNodes.length > 0" class="anp-section">
        <div class="anp-section-title">输出连接 <span class="anp-hint">（连向哪些节点）</span></div>
        <div class="anp-node-list">
          <div v-for="n in editableGraphNodes" :key="'eout-'+n.id" class="anp-node-option">
            <div class="anp-check-row">
              <BaseCheckbox
                :modelValue="editNodeOutputs.includes(n.id)"
                @update:modelValue="emit('toggle-output', n.id)"
              />
              <span class="anp-node-dot" :style="{ background: (NT as any)[n.type]?.color || '#3d9bff' }"></span>
              <span class="anp-node-name">{{ n.label }}</span>
            </div>
            <input v-if="editNodeOutputs.includes(n.id)" v-model="editOutputLabels[n.id]" class="anp-edge-label" placeholder="关系名称" @click.stop />
          </div>
        </div>
      </div>
      <DialogFooter>
        <Button variant="secondary" size="sm" @click="emit('cancel')">取消</Button>
        <Button size="sm" @click="emit('save')">保存</Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>

<style scoped>
.anp-check-row { display: flex; align-items: center; gap: 8px; padding: 5px 8px; border-radius: 6px; transition: background 0.12s; }
.anp-check-row:hover { background: rgba(255, 255, 255, 0.06); }
</style>
