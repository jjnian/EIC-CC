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
