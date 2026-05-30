import { ref } from 'vue';
import type { SourceMeta } from '../types';
import { extractFromFilesStream } from '../api/ontology';
import type { SseHandle } from '../api/http';

export interface ExtractedNode {
  id: string;
  label: string;
  type?: string;
  source?: string;
  props?: any[];
}
export interface ExtractedEdge {
  id: string;
  from: string;
  to: string;
  label?: string;
  source?: string;
  rule_driven?: boolean;
}

// 抽取过程的分步进度（让用户看到"构建本体"的每个阶段，而非只有等待）
export interface BuildStep { key: string; label: string; status: 'running' | 'done' | 'error'; }

export interface ExtractStreamOpts {
  /** 设置错误信息(errorMsg 归 useImportFiles 持有,这里通过 setter 写入)。 */
  setError: (msg: string) => void;
  /** 抽取完成、数据写入后回调(组件接 dedup 刷新选择集)。 */
  onExtracted?: () => void;
}

/**
 * 文档导入对话框的"SSE 流式抽取"部分。
 * 负责触发抽取、维护分步进度、把结果写入本 composable 的 ref,并提供可中断句柄。
 */
export function useExtractStream(opts: ExtractStreamOpts) {
  const loading = ref(false);
  // 原始抽取结果（不破坏性修改），mode 切换时 dup 计算自动失效
  const extractedRaw = ref<{ nodes: ExtractedNode[]; edges: ExtractedEdge[] } | null>(null);
  const sources = ref<SourceMeta[]>([]);
  const replyText = ref('');
  const buildSteps = ref<BuildStep[]>([]);
  // 抽取 SSE 流的可中断句柄
  let sseHandle: SseHandle | null = null;

  const markRunningAs = (status: 'done' | 'error') => {
    for (const st of buildSteps.value) {
      if (st.status === 'running') st.status = status;
    }
  };

  const abort = () => {
    if (sseHandle) { try { sseHandle.abort(); } catch { /* noop */ } sseHandle = null; }
  };

  const reset = () => {
    loading.value = false;
    extractedRaw.value = null;
    sources.value = [];
    replyText.value = '';
    buildSteps.value = [];
    abort();
  };

  const start = (files: File[], urls: string[]) => {
    loading.value = true;
    opts.setError('');
    extractedRaw.value = null;
    buildSteps.value = [{ key: 'init', label: '正在准备抽取…', status: 'running' }];
    if (sseHandle) { try { sseHandle.abort(); } catch { /* noop */ } }

    sseHandle = extractFromFilesStream(
      files,
      { urls },
      {
        onStep: (s) => {
          // 上一步标记完成,新步骤进入运行态——形成清晰的逐步推进感
          markRunningAs('done');
          buildSteps.value.push({ key: s.key, label: s.label, status: 'running' });
        },
        onComplete: (data) => {
          markRunningAs('done');
          extractedRaw.value = { nodes: data.nodes || [], edges: data.edges || [] };
          sources.value = data.sources || [];
          replyText.value = data.reply || '';
          // 刷新选择集(去重对照由 dedup 持有,组件在此回调里全选 displayed)
          opts.onExtracted?.();
          loading.value = false;
          sseHandle = null;
        },
        onError: (msg) => {
          markRunningAs('error');
          opts.setError(msg || '抽取失败');
          loading.value = false;
          sseHandle = null;
        },
        onClose: () => {
          // 正常完成由 onComplete 处理;此处只兜底复位 loading（如流意外关闭）
          if (loading.value) loading.value = false;
          sseHandle = null;
        },
      }
    );
  };

  return { loading, replyText, sources, extractedRaw, buildSteps, start, abort, reset };
}
