<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { useWorkspaces } from '../../composables/useWorkspaces';
import { useSidebarTree } from '../../composables/useSidebarTree';
import { confirm as uiConfirm } from '../../composables/useConfirm';
import { toast } from '../../composables/useToast';
import { ApiError } from '../../api/http';
import {
  createExperience, updateExperience, reindexExperience, type Experience,
} from '../../api/experiences';

const props = defineProps<{
  /** 侧栏点击进入时要定位/编辑的经验 id */
  focusId?: string | null;
  /** 自增信号：变化时打开一个空白「新建经验」表单 */
  createSignal?: number;
}>();

const ws = useWorkspaces();
const tree = useSidebarTree();

const loading = computed(() => tree.isLoadingExp(ws.currentId.value));
const experiences = computed<Experience[]>(() => tree.getExperiences(ws.currentId.value));

// 编辑器状态：id 为空 = 新建；非空 = 编辑已有
interface Draft { id: string | null; title: string; tags: string; content: string; }
const draft = ref<Draft | null>(null);
const saving = ref(false);

const reload = async (force = false) => {
  const id = ws.currentId.value;
  if (!id) return;
  await tree.loadExperiences(id, force);
};

onMounted(() => reload());
watch(() => ws.currentId.value, () => { draft.value = null; reload(); });

const newDraft = () => {
  draft.value = { id: null, title: '', tags: '', content: '' };
};

const editDraft = (x: Experience) => {
  draft.value = { id: x.id, title: x.title || '', tags: x.tags || '', content: x.content || '' };
};

// 侧栏点击定位某条经验 → 打开其编辑器
watch(() => props.focusId, (id) => {
  if (!id) return;
  const found = experiences.value.find(e => e.id === id);
  if (found) editDraft(found);
}, { immediate: true });

// 新建信号 → 打开空白表单
watch(() => props.createSignal, (v, old) => {
  if (v && v !== old) newDraft();
});

const cancelEdit = () => { draft.value = null; };

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

const preview = (content?: string) => {
  const s = (content || '').trim();
  return s.length > 120 ? s.slice(0, 120) + '…' : s;
};

// 向量索引状态 → 展示文案/样式。none 视作"待索引"（保存后自动建索引；未配置 embedding 时保持 none）
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
      // 乐观地把状态置为索引中
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
</script>

<template>
  <div class="exp-view">
    <div class="exp-header">
      <div>
        <h2>经验库</h2>
        <p>沉淀可复用的经验文档，与数据源、历史记录同级别挂在工作空间下。</p>
      </div>
      <button class="exp-new" @click="newDraft">＋ 新建经验</button>
    </div>

    <div class="exp-body">
      <!-- 左侧列表 -->
      <div class="exp-list">
        <div v-if="loading" class="exp-state">加载中…</div>
        <div v-else-if="experiences.length === 0" class="exp-empty">
          <div class="exp-empty-icon">📚</div>
          <p>当前工作空间还没有经验</p>
          <button class="exp-new" @click="newDraft">新建第一条经验</button>
        </div>
        <template v-else>
          <button
            v-for="x in experiences"
            :key="x.id"
            :class="['exp-card', { active: draft && draft.id === x.id }]"
            @click="editDraft(x)"
          >
            <div class="exp-card-top">
              <span class="exp-card-title">{{ x.title || '未命名经验' }}</span>
              <span class="exp-card-time">{{ fmtTime(x.updatedAt || x.createdAt) }}</span>
            </div>
            <div class="exp-card-meta">
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
        <label class="exp-field exp-field-grow">
          <span class="exp-label">正文</span>
          <textarea v-model="draft.content" class="exp-textarea" placeholder="粘贴或撰写经验文档内容（支持 Markdown）"></textarea>
        </label>
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
  </div>
</template>

<style scoped>
.exp-view { flex: 1; display: flex; flex-direction: column; overflow: hidden; padding: 28px 32px; }
.exp-header {
  display: flex; align-items: flex-start; justify-content: space-between;
  gap: 16px; margin-bottom: 18px; flex-shrink: 0;
}
.exp-header h2 { margin: 0 0 6px; font-size: 20px; color: var(--text-main); }
.exp-header p { margin: 0; font-size: 13px; color: var(--text-dim); }
.exp-new {
  flex-shrink: 0; background: #42b883; color: #002418; border: none;
  padding: 9px 16px; border-radius: 8px; font-size: 13px; font-weight: 600;
  cursor: pointer; font-family: inherit;
}
.exp-new:hover { background: #50caa3; }

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
.exp-card-meta { display: flex; align-items: center; gap: 8px; }
.exp-idx {
  font-size: 10px; padding: 1px 8px; border-radius: 100px;
  border: 1px solid transparent;
}
.exp-idx.ok { background: rgba(66,184,131,0.14); color: #6dd4a7; }
.exp-idx.pending { background: rgba(245,191,66,0.14); color: #f0c660; }
.exp-idx.err { background: rgba(255,102,68,0.14); color: #ff8a6f; }
.exp-idx.none { background: rgba(255,255,255,0.06); color: rgba(255,255,255,0.4); }
.exp-card-tags { display: flex; flex-wrap: wrap; gap: 6px; }
.exp-tag {
  font-size: 10px; padding: 1px 8px; border-radius: 100px;
  background: rgba(66,184,131,0.14); color: #6dd4a7;
}
.exp-card-del {
  position: absolute; top: 10px; right: 12px; width: 18px; height: 18px;
  display: flex; align-items: center; justify-content: center; border-radius: 50%;
  color: rgba(255,255,255,0.3); font-size: 15px; opacity: 0; transition: all 0.12s;
}
.exp-card:hover .exp-card-del { opacity: 1; }
.exp-card-del:hover { background: rgba(255,102,68,0.2); color: #ff8a6f; }

.exp-editor {
  flex: 0 0 46%; max-width: 46%; display: flex; flex-direction: column; gap: 12px;
  background: rgba(255,255,255,0.02); border: 1px solid rgba(255,255,255,0.08);
  border-radius: 14px; padding: 18px; overflow: hidden;
}
.exp-editor-placeholder {
  align-items: center; justify-content: center; color: var(--text-dim);
  font-size: 13px; text-align: center; gap: 12px;
}
.exp-ph-icon { font-size: 34px; opacity: 0.4; }
.exp-editor-head {
  display: flex; align-items: center; justify-content: space-between;
  font-size: 14px; font-weight: 600; color: var(--text-main);
}
.exp-x {
  background: transparent; border: none; color: rgba(255,255,255,0.4);
  font-size: 18px; cursor: pointer; width: 24px; height: 24px; border-radius: 6px;
}
.exp-x:hover { background: rgba(255,255,255,0.08); color: #fff; }
.exp-field { display: flex; flex-direction: column; gap: 6px; }
.exp-field-grow { flex: 1; min-height: 0; }
.exp-label { font-size: 12px; color: var(--text-dim); }
.exp-hint { color: rgba(255,255,255,0.3); margin-left: 4px; }
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
</style>
