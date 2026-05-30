<script setup lang="ts">
import { ref, watch } from 'vue';

const props = defineProps<{ resetTick?: number }>();

const emit = defineEmits<{
  (e: 'submit', payload: { text: string; files: File[] }): void;
}>();

const input = ref('');
const atts = ref<File[]>([]);
const fileRef = ref<HTMLInputElement | null>(null);
const dragOver = ref(false);

watch(() => props.resetTick, () => {
  input.value = '';
  atts.value = [];
});

const examples = [
  '描述一个供应链本体：包含供应商、原料、工厂、产品和客户实体',
  '从企业组织架构提取部门、岗位与人员的关系图谱',
  '基于金融审计场景构建合同、付款、审批与风险节点'
];

const addFiles = (files: FileList | null) => {
  if (!files) return;
  for (let i = 0; i < files.length; i++) {
    atts.value.push(files[i]);
  }
};

const removeAtt = (i: number) => {
  atts.value = atts.value.filter((_, j) => j !== i);
};

const handleSubmit = () => {
  if (!input.value.trim() && !atts.value.length) return;
  emit('submit', { text: input.value.trim(), files: [...atts.value] });
  input.value = '';
  atts.value = [];
};

const useExample = (text: string) => {
  input.value = text;
};

const onDrop = (e: DragEvent) => {
  e.preventDefault();
  dragOver.value = false;
  addFiles(e.dataTransfer?.files || null);
};

const onInputKeydown = (e: KeyboardEvent) => {
  if (e.key !== 'Enter') return;
  // 中文输入法选词时按 Enter,e.isComposing 为 true,不应触发发送。
  if (e.isComposing || (e as any).keyCode === 229) return;
  if (e.shiftKey || e.ctrlKey || e.metaKey || e.altKey) return;
  e.preventDefault();
  handleSubmit();
};
</script>

<template>
  <div class="welcome-view" @dragover.prevent="dragOver = true" @dragleave="dragOver = false" @drop="onDrop" :class="{ 'drag-over': dragOver }">
    <div class="welcome-inner">
      <div class="welcome-logo">
        <div class="welcome-logo-mark">推</div>
      </div>
      <h1 class="welcome-title">今天要构建什么本体？</h1>
      <p class="welcome-sub">用自然语言描述实体与关系，或上传图片 / Markdown / TXT / JSON / 代码文件，自动提取本体图谱</p>

      <div class="welcome-input-card">
        <div v-if="atts.length" class="welcome-atts">
          <div v-for="(a, i) in atts" :key="i" class="welcome-att">
            <svg viewBox="0 0 24 24" width="14" height="14" stroke="currentColor" stroke-width="2" fill="none"><path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48"/></svg>
            <span>{{ a.name }}</span>
            <button @click="removeAtt(i)">×</button>
          </div>
        </div>
        <textarea
          class="welcome-input"
          v-model="input"
          placeholder="例如：构建一个包含客户、订单、商品与物流的电商本体模型…"
          rows="3"
          @keydown="onInputKeydown"
        />
        <div class="welcome-input-footer">
          <button class="welcome-icon-btn" title="上传文件 (支持文本、PDF、图片)" @click="fileRef?.click()">
            <svg viewBox="0 0 24 24" width="18" height="18" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round">
              <path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48"/>
            </svg>
            <span>添加文件</span>
          </button>
          <input ref="fileRef" type="file" multiple accept="image/*,.docx,application/vnd.openxmlformats-officedocument.wordprocessingml.document,.txt,.md,.markdown,.json,.csv,.tsv,.log,.xml,.yaml,.yml,.html,.htm,.js,.ts,.py,.java,.sql,.toml,.ini,.env,.vue,.css,text/*" style="display:none" @change="(e: any) => { addFiles(e.target.files); e.target.value = ''; }" />
          <div class="welcome-spacer" />
          <span class="welcome-hint">Enter 发送 · Shift+Enter 换行</span>
          <button class="welcome-send" :disabled="!input.trim() && !atts.length" @click="handleSubmit" title="构建本体图">
            <svg viewBox="0 0 24 24" width="18" height="18" stroke="currentColor" stroke-width="2.5" fill="none" stroke-linecap="round" stroke-linejoin="round">
              <line x1="12" y1="19" x2="12" y2="5"></line>
              <polyline points="5 12 12 5 19 12"></polyline>
            </svg>
          </button>
        </div>
      </div>

      <div class="welcome-examples">
        <span class="welcome-ex-label">试试这些：</span>
        <button class="welcome-ex" v-for="(t, i) in examples" :key="i" @click="useExample(t)">{{ t }}</button>
      </div>

      <div class="welcome-drop-hint" v-if="dragOver">松开以上传文件</div>
    </div>
  </div>
