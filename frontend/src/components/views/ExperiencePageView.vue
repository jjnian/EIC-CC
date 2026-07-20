<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue';
import { useWorkspaces } from '../../composables/useWorkspaces';
import { useSidebarTree } from '../../composables/useSidebarTree';
import { confirm as uiConfirm } from '../../composables/useConfirm';
import { toast } from '../../composables/useToast';
import { ApiError } from '../../api/http';
import {
  createExperience, updateExperience, reindexExperience, uploadExperienceFile,
  experienceFileUrl, listExperiencesPaged, listExperienceWorkspaces, listAllExperiences, listExperiences, deleteExperience,
  listSourceExplorations, reindexAllExperiences, getExperienceIndexSummary, type Experience,
} from '../../api/experiences';
import { listAllDataSources, type DataSource } from '../../api/dataSources';
import { useWebSystemExplore } from '../../composables/useWebSystemExplore';
import ExpOntologyExtractDialog from '../ExpOntologyExtractDialog.vue';
import { Button } from '@/components/ui/button';
import BaseInput from '../form/BaseInput.vue';
import BaseTextarea from '../form/BaseTextarea.vue';
import BaseCheckbox from '../form/BaseCheckbox.vue';
import { renderMarkdown, MD_TEMPLATE, INTERVIEW_TEMPLATE } from '../../utils/markdown';
import type { OntologyNode, OntologyEdge } from '../../types';

const props = defineProps<{
  /** 侧栏点击进入时要定位/编辑的经验 id */
  focusId?: string | null;
  /** 自增信号：变化时打开一个空白「新建经验」表单 */
  createSignal?: number;
  /** 当前打开的本体模型 id（增量建图的目标） */
  currentModelId?: string;
  /** 当前是否已有打开的本体模型（决定「构建本体血缘图」能否合并到当前模型） */
  hasCurrentModel?: boolean;
}>();

const emit = defineEmits<{
  (e: 'ontology-extracted', payload: {
    mode: 'merge' | 'new';
    name: string;
    nodes: OntologyNode[];
    edges: OntologyEdge[];
  }): void;
  /** 全量建图：服务端已落成新模型，通知父级刷新并打开。 */
  (e: 'built-model', payload: { modelId: string; title: string; nodeCount: number; edgeCount: number; sourceCount: number }): void;
}>();

const ws = useWorkspaces();
const tree = useSidebarTree();

// ── 公共经验库：跨工作空间的全量列表 ───────────────────────────
const items = ref<Experience[]>([]);
const loading = ref(false);
// 分页：经验库达千/万篇时按页取，避免一次拉全量 + 渲染上万 DOM 行
const PAGE_SIZE = 60;
const total = ref(0);
const pageNo = ref(0);
const loadingMore = ref(false);
const hasMore = computed(() => items.value.length < total.value);
// 有经验的归属工作空间 id（筛选条来源，独立轻量查询，不依赖已加载页）
const wsChipIds = ref<string[]>([]);

// 数据源名称解析：DDL 抽取的经验据此显示「来自哪个数据库」（实时名，随数据源重命名变化）。
const dataSources = ref<DataSource[]>([]);
const dsById = computed<Record<string, DataSource>>(() => {
  const m: Record<string, DataSource> = {};
  for (const d of dataSources.value) m[d.id] = d;
  return m;
});
// 按归属工作空间筛选（null = 全部）
const filterWs = ref<string | null>(null);

// 兼容原有引用：experiences 即全量经验
const experiences = computed<Experience[]>(() => items.value);

const wsName = (id?: string) => {
  if (!id) return '—';
  return ws.workspaces.value.find(w => w.id === id)?.name || '(已删除)';
};

// 出现在列表里的归属工作空间（筛选条）：取自独立 distinct 查询，分页下也完整
const usedWorkspaces = computed(() =>
  ws.workspaces.value.filter(w => wsChipIds.value.includes(w.id)));

// 统一列表：手写 / DDL 供血 / 系统探索 / 上传文件 全部按更新时间倒序展示为「经验文件」
const allExperiences = computed<Experience[]>(() =>
  [...items.value].sort(
    (a, b) => (b.updatedAt || b.createdAt || 0) - (a.updatedAt || a.createdAt || 0),
  ));

// 按归属工作空间筛选后的展示列表
const visibleExperiences = computed<Experience[]>(() =>
  filterWs.value ? allExperiences.value.filter(e => e.workspaceId === filterWs.value) : allExperiences.value);

// ── 探索产物溯源 + 同源聚合 ──────────────────────────────
// 已加载项 id→经验，供探索产物解析「来自哪个源」的标题（源与产物可能分处不同页，解析不到则退化为通用文案）
const itemById = computed<Record<string, Experience>>(() => {
  const m: Record<string, Experience> = {};
  for (const e of items.value) m[e.id] = e;
  return m;
});
const sourceTitleOf = (x: Experience): string | null =>
  x.sourceExperienceId ? (itemById.value[x.sourceExperienceId]?.title || null) : null;

// websystem 源行展开查看其探索产物（点击现拉，不依赖分页）
const expandedSource = ref<string | null>(null);
const sourceProducts = ref<Record<string, Experience[]>>({});
const loadingProducts = ref<string | null>(null);
const toggleSourceProducts = async (sourceId: string) => {
  if (expandedSource.value === sourceId) { expandedSource.value = null; return; }
  expandedSource.value = sourceId;
  if (!sourceProducts.value[sourceId]) {
    loadingProducts.value = sourceId;
    try {
      sourceProducts.value = { ...sourceProducts.value, [sourceId]: await listSourceExplorations(sourceId) };
    } catch {
      sourceProducts.value = { ...sourceProducts.value, [sourceId]: [] };
    } finally {
      loadingProducts.value = null;
    }
  }
};

// 当前工作空间「引用」的经验数（「构建本体血缘图」按当前工作空间引用的经验聚合，故据此判断可用）
const currentWsCount = ref(0);

// 列表本地增删改：保持公共列表与侧栏一致
const upsertItem = (exp: Experience) => {
  const arr = [...items.value];
  const idx = arr.findIndex(x => x.id === exp.id);
  if (idx >= 0) arr[idx] = { ...arr[idx], ...exp };
  else { arr.unshift(exp); total.value += 1; }
  items.value = arr;
  extractScopeItems.value = [];   // 使建图范围选择器缓存失效，下次打开重取含新增项的全量
  // 新探索产物：使其来源的「探索产物」缓存失效，展开时重取以显示这一篇
  if (exp.origin === 'explore' && exp.sourceExperienceId) {
    const { [exp.sourceExperienceId]: _drop, ...rest } = sourceProducts.value;
    sourceProducts.value = rest;
  }
};
const removeItem = (id: string) => {
  const before = items.value.length;
  items.value = items.value.filter(x => x.id !== id);
  if (items.value.length < before) total.value = Math.max(0, total.value - 1);
};

// ── 右上角「新增」下拉菜单 ──────────────────────────────────
const addMenuOpen = ref(false);
const toggleAddMenu = () => { addMenuOpen.value = !addMenuOpen.value; };
const closeAddMenu = () => { addMenuOpen.value = false; };

// ── 从经验库一键构建本体血缘图 ──────────────────────────────
const extractDialogOpen = ref(false);
const currentWsName = computed(() => ws.current()?.name || '');
const onExtractCommit = (payload: {
  mode: 'merge' | 'new';
  name: string;
  nodes: OntologyNode[];
  edges: OntologyEdge[];
}) => {
  extractDialogOpen.value = false;
  emit('ontology-extracted', payload);
};

// 编辑器状态：id 为空 = 新建；非空 = 编辑已有
interface Draft { id: string | null; title: string; tags: string; content: string; }
const draft = ref<Draft | null>(null);
const saving = ref(false);
// 编辑器视图：edit=纯编辑 / split=实时分屏(边写边预览) / preview=纯预览。默认实时分屏。
type EditorMode = 'edit' | 'split' | 'preview';
const editorMode = ref<EditorMode>('split');

// 上传文件预览：当前选中的上传经验
const selectedUpload = ref<Experience | null>(null);

const reload = async (_force = false) => {
  loading.value = true;
  try {
    if (!ws.workspaces.value.length) await ws.reload();
    const curWs = ws.currentId.value;
    pageNo.value = 0;
    const [pageRes, dss, refExps, wsIds] = await Promise.all([
      listExperiencesPaged({ page: 0, size: PAGE_SIZE, workspaceId: filterWs.value }),
      listAllDataSources().catch(() => [] as DataSource[]),
      curWs ? listExperiences({ workspaceId: curWs }).catch(() => [] as Experience[]) : Promise.resolve([] as Experience[]),
      listExperienceWorkspaces().catch(() => [] as string[]),
    ]);
    items.value = pageRes.items;
    total.value = pageRes.total;
    dataSources.value = dss;
    currentWsCount.value = refExps.length;
    wsChipIds.value = wsIds;
    loadIndexSummary();
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
};

/** 追加下一页（「加载更多」/滚动到底）。去重追加，防并发/新增造成重复。 */
const loadMore = async () => {
  if (loadingMore.value || !hasMore.value) return;
  loadingMore.value = true;
  try {
    const next = pageNo.value + 1;
    const res = await listExperiencesPaged({ page: next, size: PAGE_SIZE, workspaceId: filterWs.value });
    const seen = new Set(items.value.map(x => x.id));
    items.value = [...items.value, ...res.items.filter(x => !seen.has(x.id))];
    total.value = res.total;
    pageNo.value = next;
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '加载更多失败');
  } finally {
    loadingMore.value = false;
  }
};

// 归属工作空间筛选改为服务端：切换即重取第一页（分页下客户端筛选会漏未加载页）
watch(filterWs, () => { reload(); });

// 建图范围选择器需要「全量」经验（分页的 items 只有已加载页）：仅在对话框打开时按需全量拉一次
const extractScopeItems = ref<Experience[]>([]);
watch(() => extractDialogOpen.value, async (open) => {
  if (open && extractScopeItems.value.length === 0) {
    try { extractScopeItems.value = await listAllExperiences(); }
    catch { extractScopeItems.value = items.value; }  // 退化为已加载页，不阻断建图
  }
});

// ── 联网调研业务知识（第四类来源：公开领域知识，冷启动/补背景用）──────────
const researchOpen = ref(false);
const researchTopic = ref('');
const researchRunning = ref(false);
const researchSteps = ref<{ key: string; label: string; status: 'running' | 'done' | 'error' }[]>([]);
const researchError = ref('');
let researchHandle: { abort: () => void } | null = null;

