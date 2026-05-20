# 分层重构实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 EIC-CC 项目对齐到「前端不连 LLM、controller 只做接口、业务全在 service、网络调用集中、超大组件拆分」的规范。

**Architecture:** 三阶段递进。阶段 1 后端引入 `support/` + 新 service,瘦身 controller;阶段 2 前端建立 `src/api/*.ts` 网络层并删除遗留 Express;阶段 3 拆分 App.vue / ChatPanel.vue / SettingsView.vue 三个超大组件。

**Tech Stack:** Spring Boot 3 / Java 17+;Vue 3 + TypeScript + Vite;原生 fetch(不引入 axios)。

**对应规范文档:** `docs/superpowers/specs/2026-05-20-layered-refactor-design.md`。

---

## 阶段 1:后端 controller 业务下沉

每个任务结束后跑一次 `cd backend && mvn -q -DskipTests package`,确保编译通过。最后一轮再做手工冒烟。

---

### 任务 1.1:新增 `support/SsePushUtils`

**Files:**
- Create: `backend/src/main/java/com/tuiyan/backend/support/SsePushUtils.java`

- [ ] **Step 1: 创建文件**

```java
package com.tuiyan.backend.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SseEmitter 装配与安全发送工具。
 * 提供统一的超时/异常处理 + cancelled 标志的 safeSend。
 */
public final class SsePushUtils {

    private static final Logger log = LoggerFactory.getLogger(SsePushUtils.class);

    private SsePushUtils() {}

    /**
     * 创建一个带超时兜底的 emitter:
     * - timeoutMs 超时 → 发送 name=error,data=timeoutMessage,然后 complete
     * - onCompletion / onError 时静默
     */
    public static SseEmitter newEmitter(long timeoutMs, String timeoutMessage) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        emitter.onTimeout(() -> {
            try {
                emitter.send(SseEmitter.event().name("error").data(timeoutMessage));
            } catch (IOException e) {
                log.warn("emit timeout-message failed: {}", e.toString());
            }
            emitter.complete();
        });
        emitter.onError(t -> log.warn("emitter error: {}", t.toString()));
        return emitter;
    }

    /**
     * 默认超时文案版本。
     */
    public static SseEmitter newEmitter(long timeoutMs) {
        return newEmitter(timeoutMs, "LLM 响应超时(>" + (timeoutMs / 1000) + "s),请检查 LLM 配置或网络后重试");
    }

    /**
     * 安全发送:cancelled 已置位时跳过;send 抛错时记日志并将 cancelled 置位,返回 false。
     */
    public static boolean safeSend(SseEmitter emitter, AtomicBoolean cancelled, String event, String data) {
        if (cancelled.get()) return false;
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
            return true;
        } catch (IllegalStateException stateErr) {
            log.warn("emitter already closed when sending {}: {}", event, stateErr.toString());
            cancelled.set(true);
            return false;
        } catch (IOException ioErr) {
            log.warn("emit {} failed (client disconnected?): {}", event, ioErr.toString());
            cancelled.set(true);
            return false;
        }
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `cd backend && mvn -q -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add backend/src/main/java/com/tuiyan/backend/support/SsePushUtils.java
git commit -m "refactor(backend): 新增 SsePushUtils,统一 SSE emitter 装配与 safeSend"
```

---

### 任务 1.2:新增 `support/PredictionMath`

**Files:**
- Create: `backend/src/main/java/com/tuiyan/backend/support/PredictionMath.java`

- [ ] **Step 1: 创建文件**

```java
package com.tuiyan.backend.support;

import com.tuiyan.backend.model.PredictRequest;

import java.util.List;
import java.util.Map;

/**
 * 推演纯数学/坐标计算助手。所有方法静态、无副作用。
 */
public final class PredictionMath {

    private PredictionMath() {}

    public static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.0;
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    public static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /**
     * 根据 req.seeds 在 req.nodes 中的坐标平均值,确定推演链的起始锚点。
     * seeds 为空或找不到时,回退到 (800, 300)。
     */
    public static double[] computeOrigin(PredictRequest req) {
        if (req.getSeeds() == null || req.getSeeds().isEmpty() || req.getNodes() == null) {
            return new double[]{800, 300};
        }
        double sx = 0, sy = 0;
        int n = 0;
        for (String sid : req.getSeeds()) {
            for (Map<String, Object> node : req.getNodes()) {
                if (sid.equals(node.get("id"))) {
                    Object xo = node.get("x"), yo = node.get("y");
                    if (xo instanceof Number && yo instanceof Number) {
                        sx += ((Number) xo).doubleValue();
                        sy += ((Number) yo).doubleValue();
                        n++;
                    }
                    break;
                }
            }
        }
        if (n == 0) return new double[]{800, 300};
        return new double[]{sx / n, sy / n};
    }

    /**
     * 在 req.nodes 中查找第一个 seed 的 label,用于场景命名兜底。
     */
    public static String lookupSeedLabel(PredictRequest req) {
        if (req.getSeeds() == null || req.getSeeds().isEmpty() || req.getNodes() == null) return null;
        String first = req.getSeeds().get(0);
        for (Map<String, Object> n : req.getNodes()) {
            if (first.equals(n.get("id"))) {
                Object lbl = n.get("label");
                return lbl == null ? null : String.valueOf(lbl);
            }
        }
        return null;
    }
}
```

- [ ] **Step 2: 编译验证 + 提交**

```bash
cd backend && mvn -q -DskipTests compile
git add backend/src/main/java/com/tuiyan/backend/support/PredictionMath.java
git commit -m "refactor(backend): 新增 PredictionMath,clamp01/round3/computeOrigin 等纯函数从 controller 抽出"
```

---

### 任务 1.3:新增 `support/IdSaltRewriter`

**Files:**
- Create: `backend/src/main/java/com/tuiyan/backend/support/IdSaltRewriter.java`

- [ ] **Step 1: 创建文件**

```java
package com.tuiyan.backend.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * id salt 重写:
 * 1) extract 流程:applyImportSalt — 给 LLM 草稿的 add_nodes/add_edges 整体加 salt 前缀,
 *    并深度扫描节点字段,命中 idMap 时替换。
 * 2) predict 流程:applyPredictionIdSalt — 仅对本批 chain 内、严格匹配 ^p_\d+$ 的 raw id 改写。
 */
public final class IdSaltRewriter {

    private static final ObjectMapper M = new ObjectMapper();

    private IdSaltRewriter() {}

    /**
     * 给 extract 草稿加 salt 前缀。返回 {nodes, edges} 的 root ObjectNode。
     */
    public static JsonNode applyImportSalt(JsonNode draft, String salt) {
        ArrayNode srcNodes = draft.has("add_nodes") && draft.get("add_nodes").isArray()
                ? (ArrayNode) draft.get("add_nodes") : M.createArrayNode();
        ArrayNode srcEdges = draft.has("add_edges") && draft.get("add_edges").isArray()
                ? (ArrayNode) draft.get("add_edges") : M.createArrayNode();

        Map<String, String> idMap = new HashMap<>();
        for (JsonNode n : srcNodes) {
            if (!n.has("id")) continue;
            String oldId = n.get("id").asText();
            if (oldId.isEmpty()) continue;
            idMap.put(oldId, "imp" + salt + "_" + oldId);
        }

        ArrayNode outNodes = M.createArrayNode();
        for (JsonNode n : srcNodes) {
            if (!n.has("id")) continue;
            ObjectNode copy = n.deepCopy();
            String oldId = copy.get("id").asText();
            String newId = idMap.getOrDefault(oldId, "imp" + salt + "_" + oldId);
            copy.put("id", newId);
            remapStringsDeep(copy, idMap, "id");
            outNodes.add(copy);
        }

        ArrayNode outEdges = M.createArrayNode();
        int eIdx = 0;
        for (JsonNode e : srcEdges) {
            String from = e.path("from").asText("");
            String to = e.path("to").asText("");
            String newFrom = idMap.getOrDefault(from, from);
            String newTo = idMap.getOrDefault(to, to);
            ObjectNode copy = e.deepCopy();
            copy.put("from", newFrom);
            copy.put("to", newTo);
            String oldEid = e.path("id").asText("");
            copy.put("id", oldEid.isEmpty()
                    ? "impe" + salt + "_" + (++eIdx)
                    : "imp" + salt + "_" + oldEid);
            remapStringsDeep(copy, idMap, "id");
            outEdges.add(copy);
        }

        ObjectNode root = M.createObjectNode();
        root.set("nodes", outNodes);
        root.set("edges", outEdges);
        return root;
    }

    /**
     * predict fork id 重写:只对本批 chain 内、严格匹配 ^p_\d+$ 的 raw id 改写为 p<salt>_N。
     * 引用祖先节点的 pXXX_N、trunk 节点 id 原样保留。
     */
    public static String applyPredictionIdSalt(String raw, String idSalt, Set<String> currentChainIds) {
        if (idSalt == null || idSalt.isEmpty()) return raw;
        if (raw == null) return null;
        if (!raw.matches("p_\\d+")) return raw;
        if (currentChainIds == null || !currentChainIds.contains(raw)) return raw;
        return "p" + idSalt + "_" + raw.substring(2);
    }

    private static void remapStringsDeep(JsonNode node, Map<String, String> idMap, String skipField) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                String fieldName = entry.getKey();
                JsonNode v = entry.getValue();
                if (v.isTextual()) {
                    if (skipField != null && skipField.equals(fieldName)) continue;
                    String mapped = idMap.get(v.asText());
                    if (mapped != null) obj.put(fieldName, mapped);
                } else if (v.isContainerNode()) {
                    remapStringsDeep(v, idMap, null);
                }
            }
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                JsonNode v = arr.get(i);
                if (v.isTextual()) {
                    String mapped = idMap.get(v.asText());
                    if (mapped != null) arr.set(i, arr.textNode(mapped));
                } else if (v.isContainerNode()) {
                    remapStringsDeep(v, idMap, null);
                }
            }
        }
    }
}
```

- [ ] **Step 2: 编译验证 + 提交**

```bash
cd backend && mvn -q -DskipTests compile
git add backend/src/main/java/com/tuiyan/backend/support/IdSaltRewriter.java
git commit -m "refactor(backend): 新增 IdSaltRewriter,整合 extract 与 predict 的 id salt 逻辑"
```

---

### 任务 1.4:新增 `support/FileSniffer`

**Files:**
- Create: `backend/src/main/java/com/tuiyan/backend/support/FileSniffer.java`

- [ ] **Step 1: 创建文件**

```java
package com.tuiyan.backend.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;

/**
 * 文件签名嗅探 + 文件名清洗。
 */
public final class FileSniffer {

    private static final Logger log = LoggerFactory.getLogger(FileSniffer.class);

    private FileSniffer() {}

    /**
     * 去除路径片段,仅保留字母/数字/点/下划线/横线;空时返回 "upload"。
     */
    public static String sanitizeFilename(String raw) {
        if (raw == null || raw.isBlank()) return "upload";
        String base = Paths.get(raw).getFileName().toString();
        String cleaned = base.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        if (cleaned.isBlank()) return "upload";
        return cleaned;
    }

    /**
     * 读取文件头 n 字节(不消耗主流)。
     */
    public static byte[] readHead(MultipartFile f, int n) {
        try (InputStream is = f.getInputStream()) {
            return is.readNBytes(n);
        } catch (IOException e) {
            log.warn("readHead failed for {}: {}", f.getOriginalFilename(), e.toString());
            return new byte[0];
        }
    }

