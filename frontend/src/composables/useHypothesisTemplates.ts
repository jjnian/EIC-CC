import { ref } from 'vue';
import { toast } from './useToast';
import {
  listTemplates,
  saveTemplate,
  touchTemplate,
  deleteTemplate,
  type HypothesisTemplate,
} from '../api/hypothesisTemplates';

/**
 * 推演假设模板的增删改查。
 *
 * 模板的加载/保存会读写组件内的表单状态（seeds/steps/intent/constraints/prompt），
 * 这些副作用通过回调注入：
 *  - getForm: 读取当前表单值（保存模板时用）
 *  - applyForm: 把模板回填到组件表单 ref（加载模板时用，确保 Vue 响应式）
 *  - getExistingNodeIds: 当前图谱仍存在的节点 id 集合（过滤已失效节点）
 *  - getModelId: 当前模型 id
 */
export function useHypothesisTemplates(opts: {
  getModelId: () => string | undefined;
  getForm: () => { seeds: string[]; steps: number; intent: 'forward' | 'backward'; constraints: any[]; prompt: string };
  applyForm: (t: HypothesisTemplate, existingIds: Set<string>) => void;
  getExistingNodeIds: () => Set<string>;
}) {
  const templates = ref<HypothesisTemplate[]>([]);

  const loadTemplates = async () => {
    const modelId = opts.getModelId();
    if (!modelId) return;
    try {
      templates.value = await listTemplates(modelId);
    } catch { /* 静默失败 */ }
  };

  /**
   * 保存当前配置为模板。原组件函数无参、内部读 templateName.value 并在成功后清空；
   * 抽出后改为接收 name 参数，清空副作用由组件在收到 true 后处理（保证仅成功时清空）。
   * @returns 是否保存成功
   */
  const saveAsTemplate = async (name: string): Promise<boolean> => {
    const tName = name.trim();
    if (!tName) { toast.warn('请输入模板名称'); return false; }
    try {
      const form = opts.getForm();
      const t = await saveTemplate({
        modelId: opts.getModelId(),
        name: tName,
        seeds: form.seeds,
        steps: form.steps,
        intent: form.intent,
        constraints: form.constraints.slice(),
        prompt: form.prompt.trim(),
      });
      templates.value.unshift(t);
      toast.info('模板已保存');
      return true;
    } catch { toast.error('保存模板失败'); return false; }
  };

  const loadFromTemplate = async (t: HypothesisTemplate) => {
    const existingIds = opts.getExistingNodeIds();
    // 表单回填的副作用在组件内完成（保持响应式 + 关闭模板面板）。
    opts.applyForm(t, existingIds);
    // 回填后判断是否有节点已失效——与原逻辑一致：用过滤后的起点数对比模板原始起点数。
    const loadedSeedCount = t.seeds.filter(id => existingIds.has(id)).length;
    if (loadedSeedCount < t.seeds.length) {
      toast.warn(`部分节点已不存在，已加载 ${loadedSeedCount}/${t.seeds.length} 个起点`);
    }
    try { await touchTemplate(t.id); } catch { /* 静默 */ }
  };

  const removeTemplate = async (t: HypothesisTemplate) => {
    try {
      await deleteTemplate(t.id);
      templates.value = templates.value.filter(x => x.id !== t.id);
      toast.info('模板已删除');
    } catch { toast.error('删除失败'); }
  };

  return { templates, loadTemplates, saveAsTemplate, loadFromTemplate, removeTemplate };
}
