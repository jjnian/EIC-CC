package com.tuiyan.backend.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tuiyan.backend.repository.ExperienceRepository;
import com.tuiyan.backend.service.agent.BrowserAgentDriver.Session;
import com.tuiyan.backend.service.indexing.ExperienceIndexService;
import com.tuiyan.backend.service.llm.LlmCallLogger;
import com.tuiyan.backend.service.llm.LlmHttpClient;
import com.tuiyan.backend.service.llm.prompt.ExplorePrompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 自动探索智能体:像人一样"用"一个 web 系统(只读导航),摸清功能、反推业务,
 * 把结果写成一篇「功能地图」经验进经验库,后续可参与本体血缘图构建。
 * <p>循环:感知(文本快照)→ 决策(LLM 给业务理解 + 下一步)→ 执行(Playwright 点击/后退)→ 记录。
 * 有步数预算、页面去重、只读危险操作拦截。纯文本驱动,不依赖视觉模型。
 */
@Service
public class ExplorationAgentService {

    private static final Logger log = LoggerFactory.getLogger(ExplorationAgentService.class);
    private static final int PAGE_CAP_MAX = 120;         // 逻辑页硬上限,防失控
    private static final int DEFAULT_PAGE_BUDGET = 30;   // 未指定时默认覆盖多少个逻辑页
    private static final int TAB_LIMIT = 6;              // 单页最多点开几个 tab 抓字段
    private static final long SOFT_TIME_BUDGET_MS = 500_000L; // 软时限:留余量给归纳+落库,别撞 SSE 600s 硬超时
    /** frontier 里跳过的非业务链接(帮助/关于/文档/下载等),避免把预算烧在无关页。 */
    private static final Pattern NAV_SKIP = Pattern.compile(
            "帮助|关于|文档|下载|打印|隐私|条款|版权|意见反馈|logout|help|about|docs?|download|print|privacy|terms",
            Pattern.CASE_INSENSITIVE);
    /** 探索文档里嵌入「结构化图片段」的隐藏注释标记;建图侧据此解析并直接合并。 */
    public static final String GRAPH_MARKER = "EXPLORE_GRAPH";

    private final ObjectMapper om = new ObjectMapper();
    private final BrowserAgentDriver driver;
    private final LlmHttpClient http;
    private final LlmCallLogger callLogger;
    private final ExperienceRepository expRepo;
    private final ExperienceIndexService indexService;

    public ExplorationAgentService(BrowserAgentDriver driver, LlmHttpClient http, LlmCallLogger callLogger,
                                   ExperienceRepository expRepo, ExperienceIndexService indexService) {
        this.driver = driver;
        this.http = http;
        this.callLogger = callLogger;
        this.expRepo = expRepo;
        this.indexService = indexService;
    }

    /** SSE 进度回调:key=阶段标识,label=展示文案。 */
    public interface StepSink { void emit(String key, String label); }

