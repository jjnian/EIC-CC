<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import type { OntologyNode, OntologyEdge } from '../../types';
import { listExperiences, type Experience } from '../../api/experiences';
import { listExperienceFolders, type ExperienceFolder } from '../../api/experienceFolders';
import { Button } from '@/components/ui/button';

/**
 * 覆盖度审计：让「图够不够完整」第一次变得可度量、可驱动。
 * 把经验库（按文件夹=领域）与图（节点/边的 domain）对照，找出：
 * 无经验支撑的领域、来源单一的领域、推断占比过高的领域、孤立节点聚集区，
 * 并规则化生成「下一步调研任务清单」——完整性靠 收集→建图→找缺口→再收集 迭代出来。
 */

const props = defineProps<{
  nodes: OntologyNode[];
  edges: OntologyEdge[];
}>();

const emit = defineEmits<{
  (e: 'focus-node', id: string): void;
}>();

const SCHEMA_ONLY = new Set(['attribute', 'constraint']);
const UNDOMAIN = '未分域';

// ── 经验库数据（领域 = 经验所在文件夹名，与建图口径一致）──
const experiences = ref<Experience[]>([]);
const folders = ref<ExperienceFolder[]>([]);
const loading = ref(true);
const loadError = ref('');
onMounted(async () => {
  try {
    const [exps, fds] = await Promise.all([listExperiences(), listExperienceFolders()]);
    experiences.value = exps || [];
    folders.value = fds || [];
  } catch (err: any) {
    loadError.value = err?.message || '经验库加载失败';
  } finally {
    loading.value = false;
  }
});
const folderName = computed(() => {
  const m = new Map<string, string>();
  for (const f of folders.value) m.set(f.id, f.name);
  return m;
});
const expDomain = (e: Experience) =>
  (e.folderId && folderName.value.get(e.folderId)) || UNDOMAIN;

const ORIGIN_LABEL: Record<string, string> = {
  manual: '手写/对话', chat: '对话', upload: '文档', ddl: '库表结构',
  websystem: '接入系统', explore: '系统探索', websearch: '联网调研', datasource: '数据源',
};
const originLabel = (o?: string) => ORIGIN_LABEL[o || 'manual'] || o || '未知';

// ── 按领域聚合 ──────────────────────────────────────────
interface DomainStat {
  domain: string;
  nodeCount: number;
  edgeCount: number;
  inferredEdges: number;
  evidencedEdges: number;
  isolatedCount: number;
  expByOrigin: Record<string, number>;
  expTotal: number;
}

const stats = computed<DomainStat[]>(() => {
  const map = new Map<string, DomainStat>();
  const of = (d?: string) => {
    const key = (d || '').trim() || UNDOMAIN;
    let s = map.get(key);
    if (!s) {
      s = { domain: key, nodeCount: 0, edgeCount: 0, inferredEdges: 0,
            evidencedEdges: 0, isolatedCount: 0, expByOrigin: {}, expTotal: 0 };
      map.set(key, s);
    }
    return s;
  };

  const biz = props.nodes.filter(n => !SCHEMA_ONLY.has(n.type));
  const degree = new Map<string, number>();
  for (const e of props.edges) {
    degree.set(e.from, (degree.get(e.from) || 0) + 1);
    degree.set(e.to, (degree.get(e.to) || 0) + 1);
  }
  for (const n of biz) {
    const s = of(n.domain);
    s.nodeCount++;
    if (!degree.get(n.id)) s.isolatedCount++;
  }
  for (const e of props.edges) {
    const s = of(e.domain);
    s.edgeCount++;
    if (e.source === 'inferred') s.inferredEdges++;
    if ((e.evidence || '').trim() || (e.evidences || []).length) s.evidencedEdges++;
  }
  for (const exp of experiences.value) {
    const s = of(expDomain(exp));
    s.expTotal++;
    const o = exp.origin || 'manual';
    s.expByOrigin[o] = (s.expByOrigin[o] || 0) + 1;
  }
  return [...map.values()].sort((a, b) => b.nodeCount - a.nodeCount);
});

