<script setup lang="ts">
/**
 * 图谱画布：负责节点/边的渲染、拖拽、平移、缩放、搜索、框选、右键菜单等所有交互。
 * <p>性能要点：
 * <ul>
 *   <li>节点 ≥ 80 / 边 ≥ 100 时启用视口裁剪（viewport culling），仅渲染可视区附近的元素；</li>
 *   <li>滚动事件用 requestAnimationFrame 节流；</li>
 *   <li>拖拽 / 缩放都基于 zoom 反算，避免在缩放时累计漂移。</li>
 * </ul>
 */
import { reactive, onMounted, onUnmounted } from 'vue';
import { NW, getPath } from '../constants';
import type { OntologyNode, OntologyEdge } from '../types';
import { useGraphSearch } from '../composables/useGraphSearch';
import { useCanvasViewport } from '../composables/useCanvasViewport';
import { useNodeColoring } from '../composables/useNodeColoring';
import { useNodeDrag } from '../composables/useNodeDrag';
import { useBoxSelect } from '../composables/useBoxSelect';
import { useCanvasContextMenu } from '../composables/useCanvasContextMenu';

const props = defineProps<{
  nodes: OntologyNode[];
  edges: OntologyEdge[];
  selId: string | null;
  layoutDirection?: 'LR' | 'TB';
  /** 只读模式：禁用拖拽、禁用右键菜单、隐藏画布动作浮条与清空按钮（预览页用）。 */
  readonly?: boolean;
  /** 分支对比差异高亮数据 */
  diffHighlight?: { sharedIds: string[]; uniqueAIds: string[]; uniqueBIds: string[] } | null;
  /** 撤销/重做按钮的可用状态(由父级图谱历史栈决定) */
  canUndo?: boolean;
  canRedo?: boolean;
}>();

const emit = defineEmits<{
  (e: 'move', id: string, x: number, y: number): void;
  (e: 'drag-start', id: string): void;
  (e: 'select', id: string | null): void;
  (e: 'auto-layout'): void;
  (e: 'toggle-layout-direction'): void;
  (e: 'clear'): void;
  (e: 'predict-from', id: string): void;
  (e: 'delete-node', id: string): void;
  (e: 'delete-nodes', ids: string[]): void;
  (e: 'edit-node', id: string): void;
  (e: 'clear-diff'): void;
  // P1-7：对预测节点请求详细解释
  (e: 'explain-node', id: string): void;
  (e: 'undo'): void;
  (e: 'redo'): void;
  (e: 'add-node', payload: { mode: 'object'; label: string; x: number; y: number; inputs: { nodeId: string; edgeLabel: string }[]; outputs: { nodeId: string; edgeLabel: string }[] }): void;
  (e: 'add-edges', payload: { label: string; inputs: string[]; outputs: string[] }): void;
  (e: 'edit-edge-relation', edgeId: string): void;
  (e: 'select-edge', edgeId: string): void;
}>();

/* ── 多选集合：留在组件内,以引用方式传给需要它的 composable ── */
// 多选集合：Ctrl+点击 或 Shift+框选 后聚集的节点 id
const multiSel = reactive(new Set<string>());

/* ── 视口与缩放（useCanvasViewport，地基：持有 cvRef / zoom / isAutoFit / pan）── */
const viewport = useCanvasViewport({
  getNodes: () => props.nodes,
  getEdges: () => props.edges,
});
const cvRef = viewport.cvRef;
const zoom = viewport.zoom;
const isAutoFit = viewport.isAutoFit;
const graphNodes = viewport.graphNodes;
const legendTypes = viewport.legendTypes;
const visibleNodes = viewport.visibleNodes;
const visibleEdges = viewport.visibleEdges;
const nmap = viewport.nmap;
const bounds = viewport.bounds;
const fitView = viewport.fitView;
const onWheel = viewport.onWheel;
const focusNode = viewport.focusNode;
const onScroll = viewport.onScroll;

/* ── 搜索（useGraphSearch composable） ── */
const search = useGraphSearch({
  nodes: () => props.nodes,
  selectNode: (id) => emit('select', id),
  focusNode: (id) => focusNode(id),
});
const searchRef = search.searchRef;
const searchQuery = search.searchQuery;
const searchIdx = search.searchIdx;
const searchMatches = search.searchMatches;
const jumpToNext = search.jumpToNext;
const jumpToPrev = search.jumpToPrev;
const clearSearch = search.clearSearch;
const isSearchMatch = search.isSearchMatch;
const isCurrentSearchTarget = search.isCurrentSearchTarget;

