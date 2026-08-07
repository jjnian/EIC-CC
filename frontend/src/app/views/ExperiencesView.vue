<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import {
  Plus, Upload, Globe, Sparkles, FolderOpen, Folder, FileText, Search,
  Trash2, LoaderCircle, Library, Eye, Pencil, BrainCircuit, Waypoints,
  Database, FileUp, PenLine, FilterX,
} from 'lucide-vue-next';
import {
  listExperiences, getExperience, createExperience, updateExperience, deleteExperience,
  uploadExperienceFile, webResearch, buildFullGraph, extractOntologyFromExperiences,
  type Experience,
} from '../../api/experiences';
import { listExperienceFolders, createExperienceFolder, type ExperienceFolder } from '../../api/experienceFolders';
import type { SseHandle } from '../../api/http';
import { useToastStore } from '../stores/toast';
import { timeAgo } from '../lib/format';
import { originMeta } from '../lib/graphStyle';
import { renderMarkdown } from '../lib/markdown';
import UiModal from '../components/UiModal.vue';
import UiDrawer from '../components/UiDrawer.vue';
import UiEmpty from '../components/UiEmpty.vue';
import ProgressSteps, { type StepItem } from '../components/ProgressSteps.vue';

const route = useRoute();
const router = useRouter();
const toast = useToastStore();

const loading = ref(true);
const experiences = ref<Experience[]>([]);
const folders = ref<ExperienceFolder[]>([]);

// 筛选 / 排序
const q = ref('');
const folderF = ref('');
const sourceF = ref('');
const sortBy = ref('time');

const detail = ref<Experience | null>(null);
const detailLoading = ref(false);

// 弹窗状态
const createOpen = ref(false);
const importOpen = ref(false);
const researchOpen = ref(false);
const buildOpen = ref(false);
const editOpen = ref(false);
const extractOpen = ref(false);

// 新建 / 编辑经验
const newExp = ref({ title: '', tags: '', content: '' });
const editExp = ref({ id: '', title: '', tags: '', content: '' });
const saving = ref(false);

// 导入
const importFiles = ref<File[]>([]);
const importing = ref(false);

// 调研 / 建图 / 抽取
const topic = ref('');
const hint = ref('');
const steps = ref<StepItem[]>([]);
const running = ref(false);
const extractSteps = ref<StepItem[]>([]);
const extractRunning = ref(false);
let sseHandle: SseHandle | null = null;

/* 来源筛选四组（对齐原型：DDL 同步 / 文档导入 / 联网调研 / 手工编写） */
const SOURCE_GROUPS: { key: string; label: string; icon: any; color: string; origins: string[] }[] = [
  { key: 'ddl', label: 'DDL 同步', icon: Database, color: '#0EA5E9', origins: ['ddl', 'datasource', 'websystem', 'explore'] },
  { key: 'upload', label: '文档导入', icon: FileUp, color: '#8B5CF6', origins: ['upload'] },
  { key: 'research', label: '联网调研', icon: Globe, color: '#10B981', origins: ['websearch'] },
  { key: 'manual', label: '手工编写', icon: PenLine, color: '', origins: ['manual', 'chat'] },
];

/* 文档卡图标配色（按来源组，内联 rgba 双主题通用） */
function originIcon(e: Experience): { icon: any; bg: string; color: string } {
  const g = SOURCE_GROUPS.find((x) => x.origins.includes(e.origin || ''));
  if (!g || !g.color) return { icon: PenLine, bg: 'var(--nav-hover)', color: 'var(--text3)' };
  return { icon: g.icon, bg: `${g.color}1a`, color: g.color };
}

const filtered = computed(() => {
  const kw = q.value.trim().toLowerCase();
  let arr = experiences.value
    .filter((e) => !folderF.value || e.folderId === folderF.value)
    .filter((e) => {
      if (!sourceF.value) return true;
      const g = SOURCE_GROUPS.find((x) => x.key === sourceF.value);
      return !!g && g.origins.includes(e.origin || '');
    })
    .filter((e) => !kw || e.title.toLowerCase().includes(kw) || (e.tags || '').toLowerCase().includes(kw));
  if (sortBy.value === 'name') arr = [...arr].sort((a, b) => a.title.localeCompare(b.title, 'zh'));
  else arr = [...arr].sort((a, b) => (b.updatedAt || b.createdAt) - (a.updatedAt || a.createdAt));
  return arr;
});

