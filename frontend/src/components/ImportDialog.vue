<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount } from 'vue';
import type { OntologyNode } from '../types';
import { useImportFiles } from '../composables/useImportFiles';
import { useExtractStream, type ExtractedNode, type ExtractedEdge } from '../composables/useExtractStream';
import { useImportDedup } from '../composables/useImportDedup';
import { Button } from '@/components/ui/button';
import BaseInput from './form/BaseInput.vue';
import BaseTextarea from './form/BaseTextarea.vue';
import { Inbox, Paperclip, X, Image as ImageIcon, Music, FileText, Camera, Zap, type LucideIcon } from '@lucide/vue';

const fileIcon = (mime: string): LucideIcon =>
  mime.startsWith('image/') ? ImageIcon : mime.startsWith('audio/') ? Music : FileText;

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

const mode = ref<'merge' | 'new'>('merge');
const newName = ref('');

// 文件 / URL 拾取
const importFiles = useImportFiles();
const {
  files, urlInput, errorMsg,
  parsedUrls, urlOverLimit, URL_LIMIT,
  iconForSource, fmtSize,
  onPick, onDrop, removeFile,
} = importFiles;

// SSE 流式抽取（errorMsg 仍由 importFiles 持有,抽取完成后回调 dedup 刷新选择集）
const extractStream = useExtractStream({
  setError: (msg) => { errorMsg.value = msg; },
  onExtracted: () => { dedup.selectAll(); },
});
const { loading, replyText, sources, extractedRaw, buildSteps } = extractStream;

// 已展开「转写文字」的音频来源下标集合（点标题旁的按钮切换）
const expandedSources = ref<Set<number>>(new Set());
const toggleSourceTranscript = (i: number) => {
  const next = new Set(expandedSources.value);
  next.has(i) ? next.delete(i) : next.add(i);
  expandedSources.value = next;
};

// 去重对照与勾选
const dedup = useImportDedup({
  getRaw: () => extractedRaw.value,
  getMode: () => mode.value,
  getCurrentNodes: () => props.currentNodes || [],
});
const {
  selectedNodeIds, selectedEdgeIds,
  dupList, displayedNodes, displayedEdges,
  toggleNode, toggleEdge, toggleAllNodes, toggleAllEdges,
  validEdges, selectedNodes,
} = dedup;

const reset = () => {
  importFiles.resetFiles();
  extractStream.reset();
  dedup.clearSelection();
  mode.value = props.hasCurrentModel ? 'merge' : 'new';
  newName.value = '';
};

watch(() => props.open, (v) => {
  if (v) reset();
  else extractStream.abort();
});

onBeforeUnmount(() => {
  extractStream.abort();
});

// 切换 mode 时让可见节点重新进入全选状态（dup 过滤变化后选择需要刷新）
watch(() => mode.value, () => {
  if (!extractedRaw.value) return;
  dedup.selectAll();
});

const extract = () => {
  if (!files.value.length && !parsedUrls.value.length) return;
  if (urlOverLimit.value) {
    errorMsg.value = `一次最多 ${URL_LIMIT} 个网址`;
    return;
  }
  extractStream.start(files.value, parsedUrls.value);
};

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
  <Teleport to="body">
  <div v-if="open" class="imp-backdrop" @mousedown="onBackdrop">
    <div class="imp-dialog">
      <div class="imp-head">
        <div class="imp-title"><span class="imp-icon"><Inbox :size="18" :stroke-width="1.75" /></span><span>从文档 / 网页抽取本体</span></div>
        <Button variant="ghost" size="icon-sm" @click="emit('close')"><X :size="16" /></Button>
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
              accept=".pdf,.docx,.xlsx,.xls,.xlsm,.txt,.md,.csv,.tsv,.json,.sql,.log,image/*,audio/*,video/*,.mp3,.wav,.m4a,.aac,.flac,.ogg,.opus,.amr,.mp4,.m4v,.mov,.mkv,.avi,.mpg,.mpeg,.wmv,.flv,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
              style="display:none"
              @change="onPick"
            />
            <div class="imp-drop-msg">
              <div class="imp-drop-ic"><Paperclip :size="26" :stroke-width="1.5" /></div>
              <div>点击或拖拽文件到此处 · 最多 8 个</div>
              <div class="imp-drop-hint">PDF / DOCX / Excel / 文本走抽取 · 图片走多模态视觉 · 音频/视频先转写成文本</div>
            </div>
          </div>
          <div v-if="files.length" class="imp-files">
            <div v-for="(f, i) in files" :key="i" class="imp-file">
              <span class="imp-file-icon"><component :is="fileIcon(f.type)" :size="15" :stroke-width="1.75" /></span>
              <span class="imp-file-name">{{ f.name }}</span>
              <span class="imp-file-size">{{ fmtSize(f.size) }}</span>
              <Button variant="ghost" size="icon-sm" @click="removeFile(i)" type="button"><X :size="15" /></Button>
            </div>
          </div>

          <label class="imp-label" style="margin-top: 4px;">或粘贴网址（每行一个，最多 {{ URL_LIMIT }} 个）</label>
          <BaseTextarea
            v-model="urlInput"
            placeholder="https://example.com/article