    /**
     * 跑一次探索,结束后落一篇经验。
     * @return 新建经验的 map(含 id/title);经验库为入口,后续走现有建图。
     */
    public Map<String, Object> explore(String baseUrl, String storageState, String username, String password,
                                       int maxSteps, boolean readOnly,
                                       String modelOverride, String configId, StepSink step,
                                       BooleanSupplier cancelled, Consumer<String> onStorageState) {
        BooleanSupplier isCancelled = cancelled != null ? cancelled : () -> false;
        int pageBudget = Math.max(1, Math.min(maxSteps <= 0 ? DEFAULT_PAGE_BUDGET : maxSteps, PAGE_CAP_MAX));
        LlmHttpClient.ResolvedConfig cfg = http.resolveConfig(modelOverride, configId);
        boolean anthropic = http.isAnthropic(cfg.baseURL(), cfg.modelName(), cfg.protocol());

        step.emit("open", "正在打开浏览器并访问 " + baseUrl + " …");
        List<PageRecord> pages = new ArrayList<>();
        List<String> trail = new ArrayList<>();
        LinkedHashSet<String> visited = new LinkedHashSet<>();
        String loginNote = null;
        boolean loggedIn = false;

        try (Session session = driver.open(baseUrl, storageState)) {
            // 若提供了账号密码,先在入口页自动登录,再开始探索;并校验是否真的登录成功
            if (username != null && !username.isBlank()) {
                step.emit("login", "正在用账号「" + username + "」自动登录系统…");
                boolean submitted = session.login(username, password);
                if (!submitted) {
                    loginNote = "提供了账号但未找到登录表单,以未登录状态探索";
                    step.emit("blocked", "未找到登录表单/按钮,以未登录状态继续探索。");
                } else if (session.looksLoggedIn()) {
                    loggedIn = true;
                    loginNote = "已用账号「" + username + "」登录后探索";
                    step.emit("act", "✓ 登录成功,继续探索。");
                    // 登录态有价值:回存 storageState 供下次免登录(走服务端侧通道,绝不回前端)
                    if (onStorageState != null) {
                        try { String ss = session.exportStorageState(); if (ss != null) onStorageState.accept(ss); }
                        catch (RuntimeException ignore) {}
                    }
                } else {
                    loginNote = "登录疑似失败(账号或密码可能有误),以未登录状态探索";
                    step.emit("blocked", "⚠ 已提交登录但仍停留在登录页(账号或密码可能有误),将以未登录状态继续探索。");
                }
            }
            // 启用网络层护栏:登录已完成,从这里起拦非幂等请求(只读)并锁定同源,防误改数据/防跑偏到第三方站点
            session.armGuard(readOnly);

            // ── 覆盖式爬取:代码维护 frontier(待探队列)+ 路由直达导航,LLM 只做页面理解 ──
            // 终止 = frontier 空(功能树探完)∨ 逻辑页预算 ∨ 软时限,不再靠"卡住计数"或 LLM 说 done。
            LinkedHashMap<String, Frontier> frontier = new LinkedHashMap<>();
            frontier.put(normalize(session.currentUrl()), new Frontier(session.currentUrl(), "入口"));
            long deadline = System.currentTimeMillis() + SOFT_TIME_BUDGET_MS;

            while (!frontier.isEmpty() && pages.size() < pageBudget) {
                if (isCancelled.getAsBoolean()) throw new ExplorationCancelledException();
                if (System.currentTimeMillis() > deadline) {
                    step.emit("end", "已达时间预算,提前收尾并归纳(已覆盖 " + pages.size() + " 页)。");
                    break;
                }
                Map.Entry<String, Frontier> next = frontier.entrySet().iterator().next();
                frontier.remove(next.getKey());
                if (visited.contains(next.getKey())) continue;
                Frontier tgt = next.getValue();

                // 导航到目标(入口页已在当前 URL,无需重复导航)
                if (!normalize(session.currentUrl()).equals(next.getKey())) {
                    try { session.navigateTo(tgt.url); }
                    catch (RuntimeException e) { step.emit("act_fail", "打开失败,跳过:" + shorten(e.getMessage())); continue; }
                }

                // 会话失效检测:登录成功后又被重定向回登录页 → 提前结束,避免在登录页空转
                if (loggedIn && !session.looksLoggedIn()) {
                    step.emit("end", "检测到会话已失效(被登出/超时),结束探索。");
                    break;
                }

                JsonNode snap = session.snapshot();
                String url = snap.path("url").asText(session.currentUrl());
                String norm = normalize(url);                 // 落点可能因重定向异于目标键
                if (!visited.add(norm)) continue;             // 重定向到已访问页 → 跳过
                String title = firstNonBlank(snap.path("title").asText(""), url);

                step.emit("perceive", "第 " + (pages.size() + 1) + " 页 · 读取:" + title);
                JsonNode understanding = understand(cfg, anthropic, buildUserPrompt(snap));
                PageRecord rec = upsertPage(pages, norm, url, title, snap, understanding);
                trail.add(title + "  (来自:" + tgt.via + ")");
                String summary = understanding.path("page_summary").asText("");
                if (!summary.isBlank()) step.emit("think", summary);

                // P3:页内 tab 逐个点开,把子视图的表格列/表单字段/状态并进本页(丰富数据字典/状态机)
                exploreTabs(session, snap, rec, step);

                // 采集本页所有可导航链接进 frontier(含折叠的侧栏子菜单),把功能树铺全
                enqueueLinks(frontier, visited, snap, title, rec);
            }

            if (isCancelled.getAsBoolean()) throw new ExplorationCancelledException();
            step.emit("synthesize", "探索结束,正在把功能地图归纳成业务文档…");
            String rawReport = renderMarkdown(baseUrl, pages, trail, pages.size(), readOnly, loginNote);
            String bizDoc = synthesizeBusinessDoc(cfg, anthropic, rawReport);
            String content = composeBusinessFile(bizDoc, rawReport);
            // #1 结构化直连建图:把探索得到的页面/对象/属性结构化成图片段嵌进文档(隐藏注释),
            // 建图时可直接合并,免去"散文 → 再用 LLM 抽结构"的有损往返。
            content = embedGraphFragment(content, buildGraphFragment(pages));

            step.emit("save", "正在把业务文档写入经验库…");
            Map<String, Object> exp = expRepo.create(titleFor(baseUrl), content, "探索,业务文档,explore", "explore");
            try { indexService.reindexAsync(String.valueOf(exp.get("id"))); } catch (RuntimeException ignore) {}
            step.emit("done", "已生成业务文档「" + exp.get("title") + "」,覆盖 " + pages.size() + " 个页面。");
            return exp;
        }
    }

