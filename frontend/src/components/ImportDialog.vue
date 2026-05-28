<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount } from 'vue';
import type { OntologyNode, SourceMeta } from '../types';
import { extractFromFilesStream } from '../api/ontology';
import type { SseHandle } from '../api/http';

interface ExtractedNode {
  id: string;
  label: string;
  type?: string;
  source?: string;
  props?: any[];
}
interface ExtractedEdge {
  id: string;
  from: string;
  to: string;
  label?: string;
  source?: string;
  rule_driven?: boolean;
}

const props = defineProps<{
  open: boolean;
  hasCurrentModel: boolean;
  currentNodes?: OntologyNode[];   // v1.0 Phase 2：用于 label 去重对照
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'commit', payload: {
    mode: 'merge' | 'new';
    name: string;
    nodes: ExtractedNode[];
    edges: ExtractedEdge[];
  }): void;
}>();

const files = ref<File[]>([]);
const urlInput = ref('');
const mode = ref<'merge' | 'new'>('merge');
const newName = ref('');
const loading = ref(false);
const errorMsg = ref('');
// 原始抽取结果（不破坏性修改），mode 切换时 dup 计算自动失效
const extractedRaw = ref<{ nodes: ExtractedNode[]; edges: ExtractedEdge[] } | null>(null);
const sources = ref<SourceMeta[]>([]);
const replyText = ref('');
const selectedNodeIds = ref<Set<string>>(new Set());
const selectedEdgeIds = ref<Set<string>>(new Set());
// 抽取过程的分步进度（让用户看到"构建本体"的每个阶段，而非只有等待）
interface BuildStep { key: string; label: string; status: 'running' | 'done' | 'error'; }
const buildSteps = ref<BuildStep[]>([]);
// 抽取 SSE 流的可中断句柄
let sseHandle: SseHandle | null = null;

const normLabel = (s: string) => (s || '').trim().toLowerCase().replace(/\s+/g, ' ');

const parsedUrls = computed(() =>
  urlInput.value.split(/[\s,]+/).map(u => u.trim()).filter(Boolean));
const URL_LIMIT = 5;
const urlOverLimit = computed(() => parsedUrls.value.length > URL_LIMIT);

const SOURCE_ICONS: Record<string, string> = {
  image: '🖼',
  pdf: '📄',
  docx: '📝',
  url: '🔗',
};
const iconForSource = (type?: string) => SOURCE_ICONS[type || ''] || '⛔';

const reset = () => {
  files.value = [];
  urlInput.value = '';
  mode.value = props.hasCurrentModel ? 'merge' : 'new';
  newName.value = '';
  loading.value = false;
  errorMsg.value = '';
  extractedRaw.value = null;
  sources.value = [];
  replyText.value = '';
  selectedNodeIds.value = new Set();
  selectedEdgeIds.value = new Set();
  buildSteps.value = [];
  if (sseHandle) { try { sseHandle.abort(); } catch {} sseHandle = null; }
};

watch(() => props.open, (v) => {
  if (v) reset();
  else if (sseHandle) { try { sseHandle.abort(); } catch {} sseHandle = null; }
});

onBeforeUnmount(() => {
  if (sseHandle) { try { sseHandle.abort(); } catch {} sseHandle = null; }
});

// 切换 mode 时让可见节点重新进入全选状态（dup 过滤变化后选择需要刷新）
watch(() => mode.value, () => {
  if (!extractedRaw.value) return;
  selectedNodeIds.value = new Set(displayedNodes.value.map(n => n.id));
  selectedEdgeIds.value = new Set(displayedEdges.value.map(e => e.id));
});

const fmtSize = (n: number) => {
  if (n < 1024) return n + ' B';
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
  return (n / 1024 / 1024).toFixed(2) + ' MB';
};

