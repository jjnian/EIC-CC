import { ref, type Ref } from 'vue';
import type { Scenario, OntologyNode, OntologyEdge, OntologyModel } from '../types';
import { listScenarios, deleteScenario, migrateScenarios } from '../api/scenarios';
import { ApiError } from '../api/http';
import { toast } from './useToast';
import { confirm } from './useConfirm';

export interface ScenariosCtx {
  /** 当前模型 id 的访问器(响应式)。 */
  currentModelId: Ref<string>;
  /** 当前画布 nodes(可写)。 */
  nodes: Ref<OntologyNode[]>;
  /** 当前画布 edges(可写)。 */
  edges: Ref<OntologyEdge[]>;
  /** 通过 id 获取 OntologyModel(返回 trunk 数据)。 */
  findModel: (id: string) => OntologyModel | undefined;
  /** 切换分支前 / 切回 trunk 时,如有正在推演的 SSE 流应中止它。 */
  abortLiveStream: () => void;
  /** 清除流式推演状态(liveActive/liveSteps/sel)。 */
  resetLiveState: () => void;
  /** 在分支切换完成后让画布拟合视野。 */
  fitView?: () => void;
}

/**
 * 分支(scenario)状态管理:加载、切换、级联删除、迁移。
 */
export function useScenarios(ctx: ScenariosCtx) {
  const branches = ref<Scenario[]>([]);
  const activeBranchId = ref<string>('trunk');
  const trunkSnapshot = ref<{ nodes: OntologyNode[]; edges: OntologyEdge[] } | null>(null);

  const loadBranches = async (modelId: string) => {
    try {
      branches.value = await listScenarios(modelId);
    } catch {
      branches.value = [];
    }
  };

  // 沿 parentBranchId 链回溯,root-first 合并所有祖先 dag 增量
  const collectAncestorChain = (leafId: string): Scenario[] => {
    const chain: Scenario[] = [];
    let cur: Scenario | undefined = branches.value.find(x => x.id === leafId);
    const guard = new Set<string>();
    while (cur && !guard.has(cur.id)) {
      guard.add(cur.id);
      chain.unshift(cur);
      if (!cur.parentBranchId) break;
      cur = branches.value.find(x => x.id === cur!.parentBranchId);
    }
    return chain;
  };

  const switchBranch = (id: string) => {
    ctx.abortLiveStream();
    ctx.resetLiveState();
    if (id === 'trunk') {
      const m = ctx.findModel(ctx.currentModelId.value);
      if (m) {
        ctx.nodes.value = structuredClone(m.graphData.nodes || []);
        ctx.edges.value = structuredClone(m.graphData.edges || []);
      }
      activeBranchId.value = 'trunk';
    } else {
      const chain = collectAncestorChain(id);
      if (chain.length) {
        const trunkM = ctx.findModel(ctx.currentModelId.value);
        const nodeMap = new Map<string, OntologyNode>();
        const edgeMap = new Map<string, OntologyEdge>();
        if (trunkM) {
          for (const n of structuredClone(trunkM.graphData.nodes || [])) nodeMap.set(n.id, n);
          for (const e of structuredClone(trunkM.graphData.edges || [])) edgeMap.set(e.id, e);
        }
        let hitLegacy = false;
        for (const b of chain) {
          if (b.dag && Array.isArray(b.dag.nodes)) {
            for (const n of structuredClone(b.dag.nodes || [])) nodeMap.set(n.id, n);
            for (const e of structuredClone(b.dag.edges || [])) edgeMap.set(e.id, e);
          } else if (Array.isArray(b.nodes)) {
            // v0.5 全快照:用其完全覆盖;停止累加后代 delta
            console.warn('[switchBranch] legacy v0.5 snapshot branch detected, halting chain merge:', b.id);
            nodeMap.clear();
            edgeMap.clear();
            for (const n of structuredClone(b.nodes || [])) nodeMap.set(n.id, n);
            for (const e of structuredClone(b.edges || [])) edgeMap.set(e.id, e);
            hitLegacy = true;
            break;
          }
        }
        if (hitLegacy) toast.warn('该分支为旧 v0.5 快照格式,建议升级以支持级联预览');
        ctx.nodes.value = Array.from(nodeMap.values());
        ctx.edges.value = Array.from(edgeMap.values());
        activeBranchId.value = id;
      }
    }
    setTimeout(() => ctx.fitView?.(), 50);
  };

  const migrateBranches = async () => {
    const ok = await confirm({
      title: '升级旧分支',
      message: '将扫描所有旧格式分支并升级为 v0.9 delta 形态。每个文件升级前会写 .bak 备份。继续?',
      confirmLabel: '开始升级',
    });
    if (!ok) return;
    try {
      const out = await migrateScenarios();
      toast.success(`迁移完成:升级 ${out.migrated} 个 / 跳过 ${out.skipped} 个 / 失败 ${out.errors} 个 / 共 ${out.total} 个分支`);
      if (ctx.currentModelId.value) await loadBranches(ctx.currentModelId.value);
    } catch (e: any) {
      if (e instanceof ApiError) toast.error('迁移失败: ' + e.message);
      else toast.error('网络错误: ' + e.message);
    }
  };

  const deleteBranch = async (id: string) => {
    // v0.8:收集所有以 id 为祖先的子分支,前端同步过滤;后端会级联删除
    const toRemove = new Set<string>([id]);
    let grew = true;
    while (grew) {
      grew = false;
      for (const b of branches.value) {
        if (b.parentBranchId && toRemove.has(b.parentBranchId) && !toRemove.has(b.id)) {
          toRemove.add(b.id);
          grew = true;
        }
      }
    }
    try {
      await deleteScenario(id);
    } catch (e: any) {
      if (e instanceof ApiError) toast.error('删除失败 (HTTP ' + e.status + ')');
      else toast.error('删除请求异常: ' + (e?.message || e));
    }
    branches.value = branches.value.filter(b => !toRemove.has(b.id));
    if (toRemove.has(activeBranchId.value)) {
      switchBranch('trunk');
    }
  };

  return {
    branches,
    activeBranchId,
    trunkSnapshot,
    loadBranches,
    switchBranch,
    collectAncestorChain,
    migrateBranches,
    deleteBranch,
  };
}
