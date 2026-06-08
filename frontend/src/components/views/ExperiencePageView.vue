<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { useWorkspaces } from '../../composables/useWorkspaces';
import { useSidebarTree } from '../../composables/useSidebarTree';
import { confirm as uiConfirm } from '../../composables/useConfirm';
import { toast } from '../../composables/useToast';
import { ApiError } from '../../api/http';
import {
  createExperience, updateExperience, reindexExperience, uploadExperienceFile,
  experienceFileUrl, type Experience,
} from '../../api/experiences';
import { runExplore } from '../../api/explore';
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

// 两个 Tab：手写/文本经验（manual + ddl）与上传的文件（upload）
type Tab = 'manual' | 'upload';
const activeTab = ref<Tab>('manual');
const manualList = computed(() => experiences.value.filter(e => e.origin !== 'upload'));
const uploadList = computed(() => experiences.value.filter(e => e.origin === 'upload'));

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

// ── 自动探索系统了解业务 ────────────────────────────────────
const exploreOpen = ref(false);
const exploreUrl = ref('');
const exploreMaxSteps = ref(15);
const exploreReadOnly = ref(true);
const exploreStorageState = ref('');
const exploreRunning = ref(false);
const exploreSteps = ref<{ key: string; label: string }[]>([]);
let exploreHandle: { abort: () => void } | null = null;

const openExplore = () => { exploreOpen.value = true; };
const closeExplore = () => {
  // 关闭对话框不打断后台探索(SSE 仍在跑,完成后会刷新列表)
  exploreOpen.value = false;
};
const startExplore = () => {
  const baseUrl = exploreUrl.value.trim();
  if (!baseUrl) return;
  exploreRunning.value = true;
  exploreSteps.value = [{ key: 'open', label: '正在启动探索…' }];
  exploreHandle = runExplore(
    {
      baseUrl,
      maxSteps: exploreMaxSteps.value,
      readOnly: exploreReadOnly.value,
      storageState: exploreStorageState.value.trim() || undefined,
    },
    {
      onStep: (key, label) => { exploreSteps.value.push({ key, label }); },
      onComplete: async (exp) => {
        toast.success(`探索完成,已生成经验「${exp.title}」`);
        await reload(true);
      },
      onError: (msg) => { toast.warn(msg || '探索失败'); },
      onClose: () => { exploreRunning.value = false; exploreHandle = null; },
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
  activeTab.value = 'manual';
  draft.value = { id: null, title: '', tags: '', content: '' };
  editorPreview.value = false;
};

// ── 上传文件建经验：抽取文本作正文、文件名作标题，并归档原件供预览 ──
const fileInput = ref<HTMLInputElement | null>(null);
const uploading = ref(false);

const triggerUpload = () => fileInput.value?.click();

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
    activeTab.value = 'upload';
    selectedUpload.value = created;   // 上传后直接预览
    toast.success('已从文件创建经验');
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '上传失败');
  } finally {
    uploading.value = false;
  }
};

const editDraft = (x: Experience) => {
  activeTab.value = 'manual';
  draft.value = { id: x.id, title: x.title || '', tags: x.tags || '', content: x.content || '' };
  editorPreview.value = false;
};

const selectUpload = (x: Experience) => {
  activeTab.value = 'upload';
  selectedUpload.value = x;
};