/* ── 节点着色 / 类型筛选（useNodeColoring） ── */
const coloring = useNodeColoring({
  getNodes: () => props.nodes,
  getEdges: () => props.edges,
  getSelId: () => props.selId,
  getDiffHighlight: () => props.diffHighlight,
  getNmap: () => nmap.value,
});
const typeFilter = coloring.typeFilter;
const toggleTypeFilter = coloring.toggleTypeFilter;
const toggleEdgeFilter = coloring.toggleEdgeFilter;
const matchesFilter = coloring.matchesFilter;
const edgeMatchesFilter = coloring.edgeMatchesFilter;
const heatmapMode = coloring.heatmapMode;
const heatColor = coloring.heatColor;
const diffColor = coloring.diffColor;
const getT = coloring.getT;
const neighborIds = coloring.neighborIds;

/* ── 节点拖拽（useNodeDrag，drag 状态在此 composable 内）── */
const drag = useNodeDrag({
  getZoom: () => zoom.value,
  getNmap: () => nmap.value,
  multiSel,
  getReadonly: () => props.readonly,
  setAutoFit: (v) => { isAutoFit.value = v; },
  emitSelect: (id) => emit('select', id),
  emitMove: (id, x, y) => emit('move', id, x, y),
  emitDragStart: (id) => emit('drag-start', id),
});
const startDrag = drag.startDrag;

/* ── 框选（useBoxSelect，boxSel 状态在此 composable 内）── */
const box = useBoxSelect({
  getZoom: () => zoom.value,
  getCvEl: () => cvRef.value,
  multiSel,
  getNodes: () => props.nodes,
  getReadonly: () => props.readonly,
});
const boxSel = box.boxSel;

/* ── 右键菜单 / 添加表单（useCanvasContextMenu） ── */
const menu = useCanvasContextMenu({
  getZoom: () => zoom.value,
  getCvEl: () => cvRef.value,
  getNodes: () => props.nodes,
  multiSel,
  getReadonly: () => props.readonly,
  emitSelect: (id) => emit('select', id),
  emitPredictFrom: (id) => emit('predict-from', id),
  emitEditNode: (id) => emit('edit-node', id),
  emitExplainNode: (id) => emit('explain-node', id),
  emitDeleteNode: (id) => emit('delete-node', id),
  emitDeleteNodes: (ids) => emit('delete-nodes', ids),
  emitAddNode: (payload) => emit('add-node', payload),
  emitAddEdges: (payload) => emit('add-edges', payload),
});
const ctxMenu = menu.ctxMenu;
const addNodeForm = menu.addNodeForm;
const addNodeInputRef = menu.addNodeInputRef;
const onNodeContext = menu.onNodeContext;
const onCanvasContext = menu.onCanvasContext;
const closeCtx = menu.closeCtx;
const triggerPredict = menu.triggerPredict;
const triggerEdit = menu.triggerEdit;
const triggerExplain = menu.triggerExplain;
const ctxNodeIsPredicted = menu.ctxNodeIsPredicted;
const triggerDelete = menu.triggerDelete;
const triggerBatchDelete = menu.triggerBatchDelete;
const submitAddNode = menu.submitAddNode;
const toggleAddNodeInput = menu.toggleAddNodeInput;
const toggleAddNodeOutput = menu.toggleAddNodeOutput;

/**
 * 在画布空白处按下:进入平移或框选模式。
 * 入口分派:先尝试框选(Shift+非只读),否则交给 viewport 平移。
 */
const startPan = (e: MouseEvent) => {
  // 画布背景按下：开启新一轮交互，清掉上一次节点按下遗留的"吞 click"标志
  suppressCanvasClick = false;
  // 点击落在节点 / 浮层上时不进入 pan，让对应控件优先响应
  if ((e.target as HTMLElement).closest('.node') || (e.target as HTMLElement).closest('.hud-overlay')) {
    return;
  }
  isAutoFit.value = false;

  // Shift + 拖拽：框选模式（只读时禁用）
  if (box.tryStart(e)) return;

  // 普通：交给 viewport 记录滚动起点 + 鼠标起点
  viewport.startPan(e);
};