    /** PDF magic bytes: 25 50 44 46 (%PDF) */
    public static boolean isPdfMagic(byte[] head) {
        if (head == null || head.length < 4) return false;
        return (head[0] & 0xff) == 0x25
                && (head[1] & 0xff) == 0x50
                && (head[2] & 0xff) == 0x44
                && (head[3] & 0xff) == 0x46;
    }

    /** PNG / JPEG / GIF / WebP magic bytes。 */
    public static boolean isImageMagic(byte[] head) {
        if (head == null || head.length < 4) return false;
        int b0 = head[0] & 0xff, b1 = head[1] & 0xff, b2 = head[2] & 0xff, b3 = head[3] & 0xff;
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return true;     // PNG
        if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return true;                     // JPEG
        if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46 && b3 == 0x38) return true;     // GIF
        if (head.length >= 12 && b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46
                && (head[8] & 0xff) == 0x57 && (head[9] & 0xff) == 0x45
                && (head[10] & 0xff) == 0x42 && (head[11] & 0xff) == 0x50) {        // WebP
            return true;
        }
        return false;
    }
}
```

- [ ] **Step 2: 编译验证 + 提交**

```bash
cd backend && mvn -q -DskipTests compile
git add backend/src/main/java/com/tuiyan/backend/support/FileSniffer.java
git commit -m "refactor(backend): 新增 FileSniffer,sanitizeFilename + PDF/图像 magic-byte 嗅探"
```

---

### 任务 1.5:新增 `support/PdfTextExtractor`

**Files:**
- Create: `backend/src/main/java/com/tuiyan/backend/support/PdfTextExtractor.java`

- [ ] **Step 1: 创建文件**

```java
package com.tuiyan.backend.support;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PDF 文本抽取 + 分页渲染为 PNG。
 */
public final class PdfTextExtractor {

    private PdfTextExtractor() {}

    /** PDF 文本抽取(失败时调用方处理 IOException)。 */
    public static String extractText(PDDocument doc) throws IOException {
        return new PDFTextStripper().getText(doc);
    }

    /**
     * 把 PDF 前 maxPages 页渲染成 PNG(dpi 指定),每张过滤体积限制 imageByteLimit。
     * 累计达 globalImageBudget 后停止。每页处理后 flush BufferedImage。
     * 直接把 dataUrl 附件追加到 attachments 列表。返回本次渲染张数。
     */
    public static int renderPages(PDDocument doc,
                                  List<Map<String, Object>> attachments,
                                  int maxPages,
                                  int dpi,
                                  long imageByteLimit,
                                  int globalImageBudget) throws IOException {
        PDFRenderer renderer = new PDFRenderer(doc);
        int total = Math.min(doc.getNumberOfPages(), maxPages);
        int rendered = 0;
        for (int p = 0; p < total; p++) {
            if (attachments.size() >= globalImageBudget) break;
            BufferedImage img = renderer.renderImageWithDPI(p, dpi);
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(img, "png", baos);
                byte[] bytes = baos.toByteArray();
                if (bytes.length > imageByteLimit) continue;
                String b64 = Base64.getEncoder().encodeToString(bytes);
                Map<String, Object> att = new LinkedHashMap<>();
                att.put("type", "image");
                att.put("dataUrl", "data:image/png;base64," + b64);
                attachments.add(att);
                rendered++;
            } finally {
                img.flush();
            }
        }
        return rendered;
    }
}
```

- [ ] **Step 2: 编译验证 + 提交**

```bash
cd backend && mvn -q -DskipTests compile
git add backend/src/main/java/com/tuiyan/backend/support/PdfTextExtractor.java
git commit -m "refactor(backend): 新增 PdfTextExtractor,PDF 文本抽取 + 分页渲染"
```

---

### 任务 1.6:新增 `GlobalExceptionHandler`

**Files:**
- Create: `backend/src/main/java/com/tuiyan/backend/config/GlobalExceptionHandler.java`

- [ ] **Step 1: 创建文件**

```java
package com.tuiyan.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.util.Map;

/**
 * 全局异常 → HTTP 状态码映射。
 * 让 controller 不再写 try/catch 业务分支。
 * 注意:SSE 路径已经建立 emitter 后无法走这里,需 controller 自行处理。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class,
                       MissingServletRequestParameterException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", e.getMessage() == null ? "请求参数错误" : e.getMessage()));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, Object>> handleIO(IOException e) {
        log.warn("IO 异常: {}", e.toString(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage() == null ? "服务器 IO 异常" : e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e) {
        log.warn("未处理异常: {}", e.toString(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage() == null ? "服务器内部错误" : e.getMessage()));
    }
}
```

- [ ] **Step 2: 编译验证 + 提交**

```bash
cd backend && mvn -q -DskipTests compile
git add backend/src/main/java/com/tuiyan/backend/config/GlobalExceptionHandler.java
git commit -m "refactor(backend): 新增 GlobalExceptionHandler,统一 4xx/5xx 异常映射"
```

---

### 任务 1.7:新增 `PrefsService`

**Files:**
- Create: `backend/src/main/java/com/tuiyan/backend/service/PrefsService.java`
- Modify: `backend/src/main/java/com/tuiyan/backend/controller/PrefsController.java`

- [ ] **Step 1: 创建 PrefsService**

```java
package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.config.AppPaths;
import com.tuiyan.backend.util.JsonAtomic;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class PrefsService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AppPaths appPaths;

    public PrefsService(AppPaths appPaths) {
        this.appPaths = appPaths;
    }

    public Map<String, Object> read() throws IOException {
        File f = appPaths.prefsFile();
        if (!f.exists()) return defaults();
        JsonNode node = objectMapper.readTree(f);
        Map<String, Object> out = new LinkedHashMap<>(defaults());
        if (node.isObject()) {
            node.fields().forEachRemaining(e ->
                    out.put(e.getKey(), objectMapper.convertValue(e.getValue(), Object.class)));
        }
        return out;
    }

    public Map<String, Object> save(Map<String, Object> body) throws IOException {
        Map<String, Object> merged = read();
        merged.putAll(body);
        JsonAtomic.write(objectMapper, appPaths.prefsFile(), merged);
        return merged;
    }

    /** 删除全部 scenarios 文件。返回删除张数。 */
    public int clearAllScenarios() {
        int n = 0;
        File dir = appPaths.scenariosDir();
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles((f, name) -> name.endsWith(".json"));
            if (files != null) {
                for (File f : files) {
                    if (f.delete()) n++;
                }
            }
        }
        return n;
    }

    private Map<String, Object> defaults() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("predictDefaultSteps", 4);
        d.put("predictMinConfidence", 0.3);
        d.put("predictStepDelayMs", 220);
        d.put("showEdgeLabels", true);
        d.put("autoFit", true);
        d.put("graphFontSize", 13);
        d.put("defaultModelConfigId", "");
        return d;
    }
}
```

- [ ] **Step 2: 瘦身 PrefsController**

替换 `backend/src/main/java/com/tuiyan/backend/controller/PrefsController.java` 全文为:

```java
package com.tuiyan.backend.controller;

import com.tuiyan.backend.service.PrefsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/prefs")
public class PrefsController {

    private final PrefsService prefsService;

    public PrefsController(PrefsService prefsService) {
        this.prefsService = prefsService;
    }

    @GetMapping
    public ResponseEntity<?> get() throws IOException {
        return ResponseEntity.ok(prefsService.read());
    }

    @PutMapping
    public ResponseEntity<?> save(@RequestBody Map<String, Object> body) throws IOException {
        return ResponseEntity.ok(prefsService.save(body));
    }

    @DeleteMapping("/scenarios")
    public ResponseEntity<?> clearAllScenarios() {
        int n = prefsService.clearAllScenarios();
        return ResponseEntity.ok(Map.of("success", n > 0, "count", n));
    }
}
```

- [ ] **Step 3: 编译验证**

```bash
cd backend && mvn -q -DskipTests package
```
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/java/com/tuiyan/backend/service/PrefsService.java backend/src/main/java/com/tuiyan/backend/controller/PrefsController.java
git commit -m "refactor(backend): 抽出 PrefsService,瘦身 PrefsController"
```

---

### 任务 1.8:`ConfigController` + `ModelController` + `ChatController` 瘦身

**Files:**
- Modify: `backend/src/main/java/com/tuiyan/backend/controller/ConfigController.java`
- Modify: `backend/src/main/java/com/tuiyan/backend/controller/ModelController.java`
- Modify: `backend/src/main/java/com/tuiyan/backend/controller/ChatController.java`
- Modify: `backend/src/main/java/com/tuiyan/backend/service/LlmService.java`(在 createModelConfig/updateModelConfig 头部加入参校验)

- [ ] **Step 1: 在 LlmService 中加入业务校验**

在 `LlmService.createModelConfig` 方法体最开始(约第 228 行处),追加:
```java
public ModelConfig createModelConfig(com.tuiyan.backend.controller.ModelController.ModelConfigRequest req) throws IOException {
    validateModelConfigRequest(req);
    // ...原有代码不变
```
在 `LlmService.updateModelConfig` 方法体最开始(约第 244 行处),追加:
```java
public ModelConfig updateModelConfig(String id, com.tuiyan.backend.controller.ModelController.ModelConfigRequest req) throws IOException {
    validateModelConfigRequest(req);
    // ...原有代码不变
```
在 `LlmService` 类内某处新增私有方法:
```java
private static void validateModelConfigRequest(com.tuiyan.backend.controller.ModelController.ModelConfigRequest req) {
    if (req.getName() == null || req.getName().isBlank()) {
        throw new IllegalArgumentException("名称不能为空");
    }
    if (req.getBaseUrl() == null || req.getBaseUrl().isBlank()) {
        throw new IllegalArgumentException("Base URL 不能为空");
    }
    if (req.getModelName() == null || req.getModelName().isBlank()) {
        throw new IllegalArgumentException("模型名称不能为空");
    }
}
```

- [ ] **Step 2: 瘦身 ConfigController**

替换 `controller/ConfigController.java` 全文为:

```java
package com.tuiyan.backend.controller;

import com.tuiyan.backend.model.ConfigRequest;
import com.tuiyan.backend.model.ConfigResponse;
import com.tuiyan.backend.service.LlmService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final LlmService llmService;

    public ConfigController(LlmService llmService) {
        this.llmService = llmService;
    }

    @GetMapping
    public ResponseEntity<ConfigResponse> getConfig() throws IOException {
        return ResponseEntity.ok(llmService.getConfigResponse());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> saveConfig(@RequestBody ConfigRequest request) throws IOException {
        llmService.saveConfig(request.getProvider(), request.getBaseUrl(), request.getModelName(), request.getApiKey());
        return ResponseEntity.ok(Map.of("success", true));
    }
}
```

- [ ] **Step 3: 瘦身 ModelController**

替换 `controller/ModelController.java` 的方法体(保留 ModelConfigRequest 内部类不变),整体替换为:

