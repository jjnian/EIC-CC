# 图谱搜索定位 + 模型连接测试 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为图谱画布增加搜索功能（按名称/类型模糊匹配），并在设置页模型卡片上增加"测试连接"按钮验证 API 可用性。

**Architecture:** 图谱搜索为纯前端功能，在 GraphCanvas.vue 的工具栏区域增加搜索框，匹配逻辑过滤节点高亮/聚焦。模型连接测试需要后端新增 `/api/models/{id}/test` 端点（向 LLM 发送最小请求验证连通性），前端在卡片上添加按钮和状态指示。

**Tech Stack:** Vue 3.5 (Composition API), TypeScript 5.8, Java 17, Spring Boot 3

---

## Part A: 图谱搜索与定位

### Task 1: 在 GraphCanvas.vue 增加搜索框 UI

**Files:**
- Modify: `frontend/src/components/GraphCanvas.vue`

- [ ] **Step 1: 在 hud-overlay 中添加搜索框 HTML**

在 `div.hud-overlay` 内部、`div.canvas-actions` 之前，添加搜索框区域：

```html
<div class="search-bar" v-if="!readonly">
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
```

- [ ] **Step 2: 添加搜索相关响应式状态和逻辑**

在 `<script setup>` 中已有的 `typeFilter` 下方，添加：

```typescript
const searchRef = ref<HTMLInputElement | null>(null);
const searchQuery = ref('');
const searchIdx = ref(0);

const searchMatches = computed(() => {
  const q = searchQuery.value.trim().toLowerCase();
  if (!q) return [];
  return props.nodes.filter(n =>
    n.label.toLowerCase().includes(q) ||
    n.type.toLowerCase().includes(q) ||
    (n.id && n.id.toLowerCase().includes(q))
  );
});

watch(searchQuery, () => { searchIdx.value = 0; });

const jumpToNext = () => {
  if (!searchMatches.value.length) return;
  searchIdx.value = (searchIdx.value + 1) % searchMatches.value.length;
  const target = searchMatches.value[searchIdx.value];
  emit('select', target.id);
  focusNode(target.id);
};

const jumpToPrev = () => {
  if (!searchMatches.value.length) return;
  searchIdx.value = (searchIdx.value - 1 + searchMatches.value.length) % searchMatches.value.length;
  const target = searchMatches.value[searchIdx.value];
  emit('select', target.id);
  focusNode(target.id);
};

const clearSearch = () => {
  searchQuery.value = '';
  searchIdx.value = 0;
};
```

- [ ] **Step 3: 添加 Ctrl+F 快捷键支持**

在已有的 `onMounted` / 事件监听区域，添加全局键盘监听：

```typescript
const onKeydown = (e: KeyboardEvent) => {
  if ((e.ctrlKey || e.metaKey) && e.key === 'f') {
    if (props.readonly) return;
    e.preventDefault();
    searchRef.value?.focus();
  }
};
onMounted(() => { window.addEventListener('keydown', onKeydown); });
onUnmounted(() => { window.removeEventListener('keydown', onKeydown); });
```

注意：需要在文件顶部的 `import { ... } from 'vue'` 中确保导入了 `onUnmounted`、`computed`、`watch`（检查已有导入是否包含，若缺失则补充）。

- [ ] **Step 4: 修改节点样式逻辑以支持搜索高亮**

修改现有的 `matchesFilter` 函数或新增搜索匹配判断，在节点的动态 class 中增加搜索匹配类名：

将节点的 `:class` 绑定修改为：

```typescript
:class="['node', {
  'node-new':       n.isNew,
  'node-sel':       selId === n.id,
  'node-predicted': n.source === 'predicted',
  'node-dim':       !matchesFilter(n) || (searchQuery && !isSearchMatch(n)),
  'node-hl':        (typeFilter && matchesFilter(n)) || isSearchMatch(n),
  'node-search-current': isCurrentSearchTarget(n)
}]"
```

添加辅助函数：

