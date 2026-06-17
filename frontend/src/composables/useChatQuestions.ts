import { computed, nextTick, type Ref } from 'vue';
import { toast } from './useToast';
import type { ChatMsg, ChatQuestionMsg } from './useConversations';

/**
 * 对话中「澄清问题」(clarifying questions) 的交互逻辑：旧格式迁移、勾选/提交回答、跳过未答提示。
 * <p>从 ChatPanel.vue 抽出。提交回答时把选择拼成一段文本写入输入框并触发一次发送（由调用方提供 send）。
 */
export function useChatQuestions(deps: {
  msgs: Ref<ChatMsg[]>;
  input: Ref<string>;
  loading: Ref<boolean>;
  inputRef: Ref<HTMLTextAreaElement | null>;
  /** 触发一次发送（拼好回答文本后调用）。 */
  send: () => void;
}) {
  const { msgs, input, loading, inputRef, send } = deps;

  // 旧会话只存了单个 m.question,迁移成新的 m.questions 数组,统一走多问题渲染/交互。
  const migrateLegacyQuestions = () => {
    for (const m of msgs.value) {
      if (m.role === 'a' && m.question && !m.questions) {
        m.questions = [{
          header: m.question.header,
          text: m.question.text,
          multiSelect: m.question.multiSelect,
          options: m.question.options || [],
          selected: m.question.answered ? [m.question.answered] : [],
          answered: m.question.answered,
        }];
        m.questionsDone = !!m.question.answered;
      }
    }
  };

  const optValue = (q: ChatQuestionMsg, label: string) =>
    q.options.find(o => o.label === label)?.value || label;

  // 这组问题是否每条都已至少选了一项(决定「提交回答」是否可用)
  const allAnswered = (m: ChatMsg) => (m.questions || []).every(q => (q.selected || []).length > 0);

  // 把整组问题的选择拼成发回给模型的一段文本
  const buildAnswerText = (m: ChatMsg): string => {
    const qs = m.questions || [];
    if (qs.length === 1) {
      return (qs[0].selected || []).map(l => optValue(qs[0], l)).join('、');
    }
    return qs.map((q, i) => {
      const topic = q.header || q.text;
      const picks = (q.selected || []).map(l => optValue(q, l)).join('、');
      return `${i + 1}. ${topic}：${picks}`;
    }).join('\n');
  };

  // 用户勾选某问题的某个选项:单选=替换(单题时直接提交),多选=切换
  const onPickOption = (mi: number, qi: number, option: { label: string; value?: string }) => {
    if (loading.value) return;
    const m = msgs.value[mi];
    const q = m?.questions?.[qi];
    if (!m || !q || m.questionsDone) return;
    if (!q.selected) q.selected = [];
    if (q.multiSelect) {
      const idx = q.selected.indexOf(option.label);
      if (idx >= 0) q.selected.splice(idx, 1); else q.selected.push(option.label);
    } else {
      q.selected = [option.label];
      if ((m.questions || []).length === 1) { onSubmitAnswers(mi); }  // 单题单选:点选即提交
    }
  };

  // 提交整组回答:拼成一条用户消息发回模型
  const onSubmitAnswers = (mi: number) => {
    if (loading.value) return;
    const m = msgs.value[mi];
    if (!m || !m.questions || m.questionsDone) return;
    if (!allAnswered(m)) { toast.warn('请先回答每个问题再提交'); return; }
    m.questions.forEach(q => { q.answered = (q.selected || []).join('、'); });
    m.questionsDone = true;
    input.value = buildAnswerText(m);
    nextTick(() => { send(); });
  };

  // 用户点「自己输入回答」→ 不发送,只聚焦输入框,由 useChatSend 在 send() 时按输入内容标记已答
  const onCustomAnswer = (_mi: number) => {
    if (loading.value) return;
    nextTick(() => { inputRef.value?.focus(); });
  };

  // 当存在最近未回答的问题组时,在输入框上方显示提示横条
  const hasPendingQuestion = computed(() =>
    msgs.value.some(m => m.role === 'a' && m.questions && m.questions.length > 0 && !m.questionsDone),
  );

  // 让用户主动忽略问题(整组标记已答/跳过,横条收起,选项变灰),便于继续别的话题
  const dismissPendingQuestion = () => {
    for (let i = msgs.value.length - 1; i >= 0; i--) {
      const m = msgs.value[i];
      if (m.role === 'a' && m.questions && m.questions.length && !m.questionsDone) {
        m.questions.forEach(q => { if (!q.answered) q.answered = '(已跳过)'; });
        m.questionsDone = true;
        break;
      }
    }
  };

  return {
    migrateLegacyQuestions,
    onPickOption,
    onSubmitAnswers,
    onCustomAnswer,
    hasPendingQuestion,
    dismissPendingQuestion,
  };
}