// 侧栏点击定位某条经验 → 按来源进入对应 Tab
watch(() => props.focusId, (id) => {
  if (!id) return;
  const found = experiences.value.find(e => e.id === id);
  if (!found) return;
  if (found.origin === 'upload') selectUpload(found);
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
        <button
          class="exp-explore"
          title="让智能体像人一样自动操作一个 web 系统，摸清功能、反推业务，生成一篇经验"
          @click="openExplore"
        >🧭 自动探索系统了解业务</button>
        <button class="exp-upload" :disabled="uploading" @click="triggerUpload">
          {{ uploading ? '解析中…' : '⤓ 上传文件' }}
        </button>
        <button class="exp-new" @click="newDraft">＋ 新建经验</button>
      </div>
      <input
        ref="fileInput"
        type="file"
        class="exp-file-input"
        accept=".pdf,.docx,.txt,.md,.mp3,.wav,.m4a,.flac,.aac,.ogg,.opus,.wma,.amr,audio/*"
        @change="onUploadPick"
      />
    </div>

    <!-- Tab 切换 -->
    <div class="exp-tabs">
      <button :class="['exp-tab', { active: activeTab === 'manual' }]" @click="activeTab = 'manual'">
        ✎ 手写经验 <span class="exp-tab-count">{{ manualList.length }}</span>
      </button>
      <button :class="['exp-tab', { active: activeTab === 'upload' }]" @click="activeTab = 'upload'">
        ⤓ 上传的文件 <span class="exp-tab-count">{{ uploadList.length }}</span>
      </button>
    </div>

    <!-- 手写经验 Tab -->
    <div v-show="activeTab === 'manual'" class="exp-body">
      <div class="exp-list">
        <div v-if="loading" class="exp-state">加载中…</div>
        <div v-else-if="manualList.length === 0" class="exp-empty">
          <div class="exp-empty-icon">📚</div>
          <p>当前工作空间还没有手写经验</p>
          <button class="exp-new" @click="newDraft">新建第一条经验</button>
        </div>
        <template v-else>
          <button
            v-for="x in manualList"
            :key="x.id"
            :class="['exp-card', { active: draft && draft.id === x.id }]"
            @click="editDraft(x)"
          >
            <div class="exp-card-top">
              <span class="exp-card-title">{{ x.title || '未命名经验' }}</span>
              <span class="exp-card-time">{{ fmtTime(x.updatedAt || x.createdAt) }}</span>
            </div>
            <div class="exp-card-meta">
              <span v-if="x.origin === 'ddl'" class="exp-origin ddl">DDL 供血</span>
              <span v-else-if="x.origin === 'explore'" class="exp-origin explore">🧭 系统探索</span>
              <span :class="['exp-idx', idxMeta(x.indexStatus).cls]" :title="`向量索引：${idxMeta(x.indexStatus).label}`">
                {{ idxMeta(x.indexStatus).label }}
              </span>
            </div>
            <div v-if="preview(x.content)" class="exp-card-preview">{{ preview(x.content) }}</div>
            <div v-if="tagList(x.tags).length" class="exp-card-tags">
              <span v-for="t in tagList(x.tags)" :key="t" class="exp-tag">{{ t }}</span>
            </div>
            <span class="exp-card-del" title="删除" @click.stop="remove(x)">×</span>
          </button>
        </template>
      </div>

      <!-- 右侧编辑器 -->
      <div class="exp-editor" v-if="draft">
        <div class="exp-editor-head">
          <span>{{ draft.id ? '编辑经验' : '新建经验' }}</span>
          <div class="exp-editor-tabs">
            <button :class="{ active: !editorPreview }" @click="editorPreview = false">编辑</button>
            <button :class="{ active: editorPreview }" @click="editorPreview = true">预览</button>
          </div>
          <button class="exp-x" title="关闭" @click="cancelEdit">×</button>
        </div>
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
        <div class="exp-actions">
          <button
            v-if="draft.id"
            class="exp-reindex"
            :disabled="reindexing"
            title="重新生成向量索引"
            @click="reindex(draft.id)"
          >{{ reindexing ? '索引中…' : '重新索引' }}</button>
          <span class="exp-actions-spacer" />
          <button class="exp-cancel" @click="cancelEdit">取消</button>
          <button class="exp-save" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</button>
        </div>
      </div>
      <div class="exp-editor exp-editor-placeholder" v-else>
        <div class="exp-ph-icon">✎</div>
        <p>选择左侧一条经验查看 / 编辑，或新建一条经验。</p>
      </div>
    </div>

    <!-- 上传的文件 Tab -->
    <div v-show="activeTab === 'upload'" class="exp-body">
      <div class="exp-list">
        <div v-if="loading" class="exp-state">加载中…</div>
        <div v-else-if="uploadList.length === 0" class="exp-empty">
          <div class="exp-empty-icon">⤓</div>
          <p>还没有上传的文件</p>
          <button class="exp-upload" :disabled="uploading" @click="triggerUpload">
            {{ uploading ? '解析中…' : '上传第一个文件' }}
          </button>
        </div>
        <template v-else>
          <button
            v-for="x in uploadList"
            :key="x.id"
            :class="['exp-card', { active: selectedUpload && selectedUpload.id === x.id }]"
            @click="selectUpload(x)"
          >
            <div class="exp-card-top">
              <span class="exp-card-title">{{ x.fileName || x.title }}</span>
              <span class="exp-card-time">{{ fmtTime(x.updatedAt || x.createdAt) }}</span>
            </div>
            <div class="exp-card-meta">
              <span class="exp-origin upload">{{ (x.fileMime || '文件').split(';')[0] }}</span>
              <span v-if="fmtSize(x.fileSize)" class="exp-size">{{ fmtSize(x.fileSize) }}</span>
              <span :class="['exp-idx', idxMeta(x.indexStatus).cls]">{{ idxMeta(x.indexStatus).label }}</span>
            </div>
            <span class="exp-card-del" title="删除" @click.stop="remove(x)">×</span>
          </button>
        </template>
      </div>

      <!-- 右侧预览 -->
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
      <div class="exp-editor exp-editor-placeholder" v-else>
        <div class="exp-ph-icon">⤓</div>
        <p>选择左侧一个上传的文件预览原件 / 抽取文本。</p>
      </div>
    </div>

    <ExpOntologyExtractDialog
      :open="extractDialogOpen"
      :workspace-name="currentWsName"
      :has-current-model="!!hasCurrentModel"
      @close="extractDialogOpen = false"
      @commit="onExtractCommit"
    />

    <!-- 自动探索系统了解业务 -->
    <div v-if="exploreOpen" class="exp-modal-mask" @click.self="closeExplore">
      <div class="exp-modal">
        <div class="exp-modal-head">
          <span>🧭 自动探索系统了解业务</span>
          <button class="exp-modal-x" @click="closeExplore">×</button>
        </div>
        <div class="exp-modal-body">
          <p class="exp-modal-desc">
            智能体会用无头浏览器像人一样<strong>只读</strong>地操作目标系统:点菜单、开页面、读表格表单,
            摸清功能后反推业务,自动归纳成一份《业务说明文档》存入经验库(附探索明细)。<strong>建议指向测试/预发环境。</strong>
          </p>
          <label class="exp-field">
            <span>系统入口地址</span>
            <input v-model="exploreUrl" type="text" placeholder="https://your-system.example.com" :disabled="exploreRunning" />
          </label>
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
            <summary>高级:预登录 storageState(可选)</summary>
            <p class="exp-modal-hint">若系统需要登录,可粘贴浏览器导出的 storageState(cookies/localStorage)JSON,智能体将带着登录态探索。</p>
            <textarea v-model="exploreStorageState" rows="3" placeholder='{"cookies":[...],"origins":[...]}' :disabled="exploreRunning"></textarea>
          </details>

          <div v-if="exploreSteps.length" class="exp-steps">
            <div v-for="(s, i) in exploreSteps" :key="i" :class="['exp-step', 'k-' + s.key]">{{ s.label }}</div>
          </div>
        </div>
        <div class="exp-modal-foot">
          <button class="exp-modal-cancel" @click="closeExplore">{{ exploreRunning ? '在后台继续' : '关闭' }}</button>
          <button class="exp-modal-go" :disabled="exploreRunning || !exploreUrl.trim()" @click="startExplore">
            {{ exploreRunning ? '探索中…' : '开始探索' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.exp-view { flex: 1; display: flex; flex-direction: column; overflow: hidden; padding: 28px 32px; }
.exp-header {
  display: flex; align-items: flex-start; justify-content: space-between;
  gap: 16px; margin-bottom: 14px; flex-shrink: 0;
}
.exp-header h2 { margin: 0 0 6px; font-size: 20px; color: var(--text-main); }
.exp-header p { margin: 0; font-size: 13px; color: var(--text-dim); }
.exp-new {
  flex-shrink: 0; background: #42b883; color: #002418; border: none;
  padding: 9px 16px; border-radius: 8px; font-size: 13px; font-weight: 600;
  cursor: pointer; font-family: inherit;
}
.exp-new:hover { background: #50caa3; }
.exp-header-actions { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }
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

/* Tabs */
.exp-tabs { display: flex; gap: 6px; margin-bottom: 14px; flex-shrink: 0;
  border-bottom: 1px solid rgba(255,255,255,0.08); }
.exp-tab {
  background: transparent; border: none; border-bottom: 2px solid transparent;
  color: var(--text-dim); font-family: inherit; font-size: 13.5px; font-weight: 600;
  padding: 8px 14px; cursor: pointer; margin-bottom: -1px;
}
.exp-tab:hover { color: var(--text-main); }
.exp-tab.active { color: #6dd4a7; border-bottom-color: #42b883; }
.exp-tab-count {
  font-size: 11px; background: rgba(255,255,255,0.08); color: var(--text-dim);
  padding: 0 7px; border-radius: 100px; margin-left: 4px;
}
.exp-tab.active .exp-tab-count { background: rgba(66,184,131,0.18); color: #6dd4a7; }

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