    /**
     * 把功能地图归纳成业务说明文档(自由 markdown,非 JSON)。失败时回退为空,
     * 由 {@link #composeBusinessFile} 用原始探索报告兜底,保证不丢探索结果。
     */
    private String synthesizeBusinessDoc(LlmHttpClient.ResolvedConfig cfg, boolean anthropic, String rawReport) {
        try {
            String user = "以下是自动探索得到的功能地图,请据此撰写《业务说明文档》:\n\n" + rawReport;
            callLogger.logConversation("explore-bizdoc", cfg.modelName(),
                    ExplorePrompts.EXPLORE_BUSINESS_DOC_SYSTEM, null, user, null);
            String body = http.buildBody(cfg, ExplorePrompts.EXPLORE_BUSINESS_DOC_SYSTEM, user,
                    null, null, false, false, LlmHttpClient.EXTRACT_TEMPERATURE);
            HttpRequest req = http.buildHttpRequest(cfg.baseURL(), cfg.apiKey(), anthropic, body, cfg.rawUrl());
            long t0 = System.currentTimeMillis();
            HttpResponse<String> resp = http.sendHttp(req, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - t0;
            if (resp.statusCode() != 200) {
                http.metrics().recordCall(cfg.modelName(), elapsed, false);
                throw new RuntimeException("HTTP " + resp.statusCode());
            }
            http.metrics().recordCall(cfg.modelName(), elapsed, true);
            JsonNode root = om.readTree(resp.body());
            String md = http.extractContent(root, anthropic);
            return md == null ? "" : md.trim();
        } catch (Exception e) {
            log.warn("[explore-agent] 业务文档归纳失败,回退原始报告: {}", e.toString());
            return "";
        }
    }

    /** 业务文档正文 + 探索明细附录;归纳失败时直接用原始探索报告,保证不丢内容。 */
    private static String composeBusinessFile(String bizDoc, String rawReport) {
        if (bizDoc == null || bizDoc.isBlank()) return rawReport;
        String detail = rawReport;
        int nl = detail.indexOf('\n'); // 去掉原始报告的一级标题,作为附录更顺
        if (nl > 0) detail = detail.substring(nl + 1).trim();
        return bizDoc + "\n\n---\n\n## 附录 · 探索明细(自动采集)\n\n" + detail + "\n";
    }

    // ── LLM 页面理解(导航由代码 frontier 负责,这里只读懂当前页) ──────────────
    private JsonNode understand(LlmHttpClient.ResolvedConfig cfg, boolean anthropic, String userPrompt) {
        try {
            callLogger.logConversation("explore-understand", cfg.modelName(),
                    ExplorePrompts.EXPLORE_UNDERSTAND_SYSTEM, null, userPrompt, null);
            String body = http.buildBody(cfg, ExplorePrompts.EXPLORE_UNDERSTAND_SYSTEM, userPrompt,
                    null, null, false, true, LlmHttpClient.EXTRACT_TEMPERATURE);
            HttpRequest req = http.buildHttpRequest(cfg.baseURL(), cfg.apiKey(), anthropic, body, cfg.rawUrl());
            long t0 = System.currentTimeMillis();
            HttpResponse<String> resp = http.sendHttp(req, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - t0;
            if (resp.statusCode() != 200) {
                http.metrics().recordCall(cfg.modelName(), elapsed, false);
                throw new RuntimeException("LLM 调用失败 HTTP " + resp.statusCode());
            }
            http.metrics().recordCall(cfg.modelName(), elapsed, true);
            JsonNode root = om.readTree(resp.body());
            String content = http.stripJsonFence(http.extractContent(root, anthropic));
            return om.readTree(content);
        } catch (Exception e) {
            // 单页理解失败不影响覆盖:回退空对象,继续爬下一页(功能地图里该页只缺业务描述)
            log.warn("[explore-agent] 页面理解失败,跳过本页描述: {}", e.toString());
            ObjectNode fb = om.createObjectNode();
            fb.put("page_summary", "(本页理解失败)");
            return fb;
        }
    }

    /** 把页面快照编码成给 LLM 的文本提示(只读懂本页,不含导航决策)。 */
    private String buildUserPrompt(JsonNode snap) {
        StringBuilder sb = new StringBuilder();
        sb.append("【当前页面快照】\n");
        sb.append("URL: ").append(snap.path("url").asText("")).append('\n');
        sb.append("标题: ").append(snap.path("title").asText("")).append('\n');
        String crumb = snap.path("breadcrumb").asText("");
        if (!crumb.isBlank()) sb.append("面包屑: ").append(crumb).append('\n');
        appendList(sb, "页面标题文字", snap.path("headings"));

        sb.append("可见操作/入口:\n");
        for (JsonNode el : snap.path("elements")) {
            sb.append("  · ").append(el.path("name").asText(""));
            if (el.path("danger").asBoolean(false)) sb.append("  (写操作,只读观测即可)");
            sb.append('\n');
        }
        for (JsonNode t : snap.path("tables")) {
            sb.append("数据表 ").append(t.path("caption").asText("")).append(" 列=[");
            sb.append(joinNode(t.path("cols"))).append("] 约").append(t.path("rows").asInt()).append("行\n");
        }
        for (JsonNode f : snap.path("forms")) {
            sb.append("表单字段: ").append(joinNode(f.path("fields"))).append('\n');
        }
        String statuses = joinNode(snap.path("statuses"));
        if (!statuses.isBlank()) sb.append("状态/枚举: ").append(statuses).append('\n');
        String text = snap.path("text").asText("");
        if (!text.isBlank()) sb.append("可见正文(节选): ").append(text).append('\n');

        sb.append("\n请输出对本页业务理解的 JSON。");
        return sb.toString();
    }

    // ── 覆盖式爬取:frontier 入队 + 页内 tab 展开 ──────────────────────────────
    /** 待探目标:导航到 url,via 记录它是从哪个页面/入口发现的(仅用于覆盖顺序日志)。 */
    private record Frontier(String url, String via) {}

    /**
     * 把本页快照里的全部可导航链接入队 frontier(含折叠的侧栏子菜单),按归一化键去重、
     * 跳过已访问/已入队/危险/非业务链接。这是"把功能树铺全"的核心——覆盖由代码保证。
     */
    private void enqueueLinks(LinkedHashMap<String, Frontier> frontier, LinkedHashSet<String> visited,
                              JsonNode snap, String fromTitle, PageRecord rec) {
        for (JsonNode ln : snap.path("links")) {
            String url = ln.path("url").asText("");
            String name = ln.path("name").asText("");
            if (url.isBlank()) continue;
            if (ln.path("danger").asBoolean(false)) continue;              // 会改数据的链接不主动进
            if (!name.isBlank() && NAV_SKIP.matcher(name).find()) continue; // 帮助/关于/下载等非业务页
            String key = normalize(url);
            if (visited.contains(key) || frontier.containsKey(key)) continue;
            frontier.put(key, new Frontier(url, fromTitle));
            if (!name.isBlank()) rec.navs.add(name);                        // 记进"从此页可进入"
        }
    }

    /**
     * P3:把本页的 tab 逐个点开,抽出各子视图的表格列/表单字段/状态,并进本页 PageRecord。
     * tab 切内容不改 URL,frontier 覆盖不到——这一步专补数据字典/状态机。best-effort,失败即跳过。
     */
    private void exploreTabs(Session session, JsonNode snap, PageRecord rec, StepSink step) {
        int done = 0;
        for (JsonNode t : snap.path("tabs")) {
            if (done >= TAB_LIMIT) break;
            if (t.path("active").asBoolean(false)) continue;   // 当前已展示的 tab 已在主快照里
            String name = t.path("name").asText("");
            if (name.isBlank()) continue;
            try {
                JsonNode facts = session.tabFacts(name);
                mergeSnapshotFacts(rec, facts);
                rec.capabilities.add("查看「" + name + "」页签");
                done++;
            } catch (RuntimeException e) {
                log.debug("[explore-agent] tab「{}」展开失败,跳过: {}", name, e.toString());
            }
        }
        if (done > 0) step.emit("act", "展开 " + done + " 个页签,补全字段/状态。");
    }

    /** 把一段 facts 快照(tables/forms/statuses)并进 PageRecord:补充属性、状态与可核对事实。 */
    private void mergeSnapshotFacts(PageRecord rec, JsonNode facts) {
        for (JsonNode t : facts.path("tables")) collect(rec.attributes, t.path("cols"));
        for (JsonNode f : facts.path("forms")) collect(rec.attributes, f.path("fields"));
        collect(rec.states, facts.path("statuses"));
        String extra = factsOf(facts);
        if (!extra.isBlank() && !rec.facts.contains(extra)) rec.facts = rec.facts + extra;
    }

    // ── 功能地图累积 ─────────────────────────────────────────
    private PageRecord upsertPage(List<PageRecord> pages, String norm, String url, String title,
                                  JsonNode snap, JsonNode understanding) {
        PageRecord rec = pages.stream().filter(p -> p.norm.equals(norm)).findFirst().orElse(null);
        if (rec == null) {
            rec = new PageRecord(norm, url, title);
            rec.facts = factsOf(snap);
            pages.add(rec);
        }
        if (rec.summary.isBlank()) rec.summary = understanding.path("page_summary").asText("");
        if (rec.type.isBlank()) rec.type = understanding.path("page_type").asText("");
        collect(rec.capabilities, understanding.path("capabilities"));
        collect(rec.entities, understanding.path("business_entities"));
        collect(rec.attributes, understanding.path("business_attributes"));
        collect(rec.states, snap.path("statuses"));       // 主页面上的状态徽标/枚举也入库
        return rec;
    }

    /** 从快照里抽出"事实"(表格列 / 表单字段),作为经验里可核对的硬信号。 */
    private String factsOf(JsonNode snap) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode t : snap.path("tables")) {
            String cap = t.path("caption").asText("");
            sb.append("  - 数据表").append(cap.isBlank() ? "" : "「" + cap + "」")
              .append(" 列=[").append(joinNode(t.path("cols"))).append("] 约")
              .append(t.path("rows").asInt()).append("行\n");
        }
        for (JsonNode f : snap.path("forms")) {
            sb.append("  - 表单字段: ").append(joinNode(f.path("fields"))).append('\n');
        }
        return sb.toString();
    }