const folderCount = (id: string) => experiences.value.filter((e) => e.folderId === id).length;
const groupCount = (origins: string[]) => experiences.value.filter((e) => origins.includes(e.origin || '')).length;

onMounted(async () => {
  q.value = (route.query.q as string) || '';
  await reload();
  if (route.query.import) importOpen.value = true;
  if (route.query.build) buildOpen.value = true;
  if (route.query.research) researchOpen.value = true;
});

async function reload() {
  loading.value = true;
  try {
    [experiences.value, folders.value] = await Promise.all([listExperiences(), listExperienceFolders()]);
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    loading.value = false;
  }
}

async function openDetail(e: Experience) {
  detailLoading.value = true;
  detail.value = e;
  try {
    detail.value = await getExperience(e.id);
  } catch (err) {
    toast.error((err as Error).message);
  } finally {
    detailLoading.value = false;
  }
}

function openEdit(e: Experience) {
  editExp.value = { id: e.id, title: e.title, tags: e.tags || '', content: e.content || '' };
  editOpen.value = true;
}

async function submitCreate() {
  if (!newExp.value.title.trim()) return;
  saving.value = true;
  try {
    await createExperience({
      title: newExp.value.title.trim(),
      content: newExp.value.content,
      tags: newExp.value.tags || undefined,
    });
    createOpen.value = false;
    newExp.value = { title: '', tags: '', content: '' };
    toast.success('经验已创建');
    reload();
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    saving.value = false;
  }
}

async function submitEdit() {
  if (!editExp.value.title.trim()) return;
  saving.value = true;
  try {
    await updateExperience(editExp.value.id, {
      title: editExp.value.title.trim(),
      content: editExp.value.content,
      tags: editExp.value.tags || undefined,
    });
    editOpen.value = false;
    toast.success('经验已更新');
    reload();
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    saving.value = false;
  }
}

async function remove(e: Experience) {
  if (!window.confirm(`确定删除「${e.title}」？`)) return;
  try {
    await deleteExperience(e.id);
    if (detail.value?.id === e.id) detail.value = null;
    toast.success('已删除');
    reload();
  } catch (err) {
    toast.error((err as Error).message);
  }
}

function onPickFiles(ev: Event) {
  const input = ev.target as HTMLInputElement;
  importFiles.value = Array.from(input.files || []);
}

async function submitImport() {
  if (!importFiles.value.length) return;
  importing.value = true;
  let ok = 0;
  try {
    for (const f of importFiles.value) {
      await uploadExperienceFile(f);
      ok++;
    }
    importOpen.value = false;
    toast.success(`已导入 ${ok} 个文档`);
    importFiles.value = [];
    reload();
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    importing.value = false;
  }
}

function submitResearch() {
  if (!topic.value.trim()) return;
  steps.value = [];
  running.value = true;
  sseHandle = webResearch(
    { topic: topic.value.trim(), maxPages: 6 },
    {
      onStep: (key, label) => steps.value.push({ key, label }),
      onComplete: () => {
        running.value = false;
        researchOpen.value = false;
        topic.value = '';
        toast.success('调研完成，知识文档已入库');
        reload();
      },
      onError: (msg) => { running.value = false; toast.error(msg); },
    },
  );
}

function submitBuild() {
  steps.value = [];
  running.value = true;
  sseHandle = buildFullGraph(
    { hint: hint.value.trim() || undefined },
    {
      onStep: (key, label) => steps.value.push({ key, label }),
      onComplete: (r) => {
        running.value = false;
        buildOpen.value = false;
        toast.success(`建图完成：${r.nodeCount} 节点 · ${r.edgeCount} 边`);
        router.push({ name: 'graph', query: { model: r.modelId } });
      },
      onError: (msg) => { running.value = false; toast.error(msg); },
    },
  );
}