const openResearch = () => {
  researchOpen.value = true;
  researchTopic.value = '';
  researchSteps.value = [];
  researchError.value = '';
};
const closeResearch = () => {
  if (researchHandle) { try { researchHandle.abort(); } catch { /* noop */ } researchHandle = null; }
  researchOpen.value = false;
  researchRunning.value = false;
};
const startResearch = () => {
  const topic = researchTopic.value.trim();
  if (!topic || researchRunning.value) return;
  researchRunning.value = true;
  researchError.value = '';
  researchSteps.value = [{ key: 'init', label: '正在准备…', status: 'running' }];
  const markRunning = (st: 'done' | 'error') => {
    for (const x of researchSteps.value) if (x.status === 'running') x.status = st;
  };
  researchHandle = webResearch({ topic }, {
    onStep: (key, label) => {
      const last = researchSteps.value[researchSteps.value.length - 1];
      if (key === 'fetching' && last && last.key === 'fetching') { last.label = label; return; }
      markRunning('done');
      researchSteps.value.push({ key, label, status: 'running' });
    },
    onComplete: async (payload) => {
      markRunning('done');
      researchRunning.value = false;
      researchHandle = null;
      toast.success('已沉淀经验「' + (payload.experience?.title || '网络调研') + '」');
      await reload(true);
      const id = payload.experience?.id;
      closeResearch();
      const found = id ? items.value.find(e => e.id === id) : null;
      if (found) selectExperience(found);
    },
    onError: (msg) => {
      markRunning('error');
      researchError.value = msg || '调研失败';
      researchRunning.value = false;
      researchHandle = null;
    },
    onClose: () => {
      researchHandle = null;
      if (researchRunning.value) {
        markRunning('error');
        researchError.value = '连接中断，请重试';
        researchRunning.value = false;
      }
    },
  });
};

// ── 接入 Web 系统 + 自动探索（状态与动作见 useWebSystemExplore）──────────
const {
  exploreOpen, wsFormId, exploreTitle, exploreUrl, exploreUsername, explorePassword,
  exploreMaxSteps, exploreReadOnly, exploreStorageState, exploreHasStorageState,
  wsSaving, exploreRunning, exploringId, exploreSteps, isEditingWs, lastStepLabel,
  editWebSystem, closeExplore, onSaveWebSystem, onSaveAndExplore, startSavedExplore, stopExplore,
  openExplore: openExploreDialog,
} = useWebSystemExplore({ ws, tree, reload });

// 从右上角「新增」菜单进入新接入：先收起菜单再开表单
const openExplore = () => { closeAddMenu(); openExploreDialog(); };

onMounted(() => { reload(); loadIndexSummary(); });
onBeforeUnmount(() => { if (summaryTimer) clearTimeout(summaryTimer); });
watch(() => ws.currentId.value, () => { draft.value = null; selectedUpload.value = null; reload(); });

const newDraft = () => {
  closeAddMenu();
  selectedUpload.value = null;
  draft.value = { id: null, title: '', tags: '', content: '' };
  editorMode.value = 'split';
};

// ── 上传文件建经验：抽取文本作正文、文件名作标题，并归档原件供预览 ──
const fileInput = ref<HTMLInputElement | null>(null);
const uploading = ref(false);

const triggerUpload = () => { closeAddMenu(); fileInput.value?.click(); };

const onUploadPick = async (ev: Event) => {
  const input = ev.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';            // 允许连续选同一文件
  if (!file) return;
  uploading.value = true;
  try {
    const created = await uploadExperienceFile(file);
    // 经验库为公共库：上传只进公共库，不自动进任何工作空间侧栏（需在工作空间右键「引用」纳入）。
    upsertItem(created);
    draft.value = null;
    selectedUpload.value = created;   // 上传后直接预览
    toast.success('已添加到公共经验库（在工作空间右键「引用」纳入）');
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '上传失败');
  } finally {
    uploading.value = false;
  }
};

const editDraft = (x: Experience) => {
  selectedUpload.value = null;
  draft.value = { id: x.id, title: x.title || '', tags: x.tags || '', content: x.content || '' };
  editorMode.value = 'split';
};

const selectUpload = (x: Experience) => {
  draft.value = null;
  selectedUpload.value = x;
};

// 统一列表：点击一条经验文件 → 上传走预览，web 系统走接入编辑，其余走编辑
const selectExperience = (x: Experience) => {
  if (x.origin === 'upload') selectUpload(x);
  else if (x.origin === 'websystem') editWebSystem(x);
  else editDraft(x);
};
// 当前在右侧打开的经验 id（用于列表高亮）
const activeId = computed(() => draft.value?.id ?? selectedUpload.value?.id ?? null);

// 侧栏点击定位某条经验 → 按来源进入对应 Tab
watch(() => props.focusId, (id) => {
  if (!id) return;
  const found = experiences.value.find(e => e.id === id);
  if (!found) return;
  if (found.origin === 'upload') selectUpload(found);
  else if (found.origin === 'websystem') editWebSystem(found);
  else editDraft(found);
}, { immediate: true });

// 新建信号 → 打开空白表单
watch(() => props.createSignal, (v, old) => {
  if (v && v !== old) newDraft();
});

const cancelEdit = () => { draft.value = null; };

const insertTemplate = () => {
  if (!draft.value) return;
  if (draft.value.content.trim() && !confirmOverwrite()) return;
  draft.value.content = MD_TEMPLATE;
};
const insertInterviewTemplate = () => {
  if (!draft.value) return;
  if (draft.value.content.trim() && !confirmOverwrite()) return;
  draft.value.content = INTERVIEW_TEMPLATE;
  if (!draft.value.title.trim()) draft.value.title = '业务访谈：';
};
const confirmOverwrite = () => window.confirm('正文已有内容，插入模板会覆盖，确定吗？');