https://another.site/page"
            :rows="3"
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
          <BaseInput
            v-if="mode === 'new'"
            v-model="newName"
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
            <template v-for="(s, i) in sources" :key="i">
              <div class="imp-source-item">
                <span class="imp-source-icon">{{ iconForSource(s.type) }}</span>
                <span class="imp-source-name">{{ s.title || s.name }}</span>
                <span v-if="s.type === 'pdf'" class="imp-source-meta">
                  {{ s.pages }} 页 · {{ s.chars?.toLocaleString() }} 字{{ s.truncated ? ' · 截断' : '' }}
                </span>
                <span v-else-if="s.type === 'docx'" class="imp-source-meta">
                  {{ s.paragraphs }} 段{{ s.tables ? ' · ' + s.tables + ' 表' : '' }} · {{ s.chars?.toLocaleString() }} 字{{ s.truncated ? ' · 截断' : '' }}
                </span>
                <span v-else-if="s.type === 'image'" class="imp-source-meta">
                  {{ (s.transcript || '').trim() ? '识别 ' + (s.chars?.toLocaleString() ?? '') + ' 字' : fmtSize(s.size) }}
                </span>
                <span v-else-if="s.type === 'audio'" class="imp-source-meta">
                  转写 {{ s.chars?.toLocaleString() }} 字{{ s.durationSec ? ' · ' + s.durationSec + ' 秒' : '' }}
                </span>
                <span v-else-if="s.type === 'url'" class="imp-source-meta">
                  {{ s.chars?.toLocaleString() }} 字{{ s.truncated ? ' · 截断' : '' }}{{ s.usedHeadless ? ' · 浏览器渲染' : '' }}
                </span>
                <span v-else class="imp-source-meta imp-source-skip">{{ s.reason }}</span>
                <span v-if="s.renderedPages" class="imp-source-rendered"><Camera :size="12" :stroke-width="1.75" /> 渲染 {{ s.renderedPages }} 页</span>
                <Button v-if="(s.type === 'audio' || s.type === 'image') && (s.transcript || '').trim()"
                        variant="ghost" size="sm"
                        type="button" @click="toggleSourceTranscript(i)">
                  {{ expandedSources.has(i) ? '收起文字' : (s.type === 'image' ? '查看识别' : '查看转写') }}
                </Button>
              </div>
              <pre v-if="(s.type === 'audio' || s.type === 'image') && expandedSources.has(i) && (s.transcript || '').trim()"
                   class="imp-transcript">{{ s.transcript }}</pre>
            </template>
          </div>

          <div class="imp-cols">
            <div class="imp-col">
              <div class="imp-col-head">
                <span>节点 ({{ selectedNodeIds.size }} / {{ displayedNodes.length }})</span>
                <Button variant="link" size="sm" @click="toggleAllNodes" type="button">全选 / 反选</Button>
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
                <Button variant="link" size="sm" @click="toggleAllEdges" type="button">全选 / 反选</Button>
              </div>
              <div class="imp-list">
                <label v-for="e in displayedEdges" :key="e.id" class="imp-list-row">
                  <input type="checkbox" :checked="selectedEdgeIds.has(e.id)" @change="toggleEdge(e.id)" />
                  <span class="imp-edge-from">{{ (extractedRaw && extractedRaw.nodes.find(n => n.id === e.from)?.label) || e.from }}</span>
                  <span class="imp-edge-arrow">→</span>
                  <span class="imp-edge-to">{{ (extractedRaw && extractedRaw.nodes.find(n => n.id === e.to)?.label) || e.to }}</span>
                  <span v-if="e.rule_driven" class="imp-row-rule"><Zap :size="12" :stroke-width="1.75" /></span>
                </label>
              </div>
            </div>
          </div>
        </div>

        <div v-if="errorMsg" class="imp-error">{{ errorMsg }}</div>
      </div>

      <div class="imp-foot">
        <Button variant="secondary" size="sm" @click="emit('close')">取消</Button>
        <Button
          v-if="!extractedRaw"
          size="sm"
          :disabled="(!files.length && !parsedUrls.length) || urlOverLimit || loading"
          @click="extract"
        >
          <span v-if="loading" class="imp-spin" /> {{ loading ? '抽取中…' : '开始抽取' }}
        </Button>
        <Button
          v-else
          size="sm"
          :disabled="!canCommit"
          @click="commit"
        >{{ mode === 'merge' ? '合并到当前图' : '另存为新模型' }}</Button>
      </div>
    </div>
  </div>
  </Teleport>
