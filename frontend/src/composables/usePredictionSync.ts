import { watch, type Ref } from 'vue';
import type { ChatMsg, PredictionMsg } from './useConversations';
import type { OntologyNode, ChainStep } from '../types';

export interface LivePrediction {
  status: 0 | 1 | 2 | 3 | 4;
  intent: 'forward' | 'backward';
  seeds: string[];
  prompt: string;
  name: string;
  steps: ChainStep[];
  pruneDetails: { nodeId: string; label: string; reason: string }[];
  branchId: string;
  error: string;
}

export interface PredictionSyncCtx {
  msgs: Ref<ChatMsg[]>;
  /** 用 getter 取最新 livePrediction（来自 props 的话直接 props.livePrediction）。 */
  livePrediction: () => LivePrediction | null | undefined;
  nodes: () => OntologyNode[];
}

/**
 * 把父级传下来的 livePrediction 状态（推演运行中的 status / steps / errors）
 * 同步到 chat 消息列表里的 role='prediction' 那条消息上。
 *
 * 为什么独立：这套状态机和聊天发送 / 流式 SSE 完全没有耦合，
 * 但代码量不小（涉及 status 边沿检测、steps 增量同步、终态释放等）。
 */
export function usePredictionSync(ctx: PredictionSyncCtx) {
  let currentPredictionMsg: ChatMsg | null = null;

  const seedLabel = (id: string) =>
    ctx.nodes().find(n => n.id === id)?.label || id;
  const buildSeedPairs = (ids: string[]) =>
    ids.map(id => ({ id, label: seedLabel(id) }));
  const statusToLabel = (s: 0 | 1 | 2 | 3 | 4): PredictionMsg['status'] =>
    s === 1 ? 'running' : s === 2 ? 'done' : s === 3 ? 'error' : s === 4 ? 'aborted' : 'done';

  watch(() => ctx.livePrediction(), (now, prev) => {
    if (!now) return;
    const enteredRunning = now.status === 1 && (!prev || prev.status !== 1);
    if (enteredRunning) {
      const msg: ChatMsg = {
        role: 'prediction',
        text: '',
        prediction: {
          intent: now.intent,
          seeds: buildSeedPairs(now.seeds),
          prompt: now.prompt,
          name: now.name,
          status: 'running',
          steps: [],
          pruneDetails: [],
        },
      };
      ctx.msgs.value.push(msg);
      currentPredictionMsg = msg;
    }
    if (currentPredictionMsg && currentPredictionMsg.prediction) {
      const p = currentPredictionMsg.prediction;
      if (now.steps.length !== p.steps.length) {
        p.steps = now.steps.map(s => ({
          step: s.step, nodeId: s.nodeId, label: s.label, type: s.type,
          triggeredBy: s.triggeredBy, ruleId: s.ruleId,
          explanation: s.explanation, confidence: s.confidence,
        }));
      }
      if (now.pruneDetails?.length && (p.pruneDetails?.length || 0) !== now.pruneDetails.length) {
        p.pruneDetails = now.pruneDetails.slice();
      }
      p.status = statusToLabel(now.status);
      if (now.branchId) p.branchId = now.branchId;
      if (now.error) p.error = now.error;
      if (p.status !== 'running') currentPredictionMsg = null;
    }
  }, { deep: true });

  return { /* 当前没有需要暴露给外部的状态 */ };
}
