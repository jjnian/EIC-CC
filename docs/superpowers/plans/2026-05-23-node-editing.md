# 节点编辑与属性管理 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让用户可以直接编辑图谱节点（修改名称、类型、属性）和删除节点/关系，而不必完全依赖 AI 对话。

**Architecture:** 在 GraphCanvas 右键菜单增加"编辑节点"和"删除节点"选项，双击节点进入内联编辑。NodeInfo 面板属性 Tab 从只读改为可编辑。所有编辑操作通过 App.vue 的事件系统传递，复用已有的 history.snapshot + persistCurrentModel 保存链路。

**Tech Stack:** Vue 3.5 (Composition API), TypeScript 5.8

---

### Task 1: GraphCanvas 右键菜单增加编辑和删除选项

**Files:**
- Modify: `frontend/src/components/GraphCanvas.vue`

- [ ] **Step 1: 添加新的 emit 事件声明**

在 `defineEmits` 中添加两个新事件：

```typescript
(e: 'delete-node', id: string): void;
(e: 'edit-node', id: string): void;
```

- [ ] **Step 2: 添加右键菜单触发方法**

在已有的 `triggerPredict` 方法附近添加：

```typescript
const triggerEdit = () => {
  if (ctxMenu.value) {
    emit('edit-node', ctxMenu.value.id);
    ctxMenu.value = null;
  }
};

const triggerDelete = () => {
  if (ctxMenu.value) {
    emit('delete-node', ctxMenu.value.id);
    ctxMenu.value = null;
  }
};
```

- [ ] **Step 3: 在右键菜单模板中添加新菜单项**

在已有的"从此推演"按钮之后，添加分隔线和两个新按钮：

```html
<div class="ctx-sep"></div>
<button class="ctx-item" @click="triggerEdit">
  <span class="ctx-icon">✎</span>
  <span>编辑节点</span>
</button>
<button class="ctx-item ctx-danger" @click="triggerDelete">
  <span class="ctx-icon">✕</span>
  <span>删除节点</span>
</button>
```

- [ ] **Step 4: 添加双击编辑支持**

在节点的 div 上添加 dblclick 事件绑定。找到节点 div 的事件绑定区域，添加：

```html
@dblclick="e => { e.stopPropagation(); emit('edit-node', n.id); }"
```

- [ ] **Step 5: 添加分隔线和危险按钮的 CSS**

```css
.ctx-sep {
  height: 1px;
  background: rgba(255,255,255,0.1);
  margin: 4px 0;
}
.ctx-danger { color: #ef4444; }
.ctx-danger:hover { background: rgba(239, 68, 68, 0.15); }
```

- [ ] **Step 6: 提交**

```bash
git add frontend/src/components/GraphCanvas.vue
git commit -m "feat(graph): add edit/delete items to node context menu"
```

---

### Task 2: App.vue 增加节点编辑和删除处理逻辑

**Files:**
- Modify: `frontend/src/App.vue`

- [ ] **Step 1: 添加编辑对话框状态**

在 App.vue 的 `<script setup>` 中添加：

```typescript
const editingNode = ref<OntologyNode | null>(null);

const openEditNode = (id: string) => {
  const n = nodes.value.find(n => n.id === id);
  if (n) editingNode.value = { ...n };
};

const saveEditNode = (updated: OntologyNode) => {
  const idx = nodes.value.findIndex(n => n.id === updated.id);
  if (idx === -1) return;
  history.snapshot();
  nodes.value[idx] = { ...nodes.value[idx], label: updated.label, type: updated.type };
  editingNode.value = null;
  persistCurrentModel();
};

const cancelEditNode = () => {
  editingNode.value = null;
};
```

- [ ] **Step 2: 添加删除节点逻辑**

```typescript
const deleteNode = async (id: string) => {
  const n = nodes.value.find(n => n.id === id);
  if (!n) return;
  const ok = await confirm({
    title: '删除节点',
    message: `确定删除节点「${n.label}」？相关的关系也会一并删除。`,
    danger: true,
    confirmText: '删除',
  });
  if (!ok) return;
  history.snapshot();
  nodes.value = nodes.value.filter(n => n.id !== id);
  edges.value = edges.value.filter(e => e.from !== id && e.to !== id);
  if (sel.value === id) sel.value = null;
  persistCurrentModel();
};
```