const onPick = (e: Event) => {
  const input = e.target as HTMLInputElement;
  if (!input.files) return;
  for (const f of Array.from(input.files)) addFile(f);
  input.value = '';
};
const onDrop = (e: DragEvent) => {
  e.preventDefault();
  if (!e.dataTransfer?.files) return;
  for (const f of Array.from(e.dataTransfer.files)) addFile(f);
};
const addFile = (f: File) => {
  if (files.value.length >= 8) return;
  const lname = f.name.toLowerCase();
  const ok = f.type.startsWith('image/')
          || f.type === 'application/pdf' || lname.endsWith('.pdf')
          || lname.endsWith('.docx')
          || f.type === 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
  if (!ok) { errorMsg.value = `不支持的文件类型：${f.name}`; return; }
  if (files.value.find(x => x.name === f.name && x.size === f.size)) return;
  files.value.push(f);
  errorMsg.value = '';
};
const removeFile = (idx: number) => { files.value.splice(idx, 1); };

const markRunningAs = (status: 'done' | 'error') => {
  for (const st of buildSteps.value) {
    if (st.status === 'running') st.status = status;
  }
};

const extract = () => {
  if (!files.value.length && !parsedUrls.value.length) return;
  if (urlOverLimit.value) {
    errorMsg.value = `一次最多 ${URL_LIMIT} 个网址`;
    return;
  }
  loading.value = true;
  errorMsg.value = '';
  extractedRaw.value = null;
  buildSteps.value = [{ key: 'init', label: '正在准备抽取…', status: 'running' }];
  if (sseHandle) { try { sseHandle.abort(); } catch { /* noop */ } }

  sseHandle = extractFromFilesStream(
    files.value,
    { urls: parsedUrls.value },
    {
      onStep: (s) => {
        // 上一步标记完成,新步骤进入运行态——形成清晰的逐步推进感
        markRunningAs('done');
        buildSteps.value.push({ key: s.key, label: s.label, status: 'running' });
      },
      onComplete: (data) => {
        markRunningAs('done');
        extractedRaw.value = { nodes: data.nodes || [], edges: data.edges || [] };
        sources.value = data.sources || [];
        replyText.value = data.reply || '';
        selectedNodeIds.value = new Set(displayedNodes.value.map(n => n.id));
        selectedEdgeIds.value = new Set(extractedRaw.value.edges.map(e => e.id));
        loading.value = false;
        sseHandle = null;
      },
      onError: (msg) => {
        markRunningAs('error');
        errorMsg.value = msg || '抽取失败';
        loading.value = false;
        sseHandle = null;
      },
      onClose: () => {
        // 正常完成由 onComplete 处理;此处只兜底复位 loading（如流意外关闭）
        if (loading.value) loading.value = false;
        sseHandle = null;
      },
    }
  );
};

// 已有图谱标签 → {id,label} 索引（mode 切换时自动失效）
const existingLabelMap = computed(() => {
  const m = new Map<string, { id: string; label: string }>();
  if (mode.value !== 'merge') return m;
  for (const n of (props.currentNodes || [])) {
    const k = normLabel(n.label || '');
    if (k) m.set(k, { id: n.id, label: n.label });
  }
  return m;
});

// dup 映射：仅 merge 模式下生效。
const dupRemap = computed<Record<string, string>>(() => {
  const out: Record<string, string> = {};
  if (!extractedRaw.value || mode.value !== 'merge') return out;
  for (const n of extractedRaw.value.nodes) {
    const k = normLabel(n.label || '');
    const hit = k ? existingLabelMap.value.get(k) : null;
    if (hit) out[n.id] = hit.id;
  }
  return out;
});

const dupList = computed(() => {
  const out: { extractedId: string; extractedLabel: string; existingLabel: string }[] = [];
  if (!extractedRaw.value || mode.value !== 'merge') return out;
  for (const n of extractedRaw.value.nodes) {
    const k = normLabel(n.label || '');
    const hit = k ? existingLabelMap.value.get(k) : null;
    if (hit) out.push({ extractedId: n.id, extractedLabel: n.label, existingLabel: hit.label });
  }
  return out;
});