```java
package com.tuiyan.backend.controller;

import com.tuiyan.backend.model.ModelConfig;
import com.tuiyan.backend.service.LlmService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/models")
public class ModelController {

    private final LlmService llmService;

    public ModelController(LlmService llmService) {
        this.llmService = llmService;
    }

    @GetMapping
    public ResponseEntity<List<ModelConfig>> getAllModels() throws IOException {
        return ResponseEntity.ok(llmService.getAllModelConfigs());
    }

    @PostMapping
    public ResponseEntity<ModelConfig> createModel(@RequestBody ModelConfigRequest request) throws IOException {
        return ResponseEntity.ok(llmService.createModelConfig(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ModelConfig> updateModel(@PathVariable String id, @RequestBody ModelConfigRequest request) throws IOException {
        return ResponseEntity.ok(llmService.updateModelConfig(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteModel(@PathVariable String id) throws IOException {
        int removed = llmService.deleteModelConfig(id);
        return ResponseEntity.ok(Map.of("success", removed > 0, "count", removed));
    }

    @PatchMapping("/{id}/toggle")
    public ResponseEntity<Map<String, Object>> toggleModel(@PathVariable String id) throws IOException {
        llmService.toggleModelConfig(id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    public static class ModelConfigRequest {
        private String name;
        private String baseUrl;
        private String modelName;
        private String apiKey;
        private String provider;
        private String description;
        private Integer contextWindow;
        private Integer maxOutputTokens;
        private List<String> capabilities;
        private String protocol;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public Integer getContextWindow() { return contextWindow; }
        public void setContextWindow(Integer contextWindow) { this.contextWindow = contextWindow; }
        public Integer getMaxOutputTokens() { return maxOutputTokens; }
        public void setMaxOutputTokens(Integer maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
        public List<String> getCapabilities() { return capabilities; }
        public void setCapabilities(List<String> capabilities) { this.capabilities = capabilities; }
        public String getProtocol() { return protocol; }
        public void setProtocol(String protocol) { this.protocol = protocol; }
    }
}
```

注意 `IllegalArgumentException` 在 LlmService 抛出 → GlobalExceptionHandler 统一转 400,故 controller 不再 catch。

- [ ] **Step 4: 瘦身 ChatController**

替换 `controller/ChatController.java` 全文为:

```java
package com.tuiyan.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tuiyan.backend.model.ChatRequest;
import com.tuiyan.backend.service.LlmService;
import com.tuiyan.backend.support.SsePushUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final LlmService llmService;

    public ChatController(LlmService llmService) {
        this.llmService = llmService;
    }

    @PostMapping
    public Object chat(@RequestBody ChatRequest request,
                       @RequestHeader(value = "Accept", defaultValue = "application/json") String accept) throws Exception {
        if (accept.contains("text/event-stream")) {
            SseEmitter emitter = SsePushUtils.newEmitter(180_000L);
            llmService.chatStreaming(request, emitter);
            return emitter;
        }
        JsonNode result = llmService.chat(request.getNodes(), request.getEdges(), request.getMessage(),
                request.getModelOverride(), request.getConfigId(), request.getHistory(), request.getAttachments());
        return ResponseEntity.ok(result);
    }
}
```

(注意:同步 chat 抛 Exception 的情况由 GlobalExceptionHandler 转 500;若是 IllegalStateException(如缺 API key)则自动转 400。)

- [ ] **Step 5: 编译验证**

```bash
cd backend && mvn -q -DskipTests package
```
Expected: BUILD SUCCESS

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/tuiyan/backend/service/LlmService.java backend/src/main/java/com/tuiyan/backend/controller/ConfigController.java backend/src/main/java/com/tuiyan/backend/controller/ModelController.java backend/src/main/java/com/tuiyan/backend/controller/ChatController.java
git commit -m "refactor(backend): 瘦身 Config/Model/Chat controller,业务校验下沉至 LlmService"
```

---

### 任务 1.9:新增 `DocumentExtractionService` + 瘦身 `OntologyModelController.extract`

**Files:**
- Create: `backend/src/main/java/com/tuiyan/backend/service/DocumentExtractionService.java`
- Modify: `backend/src/main/java/com/tuiyan/backend/controller/OntologyModelController.java`

- [ ] **Step 1: 创建 DocumentExtractionService**

```java
package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.tuiyan.backend.support.FileSniffer;
import com.tuiyan.backend.support.IdSaltRewriter;
import com.tuiyan.backend.support.PdfTextExtractor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DocumentExtractionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentExtractionService.class);

    private static final int PDF_TEXT_CHAR_BUDGET = 60_000;
    private static final long IMAGE_BYTE_LIMIT    = 8L * 1024 * 1024;
    private static final int  TOTAL_FILE_LIMIT    = 8;
    private static final int  MIN_TEXT_PER_PAGE   = 200;
    private static final int  RENDER_MAX_PAGES    = 8;
    private static final int  RENDER_DPI          = 110;
    private static final int  TOTAL_IMAGE_BUDGET  = 12;
    private static final long PDF_FILE_BYTES_LIMIT = 12L * 1024 * 1024;

    private final LlmService llmService;

    public DocumentExtractionService(LlmService llmService) {
        this.llmService = llmService;
    }

    /**
     * 文件 → LLM 抽取 → salt 重写。返回 {nodes, edges, reply, sources, salt}。
     * 入参不合法时抛 IllegalArgumentException。
     */
    public Map<String, Object> extract(List<MultipartFile> files, String modelOverride, String configId) throws Exception {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("未提供文件");
        }
        if (files.size() > TOTAL_FILE_LIMIT) {
            throw new IllegalArgumentException("一次最多 " + TOTAL_FILE_LIMIT + " 个文件");
        }

        StringBuilder combinedText = new StringBuilder();
        List<Map<String, Object>> imageAttachments = new ArrayList<>();
        List<Map<String, Object>> sourcesMeta = new ArrayList<>();

        for (MultipartFile f : files) {
            String safeName = FileSniffer.sanitizeFilename(f.getOriginalFilename());
            String contentType = f.getContentType();
            long size = f.getSize();
            if (size <= 0) continue;

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("name", safeName);
            meta.put("contentType", contentType);
            meta.put("size", size);

            byte[] head = FileSniffer.readHead(f, 8);
            boolean pdfMagic = FileSniffer.isPdfMagic(head);
            boolean imageMagic = FileSniffer.isImageMagic(head);
            boolean isPdf = (contentType != null && contentType.toLowerCase().contains("pdf") && pdfMagic)
                    || (safeName.toLowerCase().endsWith(".pdf") && pdfMagic);
            boolean isImage = (contentType != null && contentType.toLowerCase().startsWith("image/") && imageMagic)
                    || imageMagic;

            if (isPdf) {
                handlePdf(f, safeName, size, meta, combinedText, imageAttachments);
            } else if (isImage) {
                handleImage(f, safeName, size, contentType, meta, imageAttachments);
            } else {
                meta.put("type", "skipped");
                meta.put("reason", "不支持的 content-type 或文件签名:" + contentType);
            }
            sourcesMeta.add(meta);
        }

        if (combinedText.length() == 0 && imageAttachments.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "未能从上传文件中抽出任何可分析的文本或图片");
            err.put("sources", sourcesMeta);
            throw new IllegalArgumentException("未能从上传文件中抽出任何可分析的文本或图片");
        }

        JsonNode draft = llmService.extractOntologyFromSources(
                combinedText.toString(), imageAttachments, modelOverride, configId);

        String salt = Long.toString(System.currentTimeMillis(), 36);
        JsonNode rewritten = IdSaltRewriter.applyImportSalt(draft, salt);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", rewritten.path("nodes"));
        out.put("edges", rewritten.path("edges"));
        out.put("reply", draft.path("reply").asText(""));
        out.put("sources", sourcesMeta);
        out.put("salt", salt);
        return out;
    }

    private void handlePdf(MultipartFile f, String safeName, long size,
                           Map<String, Object> meta, StringBuilder combinedText,
                           List<Map<String, Object>> imageAttachments) throws IOException {
        if (size > PDF_FILE_BYTES_LIMIT) {
            throw new IllegalArgumentException(
                    "PDF " + safeName + " 超过 " + (PDF_FILE_BYTES_LIMIT / (1024 * 1024)) + " MB 限制");
        }
        String text;
        int pageCount;
        try (PDDocument doc = Loader.loadPDF(f.getBytes())) {
            pageCount = doc.getNumberOfPages();
            meta.put("pages", pageCount);
            text = PdfTextExtractor.extractText(doc);

            int rawLen = text == null ? 0 : text.trim().length();
            boolean textBare = pageCount > 0 && rawLen < pageCount * MIN_TEXT_PER_PAGE;
            if (textBare) {
                int rendered = PdfTextExtractor.renderPages(doc, imageAttachments,
                        RENDER_MAX_PAGES, RENDER_DPI, IMAGE_BYTE_LIMIT, TOTAL_IMAGE_BUDGET);
                meta.put("renderedPages", rendered);
                if (text != null && text.length() > 4_000) {
                    text = text.substring(0, 4_000);
                }
            }
        }
        int rawChars = text == null ? 0 : text.length();
        if (text != null && text.length() > PDF_TEXT_CHAR_BUDGET) {
            text = text.substring(0, PDF_TEXT_CHAR_BUDGET) + "\n[…truncated…]";
            meta.put("truncated", true);
        }
        meta.put("type", "pdf");
        meta.put("chars", rawChars);
        if (text != null && !text.isBlank()) {
            combinedText.append("# 文件 ").append(safeName).append("\n\n")
                        .append(text).append("\n\n");
        }
    }

    private void handleImage(MultipartFile f, String safeName, long size, String contentType,
                             Map<String, Object> meta,
                             List<Map<String, Object>> imageAttachments) throws IOException {
        if (size > IMAGE_BYTE_LIMIT) {
            throw new IllegalArgumentException(
                    "图片 " + safeName + " 超过 " + (IMAGE_BYTE_LIMIT / (1024 * 1024)) + " MB 限制");
        }
        if (imageAttachments.size() >= TOTAL_IMAGE_BUDGET) {
            meta.put("type", "skipped");
            meta.put("reason", "已达全局图片预算 " + TOTAL_IMAGE_BUDGET + " 张");
            return;
        }
        String b64 = Base64.getEncoder().encodeToString(f.getBytes());
        String mediaType = (contentType != null && contentType.toLowerCase().startsWith("image/"))
                ? contentType : "image/png";
        String dataUrl = "data:" + mediaType + ";base64," + b64;
        Map<String, Object> att = new LinkedHashMap<>();
        att.put("type", "image");
        att.put("dataUrl", dataUrl);
        imageAttachments.add(att);
        meta.put("type", "image");
    }
}
```

- [ ] **Step 2: 瘦身 OntologyModelController(替换全文)**

```java
package com.tuiyan.backend.controller;

import com.tuiyan.backend.model.OntologyModel;
import com.tuiyan.backend.service.DocumentExtractionService;
import com.tuiyan.backend.service.OntologyModelService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ontology-models")
public class OntologyModelController {

    private final OntologyModelService svc;
    private final DocumentExtractionService extractionService;

    public OntologyModelController(OntologyModelService svc, DocumentExtractionService extractionService) {
        this.svc = svc;
        this.extractionService = extractionService;
    }

    @GetMapping
    public ResponseEntity<?> list() throws IOException {
        return ResponseEntity.ok(svc.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) throws IOException {
        OntologyModel m = svc.get(id);
        return m == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(m);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody OntologyModel m) throws IOException {
        return ResponseEntity.ok(svc.save(m));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody OntologyModel m) throws IOException {
        m.setId(id);
        return ResponseEntity.ok(svc.save(m));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        boolean ok = svc.delete(id);
        return ResponseEntity.ok(Map.of("success", ok, "count", ok ? 1 : 0));
    }

    @PostMapping(value = "/extract", consumes = {"multipart/form-data"})
    public ResponseEntity<?> extract(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "modelOverride", required = false) String modelOverride,
            @RequestParam(value = "configId", required = false) String configId) throws Exception {
        return ResponseEntity.ok(extractionService.extract(files, modelOverride, configId));
    }
}
```

- [ ] **Step 3: 编译验证**

```bash
cd backend && mvn -q -DskipTests package
```
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/java/com/tuiyan/backend/service/DocumentExtractionService.java backend/src/main/java/com/tuiyan/backend/controller/OntologyModelController.java
git commit -m "refactor(backend): 抽出 DocumentExtractionService,OntologyModelController 从 394 行缩为 60 行"
```