// ── 缺口规则 → 调研任务清单（纯规则,不依赖 LLM,永远可复现）──
interface Gap { domain: string; severity: 'warn' | 'info'; issue: string; action: string }

const ORIGIN_ADVICE: Record<string, string> = {
  ddl: '只有库表结构：字段会说话但业务不会——建议访谈该域负责人或跑一次系统探索，补业务语义',
  manual: '只有人工笔记/访谈：建议导出相关数据库结构，用外键/字段做数据佐证',
  chat: '只有对话建模：建议导出相关数据库结构，用外键/字段做数据佐证',
  upload: '只有文档：建议补库表结构或访谈交叉验证文档口径',
  websystem: '只有系统接入：建议实际运行一次系统探索产出业务说明',
  explore: '只有系统探索：界面反推的功能需访谈确认业务口径',
  websearch: '只有联网调研：公开资料非本企业事实，务必用内部来源（访谈/库表/探索）校对',
  datasource: '只有数据源文档：建议补访谈确认业务口径',
};

const gaps = computed<Gap[]>(() => {
  const out: Gap[] = [];
  for (const s of stats.value) {
    if (s.nodeCount > 0 && s.expTotal === 0 && s.domain !== UNDOMAIN) {
      out.push({ domain: s.domain, severity: 'warn',
        issue: `${s.nodeCount} 个节点没有任何经验支撑`,
        action: '该域可能来自导入/模板：补充访谈或文档，或核对经验文件夹命名是否与领域一致' });
    }
    if (s.expTotal > 0) {
      const kinds = Object.keys(s.expByOrigin);
      if (kinds.length === 1) {
        out.push({ domain: s.domain, severity: 'info',
          issue: `经验来源单一（仅${originLabel(kinds[0])} ×${s.expTotal}）`,
          action: ORIGIN_ADVICE[kinds[0]] || '建议补充第二类来源交叉验证' });
      }
    }
    if (s.edgeCount >= 5 && s.inferredEdges / s.edgeCount > 0.4) {
      out.push({ domain: s.domain, severity: 'warn',
        issue: `推断边占比 ${Math.round(s.inferredEdges / s.edgeCount * 100)}%（${s.inferredEdges}/${s.edgeCount}）`,
        action: '在「体检」跑一键数据佐证，或进「审核」队列人工把关' });
    }
    if (s.nodeCount >= 5 && s.isolatedCount / s.nodeCount > 0.2) {
      out.push({ domain: s.domain, severity: 'warn',
        issue: `孤立节点聚集（${s.isolatedCount}/${s.nodeCount}）`,
        action: '覆盖不足或抽取碎片化：补充该域经验后增量建图，或人工连边/清理噪声' });
    }
    if (s.edgeCount >= 5 && s.evidencedEdges / s.edgeCount < 0.5) {
      out.push({ domain: s.domain, severity: 'info',
        issue: `证据覆盖率仅 ${Math.round(s.evidencedEdges / s.edgeCount * 100)}%`,
        action: '无证据的边不可审计：优先数据佐证，其次人工审核补批注' });
    }
    if (s.nodeCount === 0 && s.expTotal > 0 && s.domain !== UNDOMAIN) {
      out.push({ domain: s.domain, severity: 'info',
        issue: `有 ${s.expTotal} 篇经验但图上没有该域节点`,
        action: '该域可能尚未参与建图：勾选这些经验做一次（增量）建图' });
    }
  }
  return out.sort((a, b) => (a.severity === b.severity ? 0 : a.severity === 'warn' ? -1 : 1));
});

const warnCount = computed(() => gaps.value.filter(g => g.severity === 'warn').length);
const singleSourceDomains = computed(() =>
  stats.value.filter(s => s.expTotal > 0 && Object.keys(s.expByOrigin).length === 1).length);