注意：`confirm` 函数来自已有的 `useConfirm` composable，App.vue 中应该已经引入了。如果没有，需要在 imports 中添加。

- [ ] **Step 3: 在 GraphView 组件绑定中传递新事件**

找到 `<GraphView>` 的事件绑定，添加：

```html
@edit-node="openEditNode"
@delete-node="deleteNode"
```

- [ ] **Step 4: 在 GraphView.vue 中透传新事件**

由于 GraphView 是中间层组件，需要把 edit-node 和 delete-node 事件从 GraphCanvas 透传到 App.vue。找到 GraphView.vue 中 GraphCanvas 的绑定，确保新事件被透传。

- [ ] **Step 5: 添加编辑对话框模板**

在 App.vue 模板中（合适的位置，比如其他对话框附近）添加：

```html
<div v-if="editingNode" class="modal-mask" @click.self="cancelEditNode">
  <div class="edit-node-dialog">
    <h3>编辑节点</h3>
    <label>名称
      <input v-model="editingNode.label" class="edit-input" />
    </label>
    <label>类型
      <select v-model="editingNode.type" class="edit-input">
        <option v-for="(t, k) in NT" :key="k" :value="k">{{ t.label }}</option>
      </select>
    </label>
    <div class="edit-actions">
      <button class="edit-cancel" @click="cancelEditNode">取消</button>
      <button class="edit-save" @click="saveEditNode(editingNode)">保存</button>
    </div>
  </div>
</div>
```

- [ ] **Step 6: 添加编辑对话框样式**

```css
.edit-node-dialog {
  background: #1a2332;
  border: 1px solid rgba(255,255,255,0.12);
  border-radius: 12px;
  padding: 20px;
  width: 340px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.edit-node-dialog h3 { margin: 0; color: #e2e8f0; font-size: 16px; }
.edit-node-dialog label { display: flex; flex-direction: column; gap: 4px; color: rgba(255,255,255,0.6); font-size: 13px; }
.edit-input {
  background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.15);
  border-radius: 6px;
  padding: 8px 10px;
  color: #e2e8f0;
  font-size: 14px;
  outline: none;
}
.edit-input:focus { border-color: #42b883; }
.edit-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 4px; }
.edit-cancel {
  background: transparent;
  border: 1px solid rgba(255,255,255,0.15);
  color: rgba(255,255,255,0.6);
  border-radius: 6px;
  padding: 6px 16px;
  cursor: pointer;
}
.edit-save {
  background: #42b883;
  border: none;
  color: #fff;
  border-radius: 6px;
  padding: 6px 16px;
  cursor: pointer;
}
```

- [ ] **Step 7: 提交**

```bash
git add frontend/src/App.vue frontend/src/components/views/GraphView.vue
git commit -m "feat(graph): add node edit dialog and delete with confirmation"
```

---

### Task 3: NodeInfo 面板属性 Tab 可编辑

**Files:**
- Modify: `frontend/src/components/NodeInfo.vue`

- [ ] **Step 1: 添加编辑 emit 声明**

```typescript
const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'update-node-props', id: string, props: { key: string; value: any; source?: string }[]): void;
  (e: 'delete-edge', edgeId: string): void;
}>();
```

- [ ] **Step 2: 在属性 Tab 添加编辑功能**

将属性 Tab 中的只读展示改为可编辑：每个属性值变为 input，添加删除按钮，底部添加"新增属性"按钮。

找到属性 Tab 的模板部分（Tab 2），替换为可编辑版本：

```html
<div v-if="tab === 2 && node" class="ni-prop-list">
  <div v-for="(p, i) in editableProps" :key="i" class="ni-prop-row">
    <input v-model="p.key" class="ni-prop-key-input" placeholder="键" />
    <input v-model="p.value" class="ni-prop-val-input" placeholder="值" />
    <button class="ni-prop-del" @click="removeEditableProp(i)" title="删除">✕</button>
  </div>
  <button class="ni-prop-add" @click="addEditableProp">+ 新增属性</button>
  <button v-if="propsChanged" class="ni-prop-save" @click="saveProps">保存属性</button>
</div>
```