/* 单篇抽取本体（SSE，结果不落库，仅展示统计） */
function startExtract(e: Experience) {
  extractOpen.value = true;
  extractSteps.value = [];
  extractRunning.value = true;
  extractOntologyFromExperiences(
    { experienceIds: [e.id] },
    {
      onStep: (key, label) => extractSteps.value.push({ key, label }),
      onComplete: (r) => {
        extractRunning.value = false;
        toast.success(`「${e.title}」抽取完成：${r.nodes.length} 实体 · ${r.edges.length} 关系（未落库，可用一键建图生成模型）`);
      },
      onError: (msg) => { extractRunning.value = false; toast.error(msg); },
    },
  );
}

function cancelSse() {
  sseHandle?.abort();
  running.value = false;
}

async function addFolder() {
  const name = window.prompt('文件夹名称（即业务领域）');
  if (!name?.trim()) return;
  try {
    await createExperienceFolder({ name: name.trim() });
    folders.value = await listExperienceFolders();
  } catch (e) {
    toast.error((e as Error).message);
  }
}

function summary(e: Experience) {
  return (e.content || '').replace(/\s+/g, ' ').slice(0, 80) || '暂无摘要';
}

function folderName(id?: string) {
  return folders.value.find((f) => f.id === id)?.name || '';
}
</script>

