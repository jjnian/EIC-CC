import { ref, computed } from 'vue';
import { toast } from './useToast';
import { ApiError } from '../api/http';
import { createWebSystem, updateWebSystem, type Experience } from '../api/experiences';
import { runSavedExplore } from '../api/explore';
import type { useWorkspaces } from './useWorkspaces';
import type { useSidebarTree } from './useSidebarTree';

/**
 * 「接入 Web 系统 + 自动探索」特性的全部状态与动作。
 * <p>从 ExperiencePageView.vue 抽出：保存连接配置（创建/编辑）、运行/停止自动探索（SSE 流式）、行内进度。
 * 与经验列表的其它编辑逻辑解耦，页面组件只负责把返回值绑定到模板。
 */
export function useWebSystemExplore(deps: {
  ws: ReturnType<typeof useWorkspaces>;
  tree: ReturnType<typeof useSidebarTree>;
  /** 探索完成后刷新经验列表（force=true 强制拉取）。 */
  reload: (force?: boolean) => Promise<void>;
}) {
  const { ws, tree, reload } = deps;

  const exploreOpen = ref(false);
  const wsFormId = ref<string | null>(null);     // null=新接入；非空=编辑已保存的 web 系统
  const exploreTitle = ref('');
  const exploreUrl = ref('');
  const exploreUsername = ref('');
  const explorePassword = ref('');
  const exploreMaxSteps = ref(30);
  const exploreReadOnly = ref(true);
  const exploreStorageState = ref('');
  const exploreHasStorageState = ref(false);      // 编辑时该系统是否已配置过 storageState
  const wsSaving = ref(false);
  const exploreRunning = ref(false);              // 是否正在跑探索（步骤流式中）
  const exploringId = ref<string | null>(null);   // 正在被探索的 web 系统条目 id（行内显示进度用）
  const exploreSteps = ref<{ key: string; label: string }[]>([]);
  let exploreHandle: { abort: () => void } | null = null;

  const isEditingWs = computed(() => !!wsFormId.value);
  // 当前探索的最新一步文案（行内进度）
  const lastStepLabel = computed(() =>
    exploreSteps.value.length ? exploreSteps.value[exploreSteps.value.length - 1].label : '');

  const resetWsForm = () => {
    wsFormId.value = null;
    exploreTitle.value = '';
    exploreUrl.value = '';
    exploreUsername.value = '';
    explorePassword.value = '';
    exploreMaxSteps.value = 30;
    exploreReadOnly.value = true;
    exploreStorageState.value = '';
    exploreHasStorageState.value = false;
    exploreSteps.value = [];
  };

  // 新接入一个 web 系统（空表单）
  const openExplore = () => { resetWsForm(); exploreOpen.value = true; };

  // 编辑已保存的 web 系统（回填连接配置，密码/ storageState 不回填，留空保留）
  const editWebSystem = (x: Experience) => {
    resetWsForm();
    wsFormId.value = x.id;
    exploreTitle.value = x.title || '';
    const c = x.connection;
    if (c) {
      exploreUrl.value = c.baseUrl || '';
      exploreUsername.value = c.username || '';
      exploreMaxSteps.value = c.maxSteps ?? 30;
      exploreReadOnly.value = c.readOnly ?? true;
      exploreHasStorageState.value = !!c.hasStorageState;
    }
    exploreOpen.value = true;
  };

  const closeExplore = () => {
    // 关闭对话框不打断后台探索(SSE 仍在跑,完成后会刷新列表)
    exploreOpen.value = false;
  };

  // 入口地址缺少 http(s):// 时补 https://，让用户看到规范化后的地址（后端仍会再校验一次）
  const normalizeUrl = (raw: string): string => {
    const u = raw.trim();
    if (!u) return u;
    return /^https?:\/\//i.test(u) ? u : `https://${u}`;
  };

  // 保存接入（创建或更新连接配置），返回保存后的条目；失败返回 null
  const saveWebSystem = async (): Promise<Experience | null> => {
    if (!exploreUrl.value.trim()) { toast.warn('请填写系统入口地址'); return null; }
    const baseUrl = normalizeUrl(exploreUrl.value);
    exploreUrl.value = baseUrl;
    wsSaving.value = true;
    try {
      const payload = {
        title: exploreTitle.value.trim() || undefined,
        baseUrl,
        username: exploreUsername.value.trim() || undefined,
        password: explorePassword.value || undefined,
        maxSteps: exploreMaxSteps.value,
        readOnly: exploreReadOnly.value,
        storageState: exploreStorageState.value.trim() || undefined,
      };
      const saved = wsFormId.value
        ? await updateWebSystem(wsFormId.value, payload)
        : await createWebSystem(payload);
      const wsId = ws.currentId.value;
      if (wsId) tree.upsertExperience(wsId, saved);
      wsFormId.value = saved.id;
      exploreHasStorageState.value = !!saved.connection?.hasStorageState;
      explorePassword.value = '';
      exploreStorageState.value = '';
      return saved;
    } catch (e) {
      toast.warn(e instanceof ApiError ? e.message : '保存失败');
      return null;
    } finally {
      wsSaving.value = false;
    }
  };

  // 「保存接入」：仅保存连接，关闭对话框（之后可在列表点「探索」生成文档）
  const onSaveWebSystem = async () => {
    const saved = await saveWebSystem();
    if (saved) { toast.success('已保存接入，可在列表对它点「探索」生成业务文档'); exploreOpen.value = false; }
  };

  // 「保存并探索」：先保存连接，再立即按配置运行自动探索
  const onSaveAndExplore = async () => {
    const saved = await saveWebSystem();
    if (saved) startSavedExplore(saved.id);
  };

  // 对一个已保存的 web 系统运行自动探索：每次另产一篇 explore 业务说明经验。
  // 「探索」不弹窗，直接开跑；进度在该条目行内滚动显示，完成后 toast + 刷新列表。
  const startSavedExplore = (experienceId: string) => {
    if (exploreRunning.value) { toast.warn('已有探索在进行中，请等它结束'); return; }
    exploreRunning.value = true;
    exploringId.value = experienceId;
    exploreSteps.value = [{ key: 'open', label: '正在启动探索…' }];
    exploreHandle = runSavedExplore(
      { experienceId },
      {
        onStep: (key, label) => { exploreSteps.value.push({ key, label }); },
        onComplete: async (exp) => {
          toast.success(`探索完成,已生成业务文档「${exp.title}」`);
          await reload(true);
        },
        onError: (msg) => { toast.warn(msg || '探索失败'); },
        onClose: () => { exploreRunning.value = false; exploringId.value = null; exploreHandle = null; },
      },
    );
  };

  // 手动停止正在进行的探索：中断 SSE 连接（后端在下一步感知到客户端断开后会关掉无头浏览器、跳过归纳落库）。
  // 注意：abort 不会触发 onClose，需在此手动复位 UI 状态。
  const stopExplore = () => {
    if (!exploreRunning.value) return;
    exploreHandle?.abort();
    exploreHandle = null;
    exploreRunning.value = false;
    exploringId.value = null;
    exploreSteps.value.push({ key: 'end', label: '已手动停止探索。' });
    toast.info('已停止探索');
  };

  return {
    exploreOpen, wsFormId, exploreTitle, exploreUrl, exploreUsername, explorePassword,
    exploreMaxSteps, exploreReadOnly, exploreStorageState, exploreHasStorageState,
    wsSaving, exploreRunning, exploringId, exploreSteps,
    isEditingWs, lastStepLabel,
    openExplore, editWebSystem, closeExplore, saveWebSystem,
    onSaveWebSystem, onSaveAndExplore, startSavedExplore, stopExplore,
  };
}
