import { ref, type Ref } from 'vue';
import type { OntologyModel } from '../types';
import { listOntologies, saveOntology, deleteOntology } from '../api/ontology';
import { ApiError } from '../api/http';
import { toast } from './useToast';
import { confirm } from './useConfirm';

export interface OntologyModelCtx {
  /** 删除当前模型时回调,用于让父级跳回欢迎页等。 */
  onDeletedCurrent?: (id: string) => void;
  /** 当前模型 id 的访问器,判断「删除的是当前模型吗」。 */
  currentModelId: Ref<string>;
}

/**
 * 本体模型列表 + 创建 + 删除的网络层封装。
 * 不持有 nodes/edges/persistCurrentModel — 这些保留在使用方,避免引入复杂 ctx 链条。
 */
export function useOntologyModel(ctx: OntologyModelCtx) {
  const models = ref<OntologyModel[]>([]);
  const isCreating = ref(false);

  const loadOntologyModels = async () => {
    try {
      models.value = await listOntologies();
    } catch (e) {
      console.error('load ontology models failed', e);
    }
  };

  const findModel = (id: string): OntologyModel | undefined =>
    models.value.find(m => m.id === id);

  /** 创建新模型,失败时退化为本地草稿(保留 graphData 等字段)。 */
  const createOnBackend = async (draft: OntologyModel): Promise<OntologyModel> => {
    try {
      return await saveOntology(draft);
    } catch (e) {
      console.error('create model failed', e);
      return draft;
    }
  };

  /** 带确认弹窗的删除,内部调用 onDeletedCurrent 让父级处理路由跳转。 */
  const deleteOntologyModel = async (id: string) => {
    const ok = await confirm({
      title: '删除本体模型',
      message: '确定删除该本体模型?关联的推演分支不会自动清除。',
      confirmLabel: '删除',
      danger: true,
    });
    if (!ok) return;
    try {
      await deleteOntology(id);
    } catch (e) {
      console.error(e);
      if (e instanceof ApiError) toast.error('删除请求失败 (HTTP ' + e.status + ')');
      else toast.error('删除请求失败');
    }
    models.value = models.value.filter(m => m.id !== id);
    if (ctx.currentModelId.value === id) ctx.onDeletedCurrent?.(id);
  };

  return {
    models,
    isCreating,
    loadOntologyModels,
    findModel,
    createOnBackend,
    deleteOntologyModel,
  };
}