// 节点显示列表：merge 模式下过滤掉 dup（在原始数据上派生）
const displayedNodes = computed<ExtractedNode[]>(() => {
  if (!extractedRaw.value) return [];
  if (mode.value !== 'merge') return extractedRaw.value.nodes;
  const dup = dupRemap.value;
  return extractedRaw.value.nodes.filter(n => !(n.id in dup));
});

const displayedEdges = computed<ExtractedEdge[]>(() =>
  extractedRaw.value ? extractedRaw.value.edges : []);

const isExistingId = (id: string) => !!props.currentNodes?.some(n => n.id === id);

const toggleNode = (id: string) => {
  const s = new Set(selectedNodeIds.value);
  if (s.has(id)) s.delete(id); else s.add(id);
  selectedNodeIds.value = s;
};
const toggleEdge = (id: string) => {
  const s = new Set(selectedEdgeIds.value);
  if (s.has(id)) s.delete(id); else s.add(id);
  selectedEdgeIds.value = s;
};
const toggleAllNodes = () => {
  const all = displayedNodes.value.map(n => n.id);
  const cur = selectedNodeIds.value;
  const allSelected = all.length > 0 && all.every(id => cur.has(id));
  selectedNodeIds.value = allSelected ? new Set() : new Set(all);
};
const toggleAllEdges = () => {
  const all = displayedEdges.value.map(e => e.id);
  const cur = selectedEdgeIds.value;
  const allSelected = all.length > 0 && all.every(id => cur.has(id));
  selectedEdgeIds.value = allSelected ? new Set() : new Set(all);
};

const validEdges = computed(() => {
  if (!extractedRaw.value) return [];
  const dup = dupRemap.value;
  // 仅保留勾选的边；端点用 orig id 判断是否选中，再 apply remap，最终判端点是否落到已存在节点上
  return extractedRaw.value.edges
    .filter(e => selectedEdgeIds.value.has(e.id))
    .map(e => {
      const fromMapped = dup[e.from] || e.from;
      const toMapped = dup[e.to] || e.to;
      return { ...e, from: fromMapped, to: toMapped, _origFrom: e.from, _origTo: e.to };
    })
    .filter(e =>
      (selectedNodeIds.value.has(e._origFrom) || isExistingId(e.from)) &&
      (selectedNodeIds.value.has(e._origTo) || isExistingId(e.to)))
    .map(({ _origFrom, _origTo, ...rest }) => rest as ExtractedEdge);
});

const selectedNodes = computed(() => {
  return displayedNodes.value.filter(n => selectedNodeIds.value.has(n.id));
});

const canCommit = computed(() => {
  if (!extractedRaw.value || loading.value) return false;
  if (selectedNodes.value.length === 0) return false;
  if (mode.value === 'merge' && !props.hasCurrentModel) return false;
  if (mode.value === 'new' && !newName.value.trim()) return false;
  return true;
});

const commit = () => {
  if (!canCommit.value || !extractedRaw.value) return;
  emit('commit', {
    mode: mode.value,
    name: newName.value.trim() || '导入本体',
    nodes: selectedNodes.value,
    edges: validEdges.value,
  });
};

const onBackdrop = (e: MouseEvent) => {
  if ((e.target as HTMLElement).classList.contains('imp-backdrop')) emit('close');
};
</script>