```typescript
const isSearchMatch = (n: OntologyNode) => {
  if (!searchQuery.value) return false;
  return searchMatches.value.some(m => m.id === n.id);
};

const isCurrentSearchTarget = (n: OntologyNode) => {
  if (!searchMatches.value.length) return false;
  return searchMatches.value[searchIdx.value]?.id === n.id;
};
```

- [ ] **Step 5: 添加搜索框和高亮的 CSS 样式**

在 `<style scoped>` 中添加：

```css
.search-bar {
  position: absolute;
  top: 12px;
  left: 12px;
  display: flex;
  align-items: center;
  gap: 4px;
  background: rgba(15, 23, 42, 0.92);
  border: 1px solid rgba(255,255,255,0.12);
  border-radius: 8px;
  padding: 4px 8px;
  z-index: 20;
  backdrop-filter: blur(8px);
}
.search-input {
  background: transparent;
  border: none;
  outline: none;
  color: #e2e8f0;
  font-size: 13px;
  width: 180px;
}
.search-input::placeholder { color: rgba(255,255,255,0.35); }
.search-count {
  font-size: 11px;
  color: rgba(255,255,255,0.5);
  margin: 0 4px;
  white-space: nowrap;
}
.search-btn {
  background: transparent;
  border: none;
  color: rgba(255,255,255,0.6);
  cursor: pointer;
  font-size: 13px;
  padding: 2px 4px;
  border-radius: 4px;
}
.search-btn:hover { background: rgba(255,255,255,0.1); color: #fff; }
.node-search-current {
  outline: 2px solid #fbbf24 !important;
  outline-offset: 2px;
  z-index: 10;
}
```

- [ ] **Step 6: 验证编译通过**

Run: `cd frontend && npx tsc --noEmit`
Expected: 无错误

- [ ] **Step 7: 提交**

```bash
git add frontend/src/components/GraphCanvas.vue
git commit -m "feat(graph): add search bar with Ctrl+F shortcut and node highlight"
```

---

## Part B: 模型连接测试

### Task 2: 后端新增模型测试端点

**Files:**
- Modify: `backend/src/main/java/com/tuiyan/backend/controller/ModelController.java`
- Modify: `backend/src/main/java/com/tuiyan/backend/service/LlmService.java`

- [ ] **Step 1: 在 LlmService 中添加连接测试方法**

在 `LlmService.java` 中添加 `testConnection` 方法。找到该文件的公共方法区域，添加：

```java
/**
 * 向指定模型发送最小化请求以验证连接可用性。
 * 返回延迟(ms)；失败抛出异常。
 */
public long testModelConnection(String modelId) throws Exception {
    LlmProperties.ModelEntry entry = findModelById(modelId);
    if (entry == null) {
        throw new IllegalArgumentException("Model not found: " + modelId);
    }
    if (!entry.isEnabled()) {
        throw new IllegalStateException("Model is disabled: " + entry.getName());
    }

    String baseUrl = entry.getBaseUrl();
    String apiKey = resolveApiKey(entry);
    String modelName = entry.getModelName();

    long start = System.currentTimeMillis();

    if (LlmProvider.isAnthropicEndpoint(baseUrl, modelName)) {
        testAnthropicConnection(baseUrl, apiKey, modelName);
    } else {
        testOpenAIConnection(baseUrl, apiKey, modelName);
    }

    return System.currentTimeMillis() - start;
}

private void testOpenAIConnection(String baseUrl, String apiKey, String modelName) throws Exception {
    String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";
    String body = """
        {"model":"%s","messages":[{"role":"user","content":"hi"}],"max_tokens":1}
        """.formatted(modelName).trim();

    java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
        .uri(java.net.URI.create(url))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + apiKey)
        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
        .timeout(java.time.Duration.ofSeconds(15))
        .build();

    java.net.http.HttpResponse<String> resp = java.net.http.HttpClient.newHttpClient()
        .send(req, java.net.http.HttpResponse.BodyHandlers.ofString());

    if (resp.statusCode() >= 400) {
        throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body().substring(0, Math.min(200, resp.body().length())));
    }
}

private void testAnthropicConnection(String baseUrl, String apiKey, String modelName) throws Exception {
    String url = baseUrl.replaceAll("/+$", "") + "/messages";
    String body = """
        {"model":"%s","max_tokens":1,"messages":[{"role":"user","content":"hi"}]}
        """.formatted(modelName).trim();

    java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
        .uri(java.net.URI.create(url))
        .header("Content-Type", "application/json")
        .header("x-api-key", apiKey)
        .header("anthropic-version", "2023-06-01")
        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
        .timeout(java.time.Duration.ofSeconds(15))
        .build();

    java.net.http.HttpResponse<String> resp = java.net.http.HttpClient.newHttpClient()
        .send(req, java.net.http.HttpResponse.BodyHandlers.ofString());

    if (resp.statusCode() >= 400) {
        throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body().substring(0, Math.min(200, resp.body().length())));
    }
}

private LlmProperties.ModelEntry findModelById(String id) {
    return llmProperties.getModels().stream()
        .filter(m -> m.getId().equals(id))
        .findFirst()
        .orElse(null);
}
```

