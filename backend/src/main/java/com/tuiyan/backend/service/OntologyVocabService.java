package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tuiyan.backend.repository.WorkspaceVocabRepository;
import com.tuiyan.backend.service.llm.prompt.ExtractPrompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 本体词表骨架服务（Schema-First 建图的第一阶段）。
 * <p>经验库规模大时，各批 LLM 独立看 30k 字，同一概念在不同批里会被叫成不同名字
 * （客户/顾客/Customer），label 判等对同义词无能为力 → 图上出现假性重复节点、血缘链断裂。
 * 本服务把「先规约、后抽取」落地为三件事：
 * <ol>
 *   <li><b>构建</b>：对经验做标题+首段采样，一次 LLM 调用归纳受控词表（规范名 + 别名 + 类型），
 *       入库（每工作空间一份）；增量建图直接复用，全量建图重建覆盖；</li>
 *   <li><b>注入</b>：渲染成批抽取的 preface，要求 LLM 命中词表（含别名）时必须用规范名；</li>
 *   <li><b>归一</b>：LLM 的遵循不可靠，服务端对每批产出再做一次确定性归一——
 *       label 命中别名 → 改写为规范名、原名回填 aliases、空 type 按词表补齐。
 *       归一后的同名节点由既有的跨批 label+type 合并自然折叠。</li>
 * </ol>
 * 全程失败容忍：词表构建失败只降级为"无骨架建图"，不阻塞主流程。
 */
@Service
public class OntologyVocabService {

    private static final Logger log = LoggerFactory.getLogger(OntologyVocabService.class);

    /** 词表条目上限（与 VOCAB_SYSTEM 的约束一致，服务端兜底截断）。 */
    private static final int MAX_ENTRIES = 60;
    /** 采样：每篇经验取的首段字符数。 */
    private static final int SAMPLE_EXCERPT_CHARS = 300;
    /** 采样：最多取多少篇（超出按等距抽样，保证覆盖整个库而不是只看前面的）。 */
    private static final int SAMPLE_MAX_DOCS = 60;

    private final ExtractionLlmService extractionLlmService;
    private final WorkspaceVocabRepository repo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OntologyVocabService(ExtractionLlmService extractionLlmService, WorkspaceVocabRepository repo) {
        this.extractionLlmService = extractionLlmService;
        this.repo = repo;
    }

    /** 一篇经验的采样输入：标题 + 首段摘录。 */
    public record DocSample(String title, String excerpt) {}

    /** 词表条目：规范名 + 类型 + 别名。 */
    public record Entry(String canonical, String type, List<String> aliases) {}

    /** 受控词表。empty() 表示"无骨架"，preface/normalize 均为 no-op。 */
    public record Vocab(List<Entry> entries) {
        public static Vocab empty() { return new Vocab(List.of()); }
        public boolean isEmpty() { return entries == null || entries.isEmpty(); }
    }

    /** 从经验正文构造采样（调用方在文档收集循环里逐篇调用）。 */
    public static DocSample sampleOf(String title, String content) {
        String excerpt = content == null ? "" :
                content.substring(0, Math.min(content.length(), SAMPLE_EXCERPT_CHARS));
        return new DocSample(title == null ? "" : title, excerpt);
    }

    /**
     * 取词表：{@code reuseExisting}（增量建图）时优先复用库里已有的；
     * 否则（全量建图 / 库里没有）用本次采样重建并入库。
     * 构建失败返回 {@link Vocab#empty()}，主流程降级为无骨架建图。
     */
    public Vocab loadOrBuild(String workspaceId, List<DocSample> samples, boolean reuseExisting,
                             String modelOverride, String configId) {
        if (reuseExisting) {
            Vocab existing = load(workspaceId);
            if (!existing.isEmpty()) return existing;
        }
        if (samples == null || samples.isEmpty()) return load(workspaceId); // 无新样本时兜底复用旧表
        Vocab built = build(samples, modelOverride, configId);
        if (!built.isEmpty()) persist(workspaceId, built, samples.size());
        return built;
    }

    /** 读库里的词表；无记录 / 解析失败返回 empty。 */
    public Vocab load(String workspaceId) {
        try {
            String json = repo.findVocabJson(workspaceId);
            if (json == null || json.isBlank()) return Vocab.empty();
            return parse(objectMapper.readTree(json));
        } catch (Exception e) {
            log.warn("[vocab] 读取词表失败(按无词表继续): {}", e.toString());
            return Vocab.empty();
        }
    }

    /** 用 LLM 从采样构建词表。失败返回 empty（不抛出，主流程降级）。 */
    public Vocab build(List<DocSample> samples, String modelOverride, String configId) {
        // 等距抽样：库很大时均匀覆盖，而不是只看最前面的文档
        List<DocSample> picked = samples;
        if (samples.size() > SAMPLE_MAX_DOCS) {
            picked = new ArrayList<>(SAMPLE_MAX_DOCS);
            double stride = (double) samples.size() / SAMPLE_MAX_DOCS;
            for (int i = 0; i < SAMPLE_MAX_DOCS; i++) {
                picked.add(samples.get((int) Math.floor(i * stride)));
            }
        }
        StringBuilder sb = new StringBuilder("【经验库采样】每行一篇: 标题 ||| 首段摘录\n\n");
        for (DocSample s : picked) {
            sb.append(s.title().replace('\n', ' ')).append(" ||| ")
              .append(s.excerpt().replace('\n', ' ')).append('\n');
        }
        try {
            JsonNode out = extractionLlmService.extractRaw(
                    sb.toString(), ExtractPrompts.VOCAB_SYSTEM, modelOverride, configId);
            Vocab v = parse(out);
            log.info("[vocab] 词表构建完成: {} 个概念(采样 {}/{} 篇)", v.entries().size(), picked.size(), samples.size());
            return v;
        } catch (Exception e) {
            log.warn("[vocab] 词表构建失败(降级为无骨架建图): {}", e.toString());
            return Vocab.empty();
        }
    }