<template>
  <div class="flex h-full flex-col">
    <!-- 顶部工具栏 -->
    <div class="mb-4 flex items-center gap-3">
      <div class="relative w-64">
        <Search class="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2" style="color:var(--text3)" />
        <input v-model="q" class="input !pl-9" placeholder="搜索经验文档…" />
      </div>
      <div class="ml-auto flex items-center gap-2">
        <button class="btn-ghost" @click="importOpen = true"><Upload class="h-3.5 w-3.5" />导入文档</button>
        <button class="btn-ghost" @click="researchOpen = true"><Globe class="h-3.5 w-3.5" />联网调研</button>
        <button class="btn-ghost" @click="createOpen = true"><Plus class="h-3.5 w-3.5" />新建经验</button>
        <button class="btn-primary" @click="buildOpen = true"><Sparkles class="h-3.5 w-3.5" />一键建图</button>
      </div>
    </div>

    <div class="flex min-h-0 flex-1 gap-0">
      <!-- 左侧筛选栏 -->
      <aside class="w-[200px] shrink-0 overflow-y-auto border-r p-3" style="border-color:var(--border)">
        <div class="flex items-center justify-between px-2 pb-2">
          <span class="text-[10.5px] font-medium uppercase tracking-widest" style="color:var(--text3)">领域文件夹</span>
          <button class="rounded p-1 transition hover:brightness-95" style="color:var(--text3)" title="新建文件夹" @click="addFolder">
            <Plus :size="13" />
          </button>
        </div>
        <div class="nav-item" :class="{ active: !folderF }" @click="folderF = ''">
          <FolderOpen class="h-4 w-4" />全部文档
          <span class="ml-auto text-[11px]" style="color:var(--text3)">{{ experiences.length }}</span>
        </div>
        <div
          v-for="f in folders"
          :key="f.id"
          class="nav-item"
          :class="{ active: folderF === f.id }"
          @click="folderF = folderF === f.id ? '' : f.id"
        >
          <Folder class="h-4 w-4" /><span class="truncate">{{ f.name }}</span>
          <span class="ml-auto text-[11px]" style="color:var(--text3)">{{ folderCount(f.id) }}</span>
        </div>

        <div class="mt-4 px-2 pb-2 text-[10.5px] font-medium uppercase tracking-widest" style="color:var(--text3)">来源筛选</div>
        <div
          v-for="g in SOURCE_GROUPS"
          :key="g.key"
          class="nav-item"
          :class="{ active: sourceF === g.key }"
          @click="sourceF = sourceF === g.key ? '' : g.key"
        >
          <component :is="g.icon" class="h-4 w-4" :style="g.color ? { color: g.color } : { color: 'var(--text3)' }" />
          {{ g.label }}
          <span class="ml-auto text-[11px]" style="color:var(--text3)">{{ groupCount(g.origins) }}</span>
        </div>

        <div class="mt-4 border-t pt-3" style="border-color:var(--border)">
          <div class="nav-item" @click="folderF = ''; sourceF = ''"><FilterX class="h-4 w-4" />清除筛选</div>
        </div>
      </aside>

      <!-- 文档网格 -->
      <div class="min-h-0 flex-1 overflow-y-auto p-5">
        <!-- 信息栏 -->
        <div class="mb-4 flex items-center gap-3 text-[12px]" style="color:var(--text2)">
          <span>{{ filtered.length }} 篇文档</span><span>·</span><span>{{ sortBy === 'time' ? '按更新时间排序' : '按名称排序' }}</span>
          <div class="ml-auto flex items-center gap-2">
            <select v-model="sortBy" class="input !h-7 !w-auto !px-2 !text-[11px]" style="min-width:100px">
              <option value="time">按更新时间</option>
              <option value="name">按名称</option>
            </select>
            <span class="flex items-center gap-1.5">
              带 <span class="rounded px-1" style="background:var(--accent-bg);color:var(--accent-text)">已索引</span> 标记的文档可参与建图
            </span>
          </div>
        </div>

        <div v-if="loading" class="flex justify-center py-20" style="color:var(--text3)"><LoaderCircle :size="22" class="animate-spin" /></div>
        <UiEmpty
          v-else-if="!filtered.length"
          :icon="Library"
          title="这里还是空的"
          description="经验库是建图的唯一入口：导入文档、录音转写、库结构导出、系统探索、联网调研，都会沉淀为经验。"
        >
          <button class="btn-primary" @click="importOpen = true"><Upload :size="15" /> 导入第一份文档</button>
        </UiEmpty>

        <div v-else class="grid grid-cols-3 gap-4">
          <div
            v-for="e in filtered"
            :key="e.id"
            class="panel doc-card group relative p-4"
          >
            <!-- 悬浮操作 -->
            <div class="absolute right-3 top-3 flex items-center gap-0.5 opacity-0 transition-opacity group-hover:opacity-100">
              <button class="rounded-md p-1.5 transition hover:brightness-95" style="color:var(--text3)" title="预览" @click.stop="openDetail(e)">
                <Eye class="h-3.5 w-3.5" />
              </button>
              <button class="rounded-md p-1.5 transition hover:brightness-95" style="color:var(--text3)" title="编辑" @click.stop="openEdit(e)">
                <Pencil class="h-3.5 w-3.5" />
              </button>
              <button class="rounded-md p-1.5 transition hover:brightness-95" style="color:var(--text3)" title="抽取本体" @click.stop="startExtract(e)">
                <BrainCircuit class="h-3.5 w-3.5" />
              </button>
              <button class="rounded-md p-1.5 transition hover:text-red-400" style="color:var(--text3)" title="删除" @click.stop="remove(e)">
                <Trash2 class="h-3.5 w-3.5" />
              </button>
            </div>

            <div class="mb-2.5 flex cursor-pointer items-center gap-2" @click="openDetail(e)">
              <div class="flex h-8 w-8 items-center justify-center rounded-lg" :style="{ background: originIcon(e).bg, color: originIcon(e).color }">
                <component :is="originIcon(e).icon" class="h-4 w-4" />
              </div>
              <span
                v-if="e.indexStatus === 'indexed'"
                class="badge"
                style="background:var(--accent-bg);color:var(--accent-text)"
              >已索引</span>
              <span v-else class="badge" style="background:rgba(217,119,6,.1);color:#D97706">未索引</span>
              <span class="ml-auto text-[10.5px]" style="color:var(--text3)">{{ timeAgo(e.updatedAt || e.createdAt) }}</span>
            </div>
            <h3 class="mb-1 cursor-pointer text-[14px] font-semibold leading-snug" style="color:var(--text)" @click="openDetail(e)">{{ e.title }}</h3>
            <p class="mb-3 line-clamp-2 text-[12px] leading-relaxed" style="color:var(--text2)">{{ summary(e) }}</p>
            <div class="flex items-center gap-2 text-[10.5px]" style="color:var(--text3)">
              <span v-if="folderName(e.folderId)" class="rounded px-1.5 py-0.5" style="background:var(--nav-hover)">{{ folderName(e.folderId) }}</span>
              <span class="rounded px-1.5 py-0.5" style="background:var(--nav-hover)">{{ originMeta(e.origin).label }}</span>
            </div>
            <div class="mt-3 flex gap-1.5 border-t pt-3" style="border-color:var(--border)">
              <button class="btn-ghost flex-1 justify-center text-[11px] !h-7" @click.stop="startExtract(e)">
                <BrainCircuit class="h-3 w-3" />抽取本体
              </button>
              <button class="btn-primary flex-1 justify-center text-[11px] !h-7" @click.stop="buildOpen = true">
                <Waypoints class="h-3 w-3" />构建图谱
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 详情抽屉 -->
    <UiDrawer :open="!!detail" :title="detail?.title || ''" width="560px" @close="detail = null">
      <div v-if="detailLoading" class="flex justify-center py-16" style="color:var(--text3)"><LoaderCircle :size="20" class="animate-spin" /></div>
      <template v-else-if="detail">
        <div class="mb-3 flex flex-wrap items-center gap-2">
          <span class="badge" :class="originMeta(detail.origin).cls">{{ originMeta(detail.origin).label }}</span>
          <span v-for="t in (detail.tags || '').split(',').filter(Boolean)" :key="t" class="badge" style="background:var(--nav-hover);color:var(--text2)">{{ t }}</span>
          <span class="ml-auto text-xs" style="color:var(--text3)">{{ timeAgo(detail.updatedAt || detail.createdAt) }}</span>
        </div>
        <div class="md-body text-[13.5px] leading-relaxed" style="color:var(--text2)" v-html="renderMarkdown(detail.content || '（暂无内容）')" />
      </template>
    </UiDrawer>

    <!-- 新建经验 -->
    <UiModal :open="createOpen" title="新建经验" width="560px" @close="createOpen = false">
      <div class="space-y-4">
        <div>
          <label class="label">标题 <span style="color:var(--danger)">*</span></label>
          <input v-model="newExp.title" class="input" placeholder="例如：授信审批流程访谈纪要" />
        </div>
        <div>
          <label class="label">标签</label>
          <input v-model="newExp.tags" class="input" placeholder="逗号分隔，可选" />
        </div>
        <div>
          <label class="label">内容（Markdown）</label>
          <textarea v-model="newExp.content" class="textarea" rows="10" placeholder="把业务知识、访谈记录、规则说明写在这里…" />
        </div>
      </div>
      <template #footer>
        <div class="flex justify-end gap-2">
          <button class="btn-secondary" @click="createOpen = false">取消</button>
          <button class="btn-primary" :disabled="!newExp.title.trim() || saving" @click="submitCreate">
            <LoaderCircle v-if="saving" :size="15" class="animate-spin" /> 保存
          </button>
        </div>
      </template>
    </UiModal>

    <!-- 编辑经验 -->
    <UiModal :open="editOpen" title="编辑经验" width="560px" @close="editOpen = false">
      <div class="space-y-4">
        <div>
          <label class="label">标题 <span style="color:var(--danger)">*</span></label>
          <input v-model="editExp.title" class="input" />
        </div>
        <div>
          <label class="label">标签</label>
          <input v-model="editExp.tags" class="input" placeholder="逗号分隔，可选" />
        </div>
        <div>
          <label class="label">内容（Markdown）</label>
          <textarea v-model="editExp.content" class="textarea" rows="10" />
        </div>
      </div>
      <template #footer>
        <div class="flex justify-end gap-2">
          <button class="btn-secondary" @click="editOpen = false">取消</button>
          <button class="btn-primary" :disabled="!editExp.title.trim() || saving" @click="submitEdit">
            <LoaderCircle v-if="saving" :size="15" class="animate-spin" /> 保存
          </button>
        </div>
      </template>
    </UiModal>

    <!-- 导入文档 -->
    <UiModal :open="importOpen" title="导入文档" @close="importOpen = false">
      <div class="space-y-4">
        <label
          class="flex cursor-pointer flex-col items-center justify-center gap-2 rounded-xl border-2 border-dashed px-4 py-10 transition"
          style="border-color:var(--border);color:var(--text3)"
        >
          <Upload :size="22" style="color:var(--primary)" />
          <span class="text-[13px]">点击选择文件（PDF / DOCX / 图片 / CSV / MD / 音频）</span>
          <input type="file" multiple class="hidden" @change="onPickFiles" />
        </label>
        <div v-if="importFiles.length" class="space-y-1">
          <div
            v-for="f in importFiles"
            :key="f.name"
            class="flex items-center gap-2 rounded-lg px-3 py-1.5 text-[12.5px]"
            style="background:var(--nav-hover);color:var(--text2)"
          >
            <FileText :size="13" class="shrink-0" style="color:var(--text3)" /> <span class="truncate">{{ f.name }}</span>
          </div>
        </div>
        <p class="text-xs leading-relaxed" style="color:var(--text3)">文件将由后端抽取文本并沉淀为经验（音频自动转写），入库后即可参与建图。</p>
      </div>
      <template #footer>
        <div class="flex justify-end gap-2">
          <button class="btn-secondary" @click="importOpen = false">取消</button>
          <button class="btn-primary" :disabled="!importFiles.length || importing" @click="submitImport">
            <LoaderCircle v-if="importing" :size="15" class="animate-spin" /> 开始导入
          </button>
        </div>
      </template>
    </UiModal>

    <!-- 联网调研 -->
    <UiModal :open="researchOpen" title="联网调研" @close="researchOpen = false">
      <div class="space-y-4">
        <div>
          <label class="label">调研主题</label>
          <input v-model="topic" class="input" placeholder="例如：商业银行授信审批流程" :disabled="running" @keyup.enter="submitResearch" />
        </div>
        <ProgressSteps :steps="steps" :running="running" />
        <p class="text-xs leading-relaxed" style="color:var(--text3)">搜索 → 抓取 → AI 归纳成《业务知识文档》并沉淀进经验库。公开资料非本企业事实，建议建图后用内部来源校对。</p>
      </div>
      <template #footer>
        <div class="flex justify-end gap-2">
          <button v-if="running" class="btn-secondary" @click="cancelSse">取消</button>
          <button class="btn-primary" :disabled="!topic.trim() || running" @click="submitResearch">
            <LoaderCircle v-if="running" :size="15" class="animate-spin" /><Globe v-else :size="15" /> 开始调研
          </button>
        </div>
      </template>
    </UiModal>

    <!-- 一键建图 -->
    <UiModal :open="buildOpen" title="从经验库构建血缘图" @close="buildOpen = false">
      <div class="space-y-4">
        <div>
          <label class="label">额外提示（可选）</label>
          <input v-model="hint" class="input" placeholder="例如：重点关注审批链路" :disabled="running" />
        </div>
        <ProgressSteps :steps="steps" :running="running" />
        <p class="text-xs leading-relaxed" style="color:var(--text3)">
          将对当前工作空间的整个经验库执行建图流水线：分域抽取 → 命名归一 → 同义消解 → 跨批连边 → 确定性血缘合并。建好后自动落成新模型。
        </p>
      </div>
      <template #footer>
        <div class="flex justify-end gap-2">
          <button v-if="running" class="btn-secondary" @click="cancelSse">取消</button>
          <button class="btn-primary" :disabled="running" @click="submitBuild">
            <LoaderCircle v-if="running" :size="15" class="animate-spin" /><Sparkles v-else :size="15" /> 开始建图
          </button>
        </div>
      </template>
    </UiModal>

    <!-- 抽取本体进度 -->
    <UiModal :open="extractOpen" title="抽取本体" @close="extractOpen = false">
      <ProgressSteps :steps="extractSteps" :running="extractRunning" />
      <p class="mt-3 text-xs leading-relaxed" style="color:var(--text3)">对该文档执行实体/关系抽取，结果仅展示统计，不直接落库；确认后可用「一键建图」生成模型。</p>
    </UiModal>
  </div>
</template>
