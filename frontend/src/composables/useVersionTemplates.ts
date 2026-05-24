import { ref, type Ref } from 'vue';
import type { OntologyModel, OntologyNode, OntologyEdge } from '../types';
import { listVersions, restoreVersion, listGraphTemplates, saveGraphTemplate, deleteGraphTemplate } from '../api/ontology';
import { toast } from './useToast';
import { confirm } from './useConfirm';

export interface VersionTemplatesCtx {
  currentModelId: Ref<string>;
  nodes: Ref<OntologyNode[]>;
  edges: Ref<OntologyEdge[]>;
  models: Ref<OntologyModel[]>;
  isCreating: Ref<boolean>;
  findModel: (id: string) => OntologyModel | undefined;
  resetHistory: (n: OntologyNode[], e: OntologyEdge[]) => void;
  createOnBackend: (draft: OntologyModel) => Promise<OntologyModel>;
  openModel: (m: OntologyModel) => Promise<void>;
  persistImmediate: () => void;
}

/** 版本历史 + 模板库的 UI 状态 + 后端调用；从 App.vue 里整段抽出来。 */
export function useVersionTemplates(ctx: VersionTemplatesCtx) {
  // ===== 版本历史 =====
  const showVersionMenu = ref(false);
  const versions = ref<{ timestamp: number; nodeCount: number; edgeCount: number; fileSize: number }[]>([]);
  const versionsLoading = ref(false);

  const loadVersions = async () => {
    if (!ctx.currentModelId.value) return;
    versionsLoading.value = true;
    try {
      versions.value = await listVersions(ctx.currentModelId.value);
    } catch (e) {
      console.error('Failed to load versions', e);
      versions.value = [];
    } finally {
      versionsLoading.value = false;
    }
  };

  const toggleVersionMenu = async () => {
    if (!ctx.currentModelId.value) return;
    showVersionMenu.value = !showVersionMenu.value;
    if (showVersionMenu.value) await loadVersions();
  };

  const doRestoreVersion = async (timestamp: number) => {
    if (!ctx.currentModelId.value) return;
    const ok = await confirm({
      title: '恢复版本',
      message: `确定恢复到 ${new Date(timestamp).toLocaleString()} 的版本？当前版本会自动保存为快照。`,
      confirmLabel: '恢复',
    });
    if (!ok) return;
    try {
      const restored = await restoreVersion(ctx.currentModelId.value, timestamp);
      if (restored.graphData) {
        ctx.nodes.value = restored.graphData.nodes || [];
        ctx.edges.value = restored.graphData.edges || [];
        ctx.resetHistory(ctx.nodes.value, ctx.edges.value);
      }
      showVersionMenu.value = false;
      toast.success('已恢复到历史版本');
    } catch (e) {
      toast.error('恢复失败');
    }
  };

  // ===== 模板库 =====
  const showTemplates = ref(false);
  const templates = ref<any[]>([]);
  const templatesLoading = ref(false);

  const openTemplates = async () => {
    showTemplates.value = true;
    templatesLoading.value = true;
    try {
      templates.value = await listGraphTemplates();
    } catch (e) {
      console.error('Failed to load templates', e);
      templates.value = [];
    } finally {
      templatesLoading.value = false;
    }
  };

  const saveAsTemplate = async () => {
    if (!ctx.currentModelId.value) return;
    const model = ctx.findModel(ctx.currentModelId.value);
    if (!model) return;
    const tpl = {
      title: (model.title || '未命名') + ' (模板)',
      desc: model.desc || '',
      graphData: { nodes: ctx.nodes.value, edges: ctx.edges.value },
    };
    try {
      await saveGraphTemplate(tpl);
      toast.success('已保存为模板');
    } catch (e) {
      toast.error('保存模板失败');
    }
  };

  const createFromTemplate = async (tpl: any) => {
    if (ctx.isCreating.value) return;
    ctx.isCreating.value = true;
    try {
      const title = (tpl.title || '未命名').replace(/ \(模板\)$/, '');
      const draft: OntologyModel = {
        id: 'om_' + Date.now(),
        title,
        desc: tpl.desc || '',
        graphData: tpl.graphData || { nodes: [], edges: [] },
      };
      const saved = await ctx.createOnBackend(draft);
      ctx.models.value.unshift(saved);
      showTemplates.value = false;
      await ctx.openModel(saved);
      toast.success('已从模板创建新模型');
    } catch (e) {
      toast.error('从模板创建失败');
    } finally {
      ctx.isCreating.value = false;
    }
  };

  const removeTemplate = async (id: string) => {
    const ok = await confirm({
      title: '删除模板',
      message: '确定删除此模板？',
      danger: true,
      confirmLabel: '删除',
    });
    if (!ok) return;
    try {
      await deleteGraphTemplate(id);
      templates.value = templates.value.filter(t => t.id !== id);
      toast.success('已删除');
    } catch (e) {
      toast.error('删除失败');
    }
  };

  const closeTemplates = () => { showTemplates.value = false; };

  /** 在新标签页打开当前模型的只读预览页。 */
  const openPreview = () => {
    if (!ctx.currentModelId.value) return;
    ctx.persistImmediate();
    const url = `${location.origin}${location.pathname}?preview=${encodeURIComponent(ctx.currentModelId.value)}`;
    window.open(url, '_blank', 'noopener');
  };

  return {
    showVersionMenu,
    versions,
    versionsLoading,
    loadVersions,
    toggleVersionMenu,
    doRestoreVersion,
    showTemplates,
    templates,
    templatesLoading,
    openTemplates,
    saveAsTemplate,
    createFromTemplate,
    removeTemplate,
    closeTemplates,
    openPreview,
  };
}
