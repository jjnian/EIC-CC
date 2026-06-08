import { type Ref } from 'vue';
import type { OntologyNode, OntologyEdge } from '../types';
import { chatStream, type ChatPayload, type ChatResult, type BuildStep, type ChatMentionRef } from '../api/chat';
import type { SseHandle } from '../api/http';
import { toast } from './useToast';
import type { ChatMsg, ChatMsgAttachment, ChatBuildStep } from './useConversations';
import type { Attachment } from './useAttachments';
import type { ModelOption } from './useChatModels';
import type { ActiveMention } from './useMention';

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
  /** 当前打开的本体模型 ID (getter)，用于分析完成后在消息里挂载"查看图谱"链接。 */
  currentModelId: () => string;
  emit: (event: 'update', addNodes: OntologyNode[], addEdges: OntologyEdge[]) => void;
  closeMention: () => void;
  /** 取走并清空当前已激活的 @ 引用，与 input 一起送给后端 */
  consumeMentions?: () => ActiveMention[];
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
      if (currentAiMsg && !currentAiMsg.text) {
        currentAiMsg.text = '已停止生成。';
        if (currentAiMsg.buildSteps) {
          for (const s of currentAiMsg.buildSteps) {
            if (s.status === 'running') s.status = 'done';
          }
        }
        currentAiMsg.buildDone = true;
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
    // 用户在「问答中」直接打字发送 → 把这次输入当作对最近一组未回答 questions 的自由回答,
    // 这样问题卡片才会从「未答」切到「已答」,不会一直挂着误导用户
    const pendingMsg = [...ctx.msgs.value].reverse().find(
      m => m.role === 'a' && m.questions && m.questions.length > 0 && !m.questionsDone,
    );
    if (pendingMsg && txt.trim()) {
      const trimmed = txt.trim();
      const summary = trimmed.length > 40 ? trimmed.slice(0, 40) + '…' : trimmed;
      (pendingMsg.questions || []).forEach(q => { if (!q.answered) q.answered = summary; });
      pendingMsg.questionsDone = true;
    }
    // 取走当前已激活的 @ 引用，与本次 message 一起送给后端；先取再清，避免发送中途用户又点
    const refsForRequest: ChatMentionRef[] = (ctx.consumeMentions?.() || []).map(m => ({
      kind: m.kind, id: m.id, label: m.label,
    }));
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

    const initialSteps: ChatBuildStep[] = [];
    // 在 SSE 之前先注入一步:让用户看到本次构建依据(上传文件 + 引用的图谱上下文)
    {
      const parts: string[] = [];
      if (requestAtts.length) {
        const names = requestAtts.slice(0, 4).map(a => a.name).join('、');
        const suffix = requestAtts.length > 4 ? ` 等 ${requestAtts.length} 个` : '';
        parts.push(`上传文件 ${names}${suffix}`);
      }
      const refNodes = ctx.nodes().length;
      const refEdges = ctx.edges().length;
      if (refNodes || refEdges) {
        parts.push(`当前图谱 ${refNodes} 节点 / ${refEdges} 关系`);
      }
      if (refsForRequest.length) {
        const dsCount = refsForRequest.filter(r => r.kind === 'datasource').length;
        const nodeCount = refsForRequest.filter(r => r.kind === 'node').length;
        const relCount = refsForRequest.filter(r => r.kind === 'relation').length;
        const expCount = refsForRequest.filter(r => r.kind === 'experience').length;
        const sub: string[] = [];
        if (dsCount) sub.push(`${dsCount} 个数据源`);
        if (nodeCount) sub.push(`${nodeCount} 个节点`);
        if (relCount) sub.push(`${relCount} 条关系`);
        if (expCount) sub.push(`${expCount} 个经验文件`);
        if (sub.length) parts.push(`🎯 @ 引用 ${sub.join('/')}`);
      }
      initialSteps.push({
        key: 'fe_input_summary',
        label: parts.length ? '正在分析依据:' + parts.join(' · ') : '正在根据用户描述构建本体…',
        status: 'running',
      });
    }
    const aiMsg: ChatMsg = { role: 'a', text: '', buildSteps: initialSteps, buildDone: false };
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
      if (refsForRequest.length) body.mentions = refsForRequest;
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
          onStep: (step: BuildStep) => {
            if (!aiMsg.buildSteps) aiMsg.buildSteps = [];
            for (const s of aiMsg.buildSteps) {
              if (s.status === 'running') s.status = 'done';
            }
            aiMsg.buildSteps.push({ key: step.key, label: step.label, status: 'running' });
          },
          onText: (chunk: string) => {
            rawJsonBuf += chunk;
          },
          onComplete: (parsed: ChatResult) => {
            try {
              if (aiMsg.buildSteps) {
                for (const s of aiMsg.buildSteps) s.status = 'done';
              }
              aiMsg.buildDone = true;

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
                const mid = ctx.currentModelId();
                if (mid) aiMsg.graphModelId = mid;
              }

              // LLM 返回了澄清问题(支持一次多个、单题多选)→ 挂到这条消息渲染为可点击选项
              // 兼容旧后端的单 question 字段。
              const rawQs = parsed.questions && parsed.questions.length
                ? parsed.questions
                : (parsed.question ? [parsed.question] : []);
              const normQs = rawQs
                .filter(q => q && typeof q.text === 'string' && q.text.trim())
                .map(q => ({
                  header: q.header?.trim() || undefined,
                  text: q.text.trim(),
                  multiSelect: !!q.multiSelect,
                  options: (q.options || [])
                    .filter(o => o && typeof o.label === 'string' && o.label.trim())
                    .map(o => ({ label: o.label.trim(), value: o.value })),
                  selected: [] as string[],
                }))
                .filter(q => q.options.length > 0)
                .slice(0, 4);
              if (normQs.length) {
                aiMsg.questions = normQs;
                aiMsg.questionsDone = false;
              }
            } catch (parseErr) {
              console.error('Failed to handle complete event:', parseErr);
              if (!aiMsg.text) {
                aiMsg.text = '解析失败,模型返回内容非合法 JSON。';
              }
              aiMsg.buildDone = true;
            } finally {
              // complete 事件已处理完毕,主动结束本次流,不再依赖 onClose 触发。
              // 某些情况下后端 emitter.complete() 后连接未干净关闭,onClose 不触发,
              // 会导致 await 永久挂起、loading 卡死。这里兜底 resolve。
              resolveStream();
            }
          },
          onError: (msg: string) => {
            // LLM 报错时立即终止 SSE 与剩余分析,把进行中的步骤打上失败标记。
            if (chatHandle) {
              try { chatHandle.abort(); } catch { /* noop */ }
              chatHandle = null;
            }
            if (aiMsg.buildSteps) {
              for (const s of aiMsg.buildSteps) {
                if (s.status === 'running' || s.status === 'pending') s.status = 'error';
              }
            }
            aiMsg.text = `错误: ${msg}`;
            aiMsg.buildDone = true;
            resolveStream();
          },
          onClose: () => {
            // 兜底：complete 没触发但有累计的原始 JSON，尝试解析一次
            if (!aiMsg.text) {
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
            aiMsg.buildDone = true;
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
