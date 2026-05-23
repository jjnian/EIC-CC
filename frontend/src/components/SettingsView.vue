<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useModelConfigs, CAPABILITY_LABELS, fmtTokens } from '../composables/useModelConfigs';
import { useSettingsPrefs } from '../composables/useSettingsPrefs';

const localToast = ref<{ msg: string; kind: 'success' | 'error' } | null>(null);
const showToast = (msg: string, kind: 'success' | 'error' = 'success') => {
  localToast.value = { msg, kind };
  setTimeout(() => { localToast.value = null; }, 2500);
};

const mc = useModelConfigs({ showToast });
const sp = useSettingsPrefs();

const models = mc.models;
const loading = mc.loading;
const testResults = mc.testResults;
const runTest = mc.runTest;
const testAllModels = mc.testAllModels;
const prefs = sp.prefs;
const prefsSaved = sp.prefsSaved;

const TABS = [
  { id: 'models',   label: '模型管理', icon: '◈' },
  { id: 'predict',  label: '推演偏好', icon: '⚡' },
  { id: 'appearance', label: '图谱外观', icon: '✦' },
  { id: 'data',     label: '数据管理', icon: '◐' },
  { id: 'about',    label: '关于',     icon: 'ⓘ' }
];
const activeTab = ref('models');
const APP_VERSION = '0.5.0';

onMounted(() => {
  mc.loadModelList();
  mc.loadProviders();
  sp.loadPrefs();
});

const savePrefs = sp.savePrefs;
const clearAllScenarios = sp.clearAllScenarios;

const toast = localToast;
</script>

