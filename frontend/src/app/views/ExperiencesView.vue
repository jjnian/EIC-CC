<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import {
  Plus, Upload, Globe, Sparkles, FolderOpen, Folder, FileText,
  Trash2, LoaderCircle, Search, Library,
} from 'lucide-vue-next';
import {
  listExperiences, getExperience, createExperience, deleteExperience,
  uploadExperienceFile, webResearch, buildFullGraph,
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
const activeFolder = ref<string>('');
const query = ref('');

const detail = ref<Experience | null>(null);
const detailLoading = ref(false);

// 弹窗状态
const createOpen = ref(false);
const importOpen = ref(false);
const researchOpen = ref(false);
const buildOpen = ref(false);

// 新建经验
const newExp = ref({ title: '', tags: '', content: '' });
const saving = ref(false);

// 导入
const importFiles = ref<File[]>([]);
const importing = ref(false);

// 调研 / 建图
const topic = ref('');
const hint = ref('');
const steps = ref<StepItem[]>([]);
const running = ref(false);
let sseHandle: SseHandle | null = null;

const filtered = computed(() => {
  const q = query.value.trim().toLowerCase();
  return experiences.value
    .filter((e) => !activeFolder.value || e.folderId === activeFolder.value)
    .filter((e) => !q || e.title.toLowerCase().includes(q) || (e.tags || '').toLowerCase().includes(q))
    .sort((a, b) => (b.updatedAt || b.createdAt) - (a.updatedAt || a.createdAt));
});

onMounted(async () => {
  await reload();
  if (route.query.import) importOpen.value = true;
  if (route.query.build) buildOpen.value = true;
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
</script>

<template>
  <div class="flex h-full">
    <!-- 文件夹侧栏 -->
    <div class="flex w-52 shrink-0 flex-col border-r border-slate-200 bg-white">
      <div class="flex items-center justify-between px-4 py-3">
        <span class="text-[13px] font-semibold text-slate-700">业务领域</span>
        <button class="rounded p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-600" title="新建文件夹" @click="addFolder">
          <Plus :size="14" />
        </button>
      </div>
      <div class="flex-1 space-y-0.5 overflow-y-auto px-2 pb-3">
        <button
          class="flex w-full items-center gap-2 rounded-lg px-2.5 py-1.5 text-[13px]"
          :class="!activeFolder ? 'bg-indigo-50 font-medium text-indigo-700' : 'text-slate-600 hover:bg-slate-100'"
          @click="activeFolder = ''"
        >
          <FolderOpen :size="14" class="shrink-0" /> 全部经验
          <span class="ml-auto text-xs text-slate-400">{{ experiences.length }}</span>
        </button>
        <button
          v-for="f in folders" :key="f.id"
          class="flex w-full items-center gap-2 rounded-lg px-2.5 py-1.5 text-[13px]"
          :class="activeFolder === f.id ? 'bg-indigo-50 font-medium text-indigo-700' : 'text-slate-600 hover:bg-slate-100'"
          @click="activeFolder = f.id"
        >
          <Folder :size="14" class="shrink-0" /> <span class="truncate">{{ f.name }}</span>
        </button>
      </div>
    </div>

    <!-- 主区域 -->
    <div class="flex min-w-0 flex-1 flex-col">
      <div class="flex shrink-0 flex-wrap items-center gap-2 border-b border-slate-200 bg-white px-4 py-2.5">
        <div class="relative">
          <Search :size="14" class="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-slate-400" />
          <input v-model="query" class="input h-8 w-52 pl-8 text-[13px]" placeholder="搜索经验…" />
        </div>
        <div class="ml-auto flex items-center gap-2">
          <button class="btn-secondary btn-sm" @click="createOpen = true"><Plus :size="14" /> 新建</button>
          <button class="btn-secondary btn-sm" @click="importOpen = true"><Upload :size="14" /> 导入文档</button>
          <button class="btn-secondary btn-sm" @click="researchOpen = true"><Globe :size="14" /> 联网调研</button>
          <button class="btn-primary btn-sm" @click="buildOpen = true"><Sparkles :size="14" /> 一键建图</button>
        </div>
      </div>

      <div class="min-h-0 flex-1 overflow-y-auto p-4">
        <div v-if="loading" class="flex justify-center py-20 text-slate-400"><LoaderCircle :size="22" class="animate-spin" /></div>
        <UiEmpty
          v-else-if="!filtered.length"
          :icon="Library"
          title="这里还是空的"
          description="经验库是建图的唯一入口：导入文档、录音转写、库结构导出、系统探索、联网调研，都会沉淀为经验。"
        >
          <button class="btn-primary" @click="importOpen = true"><Upload :size="15" /> 导入第一份文档</button>
        </UiEmpty>

        <div v-else class="grid gap-3 lg:grid-cols-2 xl:grid-cols-3">
          <div
            v-for="e in filtered" :key="e.id"
            class="card group cursor-pointer p-4 transition hover:border-indigo-300 hover:shadow-md"
            @click="openDetail(e)"
          >
            <div class="mb-2 flex items-center gap-2">
              <span class="badge" :class="originMeta(e.origin).cls">{{ originMeta(e.origin).label }}</span>
              <span v-if="e.indexStatus === 'indexed'" class="badge bg-emerald-50 text-emerald-600">已索引</span>
              <button
                class="ml-auto rounded p-1 text-slate-300 opacity-0 transition hover:bg-rose-50 hover:text-rose-500 group-hover:opacity-100"
                @click.stop="remove(e)"
              ><Trash2 :size="13" /></button>
            </div>
            <div class="line-clamp-2 text-[14px] font-medium leading-snug text-slate-800">{{ e.title }}</div>
            <div class="mt-2 flex items-center gap-1.5 text-xs text-slate-400">
              <FileText :size="12" /> {{ timeAgo(e.updatedAt || e.createdAt) }}
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 详情抽屉 -->
    <UiDrawer :open="!!detail" :title="detail?.title || ''" width="560px" @close="detail = null">
      <div v-if="detailLoading" class="flex justify-center py-16 text-slate-400"><LoaderCircle :size="20" class="animate-spin" /></div>
      <template v-else-if="detail">
        <div class="mb-3 flex flex-wrap items-center gap-2">
          <span class="badge" :class="originMeta(detail.origin).cls">{{ originMeta(detail.origin).label }}</span>
          <span v-for="t in (detail.tags || '').split(',').filter(Boolean)" :key="t" class="badge bg-slate-100 text-slate-500">{{ t }}</span>
          <span class="ml-auto text-xs text-slate-400">{{ timeAgo(detail.updatedAt || detail.createdAt) }}</span>
        </div>
        <div class="md-body text-[13.5px] leading-relaxed text-slate-700" v-html="renderMarkdown(detail.content || '（暂无内容）')" />
      </template>
    </UiDrawer>

    <!-- 新建经验 -->
    <UiModal :open="createOpen" title="新建经验" width="560px" @close="createOpen = false">
      <div class="space-y-4">
        <div>
          <label class="label">标题 <span class="text-rose-500">*</span></label>
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

    <!-- 导入文档 -->
    <UiModal :open="importOpen" title="导入文档" @close="importOpen = false">
      <div class="space-y-4">
        <label class="flex cursor-pointer flex-col items-center justify-center gap-2 rounded-xl border-2 border-dashed border-slate-300 px-4 py-10 text-slate-400 transition hover:border-indigo-400 hover:text-indigo-500">
          <Upload :size="22" />
          <span class="text-[13px]">点击选择文件（PDF / DOCX / 图片 / CSV / MD / 音频）</span>
          <input type="file" multiple class="hidden" @change="onPickFiles" />
        </label>
        <div v-if="importFiles.length" class="space-y-1">
          <div v-for="f in importFiles" :key="f.name" class="flex items-center gap-2 rounded-lg bg-slate-50 px-3 py-1.5 text-[12.5px] text-slate-600">
            <FileText :size="13" class="shrink-0 text-slate-400" /> <span class="truncate">{{ f.name }}</span>
          </div>
        </div>
        <p class="text-xs leading-relaxed text-slate-400">文件将由后端抽取文本并沉淀为经验（音频自动转写），入库后即可参与建图。</p>
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
        <p class="text-xs leading-relaxed text-slate-400">搜索 → 抓取 → AI 归纳成《业务知识文档》并沉淀进经验库。公开资料非本企业事实，建议建图后用内部来源校对。</p>
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
        <p class="text-xs leading-relaxed text-slate-400">
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
  </div>
</template>

<style>
.md-body .md-p { margin: 0 0 10px; }
.md-body .md-h { font-weight: 600; margin: 14px 0 6px; color: #0f172a; }
.md-body .md-h1 { font-size: 17px; }
.md-body .md-h2 { font-size: 15.5px; }
.md-body .md-h3, .md-body .md-h4 { font-size: 14px; }
.md-body .md-ul { margin: 0 0 10px; padding-left: 18px; list-style: disc; }
.md-body .md-ul li { margin: 3px 0; }
.md-body .md-code { background: #f1f5f9; border-radius: 4px; padding: 1px 5px; font-size: 12px; }
.md-body .md-pre { background: #0f172a; color: #e2e8f0; border-radius: 10px; padding: 12px 14px; overflow-x: auto; font-size: 12px; margin: 0 0 10px; }
.md-body .md-link { color: #4f46e5; text-decoration: underline; }
</style>
