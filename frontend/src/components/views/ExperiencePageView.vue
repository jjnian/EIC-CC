<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { useWorkspaces } from '../../composables/useWorkspaces';
import { useSidebarTree } from '../../composables/useSidebarTree';
import { confirm as uiConfirm } from '../../composables/useConfirm';
import { toast } from '../../composables/useToast';
import { ApiError } from '../../api/http';
import {
  createExperience, updateExperience, reindexExperience, uploadExperienceFile,
  experienceFileUrl, createWebSystem, updateWebSystem, type Experience,
} from '../../api/experiences';
import { runSavedExplore } from '../../api/explore';
import ExpOntologyExtractDialog from '../ExpOntologyExtractDialog.vue';
import { renderMarkdown, MD_TEMPLATE } from '../../utils/markdown';
import type { OntologyNode, OntologyEdge } from '../../types';

const props = defineProps<{
  /** 侧栏点击进入时要定位/编辑的经验 id */
  focusId?: string | null;
  /** 自增信号：变化时打开一个空白「新建经验」表单 */
  createSignal?: number;
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
}>();

const ws = useWorkspaces();
const tree = useSidebarTree();

const loading = computed(() => tree.isLoadingExp(ws.currentId.value));
const experiences = computed<Experience[]>(() => tree.getExperiences(ws.currentId.value));

// 统一列表：手写 / DDL 供血 / 系统探索 / 上传文件 全部按更新时间倒序展示为「经验文件」
const allExperiences = computed<Experience[]>(() =>
  [...experiences.value].sort(
    (a, b) => (b.updatedAt || b.createdAt || 0) - (a.updatedAt || a.createdAt || 0),
  ));

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

// ── 接入 Web 系统：保存连接 → 按钮触发自动探索生成业务文档 ──────────
const exploreOpen = ref(false);
const wsFormId = ref<string | null>(null);     // null=新接入；非空=编辑已保存的 web 系统
const exploreTitle = ref('');
const exploreUrl = ref('');
const exploreUsername = ref('');
const explorePassword = ref('');
const exploreMaxSteps = ref(15);
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
  exploreMaxSteps.value = 15;
  exploreReadOnly.value = true;
  exploreStorageState.value = '';
  exploreHasStorageState.value = false;
  exploreSteps.value = [];
};

// 新接入一个 web 系统（空表单）
const openExplore = () => { closeAddMenu(); resetWsForm(); exploreOpen.value = true; };

// 编辑已保存的 web 系统（回填连接配置，密码/ storageState 不回填，留空保留）
const editWebSystem = (x: Experience) => {
  resetWsForm();
  wsFormId.value = x.id;
  exploreTitle.value = x.title || '';
  const c = x.connection;
  if (c) {
    exploreUrl.value = c.baseUrl || '';
    exploreUsername.value = c.username || '';
    exploreMaxSteps.value = c.maxSteps ?? 15;
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

// 编辑器状态：id 为空 = 新建；非空 = 编辑已有
interface Draft { id: string | null; title: string; tags: string; content: string; }
const draft = ref<Draft | null>(null);
const saving = ref(false);
const editorPreview = ref(false);   // 编辑器内「编辑 / 预览」切换

// 上传文件预览：当前选中的上传经验
const selectedUpload = ref<Experience | null>(null);

const reload = async (force = false) => {
  const id = ws.currentId.value;
  if (!id) return;
  await tree.loadExperiences(id, force);
};

onMounted(() => reload());
watch(() => ws.currentId.value, () => { draft.value = null; selectedUpload.value = null; reload(); });

const newDraft = () => {
  closeAddMenu();
  selectedUpload.value = null;
  draft.value = { id: null, title: '', tags: '', content: '' };
  editorPreview.value = false;
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
    const wsId = ws.currentId.value;
    if (wsId) tree.upsertExperience(wsId, created);
    draft.value = null;
    selectedUpload.value = created;   // 上传后直接预览
    toast.success('已从文件创建经验');
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '上传失败');
  } finally {
    uploading.value = false;
  }
};

const editDraft = (x: Experience) => {
  selectedUpload.value = null;
  draft.value = { id: x.id, title: x.title || '', tags: x.tags || '', content: x.content || '' };
  editorPreview.value = false;
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
    if (wsId) tree.upsertExperience(wsId, saved);
    draft.value = null;
    toast.success(d.id ? '已保存' : '已创建');
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
    await tree.removeExperience(ws.currentId.value, x.id);
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
    case 'upload': return { icon: '📄', label: (x.fileMime || '文件').split(';')[0], cls: 'upload' };
    case 'ddl': return { icon: '🗄️', label: 'DDL 供血', cls: 'ddl' };
    case 'websystem': return { icon: '🌐', label: 'Web 系统', cls: 'websystem' };
    case 'explore': return { icon: '🧭', label: '系统探索', cls: 'explore' };
    default: return { icon: '✎', label: '手写经验', cls: 'manual' };
  }
};
const rowName = (x: Experience) => x.fileName || x.title || '未命名经验';

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
      const wsId = ws.currentId.value;
      const found = experiences.value.find(e => e.id === id);
      if (found && wsId) tree.upsertExperience(wsId, { ...found, indexStatus: 'indexing' });
    }
  } catch (e) {
    toast.warn(e instanceof ApiError ? e.message : '触发失败');
  } finally {
    reindexing.value = false;
  }
};