<template>
  <div class="settings-view">
    <div class="sv-top">
      <div>
        <h2>平台设置</h2>
        <p>统一管理大模型、推演参数、画布外观与数据。</p>
      </div>
      <span v-if="prefsSaved" class="prefs-saved">已自动保存</span>
    </div>

    <div class="sv-layout">
      <!-- 左侧 Tab 导航 -->
      <nav class="sv-tabs">
        <button v-for="t in TABS" :key="t.id"
                :class="['sv-tab', { active: activeTab === t.id }]"
                @click="activeTab = t.id">
          <span class="sv-tab-icon">{{ t.icon }}</span>
          <span>{{ t.label }}</span>
        </button>
      </nav>

      <div class="sv-pane">
        <!-- ================ Tab: 模型管理 ================ -->
        <section v-if="activeTab === 'models'">
          <div class="sv-section-head">
            <div>
              <h3>大模型管理</h3>
              <p>模型配置来自 application.yml，修改后重启生效。</p>
            </div>
            <button class="test-all-btn" @click="testAllModels">🔌 全部测试</button>
          </div>
    <div class="model-list" v-if="!loading">
      <div v-for="m in models" :key="m.id" class="model-card" :class="{ disabled: !m.enabled }">
        <div class="model-card-header">
          <div class="model-card-info">
            <div class="mc-title-row">
              <h4>{{ m.name }}</h4>
              <span v-if="m.provider" class="mc-prov-chip" :class="'prov-' + m.provider">{{ m.provider }}</span>
              <span v-if="m.protocol === 'anthropic'" class="mc-proto-chip">Messages API</span>
            </div>
            <span class="model-meta">{{ m.baseUrl }} · {{ m.modelName }}</span>
            <p v-if="m.description" class="mc-desc">{{ m.description }}</p>
            <div v-if="m.contextWindow || m.capabilities?.length" class="mc-stats">
              <span v-if="m.contextWindow" class="mc-stat">
                <span class="mc-stat-key">上下文</span>
                <span class="mc-stat-val">{{ fmtTokens(m.contextWindow) }}</span>
              </span>
              <span v-if="m.maxOutputTokens" class="mc-stat">
                <span class="mc-stat-key">输出上限</span>
                <span class="mc-stat-val">{{ fmtTokens(m.maxOutputTokens) }}</span>
              </span>
              <span v-for="c in (m.capabilities || [])" :key="c" class="mc-cap">{{ CAPABILITY_LABELS[c] || c }}</span>
            </div>
          </div>
          <div class="model-actions">
            <button
              v-if="m.enabled"
              class="test-btn"
              :class="testResults[m.id]?.status || 'idle'"
              :disabled="testResults[m.id]?.status === 'testing'"
              @click="runTest(m.id)"
            >
              <span v-if="!testResults[m.id] || testResults[m.id].status === 'idle'">测试连接</span>
              <span v-else-if="testResults[m.id].status === 'testing'" class="test-spin">⟳</span>
              <span v-else-if="testResults[m.id].status === 'ok'" class="test-ok">✓ {{ testResults[m.id].latencyMs }}ms</span>
              <span v-else class="test-err" :title="testResults[m.id].error">✕ 失败</span>
            </button>
            <span class="status-badge" :class="m.enabled ? 'enabled' : 'disabled'">
              {{ m.enabled ? '已启用' : '已禁用' }}
            </span>
          </div>
        </div>
      </div>

      <div v-if="models.length === 0" class="empty-state">
        <p>暂无模型配置，请在 application.yml 的 app.llm.models 中添加。</p>
      </div>
    </div>
        </section>

        <!-- ================ Tab: 推演偏好 ================ -->
        <section v-if="activeTab === 'predict'">
          <div class="sv-section-head">
            <div>
              <h3>推演偏好</h3>
              <p>设置场景推演（Forward Simulation）的默认参数。</p>
            </div>
          </div>

          <div class="pref-card">
            <div class="pref-row">
              <div class="pref-label">
                <div class="pref-name">默认推演步数</div>
                <div class="pref-desc">右键节点 → 从此推演 时弹窗的默认值</div>
              </div>
              <div class="pref-control">
                <input type="range" min="1" max="8" step="1" v-model.number="prefs.predictDefaultSteps" @input="savePrefs" class="pref-slider" />
                <span class="pref-val">{{ prefs.predictDefaultSteps }} 步</span>
              </div>
            </div>

            <div class="pref-row">
              <div class="pref-label">
                <div class="pref-name">最低置信度阈值</div>
                <div class="pref-desc">小于此置信度的预测节点将以更低透明度显示</div>
              </div>
              <div class="pref-control">
                <input type="range" min="0" max="1" step="0.05" v-model.number="prefs.predictMinConfidence" @input="savePrefs" class="pref-slider" />
                <span class="pref-val">{{ Math.round(prefs.predictMinConfidence * 100) }}%</span>
              </div>
            </div>

            <div class="pref-row">
              <div class="pref-label">
                <div class="pref-name">分步动画延迟</div>
                <div class="pref-desc">每个推演节点在画布上淡入的间隔（毫秒）</div>
              </div>
              <div class="pref-control">
                <input type="range" min="0" max="800" step="20" v-model.number="prefs.predictStepDelayMs" @input="savePrefs" class="pref-slider" />
                <span class="pref-val">{{ prefs.predictStepDelayMs }} ms</span>
              </div>
            </div>

            <div class="pref-row">
              <div class="pref-label">
                <div class="pref-name">默认推演模型</div>
                <div class="pref-desc">不指定时使用此模型驱动推演与聊天</div>
              </div>
              <div class="pref-control">
                <select class="preset-select" v-model="prefs.defaultModelConfigId" @change="savePrefs">
                  <option value="">— 使用系统默认 —</option>
                  <option v-for="m in models.filter(x => x.enabled)" :key="m.id" :value="m.id">{{ m.name }}</option>
                </select>
              </div>
            </div>
          </div>
        </section>

        <!-- ================ Tab: 图谱外观 ================ -->
        <section v-if="activeTab === 'appearance'">
          <div class="sv-section-head">
            <div>
              <h3>图谱外观</h3>
              <p>调整画布渲染细节。</p>
            </div>
          </div>

          <div class="pref-card">
            <div class="pref-row">
              <div class="pref-label">
                <div class="pref-name">显示边标签</div>
                <div class="pref-desc">关闭后画布更清爽，悬停仍可看关系名</div>
              </div>
              <label class="switch">
                <input type="checkbox" v-model="prefs.showEdgeLabels" @change="savePrefs" />
                <span class="switch-slider" />
              </label>
            </div>

            <div class="pref-row">
              <div class="pref-label">
                <div class="pref-name">打开模型时自动适应屏幕</div>
                <div class="pref-desc">切换模型/分支后自动 fitView</div>
              </div>
              <label class="switch">
                <input type="checkbox" v-model="prefs.autoFit" @change="savePrefs" />
                <span class="switch-slider" />
              </label>
            </div>

            <div class="pref-row">
              <div class="pref-label">
                <div class="pref-name">节点字号</div>
                <div class="pref-desc">画布上节点标签的字体大小</div>
              </div>
              <div class="pref-control">
                <input type="range" min="10" max="18" step="1" v-model.number="prefs.graphFontSize" @input="savePrefs" class="pref-slider" />
                <span class="pref-val">{{ prefs.graphFontSize }} px</span>
              </div>
            </div>
          </div>
        </section>

        <!-- ================ Tab: 数据管理 ================ -->
        <section v-if="activeTab === 'data'">
          <div class="sv-section-head">
            <div>
              <h3>数据管理</h3>
              <p>清理本地存储的推演分支与缓存。</p>
            </div>
          </div>

          <div class="pref-card">
            <div class="pref-row">
              <div class="pref-label">
                <div class="pref-name">清空所有推演分支</div>
                <div class="pref-desc">删除 backend/src/main/resources/scenarios/ 下所有快照文件。仅影响推演结果，不影响原始本体模型。</div>
              </div>
              <button class="danger-btn" @click="clearAllScenarios">清空</button>
            </div>

            <div class="pref-row">
              <div class="pref-label">
                <div class="pref-name">导出全部偏好设置</div>
                <div class="pref-desc">把当前偏好（含推演参数、外观）下载为 JSON 文件</div>
              </div>
              <button class="action-btn" @click="() => {
                const blob = new Blob([JSON.stringify(prefs, null, 2)], { type: 'application/json' });
                const a = document.createElement('a');
                a.href = URL.createObjectURL(blob);
                a.download = 'tuiyan-prefs.json';
                a.click();
              }">下载 JSON</button>
            </div>
          </div>
        </section>

        <!-- ================ Tab: 关于 ================ -->
        <section v-if="activeTab === 'about'">
          <div class="sv-section-head">
            <div>
              <h3>关于</h3>
              <p>推演平台 (Forward Simulation Platform)</p>
            </div>
          </div>

          <div class="about-card">
            <div class="about-logo">推</div>
            <div class="about-info">
              <h4>推演平台</h4>
              <div class="about-version">v{{ APP_VERSION }}</div>
              <p>本体抽取 + 因果链前向推演工具。支持 Claude / GPT / DeepSeek / 通义千问 / Kimi / GLM 等主流大模型。</p>
              <div class="about-stack">
                <span class="stack-chip">Vue 3</span>
                <span class="stack-chip">Spring Boot</span>
                <span class="stack-chip">SSE 流式</span>
                <span class="stack-chip">OpenAI / Anthropic 双协议</span>
              </div>
            </div>
          </div>
        </section>
      </div>
    </div>

    <!-- Top-level toast -->
    <div v-if="toast" class="sv-toast" :class="'sv-toast-' + toast.kind">
      <span class="sv-toast-icon">{{ toast.kind === 'success' ? '✓' : '✗' }}</span>
      <span>{{ toast.msg }}</span>
    </div>
  </div>
