<script setup lang="ts">
import { ref } from 'vue';

const emit = defineEmits<{
  (e: 'submit', payload: { text: string; files: File[] }): void;
}>();

const input = ref('');
const atts = ref<File[]>([]);
const fileRef = ref<HTMLInputElement | null>(null);
const dragOver = ref(false);

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
          @keydown.enter.exact.prevent="handleSubmit"
        />
        <div class="welcome-input-footer">
          <button class="welcome-icon-btn" title="上传文件 (支持文本、PDF、图片)" @click="fileRef?.click()">
            <svg viewBox="0 0 24 24" width="18" height="18" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round">
              <path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48"/>
            </svg>
            <span>添加文件</span>
          </button>
          <input ref="fileRef" type="file" multiple accept="image/*,.txt,.md,.markdown,.json,.csv,.tsv,.log,.xml,.yaml,.yml,.html,.htm,.js,.ts,.py,.java,.sql,.toml,.ini,.env,.vue,.css,text/*" style="display:none" @change="(e: any) => { addFiles(e.target.files); e.target.value = ''; }" />
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
}
.welcome-logo-mark {
  width: 56px;
  height: 56px;
  border-radius: 16px;
  background: linear-gradient(135deg, #42b883, #35495e);
  display: flex; align-items: center; justify-content: center;
  font-size: 22px; font-weight: 700; color: #fff;
  box-shadow: 0 8px 24px rgba(66, 184, 131, 0.3);
}
.welcome-title {
  font-size: 32px;
  font-weight: 600;
  color: var(--text-main);
  text-align: center;
  letter-spacing: 0.5px;
}
.welcome-sub {
  color: var(--text-dim);
  font-size: 14px;
  text-align: center;
  margin-bottom: 12px;
}
.welcome-input-card {
  width: 100%;
  background: rgba(15, 23, 42, 0.6);
  backdrop-filter: blur(20px);
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 20px;
  padding: 16px 18px;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.welcome-input-card:focus-within {
  border-color: rgba(66, 184, 131, 0.5);
  box-shadow: 0 0 0 4px rgba(66, 184, 131, 0.08);
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
  background: rgba(66, 184, 131, 0.12);
  border: 1px solid rgba(66, 184, 131, 0.25);
  color: #42b883;
  padding: 4px 10px;
  border-radius: 8px;
  font-size: 12px;
}
.welcome-att button {
  background: none; border: none; color: inherit;
  cursor: pointer; font-size: 16px; line-height: 1; padding: 0 2px;
}
.welcome-input {
  width: 100%;
  background: transparent;
  border: none;
  outline: none;
  color: var(--text-main);
  font-size: 15px;
  line-height: 1.6;
  resize: none;
  font-family: inherit;
  min-height: 60px;
}
.welcome-input::placeholder { color: rgba(255, 255, 255, 0.35); }
.welcome-input-footer {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
  padding-top: 10px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}
.welcome-icon-btn {
  display: flex; align-items: center; gap: 6px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.08);
  color: var(--text-dim);
  padding: 6px 12px;
  border-radius: 10px;
  cursor: pointer;
  font-size: 12px;
  transition: all 0.15s;
}
.welcome-icon-btn:hover {
  border-color: #42b883;
  color: #42b883;
  background: rgba(66, 184, 131, 0.08);
}
.welcome-spacer { flex: 1; }
.welcome-hint {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.3);
  font-family: 'JetBrains Mono', monospace;
}
.welcome-send {
  width: 36px; height: 36px;
  border-radius: 10px;
  background: #42b883;
  color: #002418;
  border: none;
  cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  transition: all 0.15s;
}
.welcome-send:hover:not(:disabled) {
  background: #50caa3;
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(66, 184, 131, 0.3);
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
  color: rgba(255, 255, 255, 0.4);
  align-self: center;
  margin-right: 4px;
}
.welcome-ex {
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.08);
  color: var(--text-dim);
  padding: 8px 14px;
  border-radius: 100px;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s;
  font-family: inherit;
}
.welcome-ex:hover {
  border-color: rgba(66, 184, 131, 0.4);
  color: var(--text-main);
  background: rgba(66, 184, 131, 0.08);
}
.welcome-drop-hint {
  position: absolute;
  top: 50%; left: 50%;
  transform: translate(-50%, -50%);
  background: rgba(66, 184, 131, 0.15);
  border: 1px solid rgba(66, 184, 131, 0.4);
  color: #42b883;
  padding: 16px 32px;
  border-radius: 16px;
  font-size: 16px;
  font-weight: 600;
  pointer-events: none;
  backdrop-filter: blur(10px);
}
</style>