注意：`resolveApiKey` 方法在 LlmService 中已存在（用于解析 API Key，带环境变量 fallback）。如果它是 private 且签名不兼容，需要检查并适配。`llmProperties` 字段也已存在。

- [ ] **Step 2: 在 ModelController 中添加测试端点**

修改 `ModelController.java`，在 `getAllModels()` 方法下方添加：

```java
@PostMapping("/{id}/test")
public ResponseEntity<java.util.Map<String, Object>> testModel(@PathVariable String id) {
    try {
        long latencyMs = llmService.testModelConnection(id);
        return ResponseEntity.ok(java.util.Map.of(
            "status", "ok",
            "latencyMs", latencyMs
        ));
    } catch (IllegalArgumentException | IllegalStateException e) {
        return ResponseEntity.badRequest().body(java.util.Map.of(
            "status", "error",
            "error", e.getMessage()
        ));
    } catch (Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return ResponseEntity.ok(java.util.Map.of(
            "status", "error",
            "error", msg
        ));
    }
}
```

- [ ] **Step 3: 验证后端编译**

Run: `cd backend && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/java/com/tuiyan/backend/controller/ModelController.java
git add backend/src/main/java/com/tuiyan/backend/service/LlmService.java
git commit -m "feat(api): add POST /api/models/{id}/test for connection verification"
```

---

### Task 3: 前端 API 层增加 testModel 函数

**Files:**
- Modify: `frontend/src/api/models.ts`

- [ ] **Step 1: 添加 testModel API 调用**

在 `frontend/src/api/models.ts` 文件末尾添加：

```typescript
export interface TestModelResult {
  status: 'ok' | 'error';
  latencyMs?: number;
  error?: string;
}

export function testModel(id: string): Promise<TestModelResult> {
  return request<TestModelResult>(`/api/models/${id}/test`, { method: 'POST' });
}
```

- [ ] **Step 2: 验证编译**

Run: `cd frontend && npx tsc --noEmit`
Expected: 无错误

- [ ] **Step 3: 提交**

```bash
git add frontend/src/api/models.ts
git commit -m "feat(api): add testModel() client function"
```

---

### Task 4: 设置页模型卡片增加测试连接按钮

**Files:**
- Modify: `frontend/src/components/SettingsView.vue`
- Modify: `frontend/src/composables/useModelConfigs.ts`

- [ ] **Step 1: 在 useModelConfigs 中添加测试连接逻辑**

在 `frontend/src/composables/useModelConfigs.ts` 中，导入 `testModel` 并添加状态管理：

在文件顶部添加导入：
```typescript
import { ref, reactive } from 'vue';
import { listModels, testModel } from '../api/models';
import type { TestModelResult } from '../api/models';
```

（替换原有的 `import { ref } from 'vue'` 和 `import { listModels } from '../api/models'`）

在 `useModelConfigs` 函数体内，`loadProviders` 之后添加：