</template>

<style scoped>
.settings-view {
  flex: 1;
  padding: 28px 32px;
  overflow-y: auto;
  background: transparent;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* ====== Top header ====== */
.sv-top {
  display: flex; align-items: center; justify-content: space-between;
  padding-bottom: 8px;
}
.sv-top h2 { font-size: 22px; font-weight: 600; color: var(--text-main); margin-bottom: 4px; }
.sv-top p { font-size: 13px; color: var(--text-dim); }
.prefs-saved {
  font-size: 12px;
  color: #42b883;
  background: rgba(66, 184, 131, 0.12);
  padding: 4px 12px;
  border-radius: 100px;
  border: 1px solid rgba(66, 184, 131, 0.3);
  animation: fadeIn 0.2s;
}
@keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }

/* ====== Tab layout ====== */
.sv-layout { display: grid; grid-template-columns: 200px 1fr; gap: 24px; flex: 1; min-height: 0; }
.sv-tabs {
  display: flex; flex-direction: column; gap: 4px;
  background: rgba(14, 25, 41, 0.4);
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 12px;
  padding: 8px;
  height: fit-content;
}
.sv-tab {
  display: flex; align-items: center; gap: 10px;
  padding: 9px 14px;
  background: none; border: none;
  color: var(--text-dim);
  border-radius: 8px;
  font-size: 13px;
  cursor: pointer;
  text-align: left;
  font-family: inherit;
  transition: all 0.12s;
}
.sv-tab:hover { background: rgba(255, 255, 255, 0.04); color: var(--text-main); }
.sv-tab.active {
  background: rgba(66, 184, 131, 0.15);
  color: #42b883;
  border: 1px solid rgba(66, 184, 131, 0.3);
}
.sv-tab-icon { font-size: 14px; flex-shrink: 0; }