/**
 * 节点按下入口：先打标志再交给拖拽逻辑。
 * <p>选中节点会让底部信息面板展开，画布随之回流、节点上移脱离指针，导致 mouseup
 * 落在画布背景上——浏览器据此把这次 click 派发到 .graph-canvas（节点的 @click.stop
 * 拦不到），清空选中造成"弹窗一闪而过"。用一次性标志吞掉这次画布 click。
 */
let suppressCanvasClick = false;
const onNodeMousedown = (e: MouseEvent, id: string) => {
  suppressCanvasClick = true;
  startDrag(e, id);
};
const onCanvasClick = () => {
  if (suppressCanvasClick) { suppressCanvasClick = false; return; }
  closeCtx();
  multiSel.clear();
  emit('select', null);
};

let ro: ResizeObserver | null = null;

/**
 * 唯一的 window mousemove 分派:按原始优先级顺序 框选 → 拖拽 → 平移。
 * 各 handler 内部自行判断对应状态是否激活,返回 true 表示已处理应 return。
 */
const onWindowMouseMove = (e: MouseEvent) => {
  if (box.handleMouseMove(e)) return;
  if (drag.handleMouseMove(e)) return;
  viewport.handlePanMove(e);
};

/**
 * 唯一的 window mouseup 分派:框选结束优先填充 multiSel;否则结束拖拽与平移。
 */
const onWindowMouseUp = () => {
  if (box.handleMouseUp()) return;
  drag.end();
  viewport.endPan();
};

/* Ctrl+F 快捷键唤起搜索框 */
const onSearchKeydown = (e: KeyboardEvent) => {
  if ((e.ctrlKey || e.metaKey) && e.key === 'f') {
    if (props.readonly) return;
    e.preventDefault();
    searchRef.value?.focus();
  }
};

/* Delete 键批量删除选中节点 */
const onDeleteKey = (e: KeyboardEvent) => {
  if (e.key === 'Delete' && multiSel.size > 0 && !props.readonly) {
    // 避免在输入框内触发误删（用户正在编辑文本）
    const t = e.target as HTMLElement | null;
    if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) return;
    emit('delete-nodes', [...multiSel]);
    multiSel.clear();
  }
};

onMounted(() => {
  // 拖拽 / 平移用 window 级事件，保证鼠标移出画布外仍能跟踪
  window.addEventListener('mousemove', onWindowMouseMove);
  window.addEventListener('mouseup', onWindowMouseUp);
  window.addEventListener('keydown', onSearchKeydown);
  window.addEventListener('keydown', onDeleteKey);
  cvRef.value?.addEventListener('scroll', onScroll, { passive: true });

  // 容器尺寸变化（如折叠侧栏）时若处于自适应模式自动重排
  ro = new ResizeObserver(() => {
    window.requestAnimationFrame(() => {
      if (isAutoFit.value) {
        fitView();
      }
    });
  });
  if (cvRef.value) {
    ro.observe(cvRef.value);
  }

  // 等首帧布局完成再 fitView，确保 clientWidth 已可用
  setTimeout(fitView, 50);
});

onUnmounted(() => {
  window.removeEventListener('mousemove', onWindowMouseMove);
  window.removeEventListener('mouseup', onWindowMouseUp);
  window.removeEventListener('keydown', onSearchKeydown);
  window.removeEventListener('keydown', onDeleteKey);
  cvRef.value?.removeEventListener('scroll', onScroll);
  viewport.cancelScrollRaf();
  ro?.disconnect();
  ro = null;
});

// 暴露给父组件：fitView 在推演完成等场景手动调用，focusNode 给节点定位用
defineExpose({ fitView, focusNode });
</script>