---

### 任务 1.10:新增 `PredictionOrchestrator` + 瘦身 `ScenarioController`

**Files:**
- Create: `backend/src/main/java/com/tuiyan/backend/service/PredictionOrchestrator.java`
- Modify: `backend/src/main/java/com/tuiyan/backend/controller/ScenarioController.java`

- [ ] **Step 1: 创建 PredictionOrchestrator**

```java
package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tuiyan.backend.model.Constraint;
import com.tuiyan.backend.model.PredictRequest;
import com.tuiyan.backend.model.PredictionDag;
import com.tuiyan.backend.model.Scenario;
import com.tuiyan.backend.support.IdSaltRewriter;
import com.tuiyan.backend.support.PredictionMath;
import com.tuiyan.backend.support.SsePushUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 推演编排:执行 LLM 调用 + 构造图节点/边 + SSE 分步推送 + 持久化 Scenario。
 */
@Service
public class PredictionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PredictionOrchestrator.class);

    private static final double X_STEP = 220;
    private static final double Y_STEP = 100;
    private static final long STEP_DELAY_MS = 220;

    private final LlmService llmService;
    private final ScenarioService scenarioService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PredictionOrchestrator(LlmService llmService, ScenarioService scenarioService) {
        this.llmService = llmService;
        this.scenarioService = scenarioService;
    }

    /**
     * 主入口:由 controller 注入的 executor 调用。
     * cancelled 由 emitter 的 onCompletion / onTimeout 通过外部封装设置;此处简化为局部变量。
     */
    public void run(PredictRequest req, SseEmitter emitter) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onError(t -> cancelled.set(true));

        try {
            String intent = "backward".equalsIgnoreCase(req.getIntent()) ? "backward" : "forward";
            boolean backward = "backward".equals(intent);

            boolean isFork = req.getParentBranchId() != null && !req.getParentBranchId().isBlank();
            long now = System.currentTimeMillis();
            String scenarioId = "sc_" + now;
            String idSalt = isFork ? Long.toString(now, 36) : "";

            if (cancelled.get()) return;
            JsonNode result = llmService.predictChain(req);
            JsonNode chain = result.path("chain");
            if (!chain.isArray() || chain.isEmpty()) {
                SsePushUtils.safeSend(emitter, cancelled, "error", "LLM 未返回有效推演链");
                if (!cancelled.get()) emitter.complete();
                return;
            }

            Set<String> currentChainIds = new HashSet<>();
            for (JsonNode item : chain) {
                String raw = item.path("id").asText("");
                if (!raw.isEmpty()) currentChainIds.add(raw);
            }

            double[] origin = PredictionMath.computeOrigin(req);
            double baseX = origin[0];
            double baseY = origin[1];
            int direction = backward ? -1 : 1;

            Set<String> blockedIds = new HashSet<>();
            if (req.getConstraints() != null) {
                for (Constraint c : req.getConstraints()) {
                    if (c == null || c.getNodeId() == null) continue;
                    if ("block".equalsIgnoreCase(c.getMode())) blockedIds.add(c.getNodeId());
                }
            }

            Map<String, Double> effProb = new HashMap<>();
            List<Map<String, Object>> predictedNodes = new ArrayList<>();
            List<Map<String, Object>> predictedEdges = new ArrayList<>();
            List<Map<String, Object>> chainList = new ArrayList<>();
            Map<Integer, Integer> perStepCount = new HashMap<>();
            int prunedCount = 0;

            int stepIndex = 0;
            for (JsonNode item : chain) {
                if (cancelled.get()) return;
                stepIndex++;
                StepBuildResult sr = buildStep(item, stepIndex, idSalt, currentChainIds,
                        backward, blockedIds, effProb, perStepCount, baseX, baseY, direction);
                if (sr == null) { prunedCount++; blockedIds.add(applyId(item, stepIndex, idSalt, currentChainIds)); continue; }
                predictedNodes.add(sr.node);
                predictedEdges.addAll(sr.edges);
                chainList.add(sr.chainItem);

                ObjectNode stepEvent = objectMapper.createObjectNode();
                stepEvent.put("step", sr.step);
                stepEvent.put("intent", intent);
                stepEvent.set("node", objectMapper.valueToTree(sr.node));
                stepEvent.set("edges", objectMapper.valueToTree(sr.edges));
                stepEvent.set("chain", objectMapper.valueToTree(sr.chainItem));

                if (cancelled.get()) return;
                if (!SsePushUtils.safeSend(emitter, cancelled, "step", objectMapper.writeValueAsString(stepEvent))) {
                    return;
                }
                try { Thread.sleep(STEP_DELAY_MS); } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            if (cancelled.get()) return;
            Scenario s = buildScenario(req, intent, backward, now, scenarioId, chain.size(), predictedNodes, predictedEdges, chainList);

            if (prunedCount > 0) {
                ObjectNode note = objectMapper.createObjectNode();
                note.put("type", "pruned");
                note.put("count", prunedCount);
                note.put("message", "已根据 what-if 约束剪枝 " + prunedCount + " 个预测节点");
                SsePushUtils.safeSend(emitter, cancelled, "notice", objectMapper.writeValueAsString(note));
            }

            scenarioService.save(s);
            SsePushUtils.safeSend(emitter, cancelled, "complete", objectMapper.writeValueAsString(s));
            if (!cancelled.get()) emitter.complete();
        } catch (Exception e) {
            log.warn("runPrediction failed: {}", e.toString(), e);
            SsePushUtils.safeSend(emitter, cancelled, "error", e.getMessage() == null ? "推演失败" : e.getMessage());
            try {
                if (!cancelled.get()) emitter.completeWithError(e);
            } catch (Exception completeErr) {
                log.warn("completeWithError after failure also failed: {}", completeErr.toString());
            }
        }
    }

    private String applyId(JsonNode item, int stepIndex, String idSalt, Set<String> currentChainIds) {
        String rawId = item.path("id").asText("p_" + stepIndex);
        return IdSaltRewriter.applyPredictionIdSalt(rawId, idSalt, currentChainIds);
    }

    private static class StepBuildResult {
        Map<String, Object> node;
        List<Map<String, Object>> edges;
        Map<String, Object> chainItem;
        int step;
    }

    @SuppressWarnings("unchecked")
    private StepBuildResult buildStep(JsonNode item, int stepIndex, String idSalt, Set<String> currentChainIds,
                                      boolean backward, Set<String> blockedIds, Map<String, Double> effProb,
                                      Map<Integer, Integer> perStepCount, double baseX, double baseY, int direction) {
        String rawId = item.path("id").asText("p_" + stepIndex);
        String id = IdSaltRewriter.applyPredictionIdSalt(rawId, idSalt, currentChainIds);
        String label = item.path("label").asText("预测" + stepIndex);
        String type = item.path("type").asText("event");
        String ruleId = item.path("rule_id").isNull() ? null : item.path("rule_id").asText(null);
        String explanation = item.path("explanation").asText("");
        double confidence = item.path("confidence").asDouble(0.6);
        int step = item.path("step").asInt(stepIndex);

        JsonNode linkNode = backward
                ? (item.has("leads_to") ? item.get("leads_to") : item.path("triggered_by"))
                : (item.has("triggered_by") ? item.get("triggered_by") : item.path("leads_to"));

        List<String> rawLinkIds = new ArrayList<>();
        if (linkNode != null && linkNode.isArray()) {
            for (JsonNode t : linkNode) {
                rawLinkIds.add(IdSaltRewriter.applyPredictionIdSalt(t.asText(), idSalt, currentChainIds));
            }
        }
        List<String> linkIds = new ArrayList<>();
        for (String lid : rawLinkIds) {
            if (!blockedIds.contains(lid)) linkIds.add(lid);
        }
        if (!rawLinkIds.isEmpty() && linkIds.isEmpty()) {
            return null;
        }

        int slot = perStepCount.getOrDefault(step, 0);
        perStepCount.put(step, slot + 1);
        double nx = baseX + direction * step * X_STEP;
        double ny = baseY + (slot - 0.5) * Y_STEP;

        double pEff;
        if (backward || linkIds.isEmpty()) {
            pEff = PredictionMath.clamp01(confidence);
        } else {
            double notOr = 1.0;
            for (String pid : linkIds) {
                double pp = effProb.containsKey(pid) ? effProb.get(pid) : 1.0;
                notOr *= (1.0 - PredictionMath.clamp01(pp));
            }
            pEff = PredictionMath.clamp01(confidence) * (1.0 - notOr);
        }
        effProb.put(id, pEff);

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("label", label);
        node.put("type", type);
        node.put("source", "predicted");
        node.put("predictedStep", step);
        node.put("predictedIntent", backward ? "backward" : "forward");
        node.put("confidence", confidence);
        node.put("effectiveProbability", PredictionMath.round3(pEff));
        node.put("explanation", explanation);
        node.put("x", nx);
        node.put("y", ny);

        List<Map<String, Object>> edges = new ArrayList<>();
        for (String otherId : linkIds) {
            String from = backward ? id : otherId;
            String to = backward ? otherId : id;
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("id", "pe_" + from + "_" + to);
            edge.put("from", from);
            edge.put("to", to);
            edge.put("label", backward ? "可能导致" : "推演");
            edge.put("source", "predicted");
            edge.put("rule_driven", ruleId != null);
            if (ruleId != null) edge.put("ruleId", ruleId);
            edges.add(edge);
        }

        Map<String, Object> chainItem = new LinkedHashMap<>();
        chainItem.put("step", step);
        chainItem.put("nodeId", id);
        chainItem.put("label", label);
        chainItem.put("type", type);
        chainItem.put("triggeredBy", linkIds);
        chainItem.put("ruleId", ruleId);
        chainItem.put("explanation", explanation);
        chainItem.put("confidence", confidence);
        chainItem.put("effectiveProbability", PredictionMath.round3(pEff));

        StepBuildResult sr = new StepBuildResult();
        sr.node = node;
        sr.edges = edges;
        sr.chainItem = chainItem;
        sr.step = step;
        return sr;
    }

    private Scenario buildScenario(PredictRequest req, String intent, boolean backward, long now,
                                   String scenarioId, int chainSize,
                                   List<Map<String, Object>> predictedNodes,
                                   List<Map<String, Object>> predictedEdges,
                                   List<Map<String, Object>> chainList) {
        Scenario s = new Scenario();
        s.setId(scenarioId);
        s.setModelId(req.getModelId());
        s.setParentBranchId(req.getParentBranchId());
        s.setCreatedAt(now);
        s.setIntent(intent);
        s.setSeeds(req.getSeeds());
        s.setSteps(req.getSteps() == null ? chainSize : req.getSteps());
        s.setPrompt(req.getPrompt());

        String name = req.getName();
        if (name == null || name.isBlank()) {
            String seedLabel = PredictionMath.lookupSeedLabel(req);
            String prefix = backward ? "溯因·" : "";
            name = prefix + (seedLabel != null ? seedLabel : "推演") + " · "
                    + new SimpleDateFormat("MM-dd HH:mm").format(new Date());
        }
        s.setName(name);

        PredictionDag dag = new PredictionDag();
        dag.setIntent(intent);
        dag.setNodes(predictedNodes);
        dag.setEdges(predictedEdges);
        dag.setChain(chainList);
        dag.setConstraints(req.getConstraints());
        s.setDag(dag);
        s.setChain(chainList);
        return s;
    }
}
```