const save = async () => {
  const d = draft.value;
  if (!d) return;
  if (!d.title.trim()) { toast.warn('请填写标题'); return; }
  saving.value = true;
  try {
    const payload = { title: d.title.trim(), content: d.content, tags: d.tags.trim() };
    const wsId = ws.currentId.value;
    const saved = d.id
      ? await updateExperience(d.id, payload)
      : await createExperience(payload);
    // 经验库为公共库：新建只进公共库、不自动进工作空间侧栏；编辑则同步已引用本经验的当前工作空间缓存。
    if (wsId && tree.getExperiences(wsId).some(e => e.id === saved.id)) {
      tree.upsertExperience(wsId, saved);
    }
    upsertItem(saved);
    draft.value = null;
    toast.success(d.id ? '已保存' : '已添加到公共经验库（在工作空间右键「引用」纳入）');
  } catch (e) {
    toast.warn(e instanceof ApiError ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
};

const remove = async (x: Experience) => {
  const ok = await uiConfirm({
    title: '删除经验',
    message: `确认删除「${x.title}」吗？此操作不可恢复。`,
    confirmLabel: '删除',
    danger: true,
  });
  if (!ok) return;
  try {
    await deleteExperience(x.id);
    removeItem(x.id);
    // 同步侧栏归属工作空间的缓存
    if (x.workspaceId) tree.removeExperienceFromCache(x.workspaceId, x.id);
    if (draft.value?.id === x.id) draft.value = null;
    if (selectedUpload.value?.id === x.id) selectedUpload.value = null;
    toast.success('已删除');
  } catch (e) {
    toast.warn(e instanceof ApiError ? e.message : '删除失败');
  }
};

const tagList = (tags?: string): string[] =>
  (tags || '').split(',').map(t => t.trim()).filter(Boolean);

const fmtTime = (t?: number) => {
  if (!t) return '';
  const d = new Date(t);
  const now = new Date();
  return d.toDateString() === now.toDateString()
    ? d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
    : d.toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' });
};

const fmtSize = (n?: number) => {
  if (!n || n <= 0) return '';
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
};

const preview = (content?: string) => {
  const s = (content || '').trim();
  return s.length > 120 ? s.slice(0, 120) + '…' : s;
};

// 列表里每条经验文件的图标与来源标签
const originMeta = (x: Experience): { icon: string; label: string; cls: string } => {
  switch (x.origin) {
    case 'upload': return { icon: '📄', label: '上传文件', cls: 'upload' };
    case 'ddl': return { icon: '🗄️', label: '数据源抽取', cls: 'ddl' };
    case 'websystem': return { icon: '🌐', label: 'Web 系统', cls: 'websystem' };
    case 'explore': return { icon: '🧭', label: '系统探索', cls: 'explore' };
    default: return { icon: '✎', label: '手写经验', cls: 'manual' };
  }
};
const rowName = (x: Experience) => x.fileName || x.title || '未命名经验';

// DDL 经验标题形如「<库名>」数据库 DDL —— 数据源被删时从标题兜底解析库名。
const titleDbName = (title?: string): string => {
  const m = (title || '').match(/^「(.+?)」/);
  return m ? m[1] : '';
};
// DDL 经验来源数据库的显示名：优先用实时数据源名，回退到标题里的快照名。
const ddlSourceName = (x: Experience): string => {
  const ds = x.sourceDataSourceId ? dsById.value[x.sourceDataSourceId] : undefined;
  return ds?.name || titleDbName(x.title) || '未知数据库';
};
// 来源数据源是否已被删除（有 id 但在数据源列表里解析不到）。
const ddlSourceMissing = (x: Experience): boolean =>
  !!x.sourceDataSourceId && !dsById.value[x.sourceDataSourceId];
// 各来源的「来源详情」副标签（列表里跟在来源类型后，让来源一目了然）。
const sourceExtra = (x: Experience): string => {
  switch (x.origin) {
    case 'ddl': return ddlSourceName(x);
    case 'upload': return (x.fileMime || '').split(';')[0];
    case 'websystem':
    case 'explore': {
      const url = x.connection?.baseUrl || '';
      try { return url ? new URL(url).host : ''; } catch { return url; }
    }
    default: return '';
  }
};

const idxMeta = (s?: string): { label: string; cls: string } => {
  switch (s) {
    case 'indexed': return { label: '已索引', cls: 'ok' };
    case 'indexing': return { label: '索引中', cls: 'pending' };
    case 'error': return { label: '索引失败', cls: 'err' };
    default: return { label: '未索引', cls: 'none' };
  }
};

const reindexing = ref(false);
const reindex = async (id: string) => {
  reindexing.value = true;
  try {
    const r = await reindexExperience(id);
    if (!r.configured) {
      toast.warn('未配置 Embedding 模型，无法建立向量索引');
    } else {
      toast.success('已触发重新索引，稍后生效');
      const found = items.value.find(e => e.id === id);
      if (found) {
        const next = { ...found, indexStatus: 'indexing' as const };
        upsertItem(next);
        if (found.workspaceId) tree.upsertExperience(found.workspaceId, next);
      }
    }
  } catch (e) {
    toast.warn(e instanceof ApiError ? e.message : '触发失败');
  } finally {
    reindexing.value = false;
  }
};

// ── 全量索引（文件过多时一键补齐 + 进度汇总）──────────────────
const indexSummary = ref<{ total: number; indexed: number; indexing: number; pending: number } | null>(null);
const fullIndexing = ref(false);
let summaryTimer: ReturnType<typeof setTimeout> | null = null;

const loadIndexSummary = async () => {
  try {
    const r = await getExperienceIndexSummary();
    const by = r.byStatus || {};
    const indexed = by['indexed'] || 0;
    const indexing = by['indexing'] || 0;
    indexSummary.value = {
      total: r.total,
      indexed,
      indexing,
      pending: Math.max(0, r.total - indexed - indexing),
    };
    // 还有进行中的就继续轮询，直到全部落定
    if (summaryTimer) { clearTimeout(summaryTimer); summaryTimer = null; }
    if (r.configured && indexing > 0) {
      summaryTimer = setTimeout(loadIndexSummary, 3000);
    }
  } catch {
    indexSummary.value = null;
  }
};

// force=false 补未索引（文件过多时的常用项）；force=true 连已索引也重算（embedding 模型换过时用）。
const runFullIndex = async (force = false) => {
  if (fullIndexing.value) return;
  const ok = await uiConfirm({
    title: force ? '全部重建经验库索引' : '全量补索引经验库',
    message: force
      ? '将为经验库全部经验重新生成向量索引（含已索引的）。文件较多时耗时较长，后台进行。确定继续？'
      : '将为尚未索引的经验补建向量索引（已索引的跳过）。后台排队进行，确定继续？',
    confirmLabel: force ? '全部重建' : '补未索引',
    danger: force,
  });
  if (!ok) return;
  fullIndexing.value = true;
  try {
    const r = await reindexAllExperiences(force);
    if (!r.configured) {
      toast.warn('未配置 Embedding 模型，无法建立向量索引');
    } else {
      toast.success(`已调度 ${r.scheduled} 篇索引${r.skipped ? `（跳过 ${r.skipped} 篇已索引）` : ''}，后台进行中`);
      loadIndexSummary();
    }
  } catch (e) {
    toast.warn(e instanceof ApiError ? e.message : '触发失败');
  } finally {
    fullIndexing.value = false;
  }
};

// ── 上传文件预览 ────────────────────────────────────────────
type PreviewKind = 'pdf' | 'image' | 'audio' | 'video' | 'markdown' | 'text' | 'other';
const previewKind = (e: Experience): PreviewKind => {
  const mime = (e.fileMime || '').toLowerCase();
  const name = (e.fileName || '').toLowerCase();
  if (mime.includes('pdf') || name.endsWith('.pdf')) return 'pdf';
  if (mime.startsWith('image/') || /\.(png|jpe?g|gif|webp|svg|bmp)$/.test(name)) return 'image';
  if (mime.startsWith('audio/') || /\.(mp3|wav|m4a|flac|aac|ogg|opus|wma|amr)$/.test(name)) return 'audio';
  if (mime.startsWith('video/') || /\.(mp4|m4v|mov|mkv|avi|mpe?g|wmv|flv)$/.test(name)) return 'video';
  if (mime.includes('markdown') || name.endsWith('.md')) return 'markdown';
  if (mime.startsWith('text/') || name.endsWith('.txt')) return 'text';
  return 'other';
};
const fileUrl = (e: Experience, download = false) =>
  experienceFileUrl(e.id, { download, wsId: ws.currentId.value || undefined });
const selKind = computed<PreviewKind | null>(() =>
  selectedUpload.value ? previewKind(selectedUpload.value) : null);
const renderedUpload = computed(() =>
  selectedUpload.value ? renderMarkdown(selectedUpload.value.content || '') : '');

// ── 编辑器 Markdown 预览 ────────────────────────────────────
const renderedDraft = computed(() => renderMarkdown(draft.value?.content || ''));
</script>

<template>
  <div class="exp-view">
    <div class="exp-header">
      <div>
        <h2>经验库 <span class="exp-public-tag">公共</span></h2>
        <p>公共经验库：所有工作空间共享，可查看与复用。手写支持 Markdown（实时预览），可上传 PDF / Word / Excel / TXT / MD（音频/视频自动转写、图片视觉识别）并预览原件。</p>
      </div>
      <div class="exp-header-actions">
        <span
          v-if="indexSummary"
          class="exp-idx-summary"
          :title="`已索引 ${indexSummary.indexed} · 索引中 ${indexSummary.indexing} · 未索引 ${indexSummary.pending}（共 ${indexSummary.total}）`"
        >
          索引 {{ indexSummary.indexed }}/{{ indexSummary.total }}
          <em v-if="indexSummary.indexing > 0" class="exp-idx-run">· {{ indexSummary.indexing }} 进行中</em>
        </span>
        <Button
          variant="secondary"
          size="sm"
          class="exp-fullidx"
          :disabled="fullIndexing"
          title="为经验库尚未索引的经验一键补建向量索引（文件过多时用）。右键/长按可全部重建。"
          @click="runFullIndex(false)"
          @contextmenu.prevent="runFullIndex(true)"
        >{{ fullIndexing ? '调度中…' : '⚡ 全量索引' }}</Button>
        <Button
          variant="secondary"
          size="sm"
          title="联网搜索业务主题的公开资料，归纳成《业务知识文档》沉淀为经验（冷启动/补充行业背景）"
          @click="openResearch"
        >🌐 联网调研</Button>
        <Button
          variant="secondary"
          size="sm"
          class="exp-build"
          :disabled="currentWsCount === 0"
          :title="currentWsCount === 0 ? '当前工作空间还没有经验，请先在当前工作空间创建/上传经验' : '聚合当前工作空间的经验构建本体血缘图'"
          @click="extractDialogOpen = true"
        >🧬 构建本体血缘图</Button>
        <div class="exp-add">
          <Button size="sm" @click.stop="toggleAddMenu">＋ 新增 <span class="exp-add-caret" :class="{ open: addMenuOpen }">▾</span></Button>
          <div v-if="addMenuOpen" class="exp-add-backdrop" @click="closeAddMenu"></div>
          <div v-if="addMenuOpen" class="exp-add-menu">
            <div class="exp-add-kicker">添加经验来源</div>
            <button class="exp-add-item c-write" @click="newDraft">
              <span class="exp-add-ico">✎</span>
              <span><strong>新建经验</strong><em>手写一篇 Markdown 经验</em></span>
              <span class="exp-add-go">→</span>
            </button>
            <button class="exp-add-item c-upload" :disabled="uploading" @click="triggerUpload">
              <span class="exp-add-ico">⤓</span>
              <span><strong>{{ uploading ? '解析中…' : '上传文件' }}</strong><em>PDF / Word / Excel / TXT / MD / 音频 / 视频 / 图片</em></span>
              <span class="exp-add-go">→</span>
            </button>
            <button class="exp-add-item c-web" @click="openExplore">
              <span class="exp-add-ico">🌐</span>
              <span><strong>接入 Web 系统</strong><em>保存连接，随时探索反推业务生成文档</em></span>
              <span class="exp-add-go">→</span>
            </button>
          </div>
        </div>
      </div>
      <input
        ref="fileInput"
        type="file"
        class="exp-file-input"
        accept=".pdf,.docx,.xlsx,.xls,.xlsm,.txt,.md,.csv,.mp3,.wav,.m4a,.flac,.aac,.ogg,.opus,.wma,.amr,audio/*,.mp4,.m4v,.mov,.mkv,.avi,.mpg,.mpeg,.wmv,.flv,video/*,.png,.jpg,.jpeg,.gif,.webp,.bmp,image/*"
        @change="onUploadPick"
      />
    </div>

    <!-- 归属工作空间筛选条 -->
    <div v-if="usedWorkspaces.length > 1" class="exp-ws-filter">
      <button :class="['exp-chip', { on: filterWs === null }]" @click="filterWs = null">全部</button>
      <button
        v-for="w in usedWorkspaces" :key="w.id"
        :class="['exp-chip', { on: filterWs === w.id }]"
        @click="filterWs = w.id"
      >{{ w.name }}</button>
    </div>

    <!-- 统一经验文件列表 -->
    <div class="exp-body">
      <div class="exp-list">
        <div v-if="loading" class="exp-state">加载中…</div>
        <div v-else-if="visibleExperiences.length === 0" class="exp-empty">
          <div class="exp-empty-icon">📚</div>
          <p>{{ filterWs ? '该工作空间还没有经验文件' : '公共经验库还没有经验文件' }}</p>
          <Button size="sm" @click="newDraft">新建第一条经验</Button>
        </div>
        <template v-else>
          <template v-for="x in visibleExperiences" :key="x.id">
          <button
            :class="['exp-row', { active: activeId === x.id }]"
            @click="selectExperience(x)"
          >
            <span :class="['exp-row-ico', originMeta(x).cls]">{{ originMeta(x).icon }}</span>
            <div class="exp-row-main">
              <div class="exp-row-top">
                <span class="exp-row-name">{{ rowName(x) }}</span>
                <span class="exp-row-time">{{ fmtTime(x.updatedAt || x.createdAt) }}</span>
              </div>
              <div class="exp-row-meta">
                <span class="exp-ws-badge" :title="`归属工作空间：${wsName(x.workspaceId)}`">◆ {{ wsName(x.workspaceId) }}</span>
                <span :class="['exp-origin', originMeta(x).cls]">{{ originMeta(x).icon }} 来源：{{ originMeta(x).label }}</span>
                <!-- 探索产物溯源：回链到它来自哪个 web 系统源 -->
                <span
                  v-if="x.origin === 'explore' && x.sourceExperienceId"
                  class="exp-source exp-source-explore"
                  :title="sourceTitleOf(x) ? `探索自 web 系统「${sourceTitleOf(x)}」` : '探索自某个 web 系统（源未在当前页加载）'"
                >🧭 来自：{{ sourceTitleOf(x) || 'Web 系统' }}</span>
                <span
                  v-if="sourceExtra(x)"
                  class="exp-source"
                  :class="{ gone: ddlSourceMissing(x) }"
                  :title="x.origin === 'ddl'
                    ? (ddlSourceMissing(x) ? '来源数据源已删除，名称取自抽取时的快照' : `抽取自数据库「${sourceExtra(x)}」`)
                    : `来源：${sourceExtra(x)}`"
                >{{ x.origin === 'ddl' ? '🗄 ' : '' }}{{ sourceExtra(x) }}<span v-if="ddlSourceMissing(x)" class="exp-source-gone">（源已删除）</span></span>
                <span v-if="fmtSize(x.fileSize)" class="exp-size">{{ fmtSize(x.fileSize) }}</span>
                <span :class="['exp-idx', idxMeta(x.indexStatus).cls]" :title="`向量索引：${idxMeta(x.indexStatus).label}`">
                  {{ idxMeta(x.indexStatus).label }}
                </span>
                <span v-for="t in tagList(x.tags)" :key="t" class="exp-tag">{{ t }}</span>
              </div>
              <div v-if="exploringId === x.id && lastStepLabel" class="exp-row-step">
                <span class="exp-row-spin" /> {{ lastStepLabel }}
              </div>
            </div>
            <template v-if="x.origin === 'websystem'">
              <button
                class="exp-row-btn"
                :title="expandedSource === x.id ? '收起探索产物' : '查看此系统历次探索生成的业务文档'"
                @click.stop="toggleSourceProducts(x.id)"
              >{{ expandedSource === x.id ? '收起 ▾' : '探索产物 ▸' }}</button>
              <button class="exp-row-btn" title="编辑接入信息" :disabled="exploringId === x.id" @click.stop="editWebSystem(x)">编辑</button>
              <button
                v-if="exploringId === x.id"
                class="exp-row-stop"
                title="停止本次探索"
                @click.stop="stopExplore"
              >■ 停止</button>
              <button
                v-else
                class="exp-row-explore"
                :disabled="exploreRunning"
                :title="exploreRunning ? '已有探索在进行中' : '按保存的连接配置直接开始自动探索，生成业务说明文档'"
                @click.stop="startSavedExplore(x.id)"
              >🧭 探索</button>
            </template>
            <span class="exp-row-del" title="删除" @click.stop="remove(x)">×</span>
          </button>
          <!-- 同源聚合：web 系统源展开后内嵌其历次探索产物 -->
          <template v-if="x.origin === 'websystem' && expandedSource === x.id">
            <div v-if="loadingProducts === x.id" class="exp-sub-hint">加载探索产物…</div>
            <div v-else-if="(sourceProducts[x.id] || []).length === 0" class="exp-sub-hint">该系统还没有探索产物，点右侧「探索」生成一篇</div>
            <button
              v-for="c in (sourceProducts[x.id] || [])"
              :key="c.id"
              :class="['exp-row', 'exp-row-child', { active: activeId === c.id }]"
              @click="selectExperience(c)"
            >
              <span class="exp-row-ico explore">🧭</span>
              <div class="exp-row-main">
                <div class="exp-row-top">
                  <span class="exp-row-name">{{ rowName(c) }}</span>
                  <span class="exp-row-time">{{ fmtTime(c.updatedAt || c.createdAt) }}</span>
                </div>
                <div class="exp-row-meta">
                  <span :class="['exp-idx', idxMeta(c.indexStatus).cls]" :title="`向量索引：${idxMeta(c.indexStatus).label}`">
                    {{ idxMeta(c.indexStatus).label }}
                  </span>
                  <span v-for="t in tagList(c.tags)" :key="t" class="exp-tag">{{ t }}</span>
                </div>
              </div>
              <span class="exp-row-del" title="删除" @click.stop="remove(c)">×</span>
            </button>
          </template>
          </template>
        </template>
        <div v-if="hasMore && !loading" class="exp-more">
          <Button variant="secondary" size="sm" :disabled="loadingMore" @click="loadMore">
            {{ loadingMore ? '加载中…' : `加载更多（已 ${items.length} / ${total}）` }}
          </Button>
        </div>
      </div>

      <!-- 右侧预览（上传文件） -->
      <div class="exp-editor exp-preview" v-if="selectedUpload">
        <div class="exp-editor-head">
          <span class="exp-prev-title">{{ selectedUpload.fileName || selectedUpload.title }}</span>
          <a class="exp-download" :href="fileUrl(selectedUpload, true)" target="_blank" rel="noopener">⤓ 下载原件</a>
          <Button variant="ghost" size="icon-sm" title="关闭" @click="selectedUpload = null">×</Button>
        </div>

        <div v-if="!selectedUpload.hasFile" class="exp-prev-note">
          原件未归档（可能上传时对象存储不可用），下面展示抽取的文本：
        </div>

        <div class="exp-prev-body">
          <iframe v-if="selectedUpload.hasFile && selKind === 'pdf'"
                  class="exp-iframe" :src="fileUrl(selectedUpload)"></iframe>
          <!-- 图片：原图 + 视觉识别文字（OCR + 关键信息，存为经验正文） -->
          <div v-else-if="selectedUpload.hasFile && selKind === 'image'" class="exp-img-wrap">
            <img class="exp-img" :src="fileUrl(selectedUpload)" :alt="selectedUpload.fileName" />
            <div class="exp-prev-note">识别文字（图片视觉识别）：</div>
            <div v-if="(selectedUpload.content || '').trim()" class="exp-md">
              <pre class="exp-pre">{{ selectedUpload.content }}</pre>
            </div>
            <div v-else class="exp-prev-note">（暂无识别文字）</div>
          </div>
          <!-- 音频：播放器 + 对应的转写文字（ASR 自动转写，存为经验正文） -->
          <div v-else-if="selectedUpload.hasFile && selKind === 'audio'" class="exp-audio-wrap">
            <audio class="exp-audio" controls :src="fileUrl(selectedUpload)"></audio>
            <div class="exp-prev-note">转写文字（自动语音识别）：</div>
            <div v-if="(selectedUpload.content || '').trim()" class="exp-md">
              <pre class="exp-pre">{{ selectedUpload.content }}</pre>
            </div>
            <div v-else class="exp-prev-note">（暂无转写文字）</div>
          </div>

          <!-- 视频：播放器 + 音轨转写文字（抽音轨 → ASR，存为经验正文） -->
          <div v-else-if="selectedUpload.hasFile && selKind === 'video'" class="exp-audio-wrap">
            <video class="exp-video" controls :src="fileUrl(selectedUpload)"></video>
            <div class="exp-prev-note">音轨转写文字（自动语音识别）：</div>
            <div v-if="(selectedUpload.content || '').trim()" class="exp-md">
              <pre class="exp-pre">{{ selectedUpload.content }}</pre>
            </div>
            <div v-else class="exp-prev-note">（暂无转写文字）</div>
          </div>

          <!-- markdown / 文本 / 其它格式：渲染抽取的文本（其它格式浏览器无法直接预览） -->
          <template v-if="!selectedUpload.hasFile || selKind === 'markdown' || selKind === 'text' || selKind === 'other'">
            <div v-if="selKind === 'other' && selectedUpload.hasFile" class="exp-prev-note">
              该格式不支持浏览器内预览，下面是抽取的文本（可点上方「下载原件」查看原文）：
            </div>
            <div v-if="selKind === 'text'" class="exp-md"><pre class="exp-pre">{{ selectedUpload.content }}</pre></div>
            <div v-else class="exp-md" v-html="renderedUpload || '<p class=&quot;exp-md-empty&quot;>（无可显示文本）</p>'"></div>
          </template>
        </div>

        <div class="exp-prev-foot">
          <span :class="['exp-idx', idxMeta(selectedUpload.indexStatus).cls]">{{ idxMeta(selectedUpload.indexStatus).label }}</span>
          <Button variant="secondary" size="sm" :disabled="reindexing" @click="reindex(selectedUpload.id)">
            {{ reindexing ? '索引中…' : '重新索引' }}
          </Button>
        </div>
      </div>
    </div>

    <!-- 新建 / 编辑经验：整页填写（覆盖列表，不再挤在右侧） -->
    <div v-if="draft" class="exp-fullpage">
      <div class="exp-fullpage-bar">
        <Button variant="ghost" size="sm" title="返回列表" @click="cancelEdit">← 返回</Button>
        <span class="exp-fullpage-title">{{ draft.id ? '编辑经验' : '新建经验' }}</span>
        <div class="exp-editor-tabs">
          <button :class="{ active: editorMode === 'edit' }" @click="editorMode = 'edit'">编辑</button>
          <button :class="{ active: editorMode === 'split' }" @click="editorMode = 'split'">实时</button>
          <button :class="{ active: editorMode === 'preview' }" @click="editorMode = 'preview'">预览</button>
        </div>
        <span class="exp-actions-spacer" />
        <Button
          v-if="draft.id"
          variant="secondary"
          size="sm"
         
          :disabled="reindexing"
          title="重新生成向量索引"
          @click="reindex(draft.id)"
        >{{ reindexing ? '索引中…' : '重新索引' }}</Button>
        <Button variant="secondary" size="sm" @click="cancelEdit">取消</Button>
        <Button size="sm" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</Button>
      </div>
      <div class="exp-fullpage-body">
        <label class="exp-field">
          <span class="exp-label">标题</span>
          <BaseInput v-model="draft.title" placeholder="给这条经验起个标题" />
        </label>
        <label class="exp-field">
          <span class="exp-label">标签<span class="exp-hint">（逗号分隔，可空）</span></span>
          <BaseInput v-model="draft.tags" placeholder="如：供应链, 风控, 复盘" />
        </label>
        <div class="exp-field exp-field-grow">
          <span class="exp-label">
            正文<span class="exp-hint">（支持 Markdown · 实时预览）</span>
            <Button v-if="editorMode !== 'preview'" variant="ghost" size="sm" title="插入 Markdown 模板" @click="insertTemplate">插入模板</Button>
            <Button v-if="editorMode !== 'preview'" variant="ghost" size="sm" title="插入业务访谈提纲（按 对象→流程→规则→数据流向 四段引导访谈，转写/笔记按此沉淀建图质量更高）" @click="insertInterviewTemplate">访谈提纲</Button>
          </span>
          <div class="exp-edit-area" :class="editorMode">
            <textarea
              v-show="editorMode !== 'preview'"
              v-model="draft.content"
              class="exp-textarea"
              placeholder="粘贴或撰写经验文档内容（支持 Markdown，右侧实时预览）"
            ></textarea>
            <div
              v-show="editorMode !== 'edit'"
              class="exp-md exp-md-live"
              v-html="renderedDraft || '<p class=&quot;exp-md-empty&quot;>（暂无内容）</p>'"
            ></div>
          </div>
        </div>
        <p class="exp-rag-hint">保存后会自动建立向量索引，对话建模时按相关度自动召回为参考资料。</p>
      </div>
    </div>

    <ExpOntologyExtractDialog
      :open="extractDialogOpen"
      :workspace-name="currentWsName"
      :has-current-model="!!hasCurrentModel"
      :current-model-id="currentModelId"
      :experiences="extractScopeItems"
      @close="extractDialogOpen = false"
      @commit="onExtractCommit"
      @built-model="(p) => emit('built-model', p)"
    />

    <!-- 接入 Web 系统：保存连接 → 探索生成业务文档 -->
    <Teleport to="body">
    <div v-if="researchOpen" class="exp-modal-mask" @click.self="closeResearch">
      <div class="exp-modal" :style="{ '--c': 'var(--accent)' }">
        <div class="exp-modal-head">
          <span>🌐 联网调研业务知识</span>
          <button class="exp-modal-x" @click="closeResearch">×</button>
        </div>
        <div class="exp-modal-body">
          <p class="exp-research-desc">
            搜索该主题的公开资料（行业流程惯例、监管要求、通用术语），归纳成《业务知识文档》沉淀为经验，
            参与后续建图。<b>公开资料非本企业内部事实</b>，建图后请用访谈 / 系统探索 / 库表结构校对。
          </p>
          <label class="exp-modal-row">
            <span>调研主题</span>
            <input v-model="researchTopic" :disabled="researchRunning" placeholder="业务域 + 流程/规则，如：汽车金融 贷后管理 业务流程" @keydown.enter="startResearch" />
          </label>
          <div v-if="researchSteps.length" class="exp-research-steps">
            <div v-for="(st, i) in researchSteps" :key="i" class="exp-research-step" :class="st.status">
              <span class="exp-research-dot">{{ st.status === 'done' ? '✓' : st.status === 'error' ? '✗' : '●' }}</span>
              <span>{{ st.label }}</span>
            </div>
          </div>
          <div v-if="researchError" class="exp-research-error">{{ researchError }}</div>
          <div class="exp-modal-actions">
            <Button size="sm" :disabled="!researchTopic.trim() || researchRunning" @click="startResearch">
              {{ researchRunning ? '调研中…' : '开始调研' }}
            </Button>
            <Button variant="secondary" size="sm" @click="closeResearch">{{ researchRunning ? '中止' : '关闭' }}</Button>
          </div>
        </div>
      </div>
    </div>

    <div v-if="exploreOpen" class="exp-modal-mask" @click.self="closeExplore">
      <div class="exp-modal" :style="{ '--c': 'var(--accent-3)' }">
        <span class="exp-modal-corner tl" /><span class="exp-modal-corner tr" />
        <span class="exp-modal-corner bl" /><span class="exp-modal-corner br" />
        <div class="exp-modal-head">
          <div class="exp-modal-head-text">
            <div class="exp-modal-kicker">{{ isEditingWs ? 'WEB SYSTEM · EDIT' : 'WEB SYSTEM · CONNECT' }}</div>
            <span class="exp-modal-title">🌐 {{ isEditingWs ? '编辑接入的 Web 系统' : '接入 Web 系统' }}</span>
          </div>
          <Button variant="ghost" size="icon-sm" @click="closeExplore">×</Button>
        </div>
        <div class="exp-modal-body">
          <div class="exp-modal-note">
            <span class="exp-modal-note-ic">🤖</span>
            <p class="exp-modal-desc">
              填好系统入口与登录信息后<strong>保存接入</strong>,即可在列表里随时点<strong>「探索」</strong>:智能体会用无头浏览器
              像人一样<strong>只读</strong>地操作系统、摸清功能,反推业务自动归纳成一份《业务说明文档》存入经验库(附探索明细)。<strong>建议指向测试/预发环境。</strong>
            </p>
          </div>
          <label class="exp-field">
            <span>系统名称<span class="exp-modal-hint-inline">（可空，默认取入口地址）</span></span>
            <BaseInput v-model="exploreTitle" placeholder="如：订单中台（预发）" :disabled="exploreRunning" />
          </label>
          <label class="exp-field">
            <span>系统入口地址</span>
            <BaseInput v-model="exploreUrl" placeholder="https://your-system.example.com" :disabled="exploreRunning" />
          </label>
          <div class="exp-field-row">
            <label class="exp-field exp-field-half">
              <span>登录用户名</span>
              <BaseInput v-model="exploreUsername" placeholder="登录系统的账号（可空）" :disabled="exploreRunning" />
            </label>
            <label class="exp-field exp-field-half">
              <span>登录密码</span>
              <BaseInput v-model="explorePassword" type="password"
                     :placeholder="isEditingWs ? '已保存，留空表示不修改' : '登录系统的密码（可空）'" :disabled="exploreRunning" />
            </label>
          </div>
          <p class="exp-modal-hint">填写后智能体会先用该账号密码自动登录系统，再开始探索；留空则以未登录状态探索。</p>
          <div class="exp-field-row">
            <label class="exp-field">
              <span>最多探索页面数</span>
              <BaseInput v-model="exploreMaxSteps" type="number" numeric :min="3" :max="120" :disabled="exploreRunning" />
            </label>
            <BaseCheckbox v-model="exploreReadOnly" label="只读模式(拦截删除/提交/支付等写操作)" :disabled="exploreRunning" />
          </div>
          <details class="exp-adv">
            <summary>高级:预登录 storageState(可选)<span v-if="exploreHasStorageState" class="exp-ss-set">· 已配置</span></summary>
            <p class="exp-modal-hint">若系统需要登录,可粘贴浏览器导出的 storageState(cookies/localStorage)JSON,智能体将带着登录态探索。{{ isEditingWs ? '留空则沿用已保存的值。' : '' }}</p>
            <BaseTextarea v-model="exploreStorageState" :rows="3" placeholder='{"cookies":[...],"origins":[...]}' :disabled="exploreRunning" />
          </details>

          <div v-if="exploreSteps.length" class="exp-steps">
            <div v-for="(s, i) in exploreSteps" :key="i" :class="['exp-step', 'k-' + s.key]">{{ s.label }}</div>
          </div>
        </div>
        <div class="exp-modal-foot">
          <Button variant="secondary" size="sm" @click="closeExplore">{{ exploreRunning ? '在后台继续' : '关闭' }}</Button>
          <Button variant="secondary" size="sm" :disabled="exploreRunning || wsSaving || !exploreUrl.trim()" @click="onSaveWebSystem">
            {{ wsSaving ? '保存中…' : '保存接入' }}
          </Button>
          <Button size="sm" :disabled="exploreRunning || wsSaving || !exploreUrl.trim()" @click="onSaveAndExplore">
            {{ exploreRunning ? '探索中…' : '保存并探索' }}
          </Button>
        </div>
      </div>
    </div>
    </Teleport>
  </div>
</template>

<style scoped>
.exp-view { flex: 1; display: flex; flex-direction: column; overflow: hidden; padding: 28px 32px; position: relative; }

/* 新建 / 编辑经验：整页填写，覆盖整个经验库视图（含表头） */
.exp-fullpage {
  position: absolute; inset: 0; z-index: 25;
  background: var(--bg-base);
  display: flex; flex-direction: column;
  padding: 18px 28px 22px;
}
.exp-fullpage-bar {
  display: flex; align-items: center; gap: 12px; flex-shrink: 0;
  padding-bottom: 14px; margin-bottom: 16px;
  border-bottom: 1px solid var(--hairline);
}
.exp-fullpage-back {
  background: transparent; border: 1px solid var(--glass-border);
  color: var(--text-dim); padding: 6px 12px; border-radius: 8px;
  font-size: 13px; cursor: pointer; font-family: inherit;
}
.exp-fullpage-back:hover { color: var(--text-main); border-color: var(--glass-border-strong); }
.exp-fullpage-title { font-size: 16px; font-weight: 600; color: var(--text-main); }
.exp-fullpage-body {
  flex: 1; min-height: 0; width: 100%; max-width: 1080px; margin: 0 auto;
  display: flex; flex-direction: column; gap: 16px;
}
.exp-header {
  display: flex; align-items: flex-start; justify-content: space-between;
  gap: 16px; margin-bottom: 14px; flex-shrink: 0;
}
.exp-header h2 {
  margin: 0 0 6px; font-size: 21px; font-weight: 700; letter-spacing: 0.3px;
  color: var(--text-main);
}
.exp-header p { margin: 0; font-size: 13px; color: var(--text-dim); }
.exp-public-tag {
  font-size: 11px; font-weight: 600; vertical-align: middle; margin-left: 8px;
  padding: 2px 9px; border-radius: 100px; -webkit-text-fill-color: initial;
  color: #2563eb; background: rgba(37,99,235,0.08); border: 1px solid rgba(37,99,235,0.3);
}

/* 归属工作空间筛选条 */
.exp-ws-filter { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; flex-shrink: 0; }
.exp-chip {
  background: var(--bg-elev); border: 1px solid var(--glass-border);
  border-radius: 100px; padding: 4px 12px; color: var(--text-dim); cursor: pointer; font-size: 12px;
  font-family: inherit; transition: background .12s, color .12s, border-color .12s;
}
.exp-chip:hover { background: var(--bg-elev-hi); }
.exp-chip.on { background: rgba(37,99,235,0.10); border-color: rgba(37,99,235,0.4); color: #2563eb; }

/* 行内归属工作空间标签 */
.exp-ws-badge {
  font-size: 10px; padding: 1px 8px; border-radius: 100px;
  color: #2563eb; background: rgba(37,99,235,0.08); border: 1px solid rgba(37,99,235,0.25);
  white-space: nowrap; max-width: 160px; overflow: hidden; text-overflow: ellipsis;
}
/* 来源详情：DDL 抽取显示来自哪个数据库，web 系统显示入口域名等 */
.exp-source {
  font-size: 10px; padding: 1px 8px; border-radius: 100px;
  color: #d97706; background: rgba(217,119,6,0.08); border: 1px solid rgba(217,119,6,0.3);
  white-space: nowrap; max-width: 220px; overflow: hidden; text-overflow: ellipsis;
}
.exp-source.gone { color: var(--text-muted); background: var(--bg-elev); border-color: var(--glass-border); }
.exp-source-gone { opacity: .75; }
/* 探索产物溯源标签：回链到来源 web 系统（用探索的青绿色调，与 DDL 金色区分） */
.exp-source-explore {
  color: #7fe3c0; background: rgba(34,221,136,.10); border-color: rgba(34,221,136,.28); max-width: 260px;
}
/* 同源产物展开的子行：缩进 + 竖向引导线，视觉从属于上方的 web 系统源 */
.exp-row-child {
  margin-left: 26px; border-left: 2px solid rgba(34,221,136,.28);
  background: rgba(255,255,255,.015);
}
.exp-sub-hint {
  margin-left: 26px; padding: 8px 12px; font-size: 12px; color: var(--text-dim);
  border-left: 2px solid rgba(34,221,136,.28);
}
.exp-new {
  flex-shrink: 0; background: var(--accent); color: #fff; border: none;
  padding: 9px 16px; border-radius: 9px; font-size: 13px; font-weight: 600;
  cursor: pointer; font-family: inherit;
  transition: background .16s var(--ease-out);
}
.exp-new:hover { background: var(--accent-soft); }
.exp-header-actions { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }

/* 「新增」下拉 — 浅色扁平面板 */
.exp-add { position: relative; }
.exp-add-caret { font-size: 10px; opacity: 0.8; margin-left: 2px; display: inline-block; transition: transform .22s var(--ease-spring); }
.exp-add-caret.open { transform: rotate(180deg); }
.exp-add-backdrop { position: fixed; inset: 0; z-index: 40; }
.exp-add-menu {
  position: absolute; top: calc(100% + 8px); right: 0; z-index: 50;
  min-width: 272px; padding: 8px;
  background: var(--bg-base);
  border: 1px solid var(--glass-border); border-radius: 14px;
  box-shadow: var(--shadow-lg);
  display: flex; flex-direction: column; gap: 3px;
  transform-origin: top right;
  animation: expAddPop .22s var(--ease-spring) both;
  overflow: hidden;
}
@keyframes expAddPop {
  from { opacity: 0; transform: translateY(-6px) scale(0.96); }
  to   { opacity: 1; transform: translateY(0) scale(1); }
}
.exp-add-kicker {
  font-family: var(--font-mono); font-size: 9.5px; letter-spacing: 1.6px; text-transform: uppercase;
  color: var(--text-muted); padding: 4px 8px 6px;
}
.exp-add-item {
  position: relative; display: flex; align-items: center; gap: 12px; text-align: left;
  background: transparent; border: 1px solid transparent; border-radius: 10px;
  padding: 10px 11px; cursor: pointer; font-family: inherit; color: var(--text-main);
  transition: background .16s var(--ease-out), border-color .16s var(--ease-out);
  --c: var(--accent);
}
.exp-add-item.c-write  { --c: var(--accent); }
.exp-add-item.c-upload { --c: var(--accent-2); }
.exp-add-item.c-web    { --c: var(--accent-3); }
.exp-add-item:hover:not(:disabled) {
  background: color-mix(in srgb, var(--c) 8%, transparent);
  border-color: color-mix(in srgb, var(--c) 30%, transparent);
}
.exp-add-item:disabled { opacity: 0.55; cursor: default; }
.exp-add-ico {
  flex-shrink: 0; width: 34px; height: 34px; border-radius: 9px;
  display: flex; align-items: center; justify-content: center; font-size: 16px;
  color: var(--c);
  background: color-mix(in srgb, var(--c) 8%, #ffffff);
  border: 1px solid color-mix(in srgb, var(--c) 25%, transparent);
  transition: background .18s var(--ease-out);
}
.exp-add-item:hover:not(:disabled) .exp-add-ico {
  background: color-mix(in srgb, var(--c) 14%, #ffffff);
}
.exp-add-item > span:nth-child(2) { display: flex; flex-direction: column; gap: 2px; flex: 1; min-width: 0; }
.exp-add-item strong { font-size: 13px; font-weight: 600; letter-spacing: 0.1px; }
.exp-add-item em { font-style: normal; font-size: 11px; color: var(--text-dim); }
.exp-add-go {
  font-family: var(--font-mono); font-size: 14px; color: var(--c); opacity: 0;
  transform: translateX(-4px); transition: opacity .16s var(--ease-out), transform .16s var(--ease-out);
}
.exp-add-item:hover:not(:disabled) .exp-add-go { opacity: 0.9; transform: translateX(0); }
/* 顶栏三个次级按钮统一走共享 <Button variant=secondary> 的玻璃样式，仅保留不换行；
   顶栏只留「新增」一个蓝色主 CTA，避免多个蓝色按钮互相打架。 */
.exp-build { white-space: nowrap; }
.exp-idx-summary {
  font-size: 12px; color: var(--text-dim);
  padding: 4px 10px; border-radius: 999px;
  background: var(--bg-elev); border: 1px solid var(--hairline);
  white-space: nowrap; font-family: 'JetBrains Mono', monospace;
}
.exp-idx-summary .exp-idx-run { color: #d97706; font-style: normal; }
.exp-fullidx { white-space: nowrap; }
.exp-upload {
  background: transparent; color: var(--text-dim); border: 1px solid var(--glass-border);
  padding: 9px 16px; border-radius: 8px; font-size: 13px; font-weight: 600;
  cursor: pointer; font-family: inherit;
}
.exp-upload:hover { background: var(--bg-elev); color: var(--text-main); }
.exp-upload:disabled { opacity: 0.6; cursor: default; }
.exp-file-input { display: none; }

/* 自动探索按钮 */
.exp-explore {
  background: transparent; color: #7c3aed; border: 1px solid rgba(124,58,237,0.4);
  padding: 9px 16px; border-radius: 8px; font-size: 13px; font-weight: 600;
  cursor: pointer; font-family: inherit;
}
.exp-explore:hover { background: rgba(124,58,237,0.08); }

/* 探索对话框 */
.exp-modal-mask {
  position: fixed; inset: 0; z-index: 1500;
  background: rgba(0,0,0,0.45);
  display: flex; align-items: center; justify-content: center;
  animation: expModalFade .2s var(--ease-out) both;
}
@keyframes expModalFade { from { opacity: 0; } to { opacity: 1; } }
.exp-modal {
  position: relative; width: 580px; max-width: calc(100vw - 40px); max-height: 86vh; overflow: hidden;
  display: flex; flex-direction: column;
  background: var(--bg-base);
  border: 1px solid var(--glass-border); border-radius: 16px;
  box-shadow: var(--shadow-lg);
  animation: expModalPop .26s var(--ease-spring) both;
}
@keyframes expModalPop { from { opacity: 0; transform: translateY(10px) scale(0.97); } to { opacity: 1; transform: none; } }
/* 角标(浅色极简下隐藏) */
.exp-modal-corner { display: none; }
.exp-modal-head {
  display: flex; align-items: flex-start; justify-content: space-between;
  padding: 18px 20px 14px;
}
.exp-modal-head-text { flex: 1; min-width: 0; }
.exp-modal-kicker { font-family: var(--font-mono); font-size: 9.5px; letter-spacing: 2px; color: var(--text-muted); margin-bottom: 5px; }
.exp-modal-title { font-size: 17px; font-weight: 650; letter-spacing: 0.2px; color: var(--text-main); }
.exp-modal-x { background: transparent; border: 1px solid transparent; color: var(--text-muted); width: 28px; height: 28px; border-radius: 8px; font-size: 18px; line-height: 1; cursor: pointer; transition: all .15s var(--ease-out); flex-shrink: 0; }
.exp-modal-x:hover { color: var(--text-main); background: var(--bg-elev); }
.exp-modal-body { padding: 4px 20px 18px; overflow-y: auto; display: flex; flex-direction: column; gap: 13px; }
.exp-modal-note {
  display: flex; gap: 11px; align-items: flex-start;
  background: color-mix(in srgb, var(--c) 5%, #ffffff);
  border: 1px solid color-mix(in srgb, var(--c) 22%, transparent);
  border-left: 2px solid var(--c);
  border-radius: 10px; padding: 11px 13px;
}
.exp-modal-note-ic {
  flex-shrink: 0; width: 30px; height: 30px; border-radius: 8px; font-size: 15px;
  display: flex; align-items: center; justify-content: center;
  background: color-mix(in srgb, var(--c) 10%, #ffffff);
  border: 1px solid color-mix(in srgb, var(--c) 25%, transparent);
}
.exp-modal-desc { margin: 0; font-size: 12.5px; line-height: 1.65; color: var(--text-dim); }
.exp-modal-desc strong { color: color-mix(in srgb, var(--c) 55%, var(--text-main)); font-weight: 600; }
.exp-field { display: flex; flex-direction: column; gap: 6px; font-size: 12px; color: var(--text-dim); }
.exp-field > span { font-weight: 500; letter-spacing: 0.1px; }
.exp-field input, .exp-adv textarea {
  background: #fff; border: 1px solid var(--glass-border); border-radius: 9px;
  padding: 9px 11px; color: var(--text-main); font-size: 13px; font-family: inherit;
  transition: border-color .15s var(--ease-out), box-shadow .15s var(--ease-out);
}
.exp-field input::placeholder, .exp-adv textarea::placeholder { color: var(--text-muted); }
.exp-field input:focus, .exp-adv textarea:focus {
  outline: none;
  border-color: rgba(0,0,0,0.35);
  box-shadow: 0 0 0 3px rgba(0,0,0,0.05);
}
.exp-field-row { display: flex; align-items: flex-end; gap: 16px; flex-wrap: wrap; }
.exp-field-row .exp-field { flex: 0 0 140px; }
.exp-field-row .exp-field-half { flex: 1 1 0; min-width: 0; }
.exp-check {
  display: flex; align-items: center; gap: 8px; font-size: 12.5px; color: var(--text-dim); cursor: pointer;
  background: var(--bg-subtle); border: 1px solid var(--glass-border); border-radius: 9px; padding: 8px 12px;
  transition: border-color .15s var(--ease-out), color .15s var(--ease-out);
}
.exp-check:hover { color: var(--text-main); border-color: var(--glass-border-strong); }
.exp-check input { accent-color: var(--c); width: 14px; height: 14px; }
.exp-adv { font-size: 12.5px; color: var(--text-dim); border-top: 1px solid var(--hairline); padding-top: 12px; }
.exp-adv summary { cursor: pointer; user-select: none; color: var(--text-dim); transition: color .15s; }
.exp-adv summary:hover { color: var(--text-main); }
.exp-adv textarea { width: 100%; margin-top: 8px; resize: vertical; font-family: var(--font-mono); font-size: 12px; }
.exp-modal-hint { margin: 7px 0 0; font-size: 11.5px; color: var(--text-muted); line-height: 1.5; }
.exp-steps {
  margin-top: 2px; max-height: 220px; overflow-y: auto;
  background: var(--bg-subtle); border: 1px solid var(--hairline); border-radius: 10px; padding: 10px 12px;
  display: flex; flex-direction: column; gap: 4px;
}
.exp-step { font-size: 12px; line-height: 1.5; color: var(--text-dim); font-family: var(--font-mono); }
.exp-step.k-think, .exp-step.k-synthesize { color: #7c3aed; }
.exp-step.k-act { color: var(--text-main); }
.exp-step.k-blocked { color: #d97706; }
.exp-step.k-end, .exp-step.k-done { color: #2563eb; font-weight: 600; }
.exp-modal-foot {
  display: flex; justify-content: flex-end; gap: 10px;
  padding: 14px 20px; border-top: 1px solid var(--hairline); background: var(--bg-subtle);
}
.exp-modal-cancel {
  background: var(--bg-base); color: var(--text-dim); border: 1px solid var(--glass-border);
  padding: 9px 16px; border-radius: 9px; font-size: 13px; cursor: pointer; font-family: inherit;
  transition: all .15s var(--ease-out);
}
.exp-modal-cancel:hover { background: var(--bg-elev); color: var(--text-main); border-color: var(--glass-border-strong); }
.exp-modal-go {
  background: #7c3aed; color: #fff; border: none;
  padding: 9px 18px; border-radius: 9px; font-size: 13px; font-weight: 650; cursor: pointer; font-family: inherit;
  transition: background .15s var(--ease-out);
}
.exp-modal-go:hover:not(:disabled) { background: #6d28d9; }
.exp-modal-go:disabled { opacity: 0.45; cursor: not-allowed; }
.exp-modal-save {
  background: rgba(124,58,237,0.08); color: #7c3aed;
  border: 1px solid rgba(124,58,237,0.4);
  padding: 9px 16px; border-radius: 9px; font-size: 13px; font-weight: 600; cursor: pointer; font-family: inherit;
  transition: all .15s var(--ease-out);
}
.exp-modal-save:hover:not(:disabled) { background: rgba(124,58,237,0.16); }
.exp-modal-save:disabled { opacity: 0.45; cursor: not-allowed; }
.exp-modal-hint-inline { color: var(--text-muted); font-weight: 400; font-size: 11px; margin-left: 4px; }
.exp-ss-set { color: var(--accent-soft); margin-left: 6px; }

.exp-body { flex: 1; display: flex; gap: 18px; min-height: 0; }
.exp-list { flex: 1; overflow-y: auto; display: flex; flex-direction: column; gap: 10px; padding-right: 4px; }
.exp-more { display: flex; justify-content: center; padding: 8px 0 16px; }
.exp-state, .exp-empty { color: var(--text-dim); font-size: 13px; padding: 40px 0; text-align: center; }
.exp-empty { display: flex; flex-direction: column; align-items: center; gap: 12px; }
.exp-empty-icon { font-size: 40px; opacity: 0.6; }

.exp-card {
  position: relative; text-align: left; display: flex; flex-direction: column; gap: 8px;
  background: var(--bg-base); border: 1px solid var(--hairline);
  border-radius: 12px; padding: 14px 16px; cursor: pointer; font-family: inherit;
  transition: all 0.12s;
}
.exp-card:hover { background: rgba(37,99,235,0.04); border-color: rgba(37,99,235,0.35); }
.exp-card.active { background: rgba(37,99,235,0.07); border-color: rgba(37,99,235,0.5); }
.exp-card-top { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; }
.exp-card-title {
  font-size: 14px; font-weight: 600; color: var(--text-main);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.exp-card-time { font-size: 11px; color: var(--text-dim); flex-shrink: 0; font-family: 'JetBrains Mono', monospace; }
.exp-card-preview { font-size: 12px; color: var(--text-dim); line-height: 1.5; white-space: pre-wrap; word-break: break-word; }
.exp-card-meta { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.exp-origin { font-size: 10px; padding: 1px 8px; border-radius: 100px; }
.exp-origin.ddl { background: rgba(37,99,235,0.10); color: #2563eb; }
.exp-origin.upload { background: var(--bg-elev); color: var(--text-dim); }
.exp-origin.websystem { background: rgba(2,132,199,0.10); color: #0284c7; }
.exp-origin.explore { background: rgba(124,58,237,0.10); color: #7c3aed; }
.exp-size { font-size: 10px; color: var(--text-dim); font-family: 'JetBrains Mono', monospace; }
.exp-idx { font-size: 10px; padding: 1px 8px; border-radius: 100px; border: 1px solid transparent; }
.exp-idx.ok { background: rgba(37,99,235,0.10); color: #2563eb; }
.exp-idx.pending { background: rgba(217,119,6,0.10); color: #d97706; }
.exp-idx.err { background: rgba(220,38,38,0.08); color: #dc2626; }
.exp-idx.none { background: var(--bg-elev); color: var(--text-muted); }
.exp-card-tags { display: flex; flex-wrap: wrap; gap: 6px; }
.exp-tag { font-size: 10px; padding: 1px 8px; border-radius: 100px; background: rgba(37,99,235,0.08); color: #2563eb; }
.exp-card-del {
  position: absolute; top: 10px; right: 12px; width: 18px; height: 18px;
  display: flex; align-items: center; justify-content: center; border-radius: 50%;
  color: var(--text-muted); font-size: 15px; opacity: 0; transition: all 0.12s;
}
.exp-card:hover .exp-card-del { opacity: 1; }
.exp-card-del:hover { background: rgba(220,38,38,0.08); color: #dc2626; }

/* 统一经验文件列表行 */
.exp-row {
  position: relative; display: flex; align-items: center; gap: 12px; text-align: left;
  background: var(--bg-base);
  border: 1px solid var(--hairline);
  border-radius: 12px; padding: 11px 14px; cursor: pointer; font-family: inherit;
  transition: background .16s var(--ease-out), border-color .16s var(--ease-out), box-shadow .16s var(--ease-out);
  overflow: hidden;
}
.exp-row::before {
  content: ''; position: absolute; left: 0; top: 8px; bottom: 8px; width: 2px;
  border-radius: 2px; background: var(--accent-2);
  opacity: 0; transition: opacity .16s var(--ease-out);
}
.exp-row:hover {
  background: var(--bg-subtle);
  border-color: var(--glass-border-strong);
  box-shadow: var(--shadow-sm);
}
.exp-row:hover::before, .exp-row.active::before { opacity: 1; }
.exp-row.active {
  background: rgba(37,99,235,0.06);
  border-color: rgba(37,99,235,0.4);
}
.exp-row-ico {
  flex-shrink: 0; width: 36px; height: 36px; border-radius: 9px;
  display: flex; align-items: center; justify-content: center; font-size: 17px;
  background: var(--bg-elev);
}
.exp-row-ico.upload { background: var(--bg-elev); }
.exp-row-ico.ddl { background: rgba(37,99,235,0.10); }
.exp-row-ico.websystem { background: rgba(2,132,199,0.10); }
.exp-row-ico.explore { background: rgba(124,58,237,0.10); }
.exp-row-ico.manual { background: rgba(37,99,235,0.10); }
.exp-row-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 5px; }
.exp-row-top { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; }
.exp-row-name {
  font-size: 14px; font-weight: 600; color: var(--text-main);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.exp-row-time { font-size: 11px; color: var(--text-dim); flex-shrink: 0; font-family: 'JetBrains Mono', monospace; }
.exp-row-meta { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.exp-origin.manual { background: rgba(37,99,235,0.10); color: #2563eb; }
.exp-row-del {
  flex-shrink: 0; width: 22px; height: 22px;
  display: flex; align-items: center; justify-content: center; border-radius: 50%;
  color: var(--text-muted); font-size: 16px; opacity: 0; transition: all 0.12s;
}
.exp-row:hover .exp-row-del { opacity: 1; }
.exp-row-del:hover { background: rgba(220,38,38,0.08); color: #dc2626; }
.exp-row-explore {
  flex-shrink: 0; border: 1px solid rgba(2,132,199,0.4); background: rgba(2,132,199,0.08);
  color: #0284c7; font-size: 11px; font-weight: 600; padding: 4px 10px; border-radius: 100px;
  cursor: pointer; font-family: inherit; white-space: nowrap; transition: all 0.12s;
}
.exp-row-explore:hover:not(:disabled) { background: rgba(2,132,199,0.16); }
.exp-row-explore:disabled { opacity: 0.6; cursor: not-allowed; }
.exp-row-stop {
  flex-shrink: 0; border: 1px solid rgba(220,38,38,0.4); background: rgba(220,38,38,0.06);
  color: #dc2626; font-size: 11px; font-weight: 600; padding: 4px 10px; border-radius: 100px;
  cursor: pointer; font-family: inherit; white-space: nowrap; transition: all 0.12s;
}
.exp-row-stop:hover { background: rgba(220,38,38,0.12); }
.exp-row-btn {
  flex-shrink: 0; border: 1px solid var(--glass-border); background: transparent;
  color: var(--text-dim); font-size: 11px; font-weight: 600; padding: 4px 10px; border-radius: 100px;
  cursor: pointer; font-family: inherit; white-space: nowrap; transition: all 0.12s;
}
.exp-row-btn:hover { background: var(--bg-elev); color: var(--text-main); border-color: var(--glass-border-strong); }
.exp-row-step {
  display: flex; align-items: center; gap: 6px; margin-top: 2px;
  font-size: 11.5px; color: #0284c7;
}
.exp-row-spin {
  width: 10px; height: 10px; flex-shrink: 0; border-radius: 50%;
  border: 2px solid rgba(2,132,199,0.25); border-top-color: #0284c7;
  animation: exp-row-spin 0.8s linear infinite;
}
@keyframes exp-row-spin { to { transform: rotate(360deg); } }

.exp-editor {
  flex: 0 0 52%; max-width: 52%; display: flex; flex-direction: column; gap: 12px;
  background: var(--bg-base); border: 1px solid var(--hairline);
  border-radius: 14px; padding: 18px; overflow: hidden;
}
.exp-editor-placeholder {
  align-items: center; justify-content: center; color: var(--text-dim);
  font-size: 13px; text-align: center; gap: 12px;
}
.exp-ph-icon { font-size: 34px; opacity: 0.4; }
.exp-editor-head {
  display: flex; align-items: center; gap: 10px;
  font-size: 14px; font-weight: 600; color: var(--text-main);
}
.exp-editor-head > span:first-child { flex: 1; }
.exp-editor-tabs {
  display: flex; gap: 2px; padding: 3px; border-radius: 9px;
  background: var(--bg-elev); border: 1px solid var(--hairline);
}
.exp-editor-tabs button {
  background: transparent; border: none; color: var(--text-dim);
  font-family: inherit; font-size: 12px; padding: 5px 14px; border-radius: 6px; cursor: pointer;
  transition: background .14s var(--ease-out), color .14s var(--ease-out);
}
.exp-editor-tabs button:hover { color: var(--text-main); }
.exp-editor-tabs button.active {
  background: var(--bg-base);
  color: var(--text-main); font-weight: 600;
  box-shadow: var(--shadow-sm);
}
.exp-prev-title { flex: 1; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.exp-download {
  font-size: 12px; color: #2563eb; text-decoration: none;
  border: 1px solid rgba(37,99,235,0.35); padding: 4px 10px; border-radius: 6px;
}
.exp-download:hover { background: rgba(37,99,235,0.08); }
.exp-x {
  background: transparent; border: none; color: var(--text-muted);
  font-size: 18px; cursor: pointer; width: 24px; height: 24px; border-radius: 6px; flex-shrink: 0;
}
.exp-x:hover { background: var(--bg-elev); color: var(--text-main); }
.exp-field { display: flex; flex-direction: column; gap: 6px; }
.exp-field-grow { flex: 1; min-height: 0; }
.exp-label { font-size: 11px; color: var(--text-dim); display: flex; align-items: center; gap: 8px; letter-spacing: 0.4px; text-transform: uppercase; font-weight: 600; }
.exp-hint { color: var(--text-muted); margin-left: 4px; }
.exp-tpl {
  margin-left: auto; background: transparent; border: 1px solid var(--glass-border);
  color: var(--text-dim); font-family: inherit; font-size: 11px; padding: 2px 9px;
  border-radius: 6px; cursor: pointer;
}
.exp-tpl:hover { color: #2563eb; border-color: rgba(37,99,235,0.4); }
.exp-input, .exp-textarea {
  width: 100%; box-sizing: border-box; background: #fff;
  border: 1px solid var(--glass-border); border-radius: 10px;
  color: var(--text-main); font-family: inherit; font-size: 13.5px; padding: 11px 13px;
  transition: border-color 0.14s var(--ease-out), box-shadow 0.14s var(--ease-out);
}
.exp-input::placeholder, .exp-textarea::placeholder { color: var(--text-muted); }
.exp-input:focus, .exp-textarea:focus {
  outline: none;
  border-color: rgba(0,0,0,0.35); box-shadow: 0 0 0 3px rgba(0,0,0,0.05);
}
/* 实时分屏里左侧是「源」：等宽字体 + 宽松行距，与右侧富文本预览明显区分 */
.exp-textarea {
  flex: 1; min-height: 160px; resize: none;
  font-family: 'JetBrains Mono', 'SF Mono', ui-monospace, monospace;
  font-size: 13px; line-height: 1.85; letter-spacing: 0.1px;
  padding: 15px 17px;
}

/* 编辑/实时分屏/预览 容器：实时模式左右各半，边写边渲染 */
.exp-edit-area { flex: 1; min-height: 0; display: flex; gap: 16px; }
.exp-edit-area .exp-textarea { min-height: 0; height: 100%; }
.exp-edit-area.edit .exp-textarea { flex: 1; }
.exp-edit-area.preview .exp-md-live { flex: 1; }
.exp-edit-area.split .exp-textarea,
.exp-edit-area.split .exp-md-live { flex: 1 1 50%; width: 50%; min-width: 0; }
.exp-md-live {
  overflow: auto;
  background: var(--bg-subtle);
  border: 1px solid var(--hairline); border-radius: 12px;
}
.exp-rag-hint { margin: 0; font-size: 11px; color: var(--text-muted); line-height: 1.4; }
.exp-actions { display: flex; align-items: center; gap: 10px; }
.exp-actions-spacer { flex: 1; }
.exp-reindex {
  background: transparent; border: 1px solid rgba(37,99,235,0.35); color: #2563eb;
  padding: 8px 14px; border-radius: 8px; font-size: 12px; cursor: pointer; font-family: inherit;
}
.exp-reindex:hover { background: rgba(37,99,235,0.08); }
.exp-reindex:disabled { opacity: 0.6; cursor: default; }
.exp-cancel {
  background: transparent; border: 1px solid var(--glass-border); color: var(--text-dim);
  padding: 8px 16px; border-radius: 8px; font-size: 13px; cursor: pointer; font-family: inherit;
}
.exp-cancel:hover { background: var(--bg-elev); color: var(--text-main); }
.exp-save {
  background: var(--accent); color: #fff; border: none; padding: 8px 20px;
  border-radius: 8px; font-size: 13px; font-weight: 600; cursor: pointer; font-family: inherit;
}
.exp-save:hover { background: var(--accent-soft); }
.exp-save:disabled { opacity: 0.6; cursor: default; }

/* 预览区 */
.exp-preview { gap: 10px; }
.exp-prev-note { font-size: 12px; color: #d97706; background: rgba(217,119,6,0.08);
  padding: 8px 12px; border-radius: 8px; }
.exp-prev-body { flex: 1; min-height: 0; overflow: auto; border: 1px solid var(--hairline);
  border-radius: 10px; background: var(--bg-subtle); }
.exp-iframe { width: 100%; height: 100%; min-height: 420px; border: none; background: #fff; }
.exp-img-wrap { display: flex; align-items: center; justify-content: center; padding: 12px; }
.exp-img { max-width: 100%; max-height: 70vh; border-radius: 6px; }
.exp-audio-wrap { display: flex; flex-direction: column; gap: 10px; padding: 12px; }
.exp-audio { width: 100%; margin: 4px 0 8px; }
.exp-pre { margin: 0; padding: 14px; white-space: pre-wrap; word-break: break-word;
  font-family: 'JetBrains Mono', monospace; font-size: 12.5px; line-height: 1.6; color: var(--text-main); }
.exp-prev-foot { display: flex; align-items: center; gap: 10px; }

/* Markdown 渲染 */
.exp-md { padding: 18px 22px; color: var(--text-main); font-size: 14.5px; line-height: 1.85;
  overflow-wrap: anywhere; }
.exp-md :deep(> :first-child) { margin-top: 0; }
.exp-md :deep(h1) {
  font-size: 21px; font-weight: 700; letter-spacing: 0.2px; margin: 20px 0 12px;
  padding-bottom: 8px; border-bottom: 1px solid var(--hairline);
}
.exp-md :deep(h2) {
  font-size: 17px; font-weight: 650; margin: 22px 0 10px; padding-left: 10px;
  border-left: 3px solid var(--accent); color: var(--text-main);
}
.exp-md :deep(h3) { font-size: 15px; font-weight: 650; margin: 16px 0 7px; color: var(--text-main); }
.exp-md :deep(h4) { font-size: 13.5px; font-weight: 650; margin: 14px 0 6px; color: var(--text-dim); }
.exp-md :deep(p) { margin: 10px 0; }
.exp-md :deep(strong) { color: var(--text-main); font-weight: 650; }
.exp-md :deep(ul), .exp-md :deep(ol) { padding-left: 24px; margin: 10px 0; }
.exp-md :deep(li) { margin: 5px 0; }
.exp-md :deep(li::marker) { color: var(--accent); }
.exp-md :deep(code) { background: var(--bg-elev); padding: 1.5px 6px; border-radius: 5px;
  font-family: 'JetBrains Mono', monospace; font-size: 12.5px; color: #059669; }
.exp-md :deep(pre) { background: var(--bg-subtle); border: 1px solid var(--hairline);
  border-radius: 10px; padding: 14px 16px; overflow-x: auto; margin: 12px 0; }
.exp-md :deep(pre code) { background: none; padding: 0; color: var(--text-main); }
.exp-md :deep(blockquote) { border-left: 3px solid rgba(37,99,235,0.4); margin: 12px 0;
  padding: 4px 14px; color: var(--text-dim); background: rgba(37,99,235,0.04);
  border-radius: 0 8px 8px 0; }
.exp-md :deep(a) { color: #2563eb; text-decoration: none; border-bottom: 1px solid rgba(37,99,235,0.35); }
.exp-md :deep(a:hover) { border-bottom-color: #2563eb; }
.exp-md :deep(hr) { border: none; border-top: 1px solid var(--hairline); margin: 18px 0; }
.exp-md :deep(table) { border-collapse: collapse; width: 100%; margin: 12px 0; font-size: 13px; }
.exp-md :deep(th), .exp-md :deep(td) { border: 1px solid var(--hairline); padding: 7px 10px; text-align: left; }
.exp-md :deep(th) { background: var(--bg-subtle); font-weight: 650; color: var(--text-main); }
.exp-md :deep(tbody tr:nth-child(even)) { background: rgba(0,0,0,0.02); }
.exp-md :deep(.exp-md-empty) { color: var(--text-dim); }

/* 联网调研弹窗 */
.exp-research-desc { font-size: 12.5px; color: var(--text-dim); line-height: 1.7; margin: 0 0 12px; }
.exp-research-steps { display: flex; flex-direction: column; gap: 4px;
  background: var(--bg-subtle); border: 1px solid var(--hairline); border-radius: 6px; padding: 10px 12px;
  max-height: 180px; overflow-y: auto; margin-bottom: 10px; }
.exp-research-step { display: flex; align-items: center; gap: 8px; font-size: 12.5px; color: var(--text-dim); }
.exp-research-dot { width: 14px; text-align: center; font-weight: bold; }
.exp-research-step.done .exp-research-dot { color: #059669; }
.exp-research-step.running .exp-research-dot { color: #2563eb; animation: blink 1s infinite; }
.exp-research-step.error .exp-research-dot { color: #dc2626; }
.exp-research-error { color: #dc2626; background: rgba(220,38,38,0.08);
  padding: 8px 12px; border-radius: 6px; font-size: 12.5px; margin-bottom: 10px; }
@keyframes blink { 50% { opacity: .35; } }
.exp-video { width: 100%; max-height: 360px; border-radius: 8px; background: #000; margin-bottom: 10px; }
</style>