<template>
  <div class="graph-wrapper" style="position: relative; flex: 1; overflow: hidden; display: flex; background: transparent;">
    <div ref="cvRef" class="graph-canvas" style="flex: 1; overflow: auto; min-width: 0; position: relative;" @mousedown="startPan" @wheel="onWheel" @click="onCanvasClick" @contextmenu="onCanvasContext">
      <div :style="{ width: Math.max(3000, bounds.w * zoom) + 'px', height: Math.max(3000, bounds.h * zoom) + 'px', position: 'relative' }">
        <div class="scale-container" :style="{ transform: `scale(${zoom})`, transformOrigin: '0 0', width: '3000px', height: '3000px', position: 'absolute', top: 0, left: 0 }">
          <svg style="position:absolute;top:0;left:0;width:100%;height:100%;pointer-events:none;overflow:visible">
            <defs>
              <marker id="arr" markerWidth="8" markerHeight="8" refX="28" refY="4" orient="auto">
                <path d="M0,0 L0,8 L8,4 Z" fill="rgba(255, 255, 255, 0.4)"/>
              </marker>
              <marker id="arr-r" markerWidth="8" markerHeight="8" refX="28" refY="4" orient="auto">
                <path d="M0,0 L0,8 L8,4 Z" fill="#ff3399"/>
              </marker>
              <marker id="arr-s" markerWidth="8" markerHeight="8" refX="28" refY="4" orient="auto">
                <path d="M0,0 L0,8 L8,4 Z" fill="#3d9bff"/>
              </marker>
              <marker id="arr-p" markerWidth="8" markerHeight="8" refX="28" refY="4" orient="auto">
                <path d="M0,0 L0,8 L8,4 Z" fill="#fbbf24"/>
              </marker>
            </defs>
            <g>
              <template v-for="e in visibleEdges" :key="e.id">
                <g v-if="nmap[e.from] && nmap[e.to]" :style="{ opacity: !edgeMatchesFilter(e) ? 0.15 : (selId && selId !== e.from && selId !== e.to ? 0.25 : 1), transition: 'opacity .2s' }">
                  <!-- 不可见点击热区：左键查看抽屉，右键编辑输入输出 -->
                  <path v-if="!readonly" :d="getPath(nmap[e.from], nmap[e.to]).d" fill="none" stroke="transparent" stroke-width="14" style="pointer-events: stroke; cursor: pointer;" @click.stop="emit('select-edge', e.id)" @contextmenu.prevent.stop="emit('edit-edge-relation', e.id)" />
                  <path v-if="selId === e.from || selId === e.to" :d="getPath(nmap[e.from], nmap[e.to]).d" fill="none" :stroke="e.source === 'predicted' ? '#fbbf24' : (e.rule_driven ? '#ff3399' : '#3d9bff')" :stroke-width="8" opacity="0.1"/>
                  <path :d="getPath(nmap[e.from], nmap[e.to]).d" fill="none"
                        :stroke="e.source === 'predicted' ? '#fbbf24' : (e.rule_driven ? (selId === e.from || selId === e.to ? '#ff3399' : 'rgba(255, 51, 153, 0.4)') : (selId === e.from || selId === e.to ? '#3d9bff' : 'rgba(255, 255, 255, 0.3)'))"
                        :stroke-width="selId === e.from || selId === e.to ? 2 : 1.4"
                        :stroke-dasharray="e.source === 'predicted' ? '6 4' : null"/>
                  <path v-if="e.source !== 'predicted'" :class="selId === e.from || selId === e.to ? 'line-flow-fast' : 'line-flow'" :d="getPath(nmap[e.from], nmap[e.to]).d" fill="none" :stroke="e.rule_driven ? (selId === e.from || selId === e.to ? '#ff80bf' : 'rgba(255, 51, 153, 0.6)') : (selId === e.from || selId === e.to ? '#9ecbff' : 'rgba(61, 155, 255, 0.55)')" :stroke-width="selId === e.from || selId === e.to ? 2.5 : 1.5"/>
                  <g v-if="e.label">
                    <rect :x="getPath(nmap[e.from], nmap[e.to]).mx - 20" :y="getPath(nmap[e.from], nmap[e.to]).my - 17" width="40" height="14" rx="3" fill="#050810" opacity="0.85"/>
                    <text :x="getPath(nmap[e.from], nmap[e.to]).mx" :y="getPath(nmap[e.from], nmap[e.to]).my - 6" text-anchor="middle" :style="{fill: e.source === 'predicted' ? '#fbbf24' : (e.rule_driven ? '#ff3399' : (selId === e.from || selId === e.to ? '#7ab8f0' : 'rgba(197, 216, 235, 0.65)')), fontSize: '9.5px', fontFamily: 'JetBrains Mono', fontWeight: 500}">
                      <tspan v-if="e.source === 'predicted'">◇</tspan><tspan v-else-if="e.rule_driven">⚡</tspan>{{ e.label }}
                    </text>
                  </g>
                </g>
              </template>
            </g>
          </svg>

          <div class="graph-root" style="transform:none;">
            <div v-for="n in visibleNodes" :key="n.id"
                :class="['node', { 'node-new': n.isNew, 'node-sel': selId === n.id, 'node-multi-sel': multiSel.has(n.id), 'node-predicted': n.source === 'predicted', 'node-dim': !matchesFilter(n) || (searchQuery && !isSearchMatch(n)), 'node-neighbor-dim': !typeFilter && !searchQuery && neighborIds && !neighborIds.has(n.id), 'node-neighbor-hl': !typeFilter && !searchQuery && neighborIds && neighborIds.has(n.id) && selId !== n.id, 'node-hl': typeFilter && matchesFilter(n), 'node-search-current': isCurrentSearchTarget(n) }]"
                :style="{
                  left: n.x + 'px', top: n.y + 'px', width: NW + 'px',
                  background: diffColor(n) || heatColor(n) || getT(n).bg, borderLeftColor: getT(n).color,
                  borderTopColor: (selId === n.id || (typeFilter && matchesFilter(n)) || (neighborIds && neighborIds.has(n.id) && selId !== n.id)) ? getT(n).color + '44' : '#111c2c',
                  borderRightColor: (selId === n.id || (typeFilter && matchesFilter(n)) || (neighborIds && neighborIds.has(n.id) && selId !== n.id)) ? getT(n).color + '44' : '#111c2c',
                  borderBottomColor: (selId === n.id || (typeFilter && matchesFilter(n)) || (neighborIds && neighborIds.has(n.id) && selId !== n.id)) ? getT(n).color + '44' : '#111c2c',
                  boxShadow: (selId === n.id || (typeFilter && matchesFilter(n))) ? `0 0 0 1px ${getT(n).color}55,0 4px 24px ${getT(n).color}33` : (neighborIds && neighborIds.has(n.id) && selId !== n.id) ? `0 0 0 1px ${getT(n).color}44,0 2px 12px ${getT(n).color}22` : '0 2px 8px rgba(0,0,0,0.4)'
                }"
                @mousedown="e => onNodeMousedown(e, n.id)" @contextmenu="e => onNodeContext(e, n.id)" @dblclick.stop="emit('edit-node', n.id)" @click.stop>
              <div class="node-dot" :style="{ background: getT(n).color, boxShadow: selId === n.id ? `0 0 6px ${getT(n).color}88` : '' }"/>
              <div class="node-label">{{ n.label }}</div>
              <div class="node-type">{{ getT(n).label }}</div>
              <div v-if="n.source === 'predicted'" class="node-pred-badge"
                   :title="'置信度: ' + Math.round((n.confidence || 0) * 100) + '% | 有效概率: ' + Math.round((n.effectiveProbability || 0) * 100) + '%'">
                {{ Math.round((n.effectiveProbability || n.confidence || 0) * 100) }}%
              </div>
              <div v-if="(n.constraints?.length || 0) > 0" class="node-lock-badge" :title="n.constraints.map((c: any) => (c.kind || '约束') + ': ' + c.note).join('\n')">🔒</div>
            </div>
          </div>

          <!-- 框选矩形视觉反馈 -->
          <div v-if="boxSel" class="box-select" :style="{
            left: Math.min(boxSel.sx, boxSel.cx) + 'px',
            top: Math.min(boxSel.sy, boxSel.cy) + 'px',
            width: Math.abs(boxSel.cx - boxSel.sx) + 'px',
            height: Math.abs(boxSel.cy - boxSel.sy) + 'px'
          }"></div>
        </div>
      </div>
    </div>

    <!-- Right-click context menu -->
    <div v-if="ctxMenu" class="node-ctx-menu" :style="{ left: ctxMenu.x + 'px', top: ctxMenu.y + 'px' }" @click.stop>
      <button class="ctx-item" @click="triggerPredict">
        <span class="ctx-icon">⚡</span>
        <span>从此推演</span>
        <span class="ctx-hint">Forward</span>
      </button>
      <!-- P1-7：仅对推演节点显示"为什么" -->
      <button v-if="ctxNodeIsPredicted" class="ctx-item" @click="triggerExplain">
        <span class="ctx-icon">🔍</span>
        <span>为什么会发生？</span>
        <span class="ctx-hint">Explain</span>
      </button>
      <div class="ctx-sep"></div>
      <button class="ctx-item" @click="triggerEdit">
        <span class="ctx-icon">✎</span>
        <span>编辑节点</span>
      </button>
      <button v-if="multiSel.size > 1" class="ctx-item ctx-danger" @click="triggerBatchDelete">
        <span class="ctx-icon">✕</span>
        <span>删除选中 ({{ multiSel.size }})</span>
        <span class="ctx-hint">Delete</span>
      </button>
      <button v-else class="ctx-item ctx-danger" @click="triggerDelete">
        <span class="ctx-icon">✕</span>
        <span>删除节点</span>
      </button>
    </div>

    <!-- 添加对象/关系表单 (画布空白处右键) -->
    <Teleport to="body">
    <div v-if="addNodeForm" class="modal-mask" @click.self="addNodeForm = null">
      <div class="add-node-dialog" @click.stop>
        <!-- 模式切换 -->
        <div class="anp-mode-tabs">
          <button :class="['anp-mode-tab', { active: addNodeForm.mode === 'object' }]" @click="addNodeForm.mode = 'object'">添加对象</button>
          <button :class="['anp-mode-tab', { active: addNodeForm.mode === 'relation' }]" @click="addNodeForm.mode = 'relation'">添加关系</button>
        </div>

        <!-- 对象模式 -->
        <template v-if="addNodeForm.mode === 'object'">
          <label class="anp-label">名称
            <input ref="addNodeInputRef" v-model="addNodeForm.label" class="edit-input" placeholder="输入对象名称…" @keydown.enter="submitAddNode" @keydown.escape="addNodeForm = null" />
          </label>
          <div v-if="graphNodes.length > 0" class="anp-section">
            <div class="anp-section-title">输入连接 <span class="anp-hint">（从哪些节点连入）</span></div>
            <div class="anp-node-list">
              <div v-for="n in graphNodes" :key="'in-'+n.id" class="anp-node-option">
                <label class="anp-check-label" @click.prevent="toggleAddNodeInput(n.id)">
                  <span :class="['anp-checkbox', { checked: addNodeForm.inputs.includes(n.id) }]">
                    <span v-if="addNodeForm.inputs.includes(n.id)" class="anp-check-mark">✓</span>
                  </span>
                  <span class="anp-node-dot" :style="{ background: getT(n).color }"></span>
                  <span class="anp-node-name">{{ n.label }}</span>
                </label>
                <input v-if="addNodeForm.inputs.includes(n.id)" v-model="addNodeForm.inputLabels[n.id]" class="anp-edge-label" placeholder="关系名称" @click.stop />
              </div>
            </div>
          </div>
          <div v-if="graphNodes.length > 0" class="anp-section">
            <div class="anp-section-title">输出连接 <span class="anp-hint">（连向哪些节点）</span></div>
            <div class="anp-node-list">
              <div v-for="n in graphNodes" :key="'out-'+n.id" class="anp-node-option">
                <label class="anp-check-label" @click.prevent="toggleAddNodeOutput(n.id)">
                  <span :class="['anp-checkbox', { checked: addNodeForm.outputs.includes(n.id) }]">
                    <span v-if="addNodeForm.outputs.includes(n.id)" class="anp-check-mark">✓</span>
                  </span>
                  <span class="anp-node-dot" :style="{ background: getT(n).color }"></span>
                  <span class="anp-node-name">{{ n.label }}</span>
                </label>
                <input v-if="addNodeForm.outputs.includes(n.id)" v-model="addNodeForm.outputLabels[n.id]" class="anp-edge-label" placeholder="关系名称" @click.stop />
              </div>
            </div>
          </div>
          <div class="edit-actions">
            <button class="edit-cancel" @click="addNodeForm = null">取消</button>
            <button class="edit-save" @click="submitAddNode" :disabled="!addNodeForm.label.trim()">添加</button>
          </div>
        </template>

        <!-- 关系模式 -->
        <template v-else>
          <label class="anp-label">关系名称
            <input ref="addNodeInputRef" v-model="addNodeForm.label" class="edit-input" placeholder="输入关系名称（如：拥有、影响）…" @keydown.enter="submitAddNode" @keydown.escape="addNodeForm = null" />
          </label>
          <div v-if="graphNodes.length > 0" class="anp-section">
            <div class="anp-section-title">输入节点 <span class="anp-hint">（关系的起始节点，可多选）</span></div>
            <div class="anp-node-list">
              <div v-for="n in graphNodes" :key="'rin-'+n.id" class="anp-node-option">
                <label class="anp-check-label" @click.prevent="toggleAddNodeInput(n.id)">
                  <span :class="['anp-checkbox', { checked: addNodeForm.inputs.includes(n.id) }]">
                    <span v-if="addNodeForm.inputs.includes(n.id)" class="anp-check-mark">✓</span>
                  </span>
                  <span class="anp-node-dot" :style="{ background: getT(n).color }"></span>
                  <span class="anp-node-name">{{ n.label }}</span>
                </label>
              </div>
            </div>
          </div>
          <div v-if="graphNodes.length > 0" class="anp-section">
            <div class="anp-section-title">输出节点 <span class="anp-hint">（关系的目标节点，可多选）</span></div>
            <div class="anp-node-list">
              <div v-for="n in graphNodes" :key="'rout-'+n.id" class="anp-node-option">
                <label class="anp-check-label" @click.prevent="toggleAddNodeOutput(n.id)">
                  <span :class="['anp-checkbox', { checked: addNodeForm.outputs.includes(n.id) }]">
                    <span v-if="addNodeForm.outputs.includes(n.id)" class="anp-check-mark">✓</span>
                  </span>
                  <span class="anp-node-dot" :style="{ background: getT(n).color }"></span>
                  <span class="anp-node-name">{{ n.label }}</span>
                </label>
              </div>
            </div>
          </div>
          <div v-if="graphNodes.length === 0" class="anp-empty-hint">请先添加对象节点后再创建关系</div>
          <div class="edit-actions">
            <button class="edit-cancel" @click="addNodeForm = null">取消</button>
            <button class="edit-save" @click="submitAddNode" :disabled="addNodeForm.inputs.length === 0 || addNodeForm.outputs.length === 0">添加</button>
          </div>
        </template>
      </div>
    </div>
    </Teleport>

    <div class="hud-overlay" style="position: absolute; top: 0; left: 0; width: 100%; height: 100%; pointer-events: none;">
      <div class="search-bar" v-if="!readonly" style="pointer-events: auto;">
        <input
          ref="searchRef"
          v-model="searchQuery"
          class="search-input"
          type="text"
          placeholder="搜索节点名称或类型…"
          @keydown.enter="jumpToNext"
          @keydown.escape="clearSearch"
        />
        <span v-if="searchQuery && searchMatches.length" class="search-count">
          {{ searchIdx + 1 }}/{{ searchMatches.length }}
        </span>
        <button v-if="searchQuery" class="search-btn" @click="jumpToNext" title="下一个 (Enter)">↓</button>
        <button v-if="searchQuery" class="search-btn" @click="jumpToPrev" title="上一个">↑</button>
        <button v-if="searchQuery" class="search-btn" @click="clearSearch" title="清除">✕</button>
      </div>
      <div v-if="!readonly" class="canvas-actions" style="position: absolute; top: 24px; left: 50%; transform: translateX(-50%); pointer-events: auto; display: flex; gap: 8px; background: rgba(15, 23, 42, 0.6); backdrop-filter: blur(12px); padding: 6px 8px; border-radius: 12px; border: 1px solid rgba(255, 255, 255, 0.1); box-shadow: 0 4px 16px rgba(0,0,0,0.2);">
        <button class="ca-btn" @click="fitView"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M15 3h6v6M9 21H3v-6M21 3l-7 7M3 21l7 7"/></svg>适应屏幕</button>
        <button class="ca-btn" @click="emit('auto-layout')"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v3M21 16v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-3M4 12h16"/></svg>自动布局</button>
        <button class="ca-btn" @click="emit('toggle-layout-direction')" :title="layoutDirection === 'LR' ? '当前从左向右,点击改为从上到下' : '当前从上到下,点击改为从左向右'">
          <svg v-if="layoutDirection === 'LR'" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="4" y1="12" x2="20" y2="12"/><polyline points="14 6 20 12 14 18"/></svg>
          <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="4" x2="12" y2="20"/><polyline points="6 14 12 20 18 14"/></svg>
          {{ layoutDirection === 'LR' ? '从左向右' : '从上到下' }}
        </button>
        <button
          class="ca-btn"
          :class="{ 'ca-active': heatmapMode }"
          @click="heatmapMode = !heatmapMode"
          :title="heatmapMode
            ? '已开启概率色阶:推演节点按置信度着色(绿 高 → 黄 中 → 红 低)。点击关闭。'
            : '概率色阶:开启后将推演节点按置信度着色(绿 高 → 黄 中 → 红 低),方便快速识别可信度。'"
        >
          <svg class="ca-heat-icon" viewBox="0 0 24 24" width="22" height="10" fill="none" aria-hidden="true">
            <circle cx="5"  cy="12" r="3.2" fill="#42b883"/>
            <circle cx="12" cy="12" r="3.2" fill="#fbbf24"/>
            <circle cx="19" cy="12" r="3.2" fill="#ef4444"/>
          </svg>
          概率色阶
        </button>
        <button v-if="diffHighlight" class="ca-btn ca-active" @click="emit('clear-diff')" title="清除对比高亮">
          ✕ 对比
        </button>
        <button class="ca-btn" @click="emit('clear')"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>清空画布</button>
        <span class="ca-sep" aria-hidden="true"></span>
        <button class="ca-btn ca-icon" :disabled="!canUndo" @click="emit('undo')" title="撤销 (Ctrl+Z)">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 7v6h6"/><path d="M21 17a9 9 0 0 0-15-6.7L3 13"/></svg>
        </button>
        <button class="ca-btn ca-icon" :disabled="!canRedo" @click="emit('redo')" title="重做 (Ctrl+Shift+Z)">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 7v6h-6"/><path d="M3 17a9 9 0 0 1 15-6.7L21 13"/></svg>
        </button>
      </div>

      <div class="zoom-wrap" style="position: absolute; bottom: 24px; left: 24px; pointer-events: auto;">
        <button @click="zoom = Math.min(3, zoom * 1.2); isAutoFit = false;">+</button>
        <button @click="zoom = Math.max(0.1, zoom * 0.85); isAutoFit = false;">−</button>
      </div>
      <span v-if="props.nodes.length >= 80" class="perf-indicator">
        {{ visibleNodes.length }}/{{ props.nodes.length }} 节点可见
      </span>

      <div class="legend" style="position: absolute; top: 24px; right: 24px; pointer-events: auto;">
        <!-- 静态本体层图例: 类/关系/(约束)。类按 NT 里出现的类型分色,关系/约束是固定标识。 -->
        <div v-for="(t, k) in legendTypes" :key="k"
             :class="['legend-row', { 'legend-row-active': typeFilter === k, 'legend-row-inactive': typeFilter && typeFilter !== k }]"
             :title="typeFilter === k ? '点击取消筛选' : '点击仅显示' + (t as any).label"
             @click.stop="toggleTypeFilter(k as string)">
          <div class="legend-sq" :style="{ background: (t as any).color, boxShadow: typeFilter === k ? `0 0 0 2px ${(t as any).color}66` : 'none' }"/>
          <span>{{ (t as any).label }}</span>
        </div>
        <div class="legend-sep"></div>
        <div :class="['legend-row', { 'legend-row-active': typeFilter === '_edge_', 'legend-row-inactive': typeFilter && typeFilter !== '_edge_' }]"
             :title="typeFilter === '_edge_' ? '点击取消筛选' : '点击仅显示关系'"
             @click.stop="toggleEdgeFilter">
          <svg class="legend-arrow" width="18" height="10" viewBox="0 0 18 10">
            <line x1="1" y1="5" x2="14" y2="5" stroke="#22dd88" stroke-width="1.6"/>
            <path d="M14,1 L17,5 L14,9 Z" fill="#22dd88"/>
          </svg>
          <span>关系</span>
        </div>
      </div>
    </div>
  </div>
</template>