- [ ] **Step 3: 添加属性编辑逻辑**

```typescript
const editableProps = ref<{ key: string; value: any; source?: string }[]>([]);

watch(() => props.node, (n) => {
  editableProps.value = n?.props ? n.props.map(p => ({ ...p })) : [];
}, { immediate: true });

const propsChanged = computed(() => {
  return JSON.stringify(editableProps.value) !== JSON.stringify(props.node?.props || []);
});

const addEditableProp = () => {
  editableProps.value.push({ key: '', value: '' });
};

const removeEditableProp = (i: number) => {
  editableProps.value.splice(i, 1);
};

const saveProps = () => {
  if (!props.node) return;
  const cleaned = editableProps.value.filter(p => p.key.trim());
  emit('update-node-props', props.node.id, cleaned);
};
```

- [ ] **Step 4: 在关系 Tab 添加删除关系按钮**

在每个关系行的末尾添加删除按钮：

```html
<button class="ni-edge-del" @click="emit('delete-edge', e.id)" title="删除关系">✕</button>
```

- [ ] **Step 5: 添加编辑相关 CSS**

```css
.ni-prop-row { display: flex; gap: 6px; align-items: center; }
.ni-prop-key-input, .ni-prop-val-input {
  flex: 1;
  background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.12);
  border-radius: 4px;
  padding: 4px 8px;
  color: #e2e8f0;
  font-size: 12px;
  outline: none;
}
.ni-prop-key-input { max-width: 100px; }
.ni-prop-key-input:focus, .ni-prop-val-input:focus { border-color: #42b883; }
.ni-prop-del, .ni-edge-del {
  background: transparent;
  border: none;
  color: rgba(255,255,255,0.3);
  cursor: pointer;
  font-size: 12px;
  padding: 2px 4px;
}
.ni-prop-del:hover, .ni-edge-del:hover { color: #ef4444; }
.ni-prop-add {
  background: transparent;
  border: 1px dashed rgba(255,255,255,0.15);
  color: rgba(255,255,255,0.5);
  border-radius: 4px;
  padding: 4px 12px;
  font-size: 12px;
  cursor: pointer;
  width: 100%;
  margin-top: 4px;
}
.ni-prop-add:hover { border-color: #42b883; color: #42b883; }
.ni-prop-save {
  background: #42b883;
  border: none;
  color: #fff;
  border-radius: 4px;
  padding: 6px 16px;
  font-size: 12px;
  cursor: pointer;
  margin-top: 6px;
  align-self: flex-end;
}
```

- [ ] **Step 6: 提交**

```bash
git add frontend/src/components/NodeInfo.vue
git commit -m "feat(nodeinfo): make props editable and add delete-edge button"
```

---

### Task 4: App.vue 响应 NodeInfo 的编辑事件

**Files:**
- Modify: `frontend/src/App.vue`
- Modify: `frontend/src/components/views/GraphView.vue`

- [ ] **Step 1: 在 App.vue 中添加属性更新和边删除处理**

```typescript
const updateNodeProps = (id: string, newProps: { key: string; value: any; source?: string }[]) => {
  const n = nodes.value.find(n => n.id === id);
  if (!n) return;
  history.snapshot();
  n.props = newProps;
  persistCurrentModel();
};

const deleteEdge = (edgeId: string) => {
  const idx = edges.value.findIndex(e => e.id === edgeId);
  if (idx === -1) return;
  history.snapshot();
  edges.value.splice(idx, 1);
  persistCurrentModel();
};
```

- [ ] **Step 2: 在 GraphView / App 模板中绑定新事件**

在 NodeInfo 组件的事件绑定中添加：

```html
@update-node-props="updateNodeProps"
@delete-edge="deleteEdge"
```

需要确保 GraphView.vue 正确透传这些事件到 App.vue。

- [ ] **Step 3: 验证编译**

Run: `cd frontend && npx tsc --noEmit`

- [ ] **Step 4: 提交**

```bash
git add frontend/src/App.vue frontend/src/components/views/GraphView.vue
git commit -m "feat(graph): wire up node prop editing and edge deletion"
```