.sv-pane { min-width: 0; }
.sv-section-head {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 20px;
  gap: 16px;
}
.sv-section-head h3 { font-size: 17px; font-weight: 600; color: var(--text-main); margin-bottom: 4px; }
.sv-section-head p { font-size: 12px; color: var(--text-dim); }

/* ====== Pref card ====== */
.pref-card {
  background: rgba(14, 25, 41, 0.4);
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 12px;
  overflow: hidden;
}
.pref-row {
  display: flex; align-items: center; justify-content: space-between;
  gap: 16px;
  padding: 16px 20px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
}
.pref-row:last-child { border-bottom: none; }
.pref-label { flex: 1; min-width: 0; }
.pref-name { font-size: 13px; color: var(--text-main); font-weight: 500; margin-bottom: 3px; }
.pref-desc { font-size: 11px; color: var(--text-dim); line-height: 1.4; }
.pref-control { display: flex; align-items: center; gap: 12px; flex-shrink: 0; }
.pref-slider { width: 180px; accent-color: #42b883; cursor: pointer; }
.pref-val {
  min-width: 60px; text-align: right;
  font-size: 12px; color: #42b883;
  font-family: 'JetBrains Mono', monospace;
  font-weight: 500;
}

/* ====== Switch ====== */
.switch { position: relative; width: 40px; height: 22px; flex-shrink: 0; }
.switch input { opacity: 0; width: 0; height: 0; }
.switch-slider {
  position: absolute; cursor: pointer; inset: 0;
  background: rgba(255, 255, 255, 0.12);
  border-radius: 22px;
  transition: 0.2s;
}
.switch-slider::before {
  content: ''; position: absolute;
  height: 16px; width: 16px;
  left: 3px; bottom: 3px;
  background: white;
  border-radius: 50%;
  transition: 0.2s;
}
.switch input:checked + .switch-slider { background: #42b883; }
.switch input:checked + .switch-slider::before { transform: translateX(18px); }

/* ====== Danger button ====== */
.danger-btn {
  background: rgba(255, 102, 68, 0.15);
  border: 1px solid rgba(255, 102, 68, 0.35);
  color: #ff8a6f;
  padding: 7px 16px;
  border-radius: 8px;
  font-size: 13px;
  cursor: pointer;
  font-family: inherit;
  transition: all 0.15s;
}
.danger-btn:hover { background: rgba(255, 102, 68, 0.25); }

/* ====== About card ====== */
.about-card {
  background: rgba(14, 25, 41, 0.4);
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 12px;
  padding: 24px;
  display: flex; gap: 20px; align-items: flex-start;
}
.about-logo {
  width: 64px; height: 64px;
  background: linear-gradient(135deg, #42b883, #35495e);
  color: white;
  display: flex; align-items: center; justify-content: center;
  font-size: 28px; font-weight: 700;
  border-radius: 16px;
  flex-shrink: 0;
  box-shadow: 0 6px 24px rgba(66, 184, 131, 0.25);
}
.about-info { flex: 1; }
.about-info h4 { font-size: 18px; font-weight: 600; color: var(--text-main); margin-bottom: 4px; }
.about-version {
  display: inline-block;
  font-size: 11px;
  background: rgba(66, 184, 131, 0.15);
  color: #42b883;
  padding: 2px 10px;
  border-radius: 100px;
  font-family: 'JetBrains Mono', monospace;
  margin-bottom: 12px;
}
.about-info p { font-size: 13px; color: var(--text-dim); line-height: 1.6; margin-bottom: 12px; }
.about-stack { display: flex; flex-wrap: wrap; gap: 6px; }
.stack-chip {
  font-size: 11px;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.08);
  color: var(--text-dim);
  padding: 3px 10px;
  border-radius: 6px;
}

.sv-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 24px;
}

.sv-header h2 {
  font-size: 24px;
  font-weight: 600;
  color: var(--text-main);
  margin-bottom: 4px;
}

.sv-header p {
  color: var(--text-dim);
  font-size: 13px;
}

.add-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  background: #42b883;
  color: #002418;
  border: none;
  padding: 10px 20px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
  flex-shrink: 0;
}

