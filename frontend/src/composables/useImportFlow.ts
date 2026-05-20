import type { Ref } from 'vue';
import type { OntologyNode, OntologyEdge, OntologyModel } from '../types';
import { toast } from './useToast';

export interface ImportPayload {
  mode: 'merge' | 'new';
  name: string;
  nodes: OntologyNode[];
  edges: OntologyEdge[];
}

export interface ImportFlowCtx {
  /** 当前画布的 nodes(可读可写)。 */
  nodes: Ref<OntologyNode[]>;
  /** 当前画布的 edges(可读可写)。 */
  edges: Ref<OntologyEdge[]>;
  /** 当前活动分支 id。 */
  activeBranchId: Ref<string>;
  /** 切回 trunk 的回调。 */
  switchToTrunk: () => void;
  /** 防抖持久化当前模型。 */
  persistCurrentModel: (immediate?: boolean) => void;
  /** 创建新模型(返回带服务端 id 的 model)。 */
  createNewModel: (draft: OntologyModel) => Promise<OntologyModel>;
  /** 在新模型创建后将其插入列表头部并打开。 */
  registerAndOpenModel: (m: OntologyModel) => Promise<void> | void;
  /** 提交后让画布重新拟合视野。 */
  fitView?: () => void;
}

/**
 * v1.0 导入提交:根据 mode 把抽取到的 nodes/edges 合并入当前 trunk,或另存为新模型。
 */
export function useImportFlow(ctx: ImportFlowCtx) {
  const onImportCommit = async (payload: ImportPayload) => {
    if (!payload.nodes.length) return;

    // 给新节点默认坐标:从图谱右下角依次铺开,避免压在现有节点上
    const baseX = (ctx.nodes.value.length ? Math.max(...ctx.nodes.value.map(n => +(n.x || 0))) : 0) + 260;
    const baseY = (ctx.nodes.value.length ? Math.min(...ctx.nodes.value.map(n => +(n.y || 0))) : 0) + 60;
    const cols = Math.max(1, Math.ceil(Math.sqrt(payload.nodes.length)));
    const stamped: OntologyNode[] = payload.nodes.map((n, i) => ({
      ...n,
      x: n.x != null ? n.x : (baseX + (i % cols) * 200),
      y: n.y != null ? n.y : (baseY + Math.floor(i / cols) * 120),
      source: n.source || 'derived',
    }));

    if (payload.mode === 'merge') {
      if (ctx.activeBranchId.value !== 'trunk') ctx.switchToTrunk();
      ctx.nodes.value = [...ctx.nodes.value, ...stamped];
      ctx.edges.value = [...ctx.edges.value, ...payload.edges];
      ctx.persistCurrentModel(true);
      toast.success(`已合并 ${stamped.length} 个节点 / ${payload.edges.length} 条关系`);
      setTimeout(() => ctx.fitView?.(), 100);
      return;
    }

    // 另存为新模型
    const draft: OntologyModel = {
      id: 'om_' + Date.now(),
      name: payload.name || '导入本体',
      title: payload.name || '导入本体',
      description: '从文档抽取',
      graphData: { nodes: stamped, edges: payload.edges },
    };
    try {
      const saved = await ctx.createNewModel(draft);
      if (!saved || !saved.id) {
        toast.error('新模型创建失败');
        return;
      }
      await ctx.registerAndOpenModel(saved);
      toast.success('已另存为新模型');
    } catch (e: any) {
      toast.error('新模型创建失败:' + (e?.message || e));
    }
  };

  return { onImportCommit };
}