    /** 把功能地图渲染成 markdown 经验。 */
    private String renderMarkdown(String baseUrl, List<PageRecord> pages, List<String> trail,
                                  int pageCount, boolean readOnly, String loginNote) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 「").append(hostOf(baseUrl)).append("」系统自动探索报告\n\n");
        sb.append("> 入口:").append(baseUrl).append(" · 覆盖 ").append(pageCount).append(" 个页面 · ")
          .append(readOnly ? "只读模式(已拦截写操作)" : "非只读模式").append(" · ").append(LocalDate.now()).append("\n>\n");
        if (loginNote != null && !loginNote.isBlank())
            sb.append("> 登录状态:").append(loginNote).append("\n>\n");
        sb.append("> 本报告由「自动探索智能体」模拟人工操作系统生成,用于摸清系统功能、反推业务。")
          .append("可直接作为经验库文件参与本体血缘图构建。\n\n");

        sb.append("## 功能模块与页面\n\n");
        for (PageRecord p : pages) {
            sb.append("### ").append(p.title.isBlank() ? p.url : p.title).append("\n");
            sb.append("`").append(p.url).append("`\n\n");
            if (!p.summary.isBlank()) sb.append(p.summary).append("\n\n");
            if (!p.type.isBlank()) sb.append("- 页面类型:").append(p.type).append('\n');
            if (!p.capabilities.isEmpty()) sb.append("- 业务操作/功能:").append(String.join("、", p.capabilities)).append('\n');
            if (!p.entities.isEmpty()) sb.append("- 业务对象:").append(String.join("、", p.entities)).append('\n');
            if (!p.attributes.isEmpty()) sb.append("- 业务属性:").append(String.join("、", p.attributes)).append('\n');
            if (!p.states.isEmpty()) sb.append("- 状态/枚举:").append(String.join("、", p.states)).append('\n');
            if (p.facts != null && !p.facts.isBlank()) sb.append(p.facts);
            if (!p.navs.isEmpty()) sb.append("- 从此页可进入:点击「").append(String.join("」「", dedupe(p.navs))).append("」\n");
            if (!p.blocked.isEmpty()) sb.append("- (存在但只读未执行的写操作:").append(String.join("、", dedupe(p.blocked))).append(")\n");
            sb.append('\n');
        }

