import { type Ref } from 'vue';
import type { OntologyNode, OntologyEdge } from '../types';
import { chatStream, type ChatPayload, type ChatResult } from '../api/chat';
import type { SseHandle } from '../api/http';
import { toast } from './useToast';
import type { ChatMsg, ChatMsgAttachment } from './useConversations';
import type { Attachment } from './useAttachments';
import type { ModelOption } from './useChatModels';

export interface ChatSendCtx {
  msgs: Ref<ChatMsg[]>;
  input: Ref<string>;
  atts: Ref<Attachment[]>;
  loading: Ref<boolean>;
  nodes: () => OntologyNode[];
  edges: () => OntologyEdge[];
  /** 用 getter 拿当前标题，避免 useConversations 与本 composable 间的初始化顺序问题。 */
  conversationTitle: () => string;
  setConversationTitle: (t: string) => void;
  autoTitle: (msgs: ChatMsg[]) => string;
  currentModel: Ref<ModelOption | null>;
  emit: (event: 'update', addNodes: OntologyNode[], addEdges: OntologyEdge[]) => void;
  closeMention: () => void;
}

const PERSIST_TEXT_MAX = 100_000;
const PERSIST_IMG_MAX = 2_000_000;

/**
 * Chat 发送 + SSE 流式接收的状态机。
 * <p>抽出来便于：1) 单独维护 abort/close 等边沿状态；2) 让 ChatPanel 主组件只关心 UI。
 */
