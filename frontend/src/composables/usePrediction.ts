import { ref, type Ref } from 'vue';
import type { OntologyNode, OntologyEdge, Scenario, ChainStep, Constraint } from '../types';
import { streamSSE, type SSEController } from './useSSE';
import { toast } from './useToast';

export interface PredictionCtx {
  currentModelId: Ref<string>;
  nodes: Ref<OntologyNode[]>;
  edges: Ref<OntologyEdge[]>;
  /** 通过 getter 提供,避免与 useScenarios 之间的循环依赖。 */
  getActiveBranchId: () => string;
  setActiveBranchId: (id: string) => void;
  setTrunkSnapshot: (snap: { nodes: OntologyNode[]; edges: OntologyEdge[] }) => void;
  /** complete 时把新 scenario unshift 到分支列表的回调。 */
  appendBranch: (s: Scenario) => void;
  /** 推演失败时切回某分支(forkParent 或 trunk)。 */
  switchBranch: (id: string) => void;
  /** 推演完成 / 错误后让画布拟合视野。 */
  fitView?: () => void;
}

export interface PredictPayload {
  seeds: string[];
  steps: number;
  prompt: string;
  name: string;
  intent?: 'forward' | 'backward';
  constraints?: Constraint[];
}

export function usePrediction(ctx: PredictionCtx) {
  const predictDialogOpen = ref(false);
  const predictSeeds = ref<string[]>([]);
  const liveSteps = ref<ChainStep[]>([]);
  const liveLoading = ref(false);
  const liveActive = ref(false);
  const liveIntent = ref<'forward' | 'backward'>('forward');
  const liveAbort = ref<SSEController | null>(null);

  const abortLiveStream = () => {
    if (liveAbort.value) {
      try { liveAbort.value.abort(); } catch { /* noop */ }
      liveAbort.value = null;
    }
  };

  const resetLiveState = () => {
    liveActive.value = false;
    liveSteps.value = [];
  };

  const openPredictDialog = (seedId: string) => {
    if (liveActive.value) {
      toast.warn('当前推演进行中,请等待完成后再发起新推演');
      return;
    }
    predictSeeds.value = [seedId];
    predictDialogOpen.value = true;
  };

  const startPrediction = (payload: PredictPayload) => {
    predictDialogOpen.value = false;
    if (!ctx.currentModelId.value) return;

    abortLiveStream();

    const activeBranch = ctx.getActiveBranchId();
    // 'live' 是 transient pseudo-branch,不能持久化为 parent
    const forkParentId = (activeBranch === 'trunk' || activeBranch === 'live') ? null : activeBranch;
    const snapshot = {
      nodes: structuredClone(ctx.nodes.value),
      edges: structuredClone(ctx.edges.value),
    };
    ctx.setTrunkSnapshot(snapshot);

    ctx.nodes.value = structuredClone(snapshot.nodes);
    ctx.edges.value = structuredClone(snapshot.edges);
    ctx.setActiveBranchId('live');
    liveActive.value = true;
    liveSteps.value = [];
    liveLoading.value = true;
    liveIntent.value = payload.intent || 'forward';

    const body = {
      modelId: ctx.currentModelId.value,
      parentBranchId: forkParentId,
      name: payload.name,
      intent: payload.intent || 'forward',
      seeds: payload.seeds,
      steps: payload.steps,
      prompt: payload.prompt,
      constraints: payload.constraints || [],
      nodes: snapshot.nodes,
      edges: snapshot.edges,
    };

    liveAbort.value = streamSSE('/api/scenarios', body, {
      onEvent: (name, data) => {
        if (name === 'step') {
          try {
            const ev = JSON.parse(data);
            const node: OntologyNode = { ...ev.node, isNew: true };
            ctx.nodes.value.push(node);
            const newEdges: OntologyEdge[] = (ev.edges || []).map((e: any) => ({ ...e, isNew: true }));
            ctx.edges.value.push(...newEdges);
            if (ev.chain) liveSteps.value.push(ev.chain);
            setTimeout(() => {
              ctx.nodes.value.forEach(n => n.isNew = false);
              ctx.edges.value.forEach(e => e.isNew = false);
            }, 700);
          } catch { /* 单步解析失败容忍 */ }
        } else if (name === 'complete') {
          try {
            const scenario: Scenario = JSON.parse(data);
            ctx.appendBranch(scenario);
            ctx.setActiveBranchId(scenario.id);
            liveLoading.value = false;
            setTimeout(() => ctx.fitView?.(), 100);
          } catch { /* noop */ }
        } else if (name === 'notice') {
          try {
            const note = JSON.parse(data);
            if (note?.message) toast.info(note.message);
          } catch { /* noop */ }
        } else if (name === 'error') {
          toast.error('推演错误: ' + data);
          liveLoading.value = false;
          liveActive.value = false;
          ctx.switchBranch(forkParentId || 'trunk');
        }
      },
      onError: (err) => {
        toast.error('网络错误: ' + err.message);
        liveLoading.value = false;
        liveActive.value = false;
        ctx.switchBranch(forkParentId || 'trunk');
      },
      onComplete: () => {
        liveLoading.value = false;
      },
    });
  };

  const closeTimeline = () => {
    abortLiveStream();
    liveActive.value = false;
    liveLoading.value = false;
    liveSteps.value = [];
  };

  return {
    predictDialogOpen,
    predictSeeds,
    liveSteps,
    liveLoading,
    liveActive,
    liveIntent,
    liveAbort,
    openPredictDialog,
    startPrediction,
    closeTimeline,
    abortLiveStream,
    resetLiveState,
  };
}