- [ ] **Step 2: 瘦身 ScenarioController(替换全文)**

```java
package com.tuiyan.backend.controller;

import com.tuiyan.backend.model.PredictRequest;
import com.tuiyan.backend.model.Scenario;
import com.tuiyan.backend.service.PredictionOrchestrator;
import com.tuiyan.backend.service.ScenarioService;
import com.tuiyan.backend.support.SsePushUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {

    private final ScenarioService scenarioService;
    private final PredictionOrchestrator predictionOrchestrator;
    private final TaskExecutor predictionExecutor;

    public ScenarioController(ScenarioService scenarioService,
                              PredictionOrchestrator predictionOrchestrator,
                              @Qualifier("predictionExecutor") TaskExecutor predictionExecutor) {
        this.scenarioService = scenarioService;
        this.predictionOrchestrator = predictionOrchestrator;
        this.predictionExecutor = predictionExecutor;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String modelId) throws IOException {
        return ResponseEntity.ok(scenarioService.listByModel(modelId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getOne(@PathVariable String id) throws IOException {
        Scenario s = scenarioService.get(id);
        return s == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(s);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        int n = scenarioService.delete(id);
        return ResponseEntity.ok(Map.of("success", n > 0, "count", n));
    }

    @PostMapping("/migrate")
    public ResponseEntity<?> migrate() throws IOException {
        return ResponseEntity.ok(scenarioService.migrateAll());
    }

    @PostMapping
    public SseEmitter predict(@RequestBody PredictRequest req) {
        SseEmitter emitter = SsePushUtils.newEmitter(180_000L);
        predictionExecutor.execute(() -> predictionOrchestrator.run(req, emitter));
        return emitter;
    }
}
```

- [ ] **Step 3: 编译验证**

```bash
cd backend && mvn -q -DskipTests package
```
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/java/com/tuiyan/backend/service/PredictionOrchestrator.java backend/src/main/java/com/tuiyan/backend/controller/ScenarioController.java
git commit -m "refactor(backend): 抽出 PredictionOrchestrator,ScenarioController 从 387 行缩为 60 行"
```

---

### 任务 1.11:阶段 1 手工冒烟

- [ ] **Step 1: 启动后端 + 前端**

```bash
cd backend && mvn spring-boot:run &
cd ../frontend && npm run dev
```

- [ ] **Step 2: 浏览器手工冒烟**

测试以下三条路径,确认行为与重构前一致:
1. 欢迎页输入文本 → /api/chat 返回图谱草稿;
2. 选种子节点 → /api/scenarios 推演 → SSE 分步显示,完成后保存为新分支;
3. 导入对话框上传 PDF/图片 → /api/ontology-models/extract → 抽取结果合并入图。

- [ ] **Step 3: 阶段 1 收口提交(若有补丁修正)**

如有冒烟发现的问题修复,补一个 `fix(backend): 阶段 1 冒烟修复 <具体描述>` 的 commit。

---

## 阶段 2:前端 API 层 + 删除遗留

每个任务结束后跑 `cd frontend && npm run build`(或 `npm run lint` = `tsc --noEmit`)确认编译通过。

---

### 任务 2.1:新增 `api/http.ts`

**Files:**
- Create: `frontend/src/api/http.ts`

- [ ] **Step 1: 创建文件**

```ts
export class ApiError extends Error {
  status: number;
  body: unknown;
  constructor(status: number, message: string, body: unknown) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.body = body;
  }
}

async function parseError(res: Response): Promise<{ msg: string; body: unknown }> {
  const text = await res.text();
  if (!text) return { msg: `HTTP ${res.status}`, body: null };
  try {
    const json = JSON.parse(text);
    const msg = (json && typeof json === 'object' && 'error' in json)
      ? String((json as { error: unknown }).error)
      : `HTTP ${res.status}`;
    return { msg, body: json };
  } catch {
    return { msg: text || `HTTP ${res.status}`, body: text };
  }
}

/** 统一 JSON 请求。非 2xx 抛 ApiError;204 / 空响应返回 undefined。 */
export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers || {});
  if (!headers.has('Accept')) headers.set('Accept', 'application/json');
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  const res = await fetch(path, { ...init, headers });
  if (!res.ok) {
    const { msg, body } = await parseError(res);
    throw new ApiError(res.status, msg, body);
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  if (!text) return undefined as T;
  return JSON.parse(text) as T;
}

export interface SseHandlers {
  onEvent: (event: string, data: string) => void;
  onError?: (err: Error) => void;
  onClose?: () => void;
}

export interface SseHandle {
  abort: () => void;
}

/**
 * POST 一份 JSON body,服务端返回 text/event-stream;按 SSE 行协议解析后回调 onEvent。
 * 调用方负责把 onEvent 的 data 字符串(可能是 JSON)再解一层。
 */
export function sse(path: string, body: unknown, handlers: SseHandlers): SseHandle {
  const ctrl = new AbortController();
  (async () => {
    try {
      const res = await fetch(path, {
        method: 'POST',
        headers: {
          'Accept': 'text/event-stream',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(body),
        signal: ctrl.signal,
      });
      if (!res.ok || !res.body) {
        const { msg } = await parseError(res);
        handlers.onError?.(new ApiError(res.status, msg, null));
        return;
      }
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      let currentEvent = 'message';
      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        let idx: number;
        while ((idx = buffer.indexOf('\n')) >= 0) {
          const line = buffer.slice(0, idx).replace(/\r$/, '');
          buffer = buffer.slice(idx + 1);
          if (line === '') { currentEvent = 'message'; continue; }
          if (line.startsWith('event:')) {
            currentEvent = line.slice(6).trim();
          } else if (line.startsWith('data:')) {
            const data = line.slice(5).trim();
            handlers.onEvent(currentEvent, data);
          }
        }
      }
      handlers.onClose?.();
    } catch (err) {
      if ((err as Error).name === 'AbortError') return;
      handlers.onError?.(err as Error);
    }
  })();
  return { abort: () => ctrl.abort() };
}
```

- [ ] **Step 2: 编译验证 + 提交**

```bash
cd frontend && npm run lint
git add frontend/src/api/http.ts
git commit -m "refactor(frontend): 新增 api/http.ts,统一 request<T> 与 sse() 封装"
```

---

### 任务 2.2:新增 `api/config.ts` + `api/prefs.ts` + `api/models.ts`

**Files:**
- Create: `frontend/src/api/config.ts`
- Create: `frontend/src/api/prefs.ts`
- Create: `frontend/src/api/models.ts`

- [ ] **Step 1: 创建 `api/config.ts`**

```ts
import { request } from './http';

export interface ProviderInfo {
  code: string;
  displayName: string;
  baseUrl: string;
  defaultModel: string;
  models: string[];
  apiKeyEnvName: string;
}

export interface ModelConfigInfo {
  id: string;
  name: string;
  baseUrl: string;
  modelName: string;
  enabled: boolean;
  provider?: string;
  description?: string;
  contextWindow?: number;
  maxOutputTokens?: number;
  capabilities?: string[];
  protocol?: string;
}

export interface ConfigResponse {
  provider: string;
  baseUrl: string;
  modelName: string;
  providers: ProviderInfo[];
  customModels: ModelConfigInfo[];
}

export function getConfig() {
  return request<ConfigResponse>('/api/config');
}

export function saveConfig(payload: { provider: string; baseUrl: string; modelName: string; apiKey?: string }) {
  return request<{ success: true }>('/api/config', { method: 'POST', body: JSON.stringify(payload) });
}
```

- [ ] **Step 2: 创建 `api/prefs.ts`**

```ts
import { request } from './http';

export interface Prefs {
  predictDefaultSteps: number;
  predictMinConfidence: number;
  predictStepDelayMs: number;
  showEdgeLabels: boolean;
  autoFit: boolean;
  graphFontSize: number;
  defaultModelConfigId: string;
  [k: string]: unknown;
}

export function getPrefs() {
  return request<Prefs>('/api/prefs');
}

export function savePrefs(patch: Partial<Prefs>) {
  return request<Prefs>('/api/prefs', { method: 'PUT', body: JSON.stringify(patch) });
}

export function clearAllScenarios() {
  return request<{ success: boolean; count: number }>('/api/prefs/scenarios', { method: 'DELETE' });
}
```

- [ ] **Step 3: 创建 `api/models.ts`**

```ts
import { request } from './http';

export interface ModelConfig {
  id: string;
  name: string;
  baseUrl: string;
  modelName: string;
  apiKey?: string;
  enabled: boolean;
  provider?: string;
  description?: string;
  contextWindow?: number;
  maxOutputTokens?: number;
  capabilities?: string[];
  protocol?: string;
  createdAt?: number;
  updatedAt?: number;
}

export interface ModelConfigPayload {
  name: string;
  baseUrl: string;
  modelName: string;
  apiKey?: string;
  provider?: string;
  description?: string;
  contextWindow?: number;
  maxOutputTokens?: number;
  capabilities?: string[];
  protocol?: string;
}

export function listModels() {
  return request<ModelConfig[]>('/api/models');
}

export function createModel(payload: ModelConfigPayload) {
  return request<ModelConfig>('/api/models', { method: 'POST', body: JSON.stringify(payload) });
}