export function useChatSend(ctx: ChatSendCtx) {
  let chatHandle: SseHandle | null = null;
  let currentResolveStream: (() => void) | null = null;
  let currentAiMsg: ChatMsg | null = null;

  /**
   * 主动断开 SSE 流。
   * <p>中断后 SSE 不会再回调 onClose，这里手动收尾，避免 loading 卡住。
   */
  const abortChat = () => {
    if (chatHandle) {
      try { chatHandle.abort(); } catch { /* noop */ }
      chatHandle = null;
    }
    if (currentResolveStream) {
      if (currentAiMsg && currentAiMsg.text === '正在分析对话内容并构建图谱…') {
        currentAiMsg.text = '已停止生成。';
      }
      const r = currentResolveStream;
      currentResolveStream = null;
      currentAiMsg = null;
      r();
    }
  };

  const send = async () => {
    if (!ctx.input.value.trim() && !ctx.atts.value.length) return;
    ctx.closeMention();

    // 等待所有附件读取完成
    if (ctx.atts.value.some(a => a.loading)) {
      ctx.loading.value = true;
      while (ctx.atts.value.some(a => a.loading)) {
        await new Promise(r => setTimeout(r, 80));
      }
      ctx.loading.value = false;
    }

    const validAtts = ctx.atts.value.filter(a => !a.error);
    const failed = ctx.atts.value.filter(a => a.error);

    const txt = ctx.input.value;
    const uaDisplay: ChatMsgAttachment[] = ctx.atts.value.map(a => {
      const out: ChatMsgAttachment = {
        name: a.name, type: a.type, kind: a.kind, size: a.size, error: a.error,
        truncated: a.truncated,
      };
      if (!a.error && a.content) {
        const len = a.content.length;
        if (a.kind === 'image' && len <= PERSIST_IMG_MAX) {
          out.content = a.content;
        } else if (a.kind === 'text' && len <= PERSIST_TEXT_MAX) {
          out.content = a.content;
        } else if (a.kind === 'text') {
          out.content = a.content.slice(0, PERSIST_TEXT_MAX);
          out.storedTruncated = true;
        } else if (a.kind === 'image') {
          out.storedTruncated = true;
        }
      }
      return out;
    });
    ctx.msgs.value.push({ role: 'u', text: txt, atts: uaDisplay });
    ctx.input.value = '';
    const requestAtts = [...validAtts];
    ctx.atts.value = [];
    ctx.loading.value = true;

    if (failed.length) {
      ctx.msgs.value.push({ role: 'a', text: '⚠ 部分文件未能加入:\n' + failed.map(a => `· ${a.name}: ${a.error}`).join('\n') });
    }

    if (ctx.conversationTitle() === '新对话') {
      ctx.setConversationTitle(ctx.autoTitle(ctx.msgs.value));
    }

    const aiMsg: ChatMsg = { role: 'a', text: '正在分析对话内容并构建图谱…' };
    ctx.msgs.value.push(aiMsg);
    let rawJsonBuf = '';

    try {
      const history = ctx.msgs.value
        .filter(m => m !== aiMsg && (m.role === 'u' || m.role === 'a') && m.text)
        .slice(-40)
        .map(m => ({ role: m.role === 'u' ? 'user' : 'assistant', content: m.text }));

      let composedMessage = txt;
      const textAtts = requestAtts.filter(a => a.kind === 'text' && a.content);
      if (textAtts.length) {
        const docs = textAtts.map(a =>
          `=== 文件: ${a.name}${a.truncated ? ' (已截断)' : ''} ===\n${a.content}`
        ).join('\n\n');
        composedMessage = (txt ? txt + '\n\n' : '') + '附加文档内容:\n' + docs;
      }

      const imageAtts = requestAtts
        .filter(a => a.kind === 'image' && a.content)
        .map(a => ({ name: a.name, type: 'image', dataUrl: a.content }));

      const body: ChatPayload = { message: composedMessage, history };
      const nodesNow = ctx.nodes();
      const edgesNow = ctx.edges();
      if (nodesNow?.length) {
        body.nodes = nodesNow.map(n => ({ id: n.id, label: n.label, type: n.type }));
      }
      if (edgesNow?.length) {
        body.edges = edgesNow.map(e => ({ id: e.id, from: e.from, to: e.to, label: e.label || '' }));
      }
      if (imageAtts.length) body.attachments = imageAtts;
      const cm = ctx.currentModel.value;
      if (cm?.configId) {
        body.configId = cm.configId;
      } else if (cm?.type === 'preset') {
        body.modelOverride = cm.id;
      }

      abortChat();

      await new Promise<void>((resolveStream) => {
        currentResolveStream = resolveStream;
        currentAiMsg = aiMsg;
        chatHandle = chatStream(body, {
          onText: (chunk: string) => {
            rawJsonBuf += chunk;
          },
          onComplete: (parsed: ChatResult) => {
            try {
              const addNodes = (parsed.add_nodes as OntologyNode[]) || [];
              const addEdges = (parsed.add_edges as OntologyEdge[]) || [];
              const reply = (parsed.reply || '').trim();
              if (reply) {
                aiMsg.text = reply;
              } else if (addNodes.length || addEdges.length) {
                aiMsg.text = `已从对话内容提取 ${addNodes.length} 个节点 / ${addEdges.length} 条关系,已加入图谱。`;
              } else {
                aiMsg.text = '未识别到可加入图谱的实体或关系,请补充更具体的描述。';
              }

              ctx.emit('update', addNodes.map(n => ({ ...n })), addEdges);

              if (addNodes.length || addEdges.length) {
                toast.success(`图谱已更新:+${addNodes.length} 节点 / +${addEdges.length} 关系`);
              }
            } catch (parseErr) {
              console.error('Failed to handle complete event:', parseErr);
              if (!aiMsg.text || aiMsg.text === '正在分析对话内容并构建图谱…') {
                aiMsg.text = '解析失败,模型返回内容非合法 JSON。';
              }
            }
          },
          onError: (msg: string) => {
            aiMsg.text = `错误: ${msg}`;
            resolveStream();
          },
          onClose: () => {
            // 兜底：complete 没触发但有累计的原始 JSON，尝试解析一次
            if (aiMsg.text === '正在分析对话内容并构建图谱…') {
              const fallback = rawJsonBuf.trim().replace(/^```json/i, '').replace(/```$/, '').trim();
              if (fallback) {
                try {
                  const parsed = JSON.parse(fallback) as ChatResult;
                  const reply = (parsed.reply || '').trim();
                  aiMsg.text = reply || '已收到回复,但未识别到图谱更新。';
                  const addNodes = (parsed.add_nodes as OntologyNode[]) || [];
                  const addEdges = (parsed.add_edges as OntologyEdge[]) || [];
                  if (addNodes.length || addEdges.length) {
                    ctx.emit('update', addNodes.map(n => ({ ...n })), addEdges);
                    toast.success(`图谱已更新:+${addNodes.length} 节点 / +${addEdges.length} 关系`);
                  }
                } catch {
                  aiMsg.text = '未收到有效回复';
                }
              } else {
                aiMsg.text = '未收到有效回复';
              }
            }
            resolveStream();
          },
        });
      });
    } catch (error: any) {
      const lastAi = [...ctx.msgs.value].reverse().find(m => m.role === 'a');
      if (lastAi && !lastAi.text) {
        lastAi.text = `网络或解析错误: ${error.message}`;
      }
    } finally {
      chatHandle = null;
      currentResolveStream = null;
      currentAiMsg = null;
      ctx.loading.value = false;
    }
  };

  return { send, abortChat };
}
