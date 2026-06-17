import { reactive, ref, computed, watch, type Ref } from 'vue';
import type { OntologyNode, OntologyEdge } from '../types';

interface Draft {
  label: string;
  type: string;
  source: string;
  derived_source: string;
  derived_database: string;
  derived_tables: string[];
  attributes: any[];
  labels: Record<string, string>;
  constraints: any[];
}

const emptyDraft = (): Draft => ({
  label: '', type: 'class', source: 'manual',
  derived_source: '', derived_database: '',
  derived_tables: [], attributes: [], labels: {}, constraints: [],
});

/**
 * 节点详情面板的「按 tab 编辑」草稿层：每个 tab（基本/属性/关系标签/约束）独立进入编辑，
 * 未保存的改动只落在本地 draft 上，保存才通过回调上报给上层去 persist。
 * <p>从 NodeInfo.vue 抽出。切换选中节点或切 tab 时自动清空草稿、退出编辑。
 */
export function useNodeEditDraft(deps: {
  node: () => OntologyNode | null | undefined;
  edges: () => OntologyEdge[];
  tab: Ref<number>;
  updateNode: (id: string, patch: Record<string, any>) => void;
  updateEdge: (id: string, patch: Record<string, any>) => void;
}) {
  const { node, edges, tab, updateNode, updateEdge } = deps;

  // 每个 tab 独立编辑：未保存时改动只在本地 draft 上；保存才会 emit 给上层去 persist
  const editMode = reactive<Record<number, boolean>>({ 0: false, 1: false, 2: false, 3: false });
  // draft 字段按 tab 复用；切 tab 会清空。一直保持非空，避免模板里到处判 null。
  const draft = ref<Draft>(emptyDraft());

  const isEditing = computed(() => !!editMode[tab.value]);

  const startEdit = () => {
    const n = node();
    if (!n) return;
    const d = emptyDraft();
    if (tab.value === 0) {
      d.label = n.label || '';
      d.type = n.type || 'class';
      d.source = n.source || 'manual';
      d.derived_source = n.derived_source || '';
      d.derived_database = n.derived_database || '';
      d.derived_tables = [...(n.derived_tables || [])];
    } else if (tab.value === 1) {
      d.attributes = (n.attributes || []).map((a: any) => ({ ...a }));
    } else if (tab.value === 2) {
      for (const e of edges()) {
        if (e.from === n.id || e.to === n.id) d.labels[e.id] = e.label || '';
      }
    } else if (tab.value === 3) {
      d.constraints = (n.constraints || []).map((c: any) => ({ ...c }));
    }
    draft.value = d;
    editMode[tab.value] = true;
  };

  const cancelEdit = () => {
    draft.value = emptyDraft();
    editMode[tab.value] = false;
  };

  const saveEdit = () => {
    const n = node();
    if (!n) return;
    const id = n.id;
    const d = draft.value;
    if (tab.value === 0) {
      updateNode(id, {
        label: d.label,
        type: d.type,
        source: d.source,
        derived_source: d.derived_source || undefined,
        derived_database: d.derived_database || undefined,
        derived_tables: d.derived_tables.filter((s: string) => !!s),
      });
    } else if (tab.value === 1) {
      updateNode(id, { attributes: d.attributes });
    } else if (tab.value === 2) {
      for (const [eid, lb] of Object.entries(d.labels)) {
        const orig = edges().find(e => e.id === eid);
        if (orig && (orig.label || '') !== lb) {
          updateEdge(eid, { label: lb });
        }
      }
    } else if (tab.value === 3) {
      updateNode(id, { constraints: d.constraints });
    }
    draft.value = emptyDraft();
    editMode[tab.value] = false;
  };

  // 切换选中节点时退出所有编辑模式、清空草稿（节点无关的折叠态由调用方自行处理）
  watch(() => node()?.id, () => {
    for (const k of [0, 1, 2, 3]) editMode[k] = false;
    draft.value = emptyDraft();
  });

  // 切换 tab 时自动取消编辑（避免跨 tab 的草稿丢失歧义）
  watch(tab, () => { draft.value = emptyDraft(); });

  // ===== 草稿层的属性/约束/来源表 增删改 =====
  const dAddAttribute = () => {
    draft.value.attributes = [...draft.value.attributes, { name: '', valueSpace: '', source: 'manual' }];
  };
  const dRemoveAttribute = (i: number) => {
    const attrs = [...draft.value.attributes];
    attrs.splice(i, 1);
    draft.value.attributes = attrs;
  };
  const dUpdateAttribute = (i: number, field: 'name' | 'valueSpace' | 'table' | 'column' | 'sourceMethod', value: string) => {
    draft.value.attributes = draft.value.attributes.map((a: any, idx: number) =>
      idx === i ? { ...a, [field]: value } : a
    );
  };

  const dAddConstraint = () => {
    draft.value.constraints = [...draft.value.constraints, { kind: 'custom', note: '', source: 'manual' }];
  };
  const dRemoveConstraint = (i: number) => {
    const cons = [...draft.value.constraints];
    cons.splice(i, 1);
    draft.value.constraints = cons;
  };
  const dUpdateConstraint = (i: number, field: 'kind' | 'note', value: string) => {
    draft.value.constraints = draft.value.constraints.map((c: any, idx: number) =>
      idx === i ? { ...c, [field]: value } : c
    );
  };

  const dAddTable = () => {
    draft.value.derived_tables = [...draft.value.derived_tables, ''];
  };
  const dRemoveTable = (i: number) => {
    const ts = [...draft.value.derived_tables];
    ts.splice(i, 1);
    draft.value.derived_tables = ts;
  };
  const dUpdateTable = (i: number, value: string) => {
    const ts = [...draft.value.derived_tables];
    ts[i] = value;
    draft.value.derived_tables = ts;
  };

  return {
    editMode, draft, isEditing,
    startEdit, cancelEdit, saveEdit,
    dAddAttribute, dRemoveAttribute, dUpdateAttribute,
    dAddConstraint, dRemoveConstraint, dUpdateConstraint,
    dAddTable, dRemoveTable, dUpdateTable,
  };
}