    /** 词表入库（覆盖）。 */
    public void persist(String workspaceId, Vocab vocab, int sourceCount) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            ArrayNode arr = root.putArray("vocab");
            for (Entry e : vocab.entries()) {
                ObjectNode one = arr.addObject();
                one.put("canonical", e.canonical());
                if (e.type() != null && !e.type().isBlank()) one.put("type", e.type());
                if (e.aliases() != null && !e.aliases().isEmpty()) {
                    ArrayNode as = one.putArray("aliases");
                    e.aliases().forEach(as::add);
                }
            }
            repo.save(workspaceId, objectMapper.writeValueAsString(root), sourceCount);
        } catch (Exception e) {
            log.warn("[vocab] 词表入库失败(不影响本次建图): {}", e.toString());
        }
    }

    /**
     * 渲染批抽取 preface：命中词表（含别名）的概念必须用规范名，新概念才允许造词。
     * 空词表返回空串（无骨架建图）。
     */
    public String preface(Vocab vocab) {
        if (vocab == null || vocab.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("【本体词表骨架】以下是本工作空间的受控词表。抽取实体时:\n")
          .append("- 概念命中词表(含别名)时,label 必须使用规范名,把原文叫法放进 aliases,type 与词表一致;\n")
          .append("- 只有词表未覆盖的新概念才允许新建命名。\n");
        for (Entry e : vocab.entries()) {
            sb.append("- ").append(e.canonical());
            if (e.type() != null && !e.type().isBlank()) sb.append(" (").append(e.type()).append(")");
            if (e.aliases() != null && !e.aliases().isEmpty()) {
                sb.append(" 别名: ").append(String.join("、", e.aliases()));
            }
            sb.append('\n');
        }
        sb.append('\n');
        return sb.toString();
    }

    /**
     * 服务端确定性归一（LLM 对 preface 的遵循不可靠，这里兜底）：
     * 对 {@code {add_nodes:[…]}} 形状的抽取产出，label 命中词表别名 → 改写为规范名、
     * 原名回填 aliases；label 命中规范名或别名且节点 type 为空 → 按词表补 type。
     * 就地修改；空词表 no-op。返回被改写 label 的节点数（观测用）。
     */
    public int normalize(JsonNode part, Vocab vocab) {
        if (part == null || vocab == null || vocab.isEmpty()) return 0;
        JsonNode nodes = part.path("add_nodes");
        if (!nodes.isArray() || nodes.isEmpty()) return 0;

        // 标准化名(规范名或别名) → 词表条目
        Map<String, Entry> index = new HashMap<>();
        for (Entry e : vocab.entries()) {
            String canon = norm(e.canonical());
            if (canon.isEmpty()) continue;
            index.putIfAbsent(canon, e);
            if (e.aliases() != null) {
                for (String a : e.aliases()) {
                    String k = norm(a);
                    if (!k.isEmpty()) index.putIfAbsent(k, e);
                }
            }
        }
        if (index.isEmpty()) return 0;

        int rewritten = 0;
        for (JsonNode n : nodes) {
            if (!(n instanceof ObjectNode obj)) continue;
            String label = obj.path("label").asText("");
            Entry hit = index.get(norm(label));
            if (hit == null) continue;
            if (!norm(label).equals(norm(hit.canonical()))) {
                // 别名命中：改写为规范名，原名进 aliases（去重）
                obj.put("label", hit.canonical());
                LinkedHashSet<String> aliases = new LinkedHashSet<>();
                JsonNode old = obj.path("aliases");
                if (old.isArray()) for (JsonNode a : old) {
                    String s = a.asText("");
                    if (!s.isBlank() && !norm(s).equals(norm(hit.canonical()))) aliases.add(s);
                }
                aliases.add(label);
                ArrayNode arr = obj.putArray("aliases");
                aliases.forEach(arr::add);
                rewritten++;
            }
            if (obj.path("type").asText("").isBlank()
                    && hit.type() != null && !hit.type().isBlank()) {
                obj.put("type", hit.type());
            }
        }
        return rewritten;
    }

    /** 解析 {@code {vocab:[{canonical,type,aliases[]}]}}；容忍缺字段/形状错误，超上限截断。 */
    private Vocab parse(JsonNode root) {
        if (root == null) return Vocab.empty();
        JsonNode arr = root.path("vocab");
        if (!arr.isArray()) return Vocab.empty();
        List<Entry> out = new ArrayList<>();
        for (JsonNode item : arr) {
            if (out.size() >= MAX_ENTRIES) break;
            String canonical = item.path("canonical").asText("").trim();
            if (canonical.isEmpty()) continue;
            String type = item.path("type").asText("").trim();
            List<String> aliases = new ArrayList<>();
            JsonNode as = item.path("aliases");
            if (as.isArray()) {
                for (JsonNode a : as) {
                    String s = a.asText("").trim();
                    if (!s.isEmpty() && !norm(s).equals(norm(canonical))) aliases.add(s);
                }
            }
            out.add(new Entry(canonical, type, aliases));
        }
        return new Vocab(out);
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