// ── 上传文件预览 ────────────────────────────────────────────
type PreviewKind = 'pdf' | 'image' | 'audio' | 'markdown' | 'text' | 'other';
const previewKind = (e: Experience): PreviewKind => {
  const mime = (e.fileMime || '').toLowerCase();
  const name = (e.fileName || '').toLowerCase();
  if (mime.includes('pdf') || name.endsWith('.pdf')) return 'pdf';
  if (mime.startsWith('image/') || /\.(png|jpe?g|gif|webp|svg|bmp)$/.test(name)) return 'image';
  if (mime.startsWith('audio/') || /\.(mp3|wav|m4a|flac|aac|ogg|opus|wma|amr)$/.test(name)) return 'audio';
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
        <h2>经验库</h2>
        <p>沉淀可复用的经验文档：手写支持 Markdown，可上传 PDF / Word / TXT / MD（音频自动转写）并预览原件。</p>
      </div>
      <div class="exp-header-actions">
        <button
          class="exp-build"
          :disabled="experiences.length === 0"
          :title="experiences.length === 0 ? '请先在经验库中创建/上传经验' : '聚合整个工作空间经验库构建本体血缘图'"
          @click="extractDialogOpen = true"
        >🧬 构建本体血缘图</button>
        <div class="exp-add">
          <button class="exp-new" @click.stop="toggleAddMenu">＋ 新增 <span class="exp-add-caret">▾</span></button>
          <div v-if="addMenuOpen" class="exp-add-backdrop" @click="closeAddMenu"></div>
          <div v-if="addMenuOpen" class="exp-add-menu">
            <button class="exp-add-item" @click="newDraft">
              <span class="exp-add-ico">✎</span>
              <span><strong>新建经验</strong><em>手写一篇 Markdown 经验</em></span>
            </button>
            <button class="exp-add-item" :disabled="uploading" @click="triggerUpload">
              <span class="exp-add-ico">⤓</span>
              <span><strong>{{ uploading ? '解析中…' : '上传文件' }}</strong><em>PDF / Word / TXT / MD / 音频</em></span>
            </button>
            <button class="exp-add-item" @click="openExplore">
              <span class="exp-add-ico">🌐</span>
              <span><strong>接入 Web 系统</strong><em>保存连接，随时探索反推业务生成文档</em></span>
            </button>
          </div>
        </div>
      </div>
      <input
        ref="fileInput"
        type="file"
        class="exp-file-input"
        accept=".pdf,.docx,.txt,.md,.mp3,.wav,.m4a,.flac,.aac,.ogg,.opus,.wma,.amr,audio/*"
        @change="onUploadPick"
      />
    </div>

    <!-- 统一经验文件列表 -->
    <div class="exp-body">
      <div class="exp-list">
        <div v-if="loading" class="exp-state">加载中…</div>
        <div v-else-if="allExperiences.length === 0" class="exp-empty">
          <div class="exp-empty-icon">📚</div>
          <p>当前工作空间还没有经验文件</p>
          <button class="exp-new" @click="newDraft">新建第一条经验</button>
        </div>
        <template v-else>
          <button
            v-for="x in allExperiences"
            :key="x.id"
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
                <span :class="['exp-origin', originMeta(x).cls]">{{ originMeta(x).label }}</span>
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
              <button class="exp-row-btn" title="编辑接入信息" @click.stop="editWebSystem(x)">编辑</button>
              <button
                class="exp-row-explore"
                :disabled="exploreRunning"
                :title="exploringId === x.id ? '正在探索…' : '按保存的连接配置直接开始自动探索，生成业务说明文档'"
                @click.stop="startSavedExplore(x.id)"
              >{{ exploringId === x.id ? '探索中…' : '🧭 探索' }}</button>
            </template>
            <span class="exp-row-del" title="删除" @click.stop="remove(x)">×</span>
          </button>
        </template>
      </div>

      <!-- 右侧预览（上传文件） -->
      <div class="exp-editor exp-preview" v-if="selectedUpload">
        <div class="exp-editor-head">
          <span class="exp-prev-title">{{ selectedUpload.fileName || selectedUpload.title }}</span>
          <a class="exp-download" :href="fileUrl(selectedUpload, true)" target="_blank" rel="noopener">⤓ 下载原件</a>
          <button class="exp-x" title="关闭" @click="selectedUpload = null">×</button>
        </div>

        <div v-if="!selectedUpload.hasFile" class="exp-prev-note">
          原件未归档（可能上传时对象存储不可用），下面展示抽取的文本：
        </div>

        <div class="exp-prev-body">
          <iframe v-if="selectedUpload.hasFile && selKind === 'pdf'"
                  class="exp-iframe" :src="fileUrl(selectedUpload)"></iframe>
          <div v-else-if="selectedUpload.hasFile && selKind === 'image'" class="exp-img-wrap">
            <img class="exp-img" :src="fileUrl(selectedUpload)" :alt="selectedUpload.fileName" />
          </div>
          <audio v-else-if="selectedUpload.hasFile && selKind === 'audio'"
                 class="exp-audio" controls :src="fileUrl(selectedUpload)"></audio>

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
          <button class="exp-reindex" :disabled="reindexing" @click="reindex(selectedUpload.id)">
            {{ reindexing ? '索引中…' : '重新索引' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 新建 / 编辑经验：整页填写（覆盖列表，不再挤在右侧） -->
    <div v-if="draft" class="exp-fullpage">
      <div class="exp-fullpage-bar">
        <button class="exp-fullpage-back" title="返回列表" @click="cancelEdit">← 返回</button>
        <span class="exp-fullpage-title">{{ draft.id ? '编辑经验' : '新建经验' }}</span>
        <div class="exp-editor-tabs">
          <button :class="{ active: !editorPreview }" @click="editorPreview = false">编辑</button>
          <button :class="{ active: editorPreview }" @click="editorPreview = true">预览</button>
        </div>
        <span class="exp-actions-spacer" />
        <button
          v-if="draft.id"
          class="exp-reindex"
          :disabled="reindexing"
          title="重新生成向量索引"
          @click="reindex(draft.id)"
        >{{ reindexing ? '索引中…' : '重新索引' }}</button>
        <button class="exp-cancel" @click="cancelEdit">取消</button>
        <button class="exp-save" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</button>
      </div>
      <div class="exp-fullpage-body">
        <label class="exp-field">
          <span class="exp-label">标题</span>
          <input v-model="draft.title" class="exp-input" placeholder="给这条经验起个标题" />
        </label>
        <label class="exp-field">
          <span class="exp-label">标签<span class="exp-hint">（逗号分隔，可空）</span></span>
          <input v-model="draft.tags" class="exp-input" placeholder="如：供应链, 风控, 复盘" />
        </label>
        <div class="exp-field exp-field-grow">
          <span class="exp-label">
            正文<span class="exp-hint">（支持 Markdown）</span>
            <button v-if="!editorPreview" class="exp-tpl" title="插入 Markdown 模板" @click="insertTemplate">插入模板</button>
          </span>
          <textarea
            v-show="!editorPreview"
            v-model="draft.content"
            class="exp-textarea"
            placeholder="粘贴或撰写经验文档内容（支持 Markdown）"
          ></textarea>
          <div v-show="editorPreview" class="exp-md" v-html="renderedDraft || '<p class=&quot;exp-md-empty&quot;>（暂无内容）</p>'"></div>
        </div>
        <p class="exp-rag-hint">保存后会自动建立向量索引，对话建模时按相关度自动召回为参考资料。</p>
      </div>
    </div>

    <ExpOntologyExtractDialog
      :open="extractDialogOpen"
      :workspace-name="currentWsName"
      :has-current-model="!!hasCurrentModel"
      @close="extractDialogOpen = false"
      @commit="onExtractCommit"
    />

    <!-- 接入 Web 系统：保存连接 → 探索生成业务文档 -->
    <div v-if="exploreOpen" class="exp-modal-mask" @click.self="closeExplore">
      <div class="exp-modal">
        <div class="exp-modal-head">
          <span>🌐 {{ isEditingWs ? '编辑接入的 Web 系统' : '接入 Web 系统' }}</span>
          <button class="exp-modal-x" @click="closeExplore">×</button>
        </div>
        <div class="exp-modal-body">
          <p class="exp-modal-desc">
            填好系统入口与登录信息后<strong>保存接入</strong>,即可在列表里随时点<strong>「探索」</strong>:智能体会用无头浏览器
            像人一样<strong>只读</strong>地操作系统、摸清功能,反推业务自动归纳成一份《业务说明文档》存入经验库(附探索明细)。<strong>建议指向测试/预发环境。</strong>
          </p>
          <label class="exp-field">
            <span>系统名称<span class="exp-modal-hint-inline">（可空，默认取入口地址）</span></span>
            <input v-model="exploreTitle" type="text" placeholder="如：订单中台（预发）" :disabled="exploreRunning" />
          </label>
          <label class="exp-field">
            <span>系统入口地址</span>
            <input v-model="exploreUrl" type="text" placeholder="https://your-system.example.com" :disabled="exploreRunning" />
          </label>
          <div class="exp-field-row">
            <label class="exp-field exp-field-half">
              <span>登录用户名</span>
              <input v-model="exploreUsername" type="text" autocomplete="off" placeholder="登录系统的账号（可空）" :disabled="exploreRunning" />
            </label>
            <label class="exp-field exp-field-half">
              <span>登录密码</span>
              <input v-model="explorePassword" type="password" autocomplete="new-password"
                     :placeholder="isEditingWs ? '已保存，留空表示不修改' : '登录系统的密码（可空）'" :disabled="exploreRunning" />
            </label>
          </div>
          <p class="exp-modal-hint">填写后智能体会先用该账号密码自动登录系统，再开始探索；留空则以未登录状态探索。</p>
          <div class="exp-field-row">
            <label class="exp-field">
              <span>最多探索步数</span>
              <input v-model.number="exploreMaxSteps" type="number" min="3" max="40" :disabled="exploreRunning" />
            </label>
            <label class="exp-check">
              <input v-model="exploreReadOnly" type="checkbox" :disabled="exploreRunning" />
              <span>只读模式(拦截删除/提交/支付等写操作)</span>
            </label>
          </div>
          <details class="exp-adv">
            <summary>高级:预登录 storageState(可选)<span v-if="exploreHasStorageState" class="exp-ss-set">· 已配置</span></summary>
            <p class="exp-modal-hint">若系统需要登录,可粘贴浏览器导出的 storageState(cookies/localStorage)JSON,智能体将带着登录态探索。{{ isEditingWs ? '留空则沿用已保存的值。' : '' }}</p>
            <textarea v-model="exploreStorageState" rows="3" placeholder='{"cookies":[...],"origins":[...]}' :disabled="exploreRunning"></textarea>
          </details>

          <div v-if="exploreSteps.length" class="exp-steps">
            <div v-for="(s, i) in exploreSteps" :key="i" :class="['exp-step', 'k-' + s.key]">{{ s.label }}</div>
          </div>
        </div>
        <div class="exp-modal-foot">
          <button class="exp-modal-cancel" @click="closeExplore">{{ exploreRunning ? '在后台继续' : '关闭' }}</button>
          <button class="exp-modal-save" :disabled="exploreRunning || wsSaving || !exploreUrl.trim()" @click="onSaveWebSystem">
            {{ wsSaving ? '保存中…' : '保存接入' }}
          </button>
          <button class="exp-modal-go" :disabled="exploreRunning || wsSaving || !exploreUrl.trim()" @click="onSaveAndExplore">
            {{ exploreRunning ? '探索中…' : '保存并探索' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.exp-view { flex: 1; display: flex; flex-direction: column; overflow: hidden; padding: 28px 32px; position: relative; }

/* 新建 / 编辑经验：整页填写，覆盖整个经验库视图（含表头） */
.exp-fullpage {
  position: absolute; inset: 0; z-index: 25;
  background: var(--bg-base);
  display: flex; flex-direction: column;
  padding: 20px 32px 24px;
}
.exp-fullpage-bar {
  display: flex; align-items: center; gap: 12px; flex-shrink: 0;
  padding-bottom: 14px; margin-bottom: 16px;
  border-bottom: 1px solid rgba(255,255,255,0.08);
}
.exp-fullpage-back {
  background: transparent; border: 1px solid rgba(255,255,255,0.15);
  color: var(--text-dim); padding: 6px 12px; border-radius: 8px;
  font-size: 13px; cursor: pointer; font-family: inherit;
}
.exp-fullpage-back:hover { color: var(--text-main); border-color: rgba(255,255,255,0.3); }
.exp-fullpage-title { font-size: 16px; font-weight: 600; color: var(--text-main); }
.exp-fullpage-body {
  flex: 1; min-height: 0; width: 100%; max-width: 980px; margin: 0 auto;
  display: flex; flex-direction: column; gap: 14px;
}
.exp-header {
  display: flex; align-items: flex-start; justify-content: space-between;
  gap: 16px; margin-bottom: 14px; flex-shrink: 0;
}
.exp-header h2 {
  margin: 0 0 6px; font-size: 21px; font-weight: 700; letter-spacing: 0.3px;
  background: linear-gradient(180deg, #ffffff 0%, rgba(56,225,214,0.78) 130%);
  -webkit-background-clip: text; background-clip: text; -webkit-text-fill-color: transparent;
}
.exp-header p { margin: 0; font-size: 13px; color: var(--text-dim); }
.exp-new {
  flex-shrink: 0; background: linear-gradient(135deg, var(--accent-soft), var(--accent)); color: #00251a; border: none;
  padding: 9px 16px; border-radius: 9px; font-size: 13px; font-weight: 600;
  cursor: pointer; font-family: inherit;
  box-shadow: 0 6px 16px rgba(66,184,131,0.28), inset 0 1px 0 rgba(255,255,255,0.3);
  transition: transform .16s var(--ease-out), box-shadow .16s var(--ease-out);
}
.exp-new:hover { transform: translateY(-1px); box-shadow: 0 8px 20px rgba(66,184,131,0.4), inset 0 1px 0 rgba(255,255,255,0.34); }
.exp-header-actions { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }

/* 「新增」下拉 */
.exp-add { position: relative; }
.exp-add-caret { font-size: 10px; opacity: 0.8; margin-left: 2px; }
.exp-add-backdrop { position: fixed; inset: 0; z-index: 40; }
.exp-add-menu {
  position: absolute; top: calc(100% + 6px); right: 0; z-index: 50;
  min-width: 240px; padding: 6px;
  background: linear-gradient(180deg, rgba(24,32,48,0.99), rgba(16,24,38,0.99));
  border: 1px solid rgba(255,255,255,0.12); border-radius: 12px;
  box-shadow: 0 18px 44px rgba(0,0,0,0.5);
  display: flex; flex-direction: column; gap: 2px;
}
.exp-add-item {
  display: flex; align-items: center; gap: 12px; text-align: left;
  background: transparent; border: none; border-radius: 8px;
  padding: 9px 10px; cursor: pointer; font-family: inherit; color: var(--text-main);
}
.exp-add-item:hover:not(:disabled) { background: rgba(66,184,131,0.12); }
.exp-add-item:disabled { opacity: 0.55; cursor: default; }
.exp-add-ico {
  flex-shrink: 0; width: 30px; height: 30px; border-radius: 8px;
  display: flex; align-items: center; justify-content: center; font-size: 16px;
  background: rgba(255,255,255,0.06);
}
.exp-add-item span:last-child { display: flex; flex-direction: column; gap: 2px; }
.exp-add-item strong { font-size: 13px; font-weight: 600; }
.exp-add-item em { font-style: normal; font-size: 11px; color: var(--text-dim); }
.exp-build {
  background: linear-gradient(135deg, rgba(66,184,131,0.22), rgba(66,184,131,0.1));
  color: #6dd4a7; border: 1px solid rgba(66,184,131,0.5);
  padding: 9px 16px; border-radius: 8px; font-size: 13px; font-weight: 600;
  cursor: pointer; font-family: inherit;
}
.exp-build:hover:not(:disabled) {
  background: linear-gradient(135deg, rgba(66,184,131,0.34), rgba(66,184,131,0.18));
  color: #fff;
}
.exp-build:disabled { opacity: 0.45; cursor: not-allowed; }
.exp-upload {
  background: transparent; color: #6dd4a7; border: 1px solid rgba(66,184,131,0.5);
  padding: 9px 16px; border-radius: 8px; font-size: 13px; font-weight: 600;
  cursor: pointer; font-family: inherit;
}
.exp-upload:hover { background: rgba(66,184,131,0.12); }
.exp-upload:disabled { opacity: 0.6; cursor: default; }
.exp-file-input { display: none; }

/* 自动探索按钮 */
.exp-explore {
  background: transparent; color: #c9a7ff; border: 1px solid rgba(167,139,250,0.55);
  padding: 9px 16px; border-radius: 8px; font-size: 13px; font-weight: 600;
  cursor: pointer; font-family: inherit;
}
.exp-explore:hover { background: rgba(167,139,250,0.14); color: #ddc9ff; }

/* 探索对话框 */
.exp-modal-mask {
  position: fixed; inset: 0; z-index: 1500;
  background: rgba(4,8,16,0.6); backdrop-filter: blur(2px);
  display: flex; align-items: center; justify-content: center;
}
.exp-modal {
  width: 560px; max-width: calc(100vw - 40px); max-height: 86vh; overflow: hidden;
  display: flex; flex-direction: column;
  background: linear-gradient(180deg, rgba(20,28,44,0.99), rgba(13,20,34,0.99));
  border: 1px solid rgba(255,255,255,0.1); border-radius: 14px;
  box-shadow: 0 24px 60px rgba(0,0,0,0.5);
}
.exp-modal-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 14px 18px; border-bottom: 1px solid rgba(255,255,255,0.07);
  font-size: 15px; font-weight: 600; color: var(--text-main);
}
.exp-modal-x { background: none; border: none; color: var(--text-dim); font-size: 20px; cursor: pointer; line-height: 1; }
.exp-modal-x:hover { color: #fff; }
.exp-modal-body { padding: 16px 18px; overflow-y: auto; display: flex; flex-direction: column; gap: 12px; }
.exp-modal-desc { margin: 0; font-size: 12.5px; line-height: 1.6; color: var(--text-dim); }
.exp-modal-desc strong { color: #ffcf8a; }
.exp-field { display: flex; flex-direction: column; gap: 5px; font-size: 12.5px; color: var(--text-dim); }
.exp-field input, .exp-adv textarea {
  background: rgba(0,0,0,0.25); border: 1px solid rgba(255,255,255,0.12); border-radius: 8px;
  padding: 8px 10px; color: var(--text-main); font-size: 13px; font-family: inherit;
}
.exp-field input:focus, .exp-adv textarea:focus { outline: none; border-color: rgba(167,139,250,0.6); }
.exp-field-row { display: flex; align-items: flex-end; gap: 16px; flex-wrap: wrap; }
.exp-field-row .exp-field { flex: 0 0 140px; }
.exp-field-row .exp-field-half { flex: 1 1 0; min-width: 0; }
.exp-check { display: flex; align-items: center; gap: 7px; font-size: 12.5px; color: var(--text-dim); cursor: pointer; }
.exp-adv { font-size: 12.5px; color: var(--text-dim); }
.exp-adv summary { cursor: pointer; user-select: none; }
.exp-adv textarea { width: 100%; margin-top: 8px; resize: vertical; }
.exp-modal-hint { margin: 8px 0 0; font-size: 11.5px; color: var(--text-dim); opacity: 0.8; }
.exp-steps {
  margin-top: 4px; max-height: 220px; overflow-y: auto;
  background: rgba(0,0,0,0.22); border: 1px solid rgba(255,255,255,0.07); border-radius: 8px; padding: 8px 10px;
  display: flex; flex-direction: column; gap: 4px;
}
.exp-step { font-size: 12px; line-height: 1.5; color: rgba(255,255,255,0.78); font-family: ui-monospace, monospace; }
.exp-step.k-think, .exp-step.k-synthesize { color: #c9a7ff; }
.exp-step.k-act { color: #6dd4a7; }
.exp-step.k-blocked { color: #ffb27a; }
.exp-step.k-end, .exp-step.k-done { color: #9cc4ff; font-weight: 600; }
.exp-modal-foot {
  display: flex; justify-content: flex-end; gap: 10px;
  padding: 12px 18px; border-top: 1px solid rgba(255,255,255,0.07);
}
.exp-modal-cancel {
  background: transparent; color: var(--text-dim); border: 1px solid rgba(255,255,255,0.15);
  padding: 8px 16px; border-radius: 8px; font-size: 13px; cursor: pointer; font-family: inherit;
}
.exp-modal-go {
  background: #8b5cf6; color: #fff; border: none;
  padding: 8px 18px; border-radius: 8px; font-size: 13px; font-weight: 600; cursor: pointer; font-family: inherit;
}
.exp-modal-go:hover:not(:disabled) { background: #9d72f7; }
.exp-modal-go:disabled { opacity: 0.5; cursor: not-allowed; }
.exp-modal-save {
  background: transparent; color: #c9a7ff; border: 1px solid rgba(167,139,250,0.5);
  padding: 8px 16px; border-radius: 8px; font-size: 13px; font-weight: 600; cursor: pointer; font-family: inherit;
}
.exp-modal-save:hover:not(:disabled) { background: rgba(167,139,250,0.14); color: #fff; }
.exp-modal-save:disabled { opacity: 0.5; cursor: not-allowed; }
.exp-modal-hint-inline { color: var(--text-dim); font-weight: 400; font-size: 11px; margin-left: 4px; }
.exp-ss-set { color: #6dd4a7; margin-left: 6px; }

.exp-body { flex: 1; display: flex; gap: 18px; min-height: 0; }
.exp-list { flex: 1; overflow-y: auto; display: flex; flex-direction: column; gap: 10px; padding-right: 4px; }
.exp-state, .exp-empty { color: var(--text-dim); font-size: 13px; padding: 40px 0; text-align: center; }
.exp-empty { display: flex; flex-direction: column; align-items: center; gap: 12px; }
.exp-empty-icon { font-size: 40px; opacity: 0.6; }

.exp-card {
  position: relative; text-align: left; display: flex; flex-direction: column; gap: 8px;
  background: rgba(255,255,255,0.03); border: 1px solid rgba(255,255,255,0.08);
  border-radius: 12px; padding: 14px 16px; cursor: pointer; font-family: inherit;
  transition: all 0.12s;
}
.exp-card:hover { background: rgba(66,184,131,0.08); border-color: rgba(66,184,131,0.4); }
.exp-card.active { background: rgba(66,184,131,0.12); border-color: rgba(66,184,131,0.55); }
.exp-card-top { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; }
.exp-card-title {
  font-size: 14px; font-weight: 600; color: var(--text-main);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.exp-card-time { font-size: 11px; color: var(--text-dim); flex-shrink: 0; font-family: 'JetBrains Mono', monospace; }
.exp-card-preview { font-size: 12px; color: var(--text-dim); line-height: 1.5; white-space: pre-wrap; word-break: break-word; }
.exp-card-meta { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.exp-origin { font-size: 10px; padding: 1px 8px; border-radius: 100px; }
.exp-origin.ddl { background: rgba(74,141,240,0.15); color: #9cc4ff; }
.exp-origin.upload { background: rgba(255,255,255,0.07); color: var(--text-dim); }
.exp-origin.websystem { background: rgba(125,211,252,0.16); color: #bae6fd; }
.exp-origin.explore { background: rgba(167,139,250,0.16); color: #c9a7ff; }
.exp-size { font-size: 10px; color: var(--text-dim); font-family: 'JetBrains Mono', monospace; }
.exp-idx { font-size: 10px; padding: 1px 8px; border-radius: 100px; border: 1px solid transparent; }
.exp-idx.ok { background: rgba(66,184,131,0.14); color: #6dd4a7; }
.exp-idx.pending { background: rgba(245,191,66,0.14); color: #f0c660; }
.exp-idx.err { background: rgba(255,102,68,0.14); color: #ff8a6f; }
.exp-idx.none { background: rgba(255,255,255,0.06); color: rgba(255,255,255,0.4); }
.exp-card-tags { display: flex; flex-wrap: wrap; gap: 6px; }
.exp-tag { font-size: 10px; padding: 1px 8px; border-radius: 100px; background: rgba(66,184,131,0.14); color: #6dd4a7; }
.exp-card-del {
  position: absolute; top: 10px; right: 12px; width: 18px; height: 18px;
  display: flex; align-items: center; justify-content: center; border-radius: 50%;
  color: rgba(255,255,255,0.3); font-size: 15px; opacity: 0; transition: all 0.12s;
}
.exp-card:hover .exp-card-del { opacity: 1; }
.exp-card-del:hover { background: rgba(255,102,68,0.2); color: #ff8a6f; }

/* 统一经验文件列表行 */
.exp-row {
  position: relative; display: flex; align-items: center; gap: 12px; text-align: left;
  background: linear-gradient(180deg, rgba(255,255,255,0.045), rgba(255,255,255,0.02));
  border: 1px solid rgba(255,255,255,0.08);
  border-radius: 12px; padding: 11px 14px; cursor: pointer; font-family: inherit;
  transition: background .16s var(--ease-out), border-color .16s var(--ease-out), transform .16s var(--ease-out), box-shadow .16s var(--ease-out);
  box-shadow: inset 0 1px 0 rgba(255,255,255,0.03);
  overflow: hidden;
}
.exp-row::before {
  content: ''; position: absolute; left: 0; top: 8px; bottom: 8px; width: 2px;
  border-radius: 2px; background: linear-gradient(180deg, var(--accent-2), var(--accent));
  opacity: 0; transition: opacity .16s var(--ease-out);
}
.exp-row:hover {
  background: linear-gradient(180deg, rgba(56,225,214,0.08), rgba(66,184,131,0.05));
  border-color: rgba(56,225,214,0.35);
  transform: translateY(-1px);
  box-shadow: 0 8px 22px rgba(0,0,0,0.28), 0 0 0 1px rgba(56,225,214,0.1), inset 0 1px 0 rgba(255,255,255,0.05);
}
.exp-row:hover::before, .exp-row.active::before { opacity: 1; }
.exp-row.active {
  background: linear-gradient(180deg, rgba(66,184,131,0.14), rgba(56,225,214,0.06));
  border-color: rgba(66,184,131,0.5);
}
.exp-row-ico {
  flex-shrink: 0; width: 36px; height: 36px; border-radius: 9px;
  display: flex; align-items: center; justify-content: center; font-size: 17px;
  background: rgba(255,255,255,0.05);
  box-shadow: inset 0 1px 0 rgba(255,255,255,0.08), inset 0 0 0 1px rgba(255,255,255,0.04);
}
.exp-row-ico.upload { background: rgba(255,255,255,0.07); }
.exp-row-ico.ddl { background: rgba(74,141,240,0.14); }
.exp-row-ico.websystem { background: rgba(125,211,252,0.16); }
.exp-row-ico.explore { background: rgba(167,139,250,0.16); }
.exp-row-ico.manual { background: rgba(66,184,131,0.14); }
.exp-row-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 5px; }
.exp-row-top { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; }
.exp-row-name {
  font-size: 14px; font-weight: 600; color: var(--text-main);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.exp-row-time { font-size: 11px; color: var(--text-dim); flex-shrink: 0; font-family: 'JetBrains Mono', monospace; }
.exp-row-meta { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.exp-origin.manual { background: rgba(66,184,131,0.14); color: #6dd4a7; }
.exp-row-del {
  flex-shrink: 0; width: 22px; height: 22px;
  display: flex; align-items: center; justify-content: center; border-radius: 50%;
  color: rgba(255,255,255,0.3); font-size: 16px; opacity: 0; transition: all 0.12s;
}
.exp-row:hover .exp-row-del { opacity: 1; }
.exp-row-del:hover { background: rgba(255,102,68,0.2); color: #ff8a6f; }
.exp-row-explore {
  flex-shrink: 0; border: 1px solid rgba(125,211,252,0.4); background: rgba(125,211,252,0.1);
  color: #bae6fd; font-size: 11px; font-weight: 600; padding: 4px 10px; border-radius: 100px;
  cursor: pointer; font-family: inherit; white-space: nowrap; transition: all 0.12s;
}
.exp-row-explore:hover:not(:disabled) { background: rgba(125,211,252,0.2); color: #fff; }
.exp-row-explore:disabled { opacity: 0.6; cursor: not-allowed; }
.exp-row-btn {
  flex-shrink: 0; border: 1px solid rgba(255,255,255,0.16); background: transparent;
  color: var(--text-dim); font-size: 11px; font-weight: 600; padding: 4px 10px; border-radius: 100px;
  cursor: pointer; font-family: inherit; white-space: nowrap; transition: all 0.12s;
}
.exp-row-btn:hover { background: rgba(255,255,255,0.06); color: var(--text-main); border-color: rgba(255,255,255,0.3); }
.exp-row-step {
  display: flex; align-items: center; gap: 6px; margin-top: 2px;
  font-size: 11.5px; color: #bae6fd;
}
.exp-row-spin {
  width: 10px; height: 10px; flex-shrink: 0; border-radius: 50%;
  border: 2px solid rgba(125,211,252,0.35); border-top-color: #7dd3fc;
  animation: exp-row-spin 0.8s linear infinite;
}
@keyframes exp-row-spin { to { transform: rotate(360deg); } }

.exp-editor {
  flex: 0 0 52%; max-width: 52%; display: flex; flex-direction: column; gap: 12px;
  background: rgba(255,255,255,0.02); border: 1px solid rgba(255,255,255,0.08);
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
.exp-editor-tabs { display: flex; gap: 2px; background: rgba(0,0,0,0.25); border-radius: 8px; padding: 2px; }
.exp-editor-tabs button {
  background: transparent; border: none; color: var(--text-dim);
  font-family: inherit; font-size: 12px; padding: 4px 12px; border-radius: 6px; cursor: pointer;
}
.exp-editor-tabs button.active { background: rgba(66,184,131,0.2); color: #6dd4a7; }
.exp-prev-title { flex: 1; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.exp-download {
  font-size: 12px; color: #6dd4a7; text-decoration: none;
  border: 1px solid rgba(66,184,131,0.4); padding: 4px 10px; border-radius: 6px;
}
.exp-download:hover { background: rgba(66,184,131,0.12); }
.exp-x {
  background: transparent; border: none; color: rgba(255,255,255,0.4);
  font-size: 18px; cursor: pointer; width: 24px; height: 24px; border-radius: 6px; flex-shrink: 0;
}
.exp-x:hover { background: rgba(255,255,255,0.08); color: #fff; }
.exp-field { display: flex; flex-direction: column; gap: 6px; }
.exp-field-grow { flex: 1; min-height: 0; }
.exp-label { font-size: 12px; color: var(--text-dim); display: flex; align-items: center; gap: 8px; }
.exp-hint { color: rgba(255,255,255,0.3); margin-left: 4px; }
.exp-tpl {
  margin-left: auto; background: transparent; border: 1px solid rgba(255,255,255,0.14);
  color: var(--text-dim); font-family: inherit; font-size: 11px; padding: 2px 9px;
  border-radius: 6px; cursor: pointer;
}
.exp-tpl:hover { color: #6dd4a7; border-color: rgba(66,184,131,0.4); }
.exp-input, .exp-textarea {
  width: 100%; box-sizing: border-box; background: rgba(0,0,0,0.25);
  border: 1px solid rgba(255,255,255,0.12); border-radius: 8px;
  color: var(--text-main); font-family: inherit; font-size: 13px; padding: 9px 11px;
  transition: border-color 0.12s;
}
.exp-input:focus, .exp-textarea:focus { outline: none; border-color: rgba(66,184,131,0.6); }
.exp-textarea { flex: 1; min-height: 160px; resize: none; line-height: 1.6; }
.exp-rag-hint { margin: 0; font-size: 11px; color: rgba(255,255,255,0.32); line-height: 1.4; }
.exp-actions { display: flex; align-items: center; gap: 10px; }
.exp-actions-spacer { flex: 1; }
.exp-reindex {
  background: transparent; border: 1px solid rgba(66,184,131,0.4); color: #6dd4a7;
  padding: 8px 14px; border-radius: 8px; font-size: 12px; cursor: pointer; font-family: inherit;
}
.exp-reindex:hover { background: rgba(66,184,131,0.1); }
.exp-reindex:disabled { opacity: 0.6; cursor: default; }
.exp-cancel {
  background: transparent; border: 1px solid rgba(255,255,255,0.14); color: var(--text-dim);
  padding: 8px 16px; border-radius: 8px; font-size: 13px; cursor: pointer; font-family: inherit;
}
.exp-cancel:hover { background: rgba(255,255,255,0.05); color: var(--text-main); }
.exp-save {
  background: #42b883; color: #002418; border: none; padding: 8px 20px;
  border-radius: 8px; font-size: 13px; font-weight: 600; cursor: pointer; font-family: inherit;
}
.exp-save:hover { background: #50caa3; }
.exp-save:disabled { opacity: 0.6; cursor: default; }

/* 预览区 */
.exp-preview { gap: 10px; }
.exp-prev-note { font-size: 12px; color: #f0c660; background: rgba(245,191,66,0.08);
  padding: 8px 12px; border-radius: 8px; }
.exp-prev-body { flex: 1; min-height: 0; overflow: auto; border: 1px solid rgba(255,255,255,0.08);
  border-radius: 10px; background: rgba(0,0,0,0.2); }
.exp-iframe { width: 100%; height: 100%; min-height: 420px; border: none; background: #fff; }
.exp-img-wrap { display: flex; align-items: center; justify-content: center; padding: 12px; }
.exp-img { max-width: 100%; max-height: 70vh; border-radius: 6px; }
.exp-audio { width: 100%; margin: 16px 0; }
.exp-pre { margin: 0; padding: 14px; white-space: pre-wrap; word-break: break-word;
  font-family: 'JetBrains Mono', monospace; font-size: 12.5px; line-height: 1.6; color: var(--text-main); }
.exp-prev-foot { display: flex; align-items: center; gap: 10px; }

/* Markdown 渲染 */
.exp-md { padding: 14px 16px; color: var(--text-main); font-size: 13.5px; line-height: 1.7;
  overflow-wrap: anywhere; }
.exp-md :deep(h1) { font-size: 19px; margin: 4px 0 10px; }
.exp-md :deep(h2) { font-size: 16px; margin: 16px 0 8px; }
.exp-md :deep(h3) { font-size: 14px; margin: 14px 0 6px; }
.exp-md :deep(p) { margin: 8px 0; }
.exp-md :deep(ul), .exp-md :deep(ol) { padding-left: 22px; margin: 8px 0; }
.exp-md :deep(li) { margin: 3px 0; }
.exp-md :deep(code) { background: rgba(255,255,255,0.08); padding: 1px 6px; border-radius: 4px;
  font-family: 'JetBrains Mono', monospace; font-size: 12px; }
.exp-md :deep(pre) { background: rgba(0,0,0,0.35); border: 1px solid rgba(255,255,255,0.08);
  border-radius: 8px; padding: 12px 14px; overflow-x: auto; margin: 10px 0; }
.exp-md :deep(pre code) { background: none; padding: 0; }
.exp-md :deep(blockquote) { border-left: 3px solid rgba(66,184,131,0.5); margin: 8px 0;
  padding: 2px 12px; color: var(--text-dim); }
.exp-md :deep(a) { color: #6dd4a7; }
.exp-md :deep(hr) { border: none; border-top: 1px solid rgba(255,255,255,0.12); margin: 14px 0; }
.exp-md :deep(.exp-md-empty) { color: var(--text-dim); }
</style>