const unsupportedDomains = computed(() =>
  stats.value.filter(s => s.nodeCount > 0 && s.expTotal === 0 && s.domain !== UNDOMAIN).length);

// ── 导出《覆盖度审计报告》Markdown ───────────────────────
const exportMd = () => {
  const lines: string[] = [];
  lines.push(`# 覆盖度审计报告`);
  lines.push('');
  lines.push(`> 生成时间：${new Date().toLocaleString()} · 领域 ${stats.value.length} 个 · 经验 ${experiences.value.length} 篇 · 节点 ${props.nodes.length} / 边 ${props.edges.length}`);
  lines.push('');
  lines.push('## 领域覆盖一览');
  lines.push('');
  lines.push('| 领域 | 节点 | 边 | 推断边 | 证据覆盖 | 孤立 | 经验(按来源) |');
  lines.push('|---|---|---|---|---|---|---|');
  for (const s of stats.value) {
    const exp = Object.entries(s.expByOrigin).map(([o, n]) => `${originLabel(o)}×${n}`).join('、') || '—';
    const evd = s.edgeCount ? Math.round(s.evidencedEdges / s.edgeCount * 100) + '%' : '—';
    lines.push(`| ${s.domain} | ${s.nodeCount} | ${s.edgeCount} | ${s.inferredEdges} | ${evd} | ${s.isolatedCount} | ${exp} |`);
  }
  lines.push('');
  lines.push('## 调研任务清单');
  lines.push('');
  if (!gaps.value.length) lines.push('（未发现明显缺口）');
  for (const g of gaps.value) {
    lines.push(`- ${g.severity === 'warn' ? '🔴' : '🟡'} **【${g.domain}】${g.issue}**`);
    lines.push(`  - 建议：${g.action}`);
  }
  const blob = new Blob([lines.join('\n')], { type: 'text/markdown' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = `覆盖度审计-${new Date().toISOString().slice(0, 10)}.md`;
  a.click();
  URL.revokeObjectURL(a.href);
};
</script>

<template>
  <div class="ca-wrap">
    <div class="gap-section">
      <div class="ca-head">
        <div class="gap-sec-title">覆盖度总览</div>
        <Button size="sm" variant="outline" @click="exportMd">导出审计报告</Button>
      </div>
      <div class="ca-grid">
        <div class="ca-stat">
          <div class="ca-n">{{ stats.length }}</div>
          <div class="ca-l">领域<span class="ca-sub">节点/经验按文件夹归域</span></div>
        </div>
        <div class="ca-stat">
          <div class="ca-n">{{ experiences.length }}</div>
          <div class="ca-l">经验<span class="ca-sub">建图的唯一入口</span></div>
        </div>
        <div class="ca-stat">
          <div class="ca-n" :class="{ warn: unsupportedDomains }">{{ unsupportedDomains }}</div>
          <div class="ca-l">无经验支撑域<span class="ca-sub">有节点但零经验</span></div>
        </div>
        <div class="ca-stat">
          <div class="ca-n" :class="{ warn: singleSourceDomains }">{{ singleSourceDomains }}</div>
          <div class="ca-l">单一来源域<span class="ca-sub">缺交叉验证</span></div>
        </div>
      </div>
      <div v-if="loading" class="ca-note">经验库加载中…</div>
      <div v-else-if="loadError" class="ca-note warn">⚠️ {{ loadError }}（仅按图内数据统计）</div>
    </div>

    <!-- 领域明细 -->
    <div class="gap-section">
      <div class="gap-sec-title">领域覆盖明细</div>
      <div class="ca-table-wrap">
        <table class="ca-table">
          <thead>
            <tr><th>领域</th><th>节点</th><th>边</th><th>推断</th><th>孤立</th><th>经验来源</th></tr>
          </thead>
          <tbody>
            <tr v-for="s in stats" :key="s.domain">
              <td class="ca-td-domain">{{ s.domain }}</td>
              <td>{{ s.nodeCount }}</td>
              <td>{{ s.edgeCount }}</td>
              <td :class="{ warn: s.edgeCount >= 5 && s.inferredEdges / s.edgeCount > 0.4 }">{{ s.inferredEdges }}</td>
              <td :class="{ warn: s.nodeCount >= 5 && s.isolatedCount / s.nodeCount > 0.2 }">{{ s.isolatedCount }}</td>
              <td class="ca-td-exp">
                <span v-if="!s.expTotal" class="ca-none">无</span>
                <span v-for="(n, o) in s.expByOrigin" :key="o" class="ca-chip">{{ originLabel(String(o)) }}×{{ n }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 调研任务清单 -->
    <div class="gap-section">
      <div class="gap-sec-title">调研任务清单（{{ gaps.length }} 项{{ warnCount ? ` · ${warnCount} 项需优先` : '' }}）</div>
      <div v-if="!gaps.length && !loading" class="ca-note ok">✅ 未发现明显缺口：各域都有多源经验支撑</div>
      <div class="ca-gaps">
        <div v-for="(g, i) in gaps" :key="i" class="ca-gap" :class="g.severity">
          <div class="ca-gap-head">
            <span class="ca-gap-dot">{{ g.severity === 'warn' ? '🔴' : '🟡' }}</span>
            <span class="ca-gap-domain">{{ g.domain }}</span>
            <span class="ca-gap-issue">{{ g.issue }}</span>
          </div>
          <div class="ca-gap-action">→ {{ g.action }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ca-wrap { display: flex; flex-direction: column; gap: 14px; }
.gap-section { display: flex; flex-direction: column; gap: 8px; }
.gap-sec-title { font-size: 12px; color: #9aa3b2; font-weight: 600; }
.ca-head { display: flex; align-items: center; justify-content: space-between; }
.ca-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 8px; }
.ca-stat {
  background: rgba(255,255,255,.03); border: 1px solid rgba(255,255,255,.07);
  border-radius: 8px; padding: 10px 12px;
}
.ca-n { font-size: 20px; font-weight: 700; color: #dde3ee; }
.ca-n.warn { color: #ffcc44; }
.ca-l { font-size: 12px; color: #9aa3b2; display: flex; flex-direction: column; }
.ca-sub { font-size: 11px; color: #6b7280; }
.ca-note { font-size: 12px; color: #8b93a3; }
.ca-note.warn { color: #ffcc44; }
.ca-note.ok { color: #22dd88; }
.ca-table-wrap { overflow-x: auto; }
.ca-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.ca-table th {
  text-align: left; color: #7a8394; font-weight: 500; padding: 4px 8px;
  border-bottom: 1px solid rgba(255,255,255,.08); white-space: nowrap;
}
.ca-table td { padding: 5px 8px; color: #c6cede; border-bottom: 1px solid rgba(255,255,255,.04); }
.ca-table td.warn { color: #ffcc44; font-weight: 600; }
.ca-td-domain { font-weight: 600; }
.ca-td-exp { display: flex; flex-wrap: wrap; gap: 4px; }
.ca-none { color: #ff7755; }
.ca-chip {
  font-size: 11px; color: #9aa3b2; background: rgba(255,255,255,.05);
  border-radius: 4px; padding: 1px 6px; white-space: nowrap;
}
.ca-gaps { display: flex; flex-direction: column; gap: 8px; }
.ca-gap {
  background: rgba(255,255,255,.03); border: 1px solid rgba(255,255,255,.07);
  border-radius: 8px; padding: 8px 10px; display: flex; flex-direction: column; gap: 4px;
}
.ca-gap.warn { border-color: rgba(255,204,68,.25); }
.ca-gap-head { display: flex; align-items: baseline; gap: 6px; flex-wrap: wrap; }
.ca-gap-dot { font-size: 10px; }
.ca-gap-domain { font-size: 12px; font-weight: 700; color: #dde3ee; }
.ca-gap-issue { font-size: 12px; color: #c6cede; }
.ca-gap-action { font-size: 12px; color: #8b93a3; line-height: 1.5; }
</style>
