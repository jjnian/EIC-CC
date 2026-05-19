<script setup lang="ts">
import { ref, computed } from 'vue';

const props = defineProps<{
  branches: any[];
  activeBranchId: string;
}>();

const emit = defineEmits<{
  (e: 'switch', id: string): void;
  (e: 'delete', id: string): void;
  (e: 'migrate'): void;
}>();

// 检测是否还有 v0.5/0.6 老格式分支（没有 dag 字段）
const hasLegacy = computed(() => props.branches.some(b => !b.dag));

const open = ref(false);

const fmtTime = (ts: number) => {
  if (!ts) return '';
  const d = new Date(ts);
  return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
};

const branchIntent = (b: any): 'forward' | 'backward' =>
  (b.intent || b.dag?.intent) === 'backward' ? 'backward' : 'forward';

const branchStepsCount = (b: any) => b.chain?.length || b.dag?.chain?.length || 0;

// v0.8：分支按祖先 → 后代树形展开。返回 [{ branch, depth }] DFS 顺序
const branchTree = computed(() => {
  const byId = new Map<string, any>();
  for (const b of props.branches) byId.set(b.id, b);
  const childrenOf = new Map<string | null, any[]>();
  for (const b of props.branches) {
    const k = b.parentBranchId || null;
    if (!childrenOf.has(k)) childrenOf.set(k, []);
    childrenOf.get(k)!.push(b);
  }
  // 排序：每层按 createdAt 倒序（最新在上）
  for (const arr of childrenOf.values()) arr.sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));

  // 根：parentBranchId 缺失或指向不存在的分支（容错）都视为根
  const roots: any[] = [];
  for (const b of props.branches) {
    if (!b.parentBranchId || !byId.has(b.parentBranchId)) roots.push(b);
  }
  roots.sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));

  const out: { branch: any; depth: number }[] = [];
  const visited = new Set<string>();
  const walk = (b: any, depth: number) => {
    if (visited.has(b.id)) return;
    visited.add(b.id);
    out.push({ branch: b, depth });
    const kids = childrenOf.get(b.id) || [];
    for (const k of kids) walk(k, depth + 1);
  };
  for (const r of roots) walk(r, 0);
  // 兜底：若有循环引用未访问到的，也追加在末尾
  for (const b of props.branches) if (!visited.has(b.id)) walk(b, 0);
  return out;
});

const descendantCount = (id: string) => {
  let n = 0;
  const stack = [id];
  const seen = new Set<string>();
  while (stack.length) {
    const cur = stack.pop()!;
    for (const b of props.branches) {
      if (b.parentBranchId === cur && !seen.has(b.id)) {
        seen.add(b.id);
        n++;
        stack.push(b.id);
      }
    }
  }
  return n;
};

const onDelete = (id: string, e: Event) => {
  e.stopPropagation();
  const kids = descendantCount(id);
  const msg = kids > 0
    ? `删除此推演分支将级联删除 ${kids} 个子分支。是否继续？此操作不可恢复。`
    : '删除此推演分支？此操作不可恢复。';
  if (!confirm(msg)) return;
  emit('delete', id);
};
</script>