export function updateModel(id: string, payload: ModelConfigPayload) {
  return request<ModelConfig>(`/api/models/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(payload) });
}

export function deleteModel(id: string) {
  return request<{ success: boolean; count: number }>(`/api/models/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

export function toggleModel(id: string) {
  return request<{ success: true }>(`/api/models/${encodeURIComponent(id)}/toggle`, { method: 'PATCH' });
}
```

- [ ] **Step 4: 编译验证 + 提交**

```bash
cd frontend && npm run lint
git add frontend/src/api/config.ts frontend/src/api/prefs.ts frontend/src/api/models.ts
git commit -m "refactor(frontend): 新增 api/config, api/prefs, api/models"
```

---

### 任务 2.3:新增 `api/ontology.ts` + `api/scenarios.ts` + `api/chat.ts`

**Files:**
- Create: `frontend/src/api/ontology.ts`
- Create: `frontend/src/api/scenarios.ts`
- Create: `frontend/src/api/chat.ts`

- [ ] **Step 1: 创建 `api/ontology.ts`**

```ts
import { request } from './http';
import type { OntologyModel, OntologyNode, OntologyEdge } from '../types';

export function listOntologies() {
  return request<OntologyModel[]>('/api/ontology-models');
}

export function getOntology(id: string) {
  return request<OntologyModel>(`/api/ontology-models/${encodeURIComponent(id)}`);
}

export function saveOntology(model: OntologyModel) {
  return request<OntologyModel>('/api/ontology-models', {
    method: 'POST',
    body: JSON.stringify(model),
  });
}

export function updateOntology(id: string, model: OntologyModel) {
  return request<OntologyModel>(`/api/ontology-models/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify(model),
  });
}

export function deleteOntology(id: string) {
  return request<{ success: boolean; count: number }>(
    `/api/ontology-models/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

export interface ExtractResult {
  nodes: OntologyNode[];
  edges: OntologyEdge[];
  reply: string;
  sources: Array<Record<string, unknown>>;
  salt: string;
}

export function extractFromFiles(files: File[], opts?: { modelOverride?: string; configId?: string }) {
  const fd = new FormData();
  for (const f of files) fd.append('files', f);
  if (opts?.modelOverride) fd.append('modelOverride', opts.modelOverride);
  if (opts?.configId) fd.append('configId', opts.configId);
  return request<ExtractResult>('/api/ontology-models/extract', { method: 'POST', body: fd });
}
```

- [ ] **Step 2: 创建 `api/scenarios.ts`**

```ts
import { request, sse, type SseHandle } from './http';
import type { Scenario } from '../types';

export function listScenarios(modelId?: string) {
  const q = modelId ? `?modelId=${encodeURIComponent(modelId)}` : '';
  return request<Scenario[]>(`/api/scenarios${q}`);
}

export function getScenario(id: string) {
  return request<Scenario>(`/api/scenarios/${encodeURIComponent(id)}`);
}

export function deleteScenario(id: string) {
  return request<{ success: boolean; count: number }>(
    `/api/scenarios/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

export function migrateScenarios() {
  return request<Record<string, number>>('/api/scenarios/migrate', { method: 'POST' });
}

export interface PredictPayload {
  modelId: string;
  parentBranchId?: string;
  name?: string;
  intent?: 'forward' | 'backward';
  seeds: string[];
  steps: number;
  prompt?: string;
  nodes: unknown[];
  edges: unknown[];
  modelOverride?: string;
  configId?: string;
  constraints?: Array<{ nodeId: string; mode: 'force' | 'block' }>;
}

export interface PredictHandlers {
  onStep?: (data: unknown) => void;
  onNotice?: (data: unknown) => void;
  onComplete?: (data: Scenario) => void;
  onError?: (msg: string) => void;
  onClose?: () => void;
}

export function predictStream(payload: PredictPayload, handlers: PredictHandlers): SseHandle {
  return sse('/api/scenarios', payload, {
    onEvent: (event, data) => {
      try {
        switch (event) {
          case 'step':     handlers.onStep?.(JSON.parse(data)); break;
          case 'notice':   handlers.onNotice?.(JSON.parse(data)); break;
          case 'complete': handlers.onComplete?.(JSON.parse(data) as Scenario); break;
          case 'error':    handlers.onError?.(data); break;
        }
      } catch (err) {
        handlers.onError?.(`SSE 数据解析失败: ${(err as Error).message}`);
      }
    },
    onError: (err) => handlers.onError?.(err.message),
    onClose: () => handlers.onClose?.(),
  });
}
```

- [ ] **Step 3: 创建 `api/chat.ts`**

```ts
import { request, sse, type SseHandle } from './http';

export interface ChatHistoryItem { role: 'user' | 'assistant'; content: string }
export interface ChatAttachment   { type: string; dataUrl?: string; [k: string]: unknown }

export interface ChatPayload {
  message: string;
  nodes?: unknown[];
  edges?: unknown[];
  modelOverride?: string;
  configId?: string;
  history?: ChatHistoryItem[];
  attachments?: ChatAttachment[];
}

export interface ChatResult {
  reply: string;
  add_nodes?: unknown[];
  add_edges?: unknown[];
}

export function chat(payload: ChatPayload) {
  return request<ChatResult>('/api/chat', { method: 'POST', body: JSON.stringify(payload) });
}

export interface ChatStreamHandlers {
  onText?: (chunk: string) => void;
  onComplete?: (result: ChatResult) => void;
  onError?: (msg: string) => void;
  onClose?: () => void;
}

export function chatStream(payload: ChatPayload, handlers: ChatStreamHandlers): SseHandle {
  return sse('/api/chat', payload, {
    onEvent: (event, data) => {
      switch (event) {
        case 'text':     handlers.onText?.(data); break;
        case 'complete':
          try { handlers.onComplete?.(JSON.parse(data) as ChatResult); }
          catch { handlers.onError?.('完成事件 JSON 解析失败'); }
          break;
        case 'error':    handlers.onError?.(data); break;
      }
    },
    onError: (err) => handlers.onError?.(err.message),
    onClose: () => handlers.onClose?.(),
  });
}
```

- [ ] **Step 4: 编译验证 + 提交**

```bash
cd frontend && npm run lint
git add frontend/src/api/ontology.ts frontend/src/api/scenarios.ts frontend/src/api/chat.ts
git commit -m "refactor(frontend): 新增 api/ontology, api/scenarios, api/chat(含 SSE 流)"
```

---

### 任务 2.4:迁移 `useSSE.ts` 到 `api/http.ts`

**Files:**
- Modify: `frontend/src/composables/useSSE.ts`

- [ ] **Step 1: 读取当前 useSSE.ts**

Run: `Read frontend/src/composables/useSSE.ts` 确认现有接口。

- [ ] **Step 2: 把内部 fetch 改造为调用 `sse()`**

如果 `useSSE` 当前已经是通用 SSE 封装,把它内部的 fetch 调用替换为 `import { sse } from '@/api/http'`,对外接口保持不变。

具体改造:`useSSE` 暴露的 `connect / onEvent / onError / abort` 等方法内部改为持有 `SseHandle`,不再写 fetch + reader 解析。

由于不破坏对外接口,所有使用 `useSSE` 的组件不需改动。

- [ ] **Step 3: 编译验证 + 提交**

```bash
cd frontend && npm run lint
git add frontend/src/composables/useSSE.ts
git commit -m "refactor(frontend): useSSE 内部改用 api/http.ts 的 sse() 实现,接口不变"
```

---

### 任务 2.5:迁移 `App.vue` 的 fetch 调用

**Files:**
- Modify: `frontend/src/App.vue`

- [ ] **Step 1: 替换 7 处 fetch 为 api 调用**

7 处的对应映射:
- L52 `fetch('/api/ontology-models')` → `listOntologies()`
- L71 `fetch('/api/ontology-models/' + id, { PUT })` → `updateOntology(id, model)`
- L115 `fetch('/api/scenarios?modelId=...')` → `listScenarios(modelId)`
- L252 `fetch('/api/scenarios/migrate', { POST })` → `migrateScenarios()`
- L280 `fetch('/api/scenarios/' + id, { DELETE })` → `deleteScenario(id)`
- L397 `fetch('/api/ontology-models', { POST })` → `saveOntology(model)`
- L454 `fetch('/api/ontology-models/' + id, { DELETE })` → `deleteOntology(id)`

在 `<script setup>` 头部加 import:
```ts
import { listOntologies, saveOntology, updateOntology, deleteOntology } from '@/api/ontology';
import { listScenarios, deleteScenario, migrateScenarios } from '@/api/scenarios';
import { ApiError } from '@/api/http';
```

每个调用点的 try/catch 改为捕获 `ApiError`,toast 的消息从 `err.message`(原 `err.message` 已是后端返回的 error 文案,语义一致)。

- [ ] **Step 2: 编译验证**

```bash
cd frontend && npm run lint
```

- [ ] **Step 3: 浏览器冒烟**

刷新页面:模型列表加载、新建/删除模型、切换分支、迁移操作均正常。

- [ ] **Step 4: 提交**

```bash
git add frontend/src/App.vue
git commit -m "refactor(frontend): App.vue 7 处 fetch 改用 api/* 调用"
```

---

### 任务 2.6:迁移 `ChatPanel.vue` 与 `ImportDialog.vue` 的 fetch 调用

**Files:**
- Modify: `frontend/src/components/ChatPanel.vue`
- Modify: `frontend/src/components/ImportDialog.vue`

- [ ] **Step 1: ChatPanel.vue 改造**

- L299 `fetch('/api/config')` → `getConfig()`(import from `@/api/config`)
- L329 `fetch('/api/prefs')` → `getPrefs()`(import from `@/api/prefs`)

注意:`loadModels` 内部目前直接从 `/api/config` 拉所有 customModels;改用 `getConfig()` 后返回类型一致,仅替换调用点。

发送消息当前是直接 `fetch('/api/chat', ...)` 还是走 `useSSE`?读代码确认:
- 同步路径用 `chat(payload)`
- 流式路径用 `chatStream(payload, handlers)`

具体改造时若 ChatPanel 当前有自己的 fetch 调用,一并替换。

- [ ] **Step 2: ImportDialog.vue 改造**

L125 `fetch('/api/ontology-models/extract', { POST, body: formData })` → `extractFromFiles(files, { modelOverride, configId })`(import from `@/api/ontology`)。

- [ ] **Step 3: 编译验证 + 浏览器冒烟**

```bash
cd frontend && npm run lint
```

测试:聊天发送、文件导入,均正常。

- [ ] **Step 4: 提交**

```bash
git add frontend/src/components/ChatPanel.vue frontend/src/components/ImportDialog.vue
git commit -m "refactor(frontend): ChatPanel/ImportDialog 改用 api/* 调用"
```

---

### 任务 2.7:迁移 `SettingsView.vue` 的 fetch 调用

**Files:**
- Modify: `frontend/src/components/SettingsView.vue`

- [ ] **Step 1: 替换 9 处 fetch**

- L76 `fetch('/api/models')` → `listModels()`
- L89 `fetch('/api/config')` → `getConfig()`
- L156 `fetch('/api/prefs')` → `getPrefs()`
- L165 `fetch('/api/prefs', { PUT })` → `savePrefs(patch)`
- L188 `fetch('/api/prefs/scenarios', { DELETE })` → `clearAllScenarios()`
- L272 `fetch(url, { POST/PUT })` → `createModel(payload)` 或 `updateModel(id, payload)`(看 url 拼接)
- L308 `fetch('/api/models/${id}', { DELETE })` → `deleteModel(id)`
- L319 `fetch('/api/models/${id}/toggle', { PATCH })` → `toggleModel(id)`

`<script setup>` 头部 import:
```ts
import { listModels, createModel, updateModel, deleteModel, toggleModel, type ModelConfig } from '@/api/models';
import { getConfig } from '@/api/config';
import { getPrefs, savePrefs, clearAllScenarios } from '@/api/prefs';
import { ApiError } from '@/api/http';
```

把组件内的本地 `interface ModelConfig` 删除,改用 `@/api/models` 导出的类型(确认字段一致,否则在本文件保留 alias)。

- [ ] **Step 2: 编译验证 + 浏览器冒烟**

```bash
cd frontend && npm run lint
```

测试:设置页打开、模型 CRUD、prefs 修改保存、清空 scenarios。

- [ ] **Step 3: 验证 fetch 已全部迁移**

```bash
grep -rE "fetch\\('/api|new EventSource\\(" frontend/src/components frontend/src/App.vue
```
Expected: 输出为空。

- [ ] **Step 4: 提交**

```bash
git add frontend/src/components/SettingsView.vue
git commit -m "refactor(frontend): SettingsView 9 处 fetch 改用 api/* 调用"
```

---

### 任务 2.8:删除 `frontend/server.ts` 与配套遗留

**Files:**
- Delete: `frontend/server.ts`
- Delete: `frontend/llm-config.json`
- Delete: `frontend/.env.example`
- Modify: `frontend/package.json`

- [ ] **Step 1: 删文件**

```bash
rm frontend/server.ts frontend/llm-config.json frontend/.env.example
```

- [ ] **Step 2: 修改 `frontend/package.json`**

替换整个文件为:

```json
{
  "name": "eic-cc-frontend",
  "private": true,
  "version": "0.0.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vite build",
    "preview": "vite preview",
    "clean": "rm -rf dist",
    "lint": "tsc --noEmit"
  },
  "dependencies": {
    "@vitejs/plugin-vue": "^6.0.6",
    "vite": "^6.2.0",
    "vue": "^3.5.32"
  },
  "devDependencies": {
    "@types/node": "^22.14.0",
    "typescript": "~5.8.2"
  }
}
```

- [ ] **Step 3: 重新生成 lock 文件**

```bash
cd frontend && rm -f package-lock.json && npm install
```

- [ ] **Step 4: 编译验证**

```bash
cd frontend && npm run build
```
Expected: BUILD SUCCESS。

- [ ] **Step 5: 验证遗留已清**

```bash
grep -rE "fetch\\('/api|new EventSource\\(|openai|anthropic|dashscope|api[-_]?key|gemini|LLM_API_KEY|GEMINI_API_KEY" frontend/src 2>/dev/null
```
应只剩在 SettingsView/ChatPanel 中 provider 名称/UI 文案上的 `openai` / `anthropic` / `deepseek` 等(provider code 命名,非直连调用)。
不应再有 `apiKey:` 形式的 fetch payload。

- [ ] **Step 6: 浏览器冒烟全功能**

启动 backend + frontend,把聊天 / 推演 / 设置 / 导入四条主链路全部跑一遍。

- [ ] **Step 7: 提交**

```bash
git add frontend/package.json frontend/package-lock.json
git rm frontend/server.ts frontend/llm-config.json frontend/.env.example
git commit -m "refactor(frontend): 删除遗留 Express 直连 LLM,清理 package.json 未使用依赖"
```

---

## 阶段 3:超大组件拆分

每个任务结束后跑 `npm run build` 确认编译通过,并做对应功能的浏览器冒烟。

---

### 任务 3.1:抽出 `composables/useDivider.ts` + `composables/useImportFlow.ts`

**Files:**
- Create: `frontend/src/composables/useDivider.ts`
- Create: `frontend/src/composables/useImportFlow.ts`
- Modify: `frontend/src/App.vue`

- [ ] **Step 1: 创建 `useDivider.ts`**

```ts
import { ref, onUnmounted } from 'vue';

export function useDivider(initial = 360, min = 240, max = 800) {
  const chatW = ref(initial);
  const divDrag = ref<{ sx: number; sw: number } | null>(null);

  const onMove = (e: MouseEvent) => {
    if (!divDrag.value) return;
    const dx = e.clientX - divDrag.value.sx;
    chatW.value = Math.min(max, Math.max(min, divDrag.value.sw - dx));
  };
  const onUp = () => { divDrag.value = null; };

  const startDivider = (e: MouseEvent) => {
    divDrag.value = { sx: e.clientX, sw: chatW.value };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp, { once: true });
  };

  onUnmounted(() => {
    window.removeEventListener('mousemove', onMove);
    window.removeEventListener('mouseup', onUp);
  });

  return { chatW, startDivider };
}
```

- [ ] **Step 2: 创建 `useImportFlow.ts`**

从 `App.vue` 的 `onImportCommit`(约 L193~L243)整体迁出。签名:
```ts
import type { OntologyModel, OntologyNode, OntologyEdge } from '../types';

export interface ImportPayload {
  mode: 'merge' | 'new';
  nodes: OntologyNode[];
  edges: OntologyEdge[];
  reply?: string;
  modelName?: string;
}

export function useImportFlow(ctx: {
  currentModel: () => OntologyModel | null;
  applyToTrunk: (n: OntologyNode[], e: OntologyEdge[]) => void;
  createNewModel: (draft: OntologyModel) => Promise<OntologyModel>;
  toast: (msg: string, kind?: 'success' | 'error') => void;
}) {
  async function onImportCommit(payload: ImportPayload) {
    // 把原 App.vue 中 onImportCommit 函数体逐字迁过来,
    // 凡是访问 nodes.value / edges.value / models.value / currentModel
    // 之处,都通过 ctx 的回调访问。
  }
  return { onImportCommit };
}
```
**注意:** 由于 onImportCommit 内部要访问/修改图状态,需要把那部分调用通过 `ctx` 注入,而不是在 composable 内直接 import App 的状态。

- [ ] **Step 3: App.vue 接入**

`<script setup>` 中:
```ts
import { useDivider } from '@/composables/useDivider';
import { useImportFlow } from '@/composables/useImportFlow';

const { chatW, startDivider } = useDivider(360);
const { onImportCommit } = useImportFlow({
  currentModel: () => findModel(sel.value || ''),
  applyToTrunk: (n, e) => onUpdate(n, e),
  createNewModel: createOnBackend,
  toast: (msg, kind) => toast(msg, kind ?? 'success'),
});
```
删除 App.vue 中原 `chatW` / `divDrag` / `startDivider` / `onImportCommit` 定义。

- [ ] **Step 4: 编译验证 + 冒烟**

```bash
cd frontend && npm run build
```
浏览器测:拖动分割条 + 文件导入合并 / 另存。

- [ ] **Step 5: 提交**

```bash
git add frontend/src/composables/useDivider.ts frontend/src/composables/useImportFlow.ts frontend/src/App.vue
git commit -m "refactor(frontend): App.vue 抽出 useDivider 与 useImportFlow composable"
```

---

### 任务 3.2:抽出 `composables/useScenarios.ts` + `composables/usePrediction.ts`

**Files:**
- Create: `frontend/src/composables/useScenarios.ts`
- Create: `frontend/src/composables/usePrediction.ts`
- Modify: `frontend/src/App.vue`

- [ ] **Step 1: 创建 `useScenarios.ts`**

把 App.vue 中下列函数 + 相关 ref 整体迁入:
- `branches`、`activeBranchId`、`trunkSnapshot`
- `loadBranches(modelId)`、`switchBranch(id)`、`collectAncestorChain(leafId)`、`migrateBranches()`、`deleteBranch(id)`

签名(因副作用较多,采用工厂模式注入 ctx):
```ts
export function useScenarios(ctx: {
  applyDag: (snapshot: { nodes: OntologyNode[]; edges: OntologyEdge[] }) => void;
  saveTrunkSnapshot: () => { nodes: OntologyNode[]; edges: OntologyEdge[] };
  toast: (msg: string, kind?: 'success' | 'error') => void;
}) {
  // ...
  return { branches, activeBranchId, trunkSnapshot,
           loadBranches, switchBranch, collectAncestorChain, migrateBranches, deleteBranch };
}
```

- [ ] **Step 2: 创建 `usePrediction.ts`**

把 App.vue 中:
- `predictDialogOpen`、`predictSeeds`、`liveSteps`、`liveLoading`、`liveActive`、`liveIntent`、`liveAbort`
- `openPredictDialog(seedId)`、`startPrediction(payload)`、`closeTimeline()`

整体迁入,内部用 `predictStream` from `@/api/scenarios`。

- [ ] **Step 3: App.vue 接入**

```ts
import { useScenarios } from '@/composables/useScenarios';
import { usePrediction } from '@/composables/usePrediction';

const scenarios = useScenarios({
  applyDag: (snap) => { nodes.value = snap.nodes; edges.value = snap.edges; },
  saveTrunkSnapshot: () => ({ nodes: [...nodes.value], edges: [...edges.value] }),
  toast: (msg, kind) => toast(msg, kind ?? 'success'),
});
const prediction = usePrediction({
  currentModelId: () => currentModelId.value,
  activeBranchId: () => scenarios.activeBranchId.value,
  nodes: () => nodes.value,
  edges: () => edges.value,
  onComplete: (s) => { scenarios.branches.value.unshift(s); scenarios.activeBranchId.value = s.id; },
});

// 模板访问改 prediction.predictDialogOpen.value 等
```

删除 App.vue 中原对应函数与 ref。

- [ ] **Step 4: 编译验证 + 冒烟**

```bash
cd frontend && npm run build
```
浏览器测:启动推演 → 分步显示 → 完成 → 切回 trunk → 切到新分支 → 删分支 → 迁移。

- [ ] **Step 5: 提交**

```bash
git add frontend/src/composables/useScenarios.ts frontend/src/composables/usePrediction.ts frontend/src/App.vue
git commit -m "refactor(frontend): App.vue 抽出 useScenarios 与 usePrediction"
```

---

### 任务 3.3:抽出 `composables/useOntologyModel.ts`

**Files:**
- Create: `frontend/src/composables/useOntologyModel.ts`
- Modify: `frontend/src/App.vue`

- [ ] **Step 1: 创建 `useOntologyModel.ts`**

迁入:
- `models`、`nodes`、`edges`、`isCreating`
- `loadOntologyModels()`、`persistCurrentModel(immediate)`、`openModel(m)`、`createOnBackend(draft)`、`createNewModel()`、`deleteOntologyModel(id)`、`findModel(id)`

签名:
```ts
export function useOntologyModel(ctx: {
  toast: (msg: string, kind?: 'success' | 'error') => void;
  onModelOpened?: (m: OntologyModel) => void;
}) {
  // ...
  return { models, nodes, edges, isCreating,
           loadOntologyModels, persistCurrentModel, openModel,
           createOnBackend, createNewModel, deleteOntologyModel, findModel };
}
```

- [ ] **Step 2: App.vue 接入**

```ts
const model = useOntologyModel({
  toast: (m, k) => toast(m, k ?? 'success'),
  onModelOpened: (m) => { currentModelTitle.value = m.name; scenarios.loadBranches(m.id); },
});
// 模板/其他函数访问改用 model.nodes.value 等
```

App.vue 删除对应函数与 ref。

- [ ] **Step 3: 编译验证 + 冒烟**

```bash
cd frontend && npm run build
```
浏览器测:新建模型、打开、自动持久化、删除。

- [ ] **Step 4: 提交**

```bash
git add frontend/src/composables/useOntologyModel.ts frontend/src/App.vue
git commit -m "refactor(frontend): App.vue 抽出 useOntologyModel"
```

---

### 任务 3.4:抽出 `WelcomeView` 与 `GraphView` 子组件

**Files:**
- Create: `frontend/src/components/views/WelcomeView.vue`
- Create: `frontend/src/components/views/GraphView.vue`
- Modify: `frontend/src/App.vue`

- [ ] **Step 1: 创建 `views/WelcomeView.vue`**

把 App.vue 模板中 `v-if="view==='welcome'"` 的整段 + 关联样式迁入。Props/Emits:
```ts
const props = defineProps<{ resetTick: number }>();
const emit = defineEmits<{ (e: 'submit', payload: { text: string; files: File[] }): void }>();
```

- [ ] **Step 2: 创建 `views/GraphView.vue`**

把 App.vue 中 `v-if="view==='graph'"` 的 GraphCanvas + 顶栏工具按钮区域 + 分支选择器迁入。Props/Emits 显式声明所有需要的 nodes/edges/selectedId/activeBranchId/branches 等。

- [ ] **Step 3: App.vue 接入 + 瘦身模板**

```vue
<WelcomeView v-if="view === 'welcome'" :reset-tick="welcomeResetTick" @submit="onWelcomeSubmit" />
<GraphView v-else-if="view === 'graph'"
           :nodes="model.nodes.value" :edges="model.edges.value"
           :selected-id="sel" :branches="scenarios.branches.value"
           :active-branch-id="scenarios.activeBranchId.value"
           ... />
```

- [ ] **Step 4: 编译验证 + 冒烟**

```bash
cd frontend && npm run build
wc -l frontend/src/App.vue
```
预期:App.vue < 300 行。

浏览器冒烟:欢迎页提交、画布操作、视图切换。

- [ ] **Step 5: 提交**

```bash
git add frontend/src/components/views/WelcomeView.vue frontend/src/components/views/GraphView.vue frontend/src/App.vue
git commit -m "refactor(frontend): App.vue 抽出 WelcomeView + GraphView,主文件缩为 <300 行"
```

---

### 任务 3.5:ChatPanel.vue 抽出 composables

**Files:**
- Create: `frontend/src/composables/useConversations.ts`
- Create: `frontend/src/composables/useAttachments.ts`
- Create: `frontend/src/composables/useMention.ts`
- Create: `frontend/src/composables/useChatStream.ts`
- Modify: `frontend/src/components/ChatPanel.vue`

- [ ] **Step 1: 创建 `useConversations.ts`**

从 ChatPanel.vue 第 36~143 行(`STORAGE_KEY` 起到 `sortedConversations`)整体迁入。返回:
```ts
{ conversationId, conversationTitle, showConvPicker,
  loadConversations, saveConversations, autoTitle, persistCurrent,
  initConversation, newConversation, switchConversation, deleteConversation,
  sortedConversations }
```

- [ ] **Step 2: 创建 `useAttachments.ts`**

把 `atts` ref + `addFile` + `readAsText` + `readAsDataURL` + 体积常量(MAX_TEXT_BYTES/MAX_IMAGE_BYTES/TEXT_EXTS/IMAGE_EXTS)迁入。返回:
```ts
{ atts, addFile, clearAttachments }
```

- [ ] **Step 3: 创建 `useMention.ts`**

把 `mentionOpen` / `mentionQuery` / `mentionIndex` / `mentionStart` + `mentionItems` computed + `checkMention` + `selectMention` 迁入。入参为 textarea ref 与可被 @ 引用的实体列表。

- [ ] **Step 4: 创建 `useChatStream.ts`**

把 `msgs` / `input` / `loading` / `abortChat` + `send()` 函数迁入。内部用 `chatStream` from `@/api/chat`。

- [ ] **Step 5: ChatPanel.vue 接入**

`<script setup>` 顶部:
```ts
const conv = useConversations(/* ... */);
const att  = useAttachments();
const mention = useMention({ inputRef, mentionables: /* ... */ });
const stream = useChatStream({ /* model / configId / history / ... */ });
```

删除原对应代码。

- [ ] **Step 6: 编译验证**

```bash
cd frontend && npm run build
```

- [ ] **Step 7: 提交**

```bash
git add frontend/src/composables/useConversations.ts frontend/src/composables/useAttachments.ts frontend/src/composables/useMention.ts frontend/src/composables/useChatStream.ts frontend/src/components/ChatPanel.vue
git commit -m "refactor(frontend): ChatPanel 抽出 4 个 composable"
```

---

### 任务 3.6:ChatPanel 抽出子组件

**Files:**
- Create: `frontend/src/components/chat/ConversationPicker.vue`
- Create: `frontend/src/components/chat/ChatMessageList.vue`
- Create: `frontend/src/components/chat/ChatInput.vue`
- Create: `frontend/src/components/chat/AttachmentChips.vue`
- Create: `frontend/src/components/chat/ModelPicker.vue`
- Modify: `frontend/src/components/ChatPanel.vue`

- [ ] **Step 1: `chat/ConversationPicker.vue`**

抽出 ChatPanel 中会话历史下拉菜单的整段模板 + 样式。
Props: `conversations: Conversation[]`、`currentId: string`、`show: boolean`。
Emits: `pick(id)`、`new()`、`delete(id)`、`close()`。

- [ ] **Step 2: `chat/ChatMessageList.vue`**

抽出消息渲染区(role / 头像 / markdown / 代码块的渲染)。
Props: `messages: Msg[]`、`loading: boolean`。

- [ ] **Step 3: `chat/ChatInput.vue`**

抽出 textarea + 输入工具栏 + mention 弹窗。
Props: `modelValue: string`、`mentionOpen`、`mentionItems`、`mentionIndex`。
Emits: `update:modelValue`、`send`、`upload`、`mention-input`、`mention-select(item)`、`mention-keydown(e)`。

- [ ] **Step 4: `chat/AttachmentChips.vue`**

附件 chip 列表 + 删除按钮。
Props: `items: Attachment[]`。
Emits: `remove(index)`。

- [ ] **Step 5: `chat/ModelPicker.vue`**

模型选择器(预置/自定义切换)。
Props: `current: ModelOption | null`、`presets: ModelOption[]`、`customs: ModelOption[]`、`show: boolean`。
Emits: `pick(model)`、`close()`。

- [ ] **Step 6: ChatPanel.vue 改造为容器**

模板瘦身为 5 个子组件的组合。

- [ ] **Step 7: 编译验证 + 冒烟**

```bash
cd frontend && npm run build
wc -l frontend/src/components/ChatPanel.vue
```
预期:ChatPanel.vue < 350 行。

浏览器测:发消息、切会话、切模型、上传附件、@ 提及。

- [ ] **Step 8: 提交**

```bash
git add frontend/src/components/chat/ frontend/src/components/ChatPanel.vue
git commit -m "refactor(frontend): ChatPanel 拆出 5 个子组件,主文件缩为 <350 行"
```

---

### 任务 3.7:SettingsView 抽出 composables

**Files:**
- Create: `frontend/src/composables/useModelConfigs.ts`
- Create: `frontend/src/composables/useSettingsPrefs.ts`
- Modify: `frontend/src/components/SettingsView.vue`

- [ ] **Step 1: 创建 `useModelConfigs.ts`**

把 SettingsView 中:
- `models`、`providers`、`loading`、`showAddModal`、`editingModel`、`formData`、`formErrors`、`saved`、`saveError`、`saving`、`selectedProvider`
- `loadModels`、`loadProviders`、`applyPreset`、`emptyForm`、`openAdd`、`toggleCapability`、`detectProviderCode`、`openEdit`、`validateForm`、`saveModel`、`deleteModel`、`toggleModel`

整体迁入。常量 `CAPABILITY_OPTIONS` / `CAPABILITY_LABELS` / `fmtTokens` 一并迁入。

- [ ] **Step 2: 创建 `useSettingsPrefs.ts`**

把 `prefs`、`prefsSaved`、`loadPrefs`、`savePrefs`、`clearAllScenarios` 迁入。

- [ ] **Step 3: SettingsView.vue 接入**

`<script setup>` 中:
```ts
const mc = useModelConfigs({ toast: showToast });
const sp = useSettingsPrefs({ toast: showToast });
```

删除原对应代码。

- [ ] **Step 4: 编译验证 + 提交**

```bash
cd frontend && npm run build
git add frontend/src/composables/useModelConfigs.ts frontend/src/composables/useSettingsPrefs.ts frontend/src/components/SettingsView.vue
git commit -m "refactor(frontend): SettingsView 抽出 useModelConfigs 与 useSettingsPrefs"
```

---

### 任务 3.8:SettingsView 抽出子组件

**Files:**
- Create: `frontend/src/components/settings/SettingsTabs.vue`
- Create: `frontend/src/components/settings/ModelListPanel.vue`
- Create: `frontend/src/components/settings/ModelConfigForm.vue`
- Create: `frontend/src/components/settings/PrefsPanel.vue`
- Create: `frontend/src/components/settings/AboutPanel.vue`
- Modify: `frontend/src/components/SettingsView.vue`

- [ ] **Step 1: `settings/SettingsTabs.vue`**

简单 tab 导航。
Props: `tabs: { code: string; label: string }[]`、`active: string`。
Emits: `update:active(code)`。

- [ ] **Step 2: `settings/ModelListPanel.vue`**

模型卡片网格 + 添加按钮 + 启用/编辑/删除触发。
Props: `models: ModelConfig[]`、`loading: boolean`。
Emits: `add`、`edit(model)`、`delete(id)`、`toggle(model)`。

- [ ] **Step 3: `settings/ModelConfigForm.vue`**

新增/编辑表单弹窗(provider preset、能力勾选、协议选择)。
Props: `show`、`editing: ModelConfig | null`、`providers: ProviderInfo[]`、`formData`、`formErrors`、`saving`、`saved`、`saveError`。
Emits: `update:show`、`submit`、`apply-preset(code)`、`toggle-capability(code)`。

- [ ] **Step 4: `settings/PrefsPanel.vue`**

偏好表单 + 清空 scenarios 按钮。
Props: `prefs: Prefs`、`prefsSaved: boolean`。
Emits: `update:prefs(patch)`、`clear-scenarios`。

- [ ] **Step 5: `settings/AboutPanel.vue`**

版本号 + 仓库链接。无 props。

- [ ] **Step 6: SettingsView.vue 改造为容器**

模板瘦身:
```vue
<SettingsTabs :tabs="TABS" :active="activeTab" @update:active="activeTab = $event" />
<ModelListPanel v-if="activeTab === 'models'" :models="mc.models.value" ... />
<PrefsPanel    v-else-if="activeTab === 'prefs'" :prefs="sp.prefs.value" ... />
<AboutPanel    v-else-if="activeTab === 'about'" />
<ModelConfigForm v-model:show="mc.showAddModal.value" ... />
```

- [ ] **Step 7: 编译验证 + 冒烟**

```bash
cd frontend && npm run build
wc -l frontend/src/components/SettingsView.vue
```
预期:SettingsView.vue < 350 行。

浏览器测:模型列表、新增模型、编辑模型、删除模型、启用切换、prefs 修改保存、清空 scenarios、tab 切换。

- [ ] **Step 8: 提交**

```bash
git add frontend/src/components/settings/ frontend/src/components/SettingsView.vue
git commit -m "refactor(frontend): SettingsView 拆出 5 个子组件,主文件缩为 <350 行"
```

---

### 任务 3.9:阶段 3 收尾验证

- [ ] **Step 1: 行数验证**

```bash
wc -l frontend/src/App.vue frontend/src/components/ChatPanel.vue frontend/src/components/SettingsView.vue
```
预期:三者均 < 400 行。

- [ ] **Step 2: 完整冒烟 5 条主路径**

1. 欢迎页提交建图 → 看到草稿;
2. 切换分支 → 画布刷新;
3. 启动推演 → 流式 step 事件;
4. 文件导入合并到 trunk;
5. 设置页 CRUD 模型 + 修改 prefs + 清空 scenarios。

- [ ] **Step 3: 全量 grep 验证规范达成**

```bash
grep -rE "fetch\\('/api|new EventSource\\(" frontend/src
# 应为空
grep -rE "try \\{" backend/src/main/java/com/tuiyan/backend/controller/*.java | wc -l
# 应显著少于改造前(改造前 16,目标 < 5)
```

- [ ] **Step 4: 最终提交**

```bash
git commit --allow-empty -m "refactor: 分层重构三阶段完成 — controller/前端 API/超大组件全部对齐规范"
```

---

## 完成定义(DoD)清单

- [ ] 后端所有 controller 方法体均 ≤ 15 行;
- [ ] 后端 controller 不含 `try/catch` 业务分支(由 GlobalExceptionHandler 统一处理);
- [ ] 前端 `grep -rE "fetch\\('/api|new EventSource\\(" src/components src/App.vue` 为空;
- [ ] 前端 `server.ts` / `llm-config.json` / `.env.example` 不存在;
- [ ] 前端 `package.json` 仅保留 vue/vite/typescript 等实际被 import 的依赖;
- [ ] App.vue / ChatPanel.vue / SettingsView.vue 均 < 400 行;
- [ ] `cd backend && mvn package` + `cd frontend && npm run build` 均通过;
- [ ] 5 条主路径手工冒烟通过。