```typescript
const testResults = reactive<Record<string, { status: 'idle' | 'testing' | 'ok' | 'error'; latencyMs?: number; error?: string }>>({});

const runTest = async (modelId: string) => {
  testResults[modelId] = { status: 'testing' };
  try {
    const result = await testModel(modelId);
    if (result.status === 'ok') {
      testResults[modelId] = { status: 'ok', latencyMs: result.latencyMs };
    } else {
      testResults[modelId] = { status: 'error', error: result.error || '未知错误' };
    }
  } catch (e: any) {
    testResults[modelId] = { status: 'error', error: e.message || '请求失败' };
  }
};

const testAllModels = async () => {
  const enabledModels = models.value.filter(m => m.enabled);
  for (const m of enabledModels) {
    await runTest(m.id);
  }
};
```

在 return 语句中增加新字段：
```typescript
return {
  models, providers, loading,
  loadModelList, loadProviders,
  CAPABILITY_OPTIONS, CAPABILITY_LABELS, fmtTokens,
  testResults, runTest, testAllModels,
};
```

- [ ] **Step 2: 在 SettingsView.vue 中展示测试按钮和结果**

在 SettingsView.vue 的 `<script setup>` 中，从 `mc` 解构出新增的字段：

```typescript
const testResults = mc.testResults;
const runTest = mc.runTest;
const testAllModels = mc.testAllModels;
```

在模型列表区域的 `div.sv-section-head` 中，标题旁边增加"全部测试"按钮：

找到原有的：
```html
<div class="sv-section-head">
  <div>
    <h3>大模型管理</h3>
    <p>模型配置来自 application.yml，修改后重启生效。</p>
  </div>
</div>
```

替换为：
```html
<div class="sv-section-head">
  <div>
    <h3>大模型管理</h3>
    <p>模型配置来自 application.yml，修改后重启生效。</p>
  </div>
  <button class="test-all-btn" @click="testAllModels">🔌 全部测试</button>
</div>
```

在每个模型卡片的 `div.model-actions` 中，在 status-badge 前面添加测试按钮：

找到原有的 `model-actions` 区域：
```html
<div class="model-actions">
  <span class="status-badge" :class="m.enabled ? 'enabled' : 'disabled'">
    {{ m.enabled ? '已启用' : '已禁用' }}
  </span>
</div>
```

替换为：
```html
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
```

- [ ] **Step 3: 添加测试按钮的 CSS 样式**

在 SettingsView.vue 的 `<style scoped>` 中添加：

```css
.test-all-btn {
  background: rgba(66, 184, 131, 0.12);
  border: 1px solid rgba(66, 184, 131, 0.4);
  color: #42b883;
  border-radius: 6px;
  padding: 6px 14px;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.15s;
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
@keyframes spin { to { transform: rotate(360deg); } }
```

- [ ] **Step 4: 验证编译通过**

Run: `cd frontend && npx tsc --noEmit`
Expected: 无错误

- [ ] **Step 5: 提交**

```bash
git add frontend/src/composables/useModelConfigs.ts
git add frontend/src/components/SettingsView.vue
git commit -m "feat(settings): add test connection button per model card"
```

---

### Task 5: 端到端手工验证

- [ ] **Step 1: 启动后端**

Run: `cd backend && mvn spring-boot:run`
Expected: 应用启动在 8000 端口

- [ ] **Step 2: 启动前端**

Run: `cd frontend && npm run dev`
Expected: Vite 开发服务器启动

- [ ] **Step 3: 验证图谱搜索功能**

在浏览器中：
1. 打开应用，进入一个有节点的图谱
2. 按 Ctrl+F，验证搜索框出现在左上角
3. 输入节点名称的部分文字，验证计数显示正确
4. 按 Enter 验证画布自动定位到匹配节点
5. 再按 Enter 验证跳转到下一个匹配
6. 按 Escape 验证清除搜索

- [ ] **Step 4: 验证模型连接测试功能**

在浏览器中：
1. 进入设置页 → 模型管理 Tab
2. 点击某个已启用模型卡片上的"测试连接"按钮
3. 验证按钮显示旋转动画
4. 等待结果：成功显示 ✓ + 延迟毫秒数；失败显示 ✕ 失败（hover 可见错误详情）
5. 点击"全部测试"按钮，验证逐个模型依次测试

- [ ] **Step 5: 最终提交（如有微调）**

```bash
git add -A
git commit -m "fix: minor adjustments after manual testing"
```