</template>

<style scoped>
.imp-backdrop {
  position: fixed; inset: 0; background: rgba(0,0,0,0.45);
  z-index: 2000;
  display: flex; align-items: center; justify-content: center;
  animation: impFade 0.18s ease-out;
}
@keyframes impFade { from { opacity: 0; } to { opacity: 1; } }
.imp-dialog {
  width: 720px; max-width: 94vw; max-height: 90vh;
  background: var(--bg-base);
  border: 1px solid var(--glass-border);
  border-radius: 16px;
  display: flex; flex-direction: column;
  box-shadow: var(--shadow-lg);
  overflow: hidden;
}
.imp-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid var(--hairline);
}
.imp-title { display: flex; align-items: center; gap: 10px; font-size: 16px; font-weight: 600; color: var(--text-main); }
.imp-icon { display: inline-flex; align-items: center; color: var(--text-dim); }
.imp-close {
  background: transparent; border: none; color: var(--text-muted);
  width: 28px; height: 28px; border-radius: 50%; cursor: pointer; font-size: 18px;
  display: flex; align-items: center; justify-content: center;
}
.imp-close:hover { background: rgba(220,38,38,0.08); color: #dc2626; }
.imp-body { padding: 16px 20px; overflow-y: auto; flex: 1; display: flex; flex-direction: column; gap: 16px; }
.imp-section { display: flex; flex-direction: column; gap: 8px; }
.imp-label { font-size: 12px; color: var(--text-dim); letter-spacing: 0.5px; }
.imp-drop {
  border: 2px dashed rgba(37,99,235,0.3);
  background: rgba(37,99,235,0.03);
  border-radius: 12px;
  padding: 28px;
  display: flex; align-items: center; justify-content: center;
  cursor: pointer;
  transition: all 0.15s;
}
.imp-drop:hover { border-color: rgba(37,99,235,0.5); background: rgba(37,99,235,0.06); }
.imp-drop-msg { text-align: center; font-size: 13px; color: var(--text-dim); display: flex; flex-direction: column; align-items: center; gap: 6px; }
.imp-drop-ic { display: flex; color: var(--text-muted); }
.imp-drop-hint { font-size: 11px; color: var(--text-muted); font-family: 'JetBrains Mono', monospace; }
.imp-files { display: flex; flex-direction: column; gap: 4px; }
.imp-file {
  display: flex; align-items: center; gap: 8px;
  background: var(--bg-subtle);
  border: 1px solid var(--hairline);
  padding: 6px 10px;
  border-radius: 8px;
  font-size: 12px;
}
.imp-file-icon { display: inline-flex; align-items: center; flex-shrink: 0; color: var(--text-dim); }
.imp-file-name { flex: 1; color: var(--text-main); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.imp-file-size { color: var(--text-dim); font-family: 'JetBrains Mono', monospace; font-size: 11px; }
.imp-file-x { background: none; border: none; color: var(--text-dim); cursor: pointer; padding: 2px 6px; font-size: 16px; line-height: 1; }
.imp-file-x:hover { color: #dc2626; }

.imp-url-area {
  width: 100%; box-sizing: border-box;
  background: #fff;
  border: 1px solid var(--glass-border);
  color: var(--text-main);
  padding: 8px 12px;
  border-radius: 8px;
  font-size: 12px;
  font-family: 'JetBrains Mono', monospace;
  outline: none;
  resize: vertical;
  min-height: 56px;
}
.imp-url-area:focus { border-color: rgba(0,0,0,0.35); }
.imp-url-meta { font-size: 11px; color: var(--text-dim); }
.imp-url-warn { color: #dc2626; margin-left: 6px; }

.imp-tabs { display: flex; gap: 8px; }
.imp-tab {
  flex: 1;
  display: flex; flex-direction: column; gap: 3px; text-align: left;
  padding: 10px 12px;
  background: var(--bg-subtle);
  border: 1px solid var(--hairline);
  border-radius: 10px;
  color: var(--text-dim);
  cursor: pointer;
  font-family: inherit;
  transition: all 0.15s;
}
.imp-tab:hover:not(:disabled) { background: var(--bg-elev); border-color: var(--glass-border-strong); }
.imp-tab:disabled { opacity: 0.4; cursor: not-allowed; }
.imp-tab-on {
  background: rgba(37,99,235,0.08);
  border-color: rgba(37,99,235,0.45);
  color: #2563eb;
}
.imp-tab-title { font-size: 13px; font-weight: 600; }
.imp-tab-sub { font-size: 10px; opacity: 0.7; }
.imp-input {
  width: 100%;
  background: #fff;
  border: 1px solid var(--glass-border);
  color: var(--text-main);
  padding: 8px 12px;
  border-radius: 8px;
  font-size: 13px;
  font-family: inherit;
  outline: none;
}
.imp-input:focus { border-color: rgba(0,0,0,0.35); }

.imp-reply {
  font-size: 12px;
  color: var(--text-dim);
  background: rgba(37,99,235,0.05);
  border-left: 2px solid var(--accent-2);
  padding: 8px 12px;
  border-radius: 4px;
  line-height: 1.5;
}
.imp-sources { display: flex; flex-direction: column; gap: 4px; }
.imp-source-item { display: flex; align-items: center; gap: 6px; font-size: 11px; color: var(--text-dim); font-family: 'JetBrains Mono', monospace; }
.imp-source-icon { font-size: 12px; }
.imp-source-name { color: var(--text-main); }
.imp-source-skip { color: #dc2626; }
.imp-source-rendered { display: inline-flex; align-items: center; gap: 4px; color: #2563eb; font-family: 'JetBrains Mono', monospace; font-size: 10px; }
.imp-source-toggle { margin-left: auto; padding: 2px 8px; font-size: 10px; cursor: pointer;
  color: #2563eb; background: rgba(37,99,235,0.06); border: 1px solid rgba(37,99,235,0.3);
  border-radius: 4px; }
.imp-source-toggle:hover { background: rgba(37,99,235,0.12); }
.imp-transcript { margin: 0 0 4px 18px; padding: 8px 12px; max-height: 220px; overflow: auto;
  white-space: pre-wrap; word-break: break-word; font-family: 'JetBrains Mono', monospace;
  font-size: 11px; line-height: 1.6; color: var(--text-main);
  background: var(--bg-subtle); border: 1px solid var(--hairline); border-radius: 6px; }

.imp-dup-banner {
  background: rgba(37,99,235,0.05);
  border: 1px solid rgba(37,99,235,0.18);
  border-left: 2px solid #2563eb;
  border-radius: 6px;
  padding: 8px 12px;
  display: flex; flex-direction: column; gap: 6px;
}
.imp-dup-head { display: flex; align-items: center; gap: 6px; font-size: 12px; color: #2563eb; }
.imp-dup-icon { font-size: 14px; font-family: 'JetBrains Mono', monospace; }
.imp-dup-list { display: flex; flex-wrap: wrap; gap: 4px; }
.imp-dup-chip {
  font-size: 10px;
  color: var(--text-dim);
  background: var(--bg-elev);
  border: 1px solid var(--hairline);
  padding: 2px 8px;
  border-radius: 100px;
}
.imp-dup-chip strong { color: var(--text-main); font-weight: 600; }

.imp-cols { display: flex; gap: 12px; }
.imp-col { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6px; }
.imp-col-head { display: flex; align-items: center; justify-content: space-between; font-size: 11px; color: var(--text-dim); font-weight: 600; letter-spacing: 0.5px; }
.imp-link { background: none; border: none; color: #2563eb; font-size: 10px; cursor: pointer; padding: 0; font-family: inherit; }
.imp-link:hover { text-decoration: underline; }
.imp-list {
  max-height: 220px;
  overflow-y: auto;
  display: flex; flex-direction: column; gap: 2px;
  background: var(--bg-subtle);
  border: 1px solid var(--hairline);
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
.imp-list-row:hover { background: var(--bg-elev); }
.imp-list-row input { accent-color: var(--accent); flex-shrink: 0; }
.imp-row-label { color: var(--text-main); flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.imp-row-tag {
  font-size: 9px;
  padding: 1px 5px;
  border-radius: 3px;
  text-transform: uppercase;
  background: var(--bg-elev);
  color: var(--text-dim);
  font-family: 'JetBrains Mono', monospace;
}
.imp-tag-rule { background: rgba(219,39,119,0.10); color: #db2777; }
.imp-tag-event { background: rgba(37,99,235,0.10); color: #2563eb; }
.imp-tag-entity { background: rgba(37,99,235,0.10); color: #2563eb; }
.imp-tag-process { background: rgba(217,119,6,0.10); color: #d97706; }
.imp-row-inf { font-size: 9px; color: #d97706; }
.imp-row-rule { display: inline-flex; align-items: center; color: #db2777; }
.imp-edge-from, .imp-edge-to { color: var(--text-main); font-size: 11px; flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.imp-edge-to { text-align: right; }
.imp-edge-arrow { color: var(--text-dim); font-family: 'JetBrains Mono', monospace; font-size: 11px; flex-shrink: 0; }

.imp-error { color: #dc2626; font-size: 12px; background: rgba(220,38,38,0.06); border: 1px solid rgba(220,38,38,0.25); padding: 8px 12px; border-radius: 6px; }

/* 抽取过程分步展示 */
.imp-steps {
  background: var(--bg-subtle);
  border: 1px solid var(--hairline);
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
  background: var(--hairline);
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
.imp-step-done .imp-step-ico { color: #059669; }
.imp-step-error .imp-step-ico { color: #dc2626; }
.imp-step-label { color: var(--text-dim); }
.imp-step-running .imp-step-label { color: var(--text-main); }
.imp-step-done .imp-step-label { color: var(--text-dim); }
.imp-step-error .imp-step-label { color: #dc2626; }
.imp-step-spin {
  width: 12px; height: 12px;
  border: 2px solid rgba(37,99,235,0.2);
  border-top-color: #2563eb;
  border-radius: 50%;
  display: inline-block;
  animation: impSpin 0.8s linear infinite;
}

.imp-foot {
  display: flex; gap: 10px; justify-content: flex-end;
  padding: 14px 20px;
  border-top: 1px solid var(--hairline);
}
.imp-btn {
  padding: 9px 18px; border-radius: 10px; border: none;
  font-size: 13px; font-weight: 600; cursor: pointer; font-family: inherit;
  display: flex; align-items: center; gap: 8px;
}
.imp-btn-cancel { background: var(--bg-elev); color: var(--text-dim); }
.imp-btn-cancel:hover { background: var(--bg-elev-hi); color: var(--text-main); }
.imp-btn-primary { background: var(--accent); color: #fff; }
.imp-btn-primary:hover:not(:disabled) { background: var(--accent-soft); }
.imp-btn-primary:disabled { opacity: 0.4; cursor: not-allowed; }
.imp-spin {
  width: 12px; height: 12px;
  border: 2px solid rgba(255,255,255,0.35);
  border-top-color: #fff;
  border-radius: 50%;
  animation: impSpin 0.8s linear infinite;
}
@keyframes impSpin { to { transform: rotate(360deg); } }
</style>
