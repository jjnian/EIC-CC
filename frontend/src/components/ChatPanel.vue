<script setup lang="ts">
import { ref, watch, nextTick } from 'vue';
import { NT } from '../constants';

const props = defineProps<{
  nodes: any[];
  edges: any[];
  width: number;
}>();

const emit = defineEmits<{
  (e: 'update', addNodes: any[], addEdges: any[]): void;
}>();

const msgs = ref([
  { role: 'a', text: '你好！我是推演助手。\n\n用自然语言描述实体和关系，我会自动构建本体图谱。也可以上传文档、PDF、图片或数据源来提取结构。\n\n试试：「添加一个财务审计实体，与客户相关联」' }
]);
const input = ref('');
const loading = ref(false);
const atts = ref<{name: string, type: string}[]>([]);
const fileRef = ref<HTMLInputElement | null>(null);
const msgsRef = ref<HTMLElement | null>(null);

watch(msgs, () => {
  nextTick(() => {
    if (msgsRef.value) msgsRef.value.scrollTop = msgsRef.value.scrollHeight;
  });
}, { deep: true });

const addFile = (f: File) => {
  const ext = f.name.split('.').pop()?.toLowerCase() || '';
  atts.value.push({ name: f.name, type: ext });
};

const send = async () => {
  if (!input.value.trim() && !atts.value.length) return;
  const txt = input.value;
  const ua = [...atts.value];
  msgs.value.push({ role: 'u', text: txt, atts: ua } as any);
  input.value = '';
  atts.value = [];
  loading.value = true;

  try {
    const res = await fetch('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: txt, history: [] })
    });

    if (res.ok) {
      const data = await res.json();
      const nodeOffset = Math.random() * 50 - 25;

      const cx = 400 + nodeOffset;
      const cy = 300 + nodeOffset;
      const r = 150;

      const pNodes = (data.add_nodes || []).map((n: any, idx: number, arr: any[]) => {
        const angle = (idx / arr.length) * Math.PI * 2;
        return {
          ...n,
          x: cx + Math.cos(angle) * r,
          y: cy + Math.sin(angle) * r
        };
      });

      msgs.value.push({ role: 'a', text: data.reply || '图谱已更新。' });
      emit('update', pNodes, data.add_edges || []);
    } else {
      const err = await res.json();
      msgs.value.push({ role: 'a', text: `请求失败: ${err.error}` });
    }
  } catch (error: any) {
    msgs.value.push({ role: 'a', text: `网络或解析错误: ${error.message}` });
  } finally {
    loading.value = false;
  }
};
</script>

<template>
  <div class="chat-panel" :style="{ width: width + 'px' }">
    <div class="ch-head">
      <div class="ch-head-l"><div class="ch-pulse" /><span>AI 推演助手</span></div>
      <div class="ch-stat">{{ nodes.length }}节点·{{ edges.length }}关系</div>
    </div>
    <div class="ch-msgs" ref="msgsRef">
      <div v-for="(m, i) in msgs" :key="i" :class="['msg', `msg-${m.role === 'u' ? 'user' : 'asst'}`]">
        <div v-if="m.role === 'a'" class="avatar">推</div>
        <div class="msg-body">
          <div v-if="(m as any).atts?.length > 0" class="att-tags">
            <span v-for="(a, j) in (m as any).atts" :key="j" class="att-sm">{{ a.name }}</span>
          </div>
          <div class="bubble">{{ m.text }}</div>
        </div>
      </div>
      <div v-if="loading" class="msg msg-asst">
        <div class="avatar">推</div>
        <div class="msg-body">
          <div class="bubble">
            <div class="loading-dots"><span /><span /><span /></div>
          </div>
        </div>
      </div>
    </div>
    <div v-if="atts.length > 0" class="att-row">
      <div v-for="(a, i) in atts" :key="i" class="att-chip">
        <span>{{ a.name }}</span><button @click="atts = atts.filter((_, j) => j !== i)">×</button>
      </div>
    </div>
    <div class="ch-input-area">
      <div class="cmd-row" v-if="!input && !atts.length">
        <span class="cmd-label">✦ 插入</span>
        <button v-for="(t, k) in NT" :key="k" class="cmd-btn" @click="input = '添加' + (t as any).label + '节点'">
          <i :style="{ background: (t as any).color }"></i>{{ (t as any).label }}
        </button>
      </div>
      <div class="input-box">
        <textarea class="ch-input" v-model="input" placeholder="描述本体关系，或使用下方按钮附加文件…" @keydown.enter.prevent="send" rows="2" />
        <div class="input-footer">
          <div class="file-tools">
            <button class="file-icon-btn attach-btn" title="上传文件" @click="() => { if (fileRef) fileRef.click(); }">
              <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="5" x2="12" y2="19"></line><line x1="5" y1="12" x2="19" y2="12"></line></svg>
            </button>
            <input ref="fileRef" type="file" style="display:none" @change="(e: any) => e.target.files[0] && addFile(e.target.files[0])" />
          </div>
          <button class="send-btn" @click="send" :disabled="loading">
            <svg viewBox="0 0 24 24" width="18" height="18" stroke="currentColor" stroke-width="2.5" fill="none" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="19" x2="12" y2="5"></line><polyline points="5 12 12 5 19 12"></line></svg>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