        if (!trail.isEmpty()) {
            sb.append("## 探索覆盖顺序\n\n");
            int n = 1;
            for (String hop : trail) sb.append(n++).append(". ").append(hop).append('\n');
            sb.append('\n');
        }
        return sb.toString();
    }

    // ── #1 结构化图片段 ───────────────────────────────────────
    /**
     * 把探索累积的页面记录结构化成一份图片段({nodes,edges}),用建图同一套 schema 表达:
     * 页面→process 节点、业务对象→entity 节点、"页面涉及对象"→associated_with 边、业务属性→实体 props。
     * 这些是高置信度的结构化事实,建图时可直接合并,免去"散文→再用 LLM 抽结构"的有损往返。
     * @return 图片段 JSON 字符串;无可用节点时返回空串。
     */
    private String buildGraphFragment(List<PageRecord> pages) {
        ObjectNode root = om.createObjectNode();
        ArrayNode nodes = root.putArray("nodes");
        ArrayNode edges = root.putArray("edges");
        Map<String, String> entityId = new LinkedHashMap<>();        // 规范化 label → 实体节点 id
        Map<String, ArrayNode> entityProps = new LinkedHashMap<>();  // 规范化 label → 该实体 props 数组
        int pi = 0, ei = 0, edi = 0;
        for (PageRecord p : pages) {
            String pid = "xpg_" + (pi++);
            ObjectNode pn = nodes.addObject();
            pn.put("id", pid);
            pn.put("label", (p.title == null || p.title.isBlank()) ? p.url : p.title);
            pn.put("type", "process");
            pn.put("source", "derived");
            if (p.url != null && !p.url.isBlank()) pn.put("evidence", shorten(p.url));
            ArrayNode pprops = pn.putArray("props");
            if (!p.type.isBlank()) pprops.add(prop("页面类型", p.type));
            if (!p.capabilities.isEmpty()) pprops.add(prop("功能", String.join("、", p.capabilities)));
            for (String ent : p.entities) {
                String key = ent.trim().toLowerCase();
                if (key.isBlank()) continue;
                String enid = entityId.get(key);
                if (enid == null) {
                    enid = "xen_" + (ei++);
                    entityId.put(key, enid);
                    ObjectNode en = nodes.addObject();
                    en.put("id", enid);
                    en.put("label", ent.trim());
                    en.put("type", "entity");
                    en.put("source", "derived");
                    entityProps.put(key, en.putArray("props"));
                }
                ObjectNode edge = edges.addObject();
                edge.put("id", "xed_" + (edi++));
                edge.put("from", pid);
                edge.put("to", enid);
                edge.put("rel_type", "associated_with");
                edge.put("label", "涉及");
            }
        }
        // 业务属性作为各页所涉实体的候选属性附注(按 key 去重)
        for (PageRecord p : pages) {
            if (p.attributes.isEmpty()) continue;
            for (String ent : p.entities) {
                ArrayNode props = entityProps.get(ent.trim().toLowerCase());
                if (props == null) continue;
                Set<String> seen = new HashSet<>();
                for (JsonNode ex : props) seen.add(ex.path("key").asText());
                for (String attr : p.attributes) {
                    String a = attr.trim();
                    if (a.isBlank() || seen.contains(a)) continue;
                    props.add(prop(a, ""));
                    seen.add(a);
                }
            }
        }
        if (nodes.size() == 0) return "";
        try { return om.writeValueAsString(root); } catch (Exception e) { return ""; }
    }

    private ObjectNode prop(String key, String value) {
        ObjectNode o = om.createObjectNode();
        o.put("key", key);
        o.put("value", value);
        return o;
    }

    /** 把结构化图片段以隐藏 HTML 注释块嵌进文档末尾:建图侧机器可解析,人看 markdown 不受影响。 */
    private static String embedGraphFragment(String content, String fragmentJson) {
        if (fragmentJson == null || fragmentJson.isBlank()) return content;
        return content + "\n\n<!-- " + GRAPH_MARKER + "\n" + fragmentJson + "\n" + GRAPH_MARKER + " -->\n";
    }

    // ── 小工具 ───────────────────────────────────────────────
    private static void collect(LinkedHashSet<String> into, JsonNode arr) {
        if (arr != null && arr.isArray()) for (JsonNode v : arr) {
            String s = v.asText("").trim();
            if (!s.isBlank() && into.size() < 40) into.add(s);
        }
    }
    private static void appendList(StringBuilder sb, String label, JsonNode arr) {
        String j = joinNode(arr);
        if (!j.isBlank()) sb.append(label).append(": ").append(j).append('\n');
    }
    private static String joinNode(JsonNode arr) {
        if (arr == null || !arr.isArray()) return "";
        List<String> out = new ArrayList<>();
        for (JsonNode v : arr) { String s = v.asText("").trim(); if (!s.isBlank()) out.add(s); }
        return String.join("、", out);
    }
    private static List<String> dedupe(List<String> in) { return new ArrayList<>(new LinkedHashSet<>(in)); }
    private static String firstNonBlank(String a, String b) { return (a != null && !a.isBlank()) ? a : b; }
    private static String shorten(String s) { s = s == null ? "" : s; return s.length() > 120 ? s.substring(0, 120) + "…" : s; }

    /**
     * URL 归一化:得到一个"逻辑页面"去重键。
     * <p>关键:很多后台系统是 <b>hash 路由 SPA</b>(http://host/#/order)或 <b>query 路由</b>
     * (http://host/?menu=order)——它们的 path 永远是 "/",若只取 host+path,所有子页都会折叠成同一页,
     * 导致探索第 2 步起就把每个链接判成"已访问"、几步内误判"无新页面"提前收场(探索不到位的根因)。
     * 因此这里把 hash 与 query 都纳入键;同时把数字段/数字值折成 :id、剔除时间戳等易变参数,
     * 避免详情页 id 或缓存戳让"同一逻辑页"被当成无数新页、把步数预算白白烧光。
     */
    private static String normalize(String url) {
        try {
            URI u = URI.create(url);
            String host = u.getHost() == null ? "" : u.getHost();
            String path = foldIds(u.getPath() == null ? "/" : u.getPath());
            String query = u.getQuery();
            String frag = u.getFragment();   // '#' 之后:hash 路由的逻辑页(如 /order/list)
            String q = (query == null || query.isBlank()) ? "" : "?" + foldIds(stripVolatile(query));
            String h = (frag == null || frag.isBlank()) ? "" : "#" + foldIds(stripVolatile(frag));
            return host + path + q + h;
        } catch (RuntimeException e) {
            return url;  // 解析失败时保留原串(含 query/hash),宁可少折叠也别误判成同一页
        }
    }

    /** 把 URL 里的数字段/数字值折成 :id(/123→/:id、=123→=:id),让同类详情页折叠成一个逻辑页。 */
    private static String foldIds(String s) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        return s.replaceAll("/\\d+", "/:id").replaceAll("=\\d+", "=:id");
    }

    /** 剔除 query/hash 里的易变参数(时间戳、随机数、缓存戳),否则每次访问都像新页、烧光步数预算。 */
    private static final Pattern VOLATILE_PARAM = Pattern.compile(
            "(?:^|&)(?:_|t|ts|_t|_dc|v|r|rnd|rand|random|time|timestamp|nocache|cache)=[^&]*",
            Pattern.CASE_INSENSITIVE);
    private static String stripVolatile(String qs) {
        if (qs == null || qs.indexOf('=') < 0) return qs;   // 非 key=value 形态(如 hash 路由路径)原样返回
        String out = VOLATILE_PARAM.matcher(qs).replaceAll("");
        return out.startsWith("&") ? out.substring(1) : out;
    }
    private static String hostOf(String url) {
        try { return URI.create(url).getHost(); } catch (RuntimeException e) { return url; }
    }
    private static String titleFor(String baseUrl) {
        return "「" + hostOf(baseUrl) + "」业务说明文档(自动探索)";
    }

    /** 一个被探索过的页面的累积记录。 */
    private static final class PageRecord {
        final String norm;
        final String url;
        final String title;
        String summary = "";
        String type = "";
        String facts = "";
        final LinkedHashSet<String> capabilities = new LinkedHashSet<>();
        final LinkedHashSet<String> entities = new LinkedHashSet<>();
        final LinkedHashSet<String> attributes = new LinkedHashSet<>();
        final LinkedHashSet<String> states = new LinkedHashSet<>();
        final List<String> navs = new ArrayList<>();
        final List<String> blocked = new ArrayList<>();
        PageRecord(String norm, String url, String title) { this.norm = norm; this.url = url; this.title = title; }
    }

    /**
     * 客户端中断探索(用户在前端点「停止」导致 SSE 断开)时抛出:
     * 借 try-with-resources 立即关掉无头浏览器,并跳过后续的 LLM 归纳与落库,避免无谓开销。
     */
    private static final class ExplorationCancelledException extends RuntimeException {
        ExplorationCancelledException() { super("探索已被客户端中断"); }
    }
}