.add-btn:hover {
  background: #50caa3;
  transform: translateY(-1px);
}

/* 模型卡片列表 */
.model-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.model-card {
  background: rgba(14, 25, 41, 0.6);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 12px;
  padding: 16px 20px;
  transition: all 0.2s;
}

.model-card:hover {
  border-color: rgba(255, 255, 255, 0.15);
}

.model-card.disabled {
  opacity: 0.5;
}

.model-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.model-card-info h4 {
  font-size: 15px;
  color: var(--text-main);
  margin: 0 0 4px 0;
  font-weight: 500;
}

.model-meta {
  font-size: 12px;
  color: var(--text-dim);
  font-family: 'JetBrains Mono', monospace;
}
.mc-title-row { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; flex-wrap: wrap; }
.mc-prov-chip {
  font-size: 10px;
  padding: 2px 8px;
  border-radius: 100px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  background: rgba(255, 255, 255, 0.08);
  color: rgba(255, 255, 255, 0.6);
}
.mc-prov-chip.prov-anthropic { background: rgba(217, 119, 87, 0.18); color: #f1a684; }
.mc-prov-chip.prov-openai { background: rgba(16, 163, 127, 0.18); color: #4ec9b0; }
.mc-prov-chip.prov-deepseek { background: rgba(99, 102, 241, 0.18); color: #a5a8f0; }
.mc-prov-chip.prov-qwen { background: rgba(61, 155, 255, 0.18); color: #7abaff; }
.mc-prov-chip.prov-kimi { background: rgba(168, 85, 247, 0.18); color: #c4a5fc; }
.mc-prov-chip.prov-glm { background: rgba(34, 197, 94, 0.18); color: #80e0a0; }
.mc-proto-chip {
  font-size: 10px;
  padding: 2px 8px;
  border-radius: 100px;
  background: rgba(251, 191, 36, 0.15);
  color: #fbbf24;
  border: 1px solid rgba(251, 191, 36, 0.3);
}
.mc-desc {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.55);
  margin: 6px 0 0 0;
  line-height: 1.55;
}
.mc-stats {
  display: flex; flex-wrap: wrap; gap: 6px;
  margin-top: 10px;
}
.mc-stat {
  display: inline-flex; align-items: center; gap: 4px;
  font-size: 11px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.06);
  padding: 2px 8px;
  border-radius: 6px;
}
.mc-stat-key { color: rgba(255, 255, 255, 0.4); }
.mc-stat-val { color: var(--text-main); font-family: 'JetBrains Mono', monospace; font-weight: 500; }
.mc-cap {
  font-size: 10px;
  padding: 2px 8px;
  border-radius: 6px;
  background: rgba(66, 184, 131, 0.1);
  color: #6dd4a7;
  border: 1px solid rgba(66, 184, 131, 0.2);
}
.form-divider {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.5);
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 1px;
  margin-top: 8px;
  padding-top: 16px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}
.form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.form-textarea {
  background: rgba(10, 16, 27, 0.8);
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: white;
  padding: 10px 14px;
  border-radius: 8px;
  font-size: 13px;
  outline: none;
  resize: vertical;
  font-family: inherit;
}
.form-textarea:focus { border-color: #42b883; }
.cap-chips { display: flex; flex-wrap: wrap; gap: 6px; }
.cap-chip {
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.08);
  color: var(--text-dim);
  padding: 5px 12px;
  border-radius: 100px;
  font-size: 12px;
  cursor: pointer;
  font-family: inherit;
  transition: all 0.15s;
}
.cap-chip:hover { border-color: rgba(66, 184, 131, 0.4); color: var(--text-main); }
.cap-chip.active {
  background: rgba(66, 184, 131, 0.15);
  border-color: rgba(66, 184, 131, 0.4);
  color: #42b883;
}

.model-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.status-badge {
  font-size: 11px;
  padding: 3px 10px;
  border-radius: 12px;
  font-weight: 500;
}

.status-badge.enabled {
  background: rgba(66, 184, 131, 0.15);
  color: #42b883;
}

.status-badge.disabled {
  background: rgba(255, 255, 255, 0.06);
  color: var(--text-dim);
}

.action-btn {
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.08);
  color: var(--text-dim);
  padding: 6px 10px;
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
  display: flex;
  align-items: center;
  justify-content: center;
}

.action-btn:hover {
  border-color: rgba(255, 255, 255, 0.2);
  color: var(--text-main);
}

.action-btn.toggle:hover {
  border-color: #42b883;
  color: #42b883;
}

.action-btn.edit:hover {
  border-color: #3d9bff;
  color: #3d9bff;
}

.action-btn.delete:hover {
  border-color: #ff6644;
  color: #ff6644;
}

.empty-state {
  text-align: center;
  padding: 48px;
  color: var(--text-dim);
}

/* 模态框 */
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.modal {
  background: #141e30;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 16px;
  width: 480px;
  max-width: 90vw;
  max-height: 90vh;
  overflow-y: auto;
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 24px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}

.modal-header h3 {
  font-size: 18px;
  color: var(--text-main);
  margin: 0;
}

.modal-close {
  background: none;
  border: none;
  color: var(--text-dim);
  font-size: 24px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 4px;
  transition: all 0.2s;
}

.modal-close:hover {
  color: var(--text-main);
  background: rgba(255, 255, 255, 0.06);
}

.modal-body {
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-group label {
  font-size: 13px;
  color: rgba(255, 255, 255, 0.7);
  font-weight: 500;
}

.form-group input {
  background: rgba(10, 16, 27, 0.8);
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: white;
  padding: 10px 14px;
  border-radius: 8px;
  font-size: 14px;
  outline: none;
  transition: all 0.2s;
}

.form-group input:focus {
  border-color: #42b883;
  box-shadow: 0 0 0 2px rgba(66, 184, 131, 0.15);
}

.form-group.error input {
  border-color: #ff6644;
}

.form-error {
  font-size: 12px;
  color: #ff6644;
}

.sv-help {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.4);
}
.sv-help code {
  background: rgba(255, 255, 255, 0.06);
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 11px;
  color: #42b883;
}
.sv-help-inline {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.3);
  font-weight: normal;
  margin-left: 4px;
}
.preset-select {
  background: rgba(10, 16, 27, 0.8);
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: white;
  padding: 10px 14px;
  border-radius: 8px;
  font-size: 14px;
  outline: none;
  cursor: pointer;
  font-family: inherit;
}
.preset-select:focus {
  border-color: #42b883;
}
.preset-select option {
  background: #141e30;
  color: white;
}
.anthropic-badge {
  display: inline-block;
  margin-left: 8px;
  background: rgba(217, 119, 87, 0.15);
  color: #f1a684;
  padding: 1px 8px;
  border-radius: 100px;
  font-size: 10px;
  font-weight: 500;
  border: 1px solid rgba(217, 119, 87, 0.3);
}

.modal-error-banner {
  margin: 0 24px 0;
  padding: 10px 14px;
  background: rgba(255, 102, 68, 0.12);
  border: 1px solid rgba(255, 102, 68, 0.35);
  color: #ff8a6f;
  border-radius: 8px;
  font-size: 13px;
  display: flex; align-items: center; gap: 8px;
}
.modal-btn:disabled { opacity: 0.55; cursor: not-allowed; transform: none !important; }
.spinner-sm {
  display: inline-block;
  width: 12px; height: 12px;
  margin-right: 6px;
  border: 2px solid rgba(255,255,255,0.35);
  border-top-color: currentColor;
  border-radius: 50%;
  animation: spin .8s linear infinite;
  vertical-align: -2px;
}
@keyframes spin { to { transform: rotate(360deg); } }
.sv-toast {
  position: fixed;
  top: 24px; left: 50%;
  transform: translateX(-50%);
  display: flex; align-items: center; gap: 10px;
  padding: 10px 18px;
  border-radius: 100px;
  font-size: 13px;
  font-weight: 500;
  z-index: 3000;
  backdrop-filter: blur(16px);
  animation: toastIn .25s cubic-bezier(.34,1.56,.64,1);
  box-shadow: 0 12px 36px rgba(0,0,0,0.35);
}
@keyframes toastIn { from { opacity:0; transform:translate(-50%,-12px); } to { opacity:1; transform:translate(-50%,0); } }
.sv-toast-success {
  background: rgba(66, 184, 131, 0.18);
  border: 1px solid rgba(66, 184, 131, 0.45);
  color: #6dd4a7;
}
.sv-toast-error {
  background: rgba(255, 102, 68, 0.18);
  border: 1px solid rgba(255, 102, 68, 0.45);
  color: #ff8a6f;
}
.sv-toast-icon {
  display: inline-flex; align-items: center; justify-content: center;
  width: 18px; height: 18px;
  border-radius: 50%;
  background: currentColor;
  color: #0a121b;
  font-size: 11px;
  font-weight: 700;
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding: 16px 24px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}

.modal-btn {
  padding: 10px 24px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
  border: none;
}

.modal-btn.cancel {
  background: rgba(255, 255, 255, 0.06);
  color: var(--text-dim);
}

.modal-btn.cancel:hover {
  background: rgba(255, 255, 255, 0.1);
  color: var(--text-main);
}

.modal-btn.save {
  background: #42b883;
  color: #002418;
}

.modal-btn.save:hover {
  background: #50caa3;
}

.test-all-btn {
  background: rgba(66, 184, 131, 0.12);
  border: 1px solid rgba(66, 184, 131, 0.4);
  color: #42b883;
  border-radius: 6px;
  padding: 6px 14px;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.15s;
  white-space: nowrap;
}
.test-all-btn:hover { background: rgba(66, 184, 131, 0.22); }

.test-btn {
  background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.15);
  color: rgba(255,255,255,0.7);
  border-radius: 6px;
  padding: 4px 10px;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s;
  min-width: 80px;
  text-align: center;
}
.test-btn:hover { background: rgba(255,255,255,0.1); }
.test-btn:disabled { opacity: 0.7; cursor: not-allowed; }
.test-btn.ok { border-color: rgba(66, 184, 131, 0.5); }
.test-btn.error { border-color: rgba(239, 68, 68, 0.5); }

.test-ok { color: #42b883; }
.test-err { color: #ef4444; }
.test-spin {
  display: inline-block;
  animation: spin 1s linear infinite;
}
</style>