</template>

<style scoped>
.welcome-view {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
  overflow-y: auto;
  position: relative;
}
.welcome-view.drag-over::after {
  content: '';
  position: absolute;
  inset: 16px;
  border: 2px dashed rgba(66, 184, 131, 0.6);
  border-radius: 24px;
  background: rgba(66, 184, 131, 0.05);
  pointer-events: none;
}
.welcome-inner {
  width: 100%;
  max-width: 760px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 20px;
}
.welcome-logo {
  margin-bottom: 4px;
  position: relative;
}
.welcome-logo::before {
  content: '';
  position: absolute;
  inset: -24px;
  background: radial-gradient(circle at 50% 50%, rgba(66, 184, 131, 0.22), transparent 60%);
  filter: blur(20px);
  z-index: -1;
  pointer-events: none;
}
.welcome-logo-mark {
  width: 60px;
  height: 60px;
  border-radius: 18px;
  background: linear-gradient(135deg, #5fd4a3 0%, #42b883 50%, #35495e 100%);
  display: flex; align-items: center; justify-content: center;
  font-size: 24px; font-weight: 800; color: #fff;
  box-shadow:
    0 14px 36px rgba(66, 184, 131, 0.40),
    0 4px 12px rgba(0, 0, 0, 0.3),
    inset 0 1px 0 rgba(255, 255, 255, 0.30),
    inset 0 -1px 0 rgba(0, 0, 0, 0.18);
  letter-spacing: 0.5px;
  font-family: 'Inter', sans-serif;
}
.welcome-title {
  font-size: 34px;
  font-weight: 700;
  color: var(--text-main);
  text-align: center;
  letter-spacing: 0.4px;
  background: linear-gradient(180deg, #ffffff 0%, rgba(244, 247, 251, 0.78) 100%);
  -webkit-background-clip: text; background-clip: text;
  -webkit-text-fill-color: transparent;
  font-family: 'Inter', sans-serif;
}
.welcome-sub {
  color: var(--text-dim);
  font-size: 14px;
  text-align: center;
  margin-bottom: 12px;
  letter-spacing: 0.2px;
  line-height: 1.7;
}
.welcome-input-card {
  width: 100%;
  background: linear-gradient(180deg, rgba(15, 23, 42, 0.65) 0%, rgba(11, 18, 32, 0.55) 100%);
  backdrop-filter: blur(24px) saturate(140%);
  -webkit-backdrop-filter: blur(24px) saturate(140%);
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 22px;
  padding: 16px 18px;
  transition: border-color 0.2s ease, box-shadow 0.2s ease, transform 0.2s ease;
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.28), inset 0 1px 0 rgba(255, 255, 255, 0.05);
}
.welcome-input-card:focus-within {
  border-color: rgba(66, 184, 131, 0.55);
  box-shadow: 0 16px 40px rgba(0, 0, 0, 0.32),
              0 0 0 4px rgba(66, 184, 131, 0.10),
              inset 0 1px 0 rgba(255, 255, 255, 0.06);
}
.welcome-atts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}
.welcome-att {
  display: flex;
  align-items: center;
  gap: 6px;
  background: linear-gradient(180deg, rgba(66, 184, 131, 0.16), rgba(66, 184, 131, 0.08));
  border: 1px solid rgba(66, 184, 131, 0.28);
  color: #5fd4a3;
  padding: 4px 10px;
  border-radius: 8px;
  font-size: 12px;
  letter-spacing: 0.2px;
}
.welcome-att button {
  background: none; border: none; color: inherit;
  cursor: pointer; font-size: 16px; line-height: 1; padding: 0 2px;
  transition: color 0.15s ease;
}
.welcome-att button:hover { color: #ff8a6f; }
.welcome-input {
  width: 100%;
  background: transparent;
  border: none;
  outline: none;
  color: var(--text-main);
  font-size: 15px;
  line-height: 1.65;
  resize: none;
  font-family: inherit;
  min-height: 60px;
  letter-spacing: 0.15px;
}
.welcome-input::placeholder { color: rgba(255, 255, 255, 0.32); }
.welcome-input-footer {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
  padding-top: 10px;
  border-top: 1px solid rgba(255, 255, 255, 0.07);
}
.welcome-icon-btn {
  display: flex; align-items: center; gap: 6px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.10);
  color: var(--text-dim);
  padding: 7px 13px;
  border-radius: 11px;
  cursor: pointer;
  font-size: 12px;
  transition: border-color 0.15s ease, color 0.15s ease, background 0.15s ease;
  letter-spacing: 0.15px;
  font-family: inherit;
}
.welcome-icon-btn:hover {
  border-color: rgba(66, 184, 131, 0.5);
  color: #5fd4a3;
  background: rgba(66, 184, 131, 0.08);
}
.welcome-spacer { flex: 1; }
.welcome-hint {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.32);
  font-family: 'JetBrains Mono', monospace;
  letter-spacing: 0.3px;
}
.welcome-send {
  width: 36px; height: 36px;
  border-radius: 11px;
  background: linear-gradient(135deg, #5fd4a3, #42b883);
  color: #062a1c;
  border: none;
  cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  transition: transform 0.18s ease, box-shadow 0.18s ease;
  box-shadow: 0 6px 16px rgba(66, 184, 131, 0.32), inset 0 1px 0 rgba(255, 255, 255, 0.32);
}
.welcome-send:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 10px 24px rgba(66, 184, 131, 0.42), inset 0 1px 0 rgba(255, 255, 255, 0.36);
}
.welcome-send:disabled { opacity: 0.4; cursor: not-allowed; }
.welcome-examples {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
  margin-top: 8px;
}
.welcome-ex-label {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.45);
  align-self: center;
  margin-right: 4px;
  letter-spacing: 0.3px;
  font-family: 'JetBrains Mono', monospace;
}
.welcome-ex {
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.05), rgba(255, 255, 255, 0.02));
  border: 1px solid rgba(255, 255, 255, 0.10);
  color: var(--text-dim);
  padding: 8px 16px;
  border-radius: 100px;
  font-size: 12.5px;
  cursor: pointer;
  transition: border-color 0.15s ease, color 0.15s ease, background 0.15s ease, transform 0.15s ease;
  font-family: inherit;
  letter-spacing: 0.15px;
}
.welcome-ex:hover {
  border-color: rgba(66, 184, 131, 0.45);
  color: var(--text-main);
  background: linear-gradient(180deg, rgba(66, 184, 131, 0.10), rgba(66, 184, 131, 0.04));
  transform: translateY(-1px);
}
.welcome-drop-hint {
  position: absolute;
  top: 50%; left: 50%;
  transform: translate(-50%, -50%);
  background: linear-gradient(180deg, rgba(66, 184, 131, 0.22), rgba(66, 184, 131, 0.10));
  border: 1px solid rgba(66, 184, 131, 0.45);
  color: #5fd4a3;
  padding: 16px 32px;
  border-radius: 18px;
  font-size: 16px;
  font-weight: 600;
  pointer-events: none;
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  box-shadow: 0 12px 32px rgba(66, 184, 131, 0.22);
  letter-spacing: 0.4px;
}
</style>
