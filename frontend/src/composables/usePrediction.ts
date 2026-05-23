import { ref, type Ref } from 'vue';
import type { OntologyNode, OntologyEdge, Scenario, ChainStep, Constraint } from '../types';
import { predictStream, type PredictPayload as ApiPredictPayload } from '../api/scenarios';
import type { SseHandle } from '../api/http';
import { toast } from './useToast';

/**
 * usePrediction 的外部依赖契约。
 * 通过把"图谱状态、当前分支、画布操作"等以 Ref/getter/回调的形式注入，
 * 避免本组合式与 useScenarios、GraphCanvas 之间产生硬耦合。
 */
export interface PredictionCtx {
  /** 当前选中的 LLM 模型 id；推演前必须非空。 */
  currentModelId: Ref<string>;
  /** 主画布上的节点引用，推演过程中会向其 push 新节点。 */
  nodes: Ref<OntologyNode[]>;
  /** 主画布上的边引用，推演过程中会向其 push 新边。 */
  edges: Ref<OntologyEdge[]>;
  /** 通过 getter 提供，避免与 useScenarios 之间的循环依赖。 */
  getActiveBranchId: () => string;
  setActiveBranchId: (id: string) => void;
  /** 推演开始前抓取一次 trunk 快照，complete 时用作分支 base。 */
  setTrunkSnapshot: (snap: { nodes: OntologyNode[]; edges: OntologyEdge[] }) => void;
  /** complete 时把新 scenario unshift 到分支列表的回调。 */
  appendBranch: (s: Scenario) => void;
  /** 推演失败时切回某分支（forkParent 或 trunk）。 */
  switchBranch: (id: string) => void;
  /** 推演完成 / 错误后让画布拟合视野。 */
  fitView?: () => void;
}

/** 前端发起推演时的简化参数（不含 modelId/nodes/edges，由本组合式补齐）。 */
export interface PredictPayload {
  seeds: string[];
  steps: number;
  prompt: string;
  name: string;
  intent?: 'forward' | 'backward';
  constraints?: Constraint[];
}

/** 服务端 SSE 'step' 事件的载荷形状（由 PredictionOrchestrator 写出）。 */
interface StepEvent {
  step: number;
  intent: string;
  node: OntologyNode;
  edges: OntologyEdge[];
  chain: ChainStep;
}

/**
 * 推演组合式：封装"打开对话框 → 启动 SSE → 逐步渲染 → 完成/失败收尾"全流程。
 * <p>外部只需要持有返回的 ref 和动作函数即可，无需关心 SSE 协议或图谱状态。
 */