<template>
  <div class="bp-wrap">
    <button class="bp-btn" :class="{ 'bp-btn-pred': activeBranchId !== 'trunk' }" @click="open = !open">
      <span class="bp-icon">{{ activeBranchId === 'trunk' ? '◈' : '⚡' }}</span>
      <span class="bp-name">{{
        activeBranchId === 'trunk' ? '主分支' :
          (branches.find(b => b.id === activeBranchId)?.name || '推演分支')
      }}</span>
      <span class="bp-count" v-if="branches.length > 0">{{ branches.length }}</span>
      <svg viewBox="0 0 24 24" width="12" height="12" stroke="currentColor" stroke-width="2" fill="none"><polyline points="6 9 12 15 18 9"/></svg>
    </button>

    <div v-if="open" class="bp-overlay" @click="open = false" />

    <div v-if="open" class="bp-dropdown">
      <div class="bp-section-label">分支</div>
      <div class="bp-item" :class="{ active: activeBranchId === 'trunk' }" @click="emit('switch', 'trunk'); open = false">
        <span class="bp-item-icon">◈</span>
        <div class="bp-item-info">
          <div class="bp-item-name">主分支</div>
          <div class="bp-item-sub">原始本体图</div>
        </div>
        <span v-if="activeBranchId === 'trunk'" class="bp-check">✓</span>
      </div>

      <template v-if="branches.length">
        <div class="bp-section-label">推演 ({{ branches.length }})</div>
        <div v-for="item in branchTree" :key="item.branch.id"
             class="bp-item bp-item-pred"
             :class="{ active: activeBranchId === item.branch.id, 'bp-item-back': branchIntent(item.branch) === 'backward' }"
             :style="{ paddingLeft: (12 + item.depth * 14) + 'px' }"
             @click="emit('switch', item.branch.id); open = false">
          <span v-if="item.depth > 0" class="bp-tree-rail">⤷</span>
          <span class="bp-item-icon">{{ branchIntent(item.branch) === 'backward' ? '←' : '→' }}</span>
          <div class="bp-item-info">
            <div class="bp-item-name">{{ item.branch.name }}</div>
            <div class="bp-item-sub">
              {{ fmtTime(item.branch.createdAt) }} ·
              {{ branchIntent(item.branch) === 'backward' ? '溯因' : '前向' }} ·
              {{ branchStepsCount(item.branch) }} 步
            </div>
          </div>
          <button class="bp-del" @click="onDelete(item.branch.id, $event)" title="删除">×</button>
          <span v-if="activeBranchId === item.branch.id" class="bp-check">✓</span>
        </div>
      </template>

      <div v-else class="bp-empty">暂无推演分支<br/>右键节点 → 「从此推演」生成</div>

      <div v-if="hasLegacy" class="bp-footer">
        <button class="bp-migrate" @click="emit('migrate'); open = false" title="把 v0.5/0.6 老分支升级为 v0.9 delta 格式">
          ⚙ 升级旧分支到 v0.9
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.bp-wrap { position: relative; }
.bp-btn {
  display: flex; align-items: center; gap: 8px;
  background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.1);
  color: var(--text-main);
  padding: 6px 12px;
  border-radius: 10px;
  font-size: 13px;
  cursor: pointer;
  font-family: inherit;
  transition: all 0.15s;
}
.bp-btn:hover { background: rgba(255,255,255,0.12); }
.bp-btn-pred {
  background: rgba(251, 191, 36, 0.15);
  border-color: rgba(251, 191, 36, 0.35);
  color: #fbbf24;
}
.bp-btn-pred:hover { background: rgba(251, 191, 36, 0.22); }
.bp-icon { font-size: 12px; }
.bp-name { max-width: 160px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 500; }
.bp-count {
  background: rgba(255,255,255,0.12);
  padding: 1px 7px;
  border-radius: 100px;
  font-size: 10px;
  font-family: 'JetBrains Mono', monospace;
}
.bp-btn-pred .bp-count { background: rgba(251, 191, 36, 0.25); }
.bp-overlay { position: fixed; inset: 0; z-index: 90; }
.bp-dropdown {
  position: absolute;
  top: calc(100% + 6px);
  left: 0;
  min-width: 280px;
  max-width: 360px;
  max-height: 400px;
  overflow-y: auto;
  background: rgba(14, 25, 41, 0.98);
  backdrop-filter: blur(16px);
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 12px;
  padding: 6px;
  z-index: 100;
  box-shadow: 0 12px 36px rgba(0, 0, 0, 0.5);
}
.bp-section-label {
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 1px;
  color: rgba(255,255,255,0.35);
  padding: 8px 12px 4px;
  font-family: 'Inter', sans-serif;
}
.bp-item {
  display: flex; align-items: center; gap: 10px;
  padding: 8px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.12s;
  position: relative;
}
.bp-item:hover { background: rgba(255,255,255,0.06); }
.bp-item.active { background: rgba(66, 184, 131, 0.12); }
.bp-item-pred.active { background: rgba(251, 191, 36, 0.15); }
.bp-item-back.active { background: rgba(99, 179, 237, 0.15); }
.bp-item-icon {
  font-size: 14px; color: var(--text-dim); flex-shrink: 0;
  font-family: 'JetBrains Mono', monospace; font-weight: 700;
}
.bp-tree-rail {
  font-size: 10px;
  color: rgba(255,255,255,0.25);
  flex-shrink: 0;
  margin-left: -4px;
  font-family: 'JetBrains Mono', monospace;
}
.bp-item-pred .bp-item-icon { color: #fbbf24; }
.bp-item-back .bp-item-icon { color: #63b3ed; }
.bp-item-back .bp-check { color: #63b3ed; }
.bp-item-info { flex: 1; min-width: 0; }
.bp-item-name {
  font-size: 13px; color: var(--text-main); font-weight: 500;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.bp-item-sub {
  font-size: 10px; color: var(--text-dim);
  font-family: 'JetBrains Mono', monospace;
  margin-top: 2px;
}
.bp-check { color: #42b883; font-size: 14px; flex-shrink: 0; }
.bp-item-pred .bp-check { color: #fbbf24; }
.bp-del {
  background: none; border: none; color: rgba(255,255,255,0.3);
  font-size: 16px; cursor: pointer; padding: 0 6px; line-height: 1;
}
.bp-del:hover { color: #ff8a8a; }
.bp-empty {
  padding: 20px 12px;
  text-align: center;
  font-size: 12px;
  color: rgba(255,255,255,0.4);
  line-height: 1.6;
}
.bp-footer {
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px solid rgba(255,255,255,0.06);
}
.bp-migrate {
  width: 100%;
  background: rgba(99, 179, 237, 0.08);
  border: 1px solid rgba(99, 179, 237, 0.2);
  color: #63b3ed;
  padding: 8px 12px;
  border-radius: 8px;
  cursor: pointer;
  font-family: inherit;
  font-size: 12px;
  transition: background 0.12s;
}
.bp-migrate:hover { background: rgba(99, 179, 237, 0.15); border-color: rgba(99, 179, 237, 0.35); }
</style>