<template>
  <div v-if="open" class="imp-backdrop" @mousedown="onBackdrop">
    <div class="imp-dialog">
      <div class="imp-head">
        <div class="imp-title"><span class="imp-icon">📥</span><span>从文档 / 网页抽取本体</span></div>
        <button class="imp-close" @click="emit('close')">×</button>
      </div>

      <div class="imp-body">
        <!-- 文件区 -->
        <div v-if="!extractedRaw && !loading" class="imp-section">
          <label class="imp-label">上传 PDF / DOCX / 图片（流程图、表格、文档截图）</label>
          <div
            class="imp-drop"
            @dragover.prevent
            @drop="onDrop"
            @click="(($refs.fileInput as HTMLInputElement | undefined)?.click())"
          >
            <input
              ref="fileInput"
              type="file"
              multiple
              accept=".pdf,.docx,image/*,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
              style="display:none"
              @change="onPick"
            />
            <div class="imp-drop-msg">
              <div style="font-size: 28px;">📎</div>
              <div>点击或拖拽文件到此处 · 最多 8 个</div>
              <div class="imp-drop-hint">PDF / DOCX 走文本抽取 · 图片走多模态视觉</div>
            </div>
          </div>
          <div v-if="files.length" class="imp-files">
            <div v-for="(f, i) in files" :key="i" class="imp-file">
              <span class="imp-file-icon">{{ f.type.startsWith('image/') ? '🖼' : '📄' }}</span>
              <span class="imp-file-name">{{ f.name }}</span>
              <span class="imp-file-size">{{ fmtSize(f.size) }}</span>
              <button class="imp-file-x" @click="removeFile(i)" type="button">×</button>
            </div>
          </div>

          <label class="imp-label" style="margin-top: 4px;">或粘贴网址（每行一个，最多 {{ URL_LIMIT }} 个）</label>
          <textarea
            v-model="urlInput"
            class="imp-url-area"
            placeholder="https://example.com/article&#10;https://another.site/page"
            rows="3"
            spellcheck="false"
          />
          <div v-if="parsedUrls.length" class="imp-url-meta">
            已识别 {{ parsedUrls.length }} 个网址
            <span v-if="urlOverLimit" class="imp-url-warn">· 超过 {{ URL_LIMIT }} 个上限</span>
          </div>
        </div>

        <!-- 模式 -->
        <div v-if="!extractedRaw && !loading" class="imp-section">
          <label class="imp-label">抽取后</label>
          <div class="imp-tabs">
            <button
              class="imp-tab"
              :class="{ 'imp-tab-on': mode === 'merge' }"
              :disabled="!hasCurrentModel"
              @click="mode = 'merge'"
              type="button"
            >
              <span class="imp-tab-title">合并到当前模型</span>
              <span class="imp-tab-sub">{{ hasCurrentModel ? '增量并入当前 trunk' : '请先选择一个模型' }}</span>
            </button>
            <button
              class="imp-tab"
              :class="{ 'imp-tab-on': mode === 'new' }"
              @click="mode = 'new'"
              type="button"
            >
              <span class="imp-tab-title">另存为新模型</span>
              <span class="imp-tab-sub">独立创建一份本体</span>
            </button>
          </div>
          <input
            v-if="mode === 'new'"
            v-model="newName"
            class="imp-input"
            placeholder="新模型名称…"
            style="margin-top: 10px;"
          />
        </div>

        <!-- 抽取过程：逐步展示"构建本体"的每个阶段 -->
        <div v-if="!extractedRaw && buildSteps.length" class="imp-section imp-steps">
          <div class="imp-steps-title">
            {{ loading ? '正在构建本体…' : '构建已结束' }}
          </div>
          <div class="imp-step-list">
            <div
              v-for="(s, i) in buildSteps"
              :key="i"
              class="imp-step"
              :class="'imp-step-' + s.status"
            >
              <span class="imp-step-ico">
                <span v-if="s.status === 'running'" class="imp-step-spin" />
                <span v-else-if="s.status === 'done'">✓</span>
                <span v-else>✕</span>
              </span>
              <span class="imp-step-label">{{ s.label }}</span>
            </div>
          </div>
        </div>

        <!-- 抽取结果预览 -->
        <div v-if="extractedRaw" class="imp-section imp-result">
          <div v-if="replyText" class="imp-reply">{{ replyText }}</div>

          <div v-if="dupList.length" class="imp-dup-banner">
            <div class="imp-dup-head">
              <span class="imp-dup-icon">↩</span>
              <span>检测到 {{ dupList.length }} 个节点已存在于当前图谱，关系会自动重定向到现有节点</span>
            </div>
            <div class="imp-dup-list">
              <span v-for="(d, i) in dupList" :key="i" class="imp-dup-chip">
                {{ d.extractedLabel }} → <strong>{{ d.existingLabel }}</strong>
              </span>
            </div>
          </div>

          <div v-if="sources.length" class="imp-sources">
            <div v-for="(s, i) in sources" :key="i" class="imp-source-item">
              <span class="imp-source-icon">{{ iconForSource(s.type) }}</span>
              <span class="imp-source-name">{{ s.title || s.name }}</span>
              <span v-if="s.type === 'pdf'" class="imp-source-meta">
                {{ s.pages }} 页 · {{ s.chars?.toLocaleString() }} 字{{ s.truncated ? ' · 截断' : '' }}
              </span>
              <span v-else-if="s.type === 'docx'" class="imp-source-meta">
                {{ s.paragraphs }} 段{{ s.tables ? ' · ' + s.tables + ' 表' : '' }} · {{ s.chars?.toLocaleString() }} 字{{ s.truncated ? ' · 截断' : '' }}
              </span>
              <span v-else-if="s.type === 'image'" class="imp-source-meta">{{ fmtSize(s.size) }}</span>
              <span v-else-if="s.type === 'url'" class="imp-source-meta">
                {{ s.chars?.toLocaleString() }} 字{{ s.truncated ? ' · 截断' : '' }}{{ s.usedHeadless ? ' · 浏览器渲染' : '' }}
              </span>
              <span v-else class="imp-source-meta imp-source-skip">{{ s.reason }}</span>
              <span v-if="s.renderedPages" class="imp-source-rendered">📸 渲染 {{ s.renderedPages }} 页</span>
            </div>
          </div>

          <div class="imp-cols">
            <div class="imp-col">
              <div class="imp-col-head">
                <span>节点 ({{ selectedNodeIds.size }} / {{ displayedNodes.length }})</span>
                <button class="imp-link" @click="toggleAllNodes" type="button">全选 / 反选</button>
              </div>
              <div class="imp-list">
                <label v-for="n in displayedNodes" :key="n.id" class="imp-list-row">
                  <input type="checkbox" :checked="selectedNodeIds.has(n.id)" @change="toggleNode(n.id)" />
                  <span class="imp-row-label">{{ n.label }}</span>
                  <span class="imp-row-tag" :class="'imp-tag-' + (n.type || 'other')">{{ n.type }}</span>
                  <span v-if="n.source === 'inferred'" class="imp-row-inf">推测</span>
                </label>
              </div>
            </div>
            <div class="imp-col">
              <div class="imp-col-head">
                <span>关系 ({{ selectedEdgeIds.size }} / {{ displayedEdges.length }})</span>
                <button class="imp-link" @click="toggleAllEdges" type="button">全选 / 反选</button>
              </div>
              <div class="imp-list">
                <label v-for="e in displayedEdges" :key="e.id" class="imp-list-row">
                  <input type="checkbox" :checked="selectedEdgeIds.has(e.id)" @change="toggleEdge(e.id)" />
                  <span class="imp-edge-from">{{ (extractedRaw && extractedRaw.nodes.find(n => n.id === e.from)?.label) || e.from }}</span>
                  <span class="imp-edge-arrow">→</span>
                  <span class="imp-edge-to">{{ (extractedRaw && extractedRaw.nodes.find(n => n.id === e.to)?.label) || e.to }}</span>
                  <span v-if="e.rule_driven" class="imp-row-rule">⚡</span>
                </label>
              </div>
            </div>
          </div>
        </div>

        <div v-if="errorMsg" class="imp-error">{{ errorMsg }}</div>
      </div>

      <div class="imp-foot">
        <button class="imp-btn imp-btn-cancel" type="button" @click="emit('close')">取消</button>
        <button
          v-if="!extractedRaw"
          class="imp-btn imp-btn-primary"
          type="button"
          :disabled="(!files.length && !parsedUrls.length) || urlOverLimit || loading"
          @click="extract"
        >
          <span v-if="loading" class="imp-spin" /> {{ loading ? '抽取中…' : '开始抽取' }}
        </button>
        <button
          v-else
          class="imp-btn imp-btn-primary"
          type="button"
          :disabled="!canCommit"
          @click="commit"
        >{{ mode === 'merge' ? '合并到当前图' : '另存为新模型' }}</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.imp-backdrop {
  position: fixed; inset: 0; background: rgba(0,0,0,0.5);
  backdrop-filter: blur(4px); z-index: 2000;
  display: flex; align-items: center; justify-content: center;
  animation: impFade 0.18s ease-out;
}
@keyframes impFade { from { opacity: 0; } to { opacity: 1; } }
.imp-dialog {
  width: 720px; max-width: 94vw; max-height: 90vh;
  background: rgba(15, 23, 42, 0.96);
  border: 1px solid rgba(66, 184, 131, 0.25);
  border-radius: 16px;
  display: flex; flex-direction: column;
  box-shadow: 0 24px 64px rgba(0, 0, 0, 0.5);
  overflow: hidden;
}
.imp-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid rgba(255,255,255,0.08);
}
.imp-title { display: flex; align-items: center; gap: 10px; font-size: 16px; font-weight: 600; color: var(--text-main); }
.imp-icon { font-size: 18px; }
.imp-close {
  background: rgba(255,255,255,0.06); border: none; color: var(--text-dim);
  width: 28px; height: 28px; border-radius: 50%; cursor: pointer; font-size: 18px;
  display: flex; align-items: center; justify-content: center;
}
.imp-close:hover { background: rgba(255,99,99,0.2); color: #ff8a8a; }
.imp-body { padding: 16px 20px; overflow-y: auto; flex: 1; display: flex; flex-direction: column; gap: 16px; }
.imp-section { display: flex; flex-direction: column; gap: 8px; }
.imp-label { font-size: 12px; color: var(--text-dim); letter-spacing: 0.5px; }
.imp-drop {
  border: 2px dashed rgba(66, 184, 131, 0.25);
  background: rgba(66, 184, 131, 0.04);
  border-radius: 12px;
  padding: 28px;
  display: flex; align-items: center; justify-content: center;
  cursor: pointer;
  transition: all 0.15s;
}
.imp-drop:hover { border-color: rgba(66, 184, 131, 0.5); background: rgba(66, 184, 131, 0.08); }
.imp-drop-msg { text-align: center; font-size: 13px; color: var(--text-dim); display: flex; flex-direction: column; gap: 6px; }
.imp-drop-hint { font-size: 11px; color: rgba(255,255,255,0.3); font-family: 'JetBrains Mono', monospace; }
.imp-files { display: flex; flex-direction: column; gap: 4px; }
.imp-file {
  display: flex; align-items: center; gap: 8px;
  background: rgba(10, 16, 27, 0.6);
  border: 1px solid rgba(255,255,255,0.06);
  padding: 6px 10px;
  border-radius: 8px;
  font-size: 12px;
}
.imp-file-icon { font-size: 14px; flex-shrink: 0; }
.imp-file-name { flex: 1; color: var(--text-main); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.imp-file-size { color: var(--text-dim); font-family: 'JetBrains Mono', monospace; font-size: 11px; }
.imp-file-x { background: none; border: none; color: var(--text-dim); cursor: pointer; padding: 2px 6px; font-size: 16px; line-height: 1; }
.imp-file-x:hover { color: #ff8a8a; }

.imp-url-area {
  width: 100%; box-sizing: border-box;
  background: rgba(10, 16, 27, 0.8);
  border: 1px solid rgba(255,255,255,0.1);
  color: var(--text-main);
  padding: 8px 12px;
  border-radius: 8px;
  font-size: 12px;
  font-family: 'JetBrains Mono', monospace;
  outline: none;
  resize: vertical;
  min-height: 56px;
}
.imp-url-area:focus { border-color: rgba(66, 184, 131, 0.5); }
.imp-url-meta { font-size: 11px; color: var(--text-dim); }
.imp-url-warn { color: #ff8a8a; margin-left: 6px; }

.imp-tabs { display: flex; gap: 8px; }
.imp-tab {
  flex: 1;
  display: flex; flex-direction: column; gap: 3px; text-align: left;
  padding: 10px 12px;
  background: rgba(10, 16, 27, 0.6);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 10px;
  color: var(--text-dim);
  cursor: pointer;
  font-family: inherit;
  transition: all 0.15s;
}
.imp-tab:hover:not(:disabled) { background: rgba(10, 16, 27, 0.9); border-color: rgba(255,255,255,0.15); }
.imp-tab:disabled { opacity: 0.4; cursor: not-allowed; }
.imp-tab-on {
  background: rgba(66, 184, 131, 0.12);
  border-color: rgba(66, 184, 131, 0.5);
  color: #42b883;
}
.imp-tab-title { font-size: 13px; font-weight: 600; }
.imp-tab-sub { font-size: 10px; opacity: 0.7; }
.imp-input {
  width: 100%;
  background: rgba(10, 16, 27, 0.8);
  border: 1px solid rgba(255,255,255,0.1);
  color: var(--text-main);
  padding: 8px 12px;
  border-radius: 8px;
  font-size: 13px;
  font-family: inherit;
  outline: none;
}
.imp-input:focus { border-color: rgba(66, 184, 131, 0.5); }

.imp-reply {
  font-size: 12px;
  color: var(--text-dim);
  background: rgba(66, 184, 131, 0.06);
  border-left: 2px solid #42b883;
  padding: 8px 12px;
  border-radius: 4px;
  line-height: 1.5;
}
.imp-sources { display: flex; flex-direction: column; gap: 4px; }
.imp-source-item { display: flex; align-items: center; gap: 6px; font-size: 11px; color: var(--text-dim); font-family: 'JetBrains Mono', monospace; }
.imp-source-icon { font-size: 12px; }
.imp-source-name { color: var(--text-main); }
.imp-source-skip { color: #ff8a8a; }
.imp-source-rendered { color: #63b3ed; font-family: 'JetBrains Mono', monospace; font-size: 10px; }

.imp-dup-banner {
  background: rgba(99, 179, 237, 0.06);
  border: 1px solid rgba(99, 179, 237, 0.18);
  border-left: 2px solid #63b3ed;
  border-radius: 6px;
  padding: 8px 12px;
  display: flex; flex-direction: column; gap: 6px;
}
.imp-dup-head { display: flex; align-items: center; gap: 6px; font-size: 12px; color: #63b3ed; }
.imp-dup-icon { font-size: 14px; font-family: 'JetBrains Mono', monospace; }
.imp-dup-list { display: flex; flex-wrap: wrap; gap: 4px; }
.imp-dup-chip {
  font-size: 10px;
  color: var(--text-dim);
  background: rgba(255,255,255,0.04);
  border: 1px solid rgba(255,255,255,0.06);
  padding: 2px 8px;
  border-radius: 100px;
}
.imp-dup-chip strong { color: var(--text-main); font-weight: 600; }

.imp-cols { display: flex; gap: 12px; }
.imp-col { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6px; }
.imp-col-head { display: flex; align-items: center; justify-content: space-between; font-size: 11px; color: var(--text-dim); font-weight: 600; letter-spacing: 0.5px; }
.imp-link { background: none; border: none; color: #42b883; font-size: 10px; cursor: pointer; padding: 0; font-family: inherit; }
.imp-link:hover { text-decoration: underline; }
.imp-list {
  max-height: 220px;
  overflow-y: auto;
  display: flex; flex-direction: column; gap: 2px;
  background: rgba(10, 16, 27, 0.4);
  border: 1px solid rgba(255,255,255,0.06);
  border-radius: 8px;
  padding: 6px;
}
.imp-list-row {
  display: flex; align-items: center; gap: 6px;
  padding: 4px 6px;
  border-radius: 4px;
  font-size: 12px;
  cursor: pointer;
}
.imp-list-row:hover { background: rgba(255,255,255,0.04); }
.imp-list-row input { accent-color: #42b883; flex-shrink: 0; }
.imp-row-label { color: var(--text-main); flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.imp-row-tag {
  font-size: 9px;
  padding: 1px 5px;
  border-radius: 3px;
  text-transform: uppercase;
  background: rgba(255,255,255,0.06);
  color: var(--text-dim);
  font-family: 'JetBrains Mono', monospace;
}
.imp-tag-rule { background: rgba(255,51,153,0.12); color: #ff3399; }
.imp-tag-event { background: rgba(99,179,237,0.12); color: #63b3ed; }
.imp-tag-entity { background: rgba(66,184,131,0.12); color: #42b883; }
.imp-tag-process { background: rgba(251,191,36,0.12); color: #fbbf24; }
.imp-row-inf { font-size: 9px; color: #fbbf24; }
.imp-row-rule { color: #ff3399; }
.imp-edge-from, .imp-edge-to { color: var(--text-main); font-size: 11px; flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.imp-edge-to { text-align: right; }
.imp-edge-arrow { color: var(--text-dim); font-family: 'JetBrains Mono', monospace; font-size: 11px; flex-shrink: 0; }

.imp-error { color: #ff8a8a; font-size: 12px; background: rgba(255,99,99,0.08); border: 1px solid rgba(255,99,99,0.2); padding: 8px 12px; border-radius: 6px; }

/* 抽取过程分步展示 */
.imp-steps {
  background: rgba(10, 16, 27, 0.5);
  border: 1px solid rgba(66, 184, 131, 0.18);
  border-radius: 12px;
  padding: 14px 16px;
}
.imp-steps-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-main);
  margin-bottom: 10px;
}
.imp-step-list { display: flex; flex-direction: column; gap: 2px; }
.imp-step {
  display: flex; align-items: flex-start; gap: 10px;
  padding: 6px 4px;
  font-size: 12px;
  line-height: 1.5;
  position: relative;
}
/* 步骤之间的竖线，营造时间轴感 */
.imp-step:not(:last-child)::after {
  content: '';
  position: absolute;
  left: 11px; top: 22px; bottom: -2px;
  width: 1px;
  background: rgba(255, 255, 255, 0.08);
}
.imp-step-ico {
  flex-shrink: 0;
  width: 16px; height: 16px;
  display: flex; align-items: center; justify-content: center;
  font-size: 12px; font-weight: 700;
  border-radius: 50%;
  margin-top: 1px;
  z-index: 1;
}
.imp-step-done .imp-step-ico { color: #42b883; }
.imp-step-error .imp-step-ico { color: #ff8a8a; }
.imp-step-label { color: var(--text-dim); }
.imp-step-running .imp-step-label { color: var(--text-main); }
.imp-step-done .imp-step-label { color: var(--text-dim); }
.imp-step-error .imp-step-label { color: #ff8a8a; }
.imp-step-spin {
  width: 12px; height: 12px;
  border: 2px solid rgba(66, 184, 131, 0.25);
  border-top-color: #42b883;
  border-radius: 50%;
  display: inline-block;
  animation: impSpin 0.8s linear infinite;
}

.imp-foot {
  display: flex; gap: 10px; justify-content: flex-end;
  padding: 14px 20px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}
.imp-btn {
  padding: 9px 18px; border-radius: 10px; border: none;
  font-size: 13px; font-weight: 600; cursor: pointer; font-family: inherit;
  display: flex; align-items: center; gap: 8px;
}
.imp-btn-cancel { background: rgba(255,255,255,0.06); color: var(--text-dim); }
.imp-btn-cancel:hover { background: rgba(255,255,255,0.12); color: var(--text-main); }
.imp-btn-primary { background: #42b883; color: #1a1a1a; }
.imp-btn-primary:hover:not(:disabled) { background: #5cc99a; transform: translateY(-1px); }
.imp-btn-primary:disabled { opacity: 0.4; cursor: not-allowed; }
.imp-spin {
  width: 12px; height: 12px;
  border: 2px solid rgba(26,26,26,0.3);
  border-top-color: #1a1a1a;
  border-radius: 50%;
  animation: impSpin 0.8s linear infinite;
}
@keyframes impSpin { to { transform: rotate(360deg); } }
</style>