export function usePrediction(ctx: PredictionCtx) {
  // 推演对话框开关与默认种子
  const predictDialogOpen = ref(false);
  const predictSeeds = ref<string[]>([]);
  // 实时滚动展示的链条步骤（chain 事件累积到这里）
  const liveSteps = ref<ChainStep[]>([]);
  // liveLoading：SSE 还在流式推送中；liveActive：本次推演整个生命周期内为 true（含完成后的展示阶段）
  const liveLoading = ref(false);
  const liveActive = ref(false);
  const liveIntent = ref<'forward' | 'backward'>('forward');
  const liveAbort = ref<SseHandle | null>(null);
  const livePruneDetails = ref<{ nodeId: string; label: string; reason: string }[]>([]);
  // 启动时的上下文，供对话里展示推演消息使用
  const liveSeeds = ref<string[]>([]);
  const livePrompt = ref<string>('');
  const liveName = ref<string>('');
  const liveBranchId = ref<string>('');
  const liveError = ref<string>('');
  /** 0=空闲 1=运行中 2=完成 3=错误 4=已停止。给 ChatPanel 监听用。 */
  const liveStatus = ref<0 | 1 | 2 | 3 | 4>(0);

  /** 主动断开 SSE 流（用户手动停止 / 重新发起推演时调用）。 */
  const abortLiveStream = () => {
    if (liveAbort.value) {
      try { liveAbort.value.abort(); } catch { /* noop */ }
      liveAbort.value = null;
    }
  };

  /** 把"实时推演视图"重置回未启动状态；不影响 liveStatus（由调用方决定）。 */
  const resetLiveState = () => {
    liveActive.value = false;
    liveSteps.value = [];
  };

  /** 用户从画布右键菜单触发"从此推演"时调用，弹出对话框让用户填参数。 */
  const openPredictDialog = (seedId: string) => {
    if (liveActive.value) {
      // 同一时间只允许一个推演在跑，否则节点流会互相污染
      toast.warn('当前推演进行中,请等待完成后再发起新推演');
      return;
    }
    predictSeeds.value = [seedId];
    predictDialogOpen.value = true;
  };

  /**
   * 启动推演：抓快照 → 切到 'live' 伪分支 → 建 SSE 流。
   * <p>'live' 是一个临时伪分支，用来把"正在生长的画布"与已有分支隔离开；
   * 完成后会被替换为后端返回的真实 scenario.id。
   */
  const startPrediction = (payload: PredictPayload) => {
    predictDialogOpen.value = false;
    if (!ctx.currentModelId.value) return;

    abortLiveStream();

    const activeBranch = ctx.getActiveBranchId();
    // 'live' 是 transient pseudo-branch，不能持久化为 parent；trunk 也视为顶层无 parent
    const forkParentId = (activeBranch === 'trunk' || activeBranch === 'live') ? null : activeBranch;
    // 推演前先深拷贝一份当前画布状态，作为 trunk 快照与 live 分支的初始内容
    const snapshot = {
      nodes: structuredClone(ctx.nodes.value),
      edges: structuredClone(ctx.edges.value),
    };
    ctx.setTrunkSnapshot(snapshot);

    // 把画布切到 live 伪分支：从快照副本开始，避免污染原 trunk 数据
    ctx.nodes.value = structuredClone(snapshot.nodes);
    ctx.edges.value = structuredClone(snapshot.edges);
    ctx.setActiveBranchId('live');
    liveActive.value = true;
    liveSteps.value = [];
    livePruneDetails.value = [];
    liveLoading.value = true;
    liveIntent.value = payload.intent || 'forward';
    liveSeeds.value = [...payload.seeds];
    livePrompt.value = payload.prompt || '';
    liveName.value = payload.name || '';
    liveBranchId.value = '';
    liveError.value = '';
    liveStatus.value = 1;

    // 把前端 payload 补齐为后端 API 需要的形状（含 modelId、parentBranchId、当前画布快照）
    const apiPayload: ApiPredictPayload = {
      modelId: ctx.currentModelId.value,
      parentBranchId: forkParentId || undefined,
      name: payload.name,
      intent: payload.intent || 'forward',
      seeds: payload.seeds,
      steps: payload.steps,
      prompt: payload.prompt,
      constraints: payload.constraints || [],
      nodes: snapshot.nodes,
      edges: snapshot.edges,
    };

    liveAbort.value = predictStream(apiPayload, {
      onStep: (raw: unknown) => {
        try {
          // 后端发来的每一步都打上 isNew 标记，触发画布的高亮动画
          const ev = raw as StepEvent;
          const node: OntologyNode = { ...ev.node, isNew: true };
          ctx.nodes.value.push(node);
          const newEdges: OntologyEdge[] = (ev.edges || []).map(e => ({ ...e, isNew: true }));
          ctx.edges.value.push(...newEdges);
          if (ev.chain) liveSteps.value.push(ev.chain);
          // 700ms 后清除 isNew，让动画自然收尾；与 GraphCanvas 中 .node-new 过渡时长保持一致
          setTimeout(() => {
            ctx.nodes.value.forEach(n => n.isNew = false);
            ctx.edges.value.forEach(e => e.isNew = false);
          }, 700);
        } catch { /* 单步解析失败容忍：不让一帧错误打断整流 */ }
      },
      onComplete: (scenario: Scenario) => {
        // 后端持久化成功 → 用真实 scenario 替换 live 伪分支
        ctx.appendBranch(scenario);
        ctx.setActiveBranchId(scenario.id);
        liveLoading.value = false;
        liveBranchId.value = scenario.id;
        liveStatus.value = 2;
        // 等下一帧再 fitView，确保新节点已 mount 到 DOM
        setTimeout(() => ctx.fitView?.(), 100);
      },
      onNotice: (raw: unknown) => {
        // 目前唯一的 notice 类型是 pruned（剪枝详情），后端在有 what-if block 约束时发送
        const note = raw as { type?: string; message?: string; details?: { nodeId: string; label: string; reason: string }[] };
        if (note?.message) toast.info(note.message);
        if (note?.type === 'pruned' && note?.details) {
          livePruneDetails.value = note.details;
        }
      },
      onError: (msg: string) => {
        toast.error('推演错误: ' + msg);
        liveLoading.value = false;
        liveError.value = msg;
        liveStatus.value = 3;
        liveActive.value = false;
        // 推演失败时切回 fork 父分支或 trunk，避免用户停留在残缺的 live 视图
        ctx.switchBranch(forkParentId || 'trunk');
      },
      onClose: () => {
        liveLoading.value = false;
      },
    });
  };

  /** 用户主动关闭实时时间轴（点 × 或对话框 ESC）。 */
  const closeTimeline = () => {
    abortLiveStream();
    // 状态机：若仍在 running，标记 aborted，让对话里的推演消息显示"已停止"。
    if (liveStatus.value === 1) liveStatus.value = 4;
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
    livePruneDetails,
    liveSeeds,
    livePrompt,
    liveName,
    liveBranchId,
    liveError,
    liveStatus,
    openPredictDialog,
    startPrediction,
    closeTimeline,
    abortLiveStream,
    resetLiveState,
  };
}
