package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.repository.ExperienceRepository;
import com.tuiyan.backend.service.indexing.ExperienceIndexService;
import com.tuiyan.backend.service.llm.LlmCallLogger;
import com.tuiyan.backend.service.llm.LlmHttpClient;
import com.tuiyan.backend.service.llm.prompt.ResearchPrompts;
import com.tuiyan.backend.support.WebPageFetcher;
import com.tuiyan.backend.support.WebSearchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 联网调研：搜索业务主题 → 抓取命中网页 → LLM 归纳成《业务知识文档》→ 落经验库参与建图。
 * <p>这是继「对话/录音、系统探索、数据库」之后的第四类业务信息来源——公开领域知识
 * （行业流程惯例、监管要求、通用术语），用于冷启动或补全内部材料覆盖不到的背景。
 * <p>产出经验 origin=websearch、正文带来源编号与链接，建图时与其它经验一起聚合；
 * 因公开资料非本企业事实，建议建图后用内部三源（访谈/系统/库表）校对。
 */
@Service
public class WebResearchService {

    private static final Logger log = LoggerFactory.getLogger(WebResearchService.class);

    /** 默认/最大抓取页数：多角度拓展后候选更丰富，页数上调以扩大覆盖（总量另受 TOTAL_CHAR_BUDGET 约束）。 */
    private static final int DEFAULT_PAGES = 8;
    private static final int MAX_PAGES = 12;
    /** 单页正文喂给 LLM 的字符上限（页数增多，单页收紧，避免撑爆上下文）。 */
    private static final int PAGE_CHAR_BUDGET = 14_000;
    /** 喂给归纳 LLM 的材料总字符上限：超出则少喂几篇，护住上下文与成本。 */
    private static final int TOTAL_CHAR_BUDGET = 120_000;
    /** 每条子查询取的候选数。 */
    private static final int HITS_PER_QUERY = 6;
    /** 查询拓展的最大子查询数（含原主题兜底）。 */
    private static final int MAX_SUBQUERIES = 6;

    private final LlmHttpClient http;
    private final LlmCallLogger callLogger;
    private final ExperienceRepository repo;
    private final ExperienceIndexService indexService;
    private final WebSearchClient searchClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WebResearchService(LlmHttpClient http,
                              LlmCallLogger callLogger,
                              ExperienceRepository repo,
                              ExperienceIndexService indexService,
                              WebSearchClient searchClient) {
        this.http = http;
        this.callLogger = callLogger;
        this.repo = repo;
        this.indexService = indexService;
        this.searchClient = searchClient;
    }

    /** 已抓到的一篇材料。 */
    private record PageDoc(int no, String title, String url, String text) {}

    /**
     * 执行一轮联网调研；调用前需保证 WorkspaceContext 已设置（经验引用按工作空间隔离）。
     * @return 新建的经验（含 id/title/origin=websearch）
     */
    public Map<String, Object> research(String topic,
                                        int maxPages,
                                        String modelOverride,
                                        String configId,
                                        ExperienceOntologyService.StepSink step) throws IOException {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("请填写要调研的业务主题");
        }
        String q = topic.strip();
        int pages = maxPages <= 0 ? DEFAULT_PAGES : Math.min(maxPages, MAX_PAGES);
        LlmHttpClient.ResolvedConfig cfg = http.resolveConfig(modelOverride, configId);
        boolean anthropic = http.isAnthropic(cfg.baseURL(), cfg.modelName(), cfg.protocol());

        // 1) 多角度查询拓展：单条查询覆盖太窄是"探索不出来"的主因，拆成多侧面子查询扩大信息面
        List<String> queries = expandQueries(q, cfg, anthropic);
        step.emit("searching", "正在从 " + queries.size() + " 个角度联网搜索「" + q + "」…");

        // 2) 逐条子查询搜索，跨查询按 URL 去重后合并候选（同一页只保留一次）
        List<WebSearchClient.SearchHit> candidates = new ArrayList<>();
        java.util.Set<String> seenUrls = new java.util.HashSet<>();
        for (String sub : queries) {
            try {
                for (WebSearchClient.SearchHit h : searchClient.search(sub, HITS_PER_QUERY)) {
                    if (seenUrls.add(h.url())) candidates.add(h);
                }
            } catch (RuntimeException e) {
                log.warn("[web-research] 子查询搜索失败「{}」: {}", sub, e.toString());
            }
        }
        if (candidates.isEmpty()) {
            throw new IllegalStateException("搜索服务返回 0 条结果，请换个关键词（建议：业务域 + 流程/规则，如「汽车金融 贷后管理 流程」）");
        }

        // 3) 并行抓取候选正文（有界并发；SPA 页会回退 Playwright 渲染，串行太慢），直到抓满 pages 篇
        List<PageDoc> docs = fetchPages(candidates, pages, step);
        if (docs.isEmpty()) {
            throw new IllegalStateException("搜索命中的页面均抓取失败，请稍后重试或直接用「文档导入」贴入网址");
        }

        step.emit("synthesizing", "已从 " + queries.size() + " 个角度抓取 " + docs.size() + " 篇材料，正在归纳为业务知识文档…");
        String markdown = synthesize(q, docs, cfg, anthropic);

        // 追加参考来源（LLM 正文里是 [n] 编号，这里给编号落地成链接）
        StringBuilder md = new StringBuilder(markdown.strip());
        md.append("\n\n## 参考来源\n\n");
        for (PageDoc d : docs) {
            md.append("[").append(d.no()).append("] ").append(d.title())
              .append("  \n").append(d.url()).append("\n\n");
        }
        md.append("> 本文由联网调研自动归纳（公开资料，非本企业内部事实），建图后请用访谈 / 系统探索 / 库表结构校对。\n");

        step.emit("saving", "正在保存为经验并建立索引…");
        Map<String, Object> exp = repo.create("网络调研：" + q, md.toString(), "websearch", "websearch");
        String id = String.valueOf(exp.get("id"));
        try {
            repo.reference(id); // 调研是工作空间内的明确动作：直接引用进当前空间侧栏
        } catch (Exception e) {
            log.warn("[web-research] 自动引用失败: {}", e.toString());
        }
        try {
            indexService.reindexAsync(id);
        } catch (Exception e) {
            log.warn("[web-research] 触发索引失败: {}", e.toString());
        }
        step.emit("done", "完成：已沉淀经验「网络调研：" + q + "」（" + docs.size() + " 篇来源）");
        return exp;
    }

    /** 调 LLM 把抓到的材料归纳成 markdown（非 JSON 模式、低温度）。 */
    private String synthesize(String topic, List<PageDoc> docs,
                              LlmHttpClient.ResolvedConfig cfg, boolean anthropic) throws IOException {
        StringBuilder user = new StringBuilder();
        user.append("业务主题：").append(topic).append("\n\n以下是联网搜索到的材料：\n\n");
        for (PageDoc d : docs) {
            user.append("===== 材料[").append(d.no()).append("] ").append(d.title())
                .append("（").append(d.url()).append("）=====\n")
                .append(d.text()).append("\n\n");
        }
        user.append("请按 system 要求归纳输出《业务知识文档》。");
        String content = callLlm(cfg, anthropic, ResearchPrompts.WEB_RESEARCH_SYSTEM, user.toString());
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("LLM 返回空内容，请重试");
        }
        return content;
    }

    /**
     * 多角度查询拓展：让 LLM 把主题拆成多条互补子查询（含原主题兜底）。任何失败都退化为 [原主题]，
     * 保证联网调研不因拓展失败而中断。
     */
    private List<String> expandQueries(String topic, LlmHttpClient.ResolvedConfig cfg, boolean anthropic) {
        List<String> out = new ArrayList<>();
        out.add(topic);   // 原主题始终参与，作兜底
        try {
            String raw = callLlm(cfg, anthropic, ResearchPrompts.QUERY_EXPANSION_SYSTEM, "业务主题：" + topic);
            if (raw != null) {
                String json = raw.substring(raw.indexOf('['), raw.lastIndexOf(']') + 1);  // 容忍前后杂字
                for (JsonNode n : objectMapper.readTree(json)) {
                    String s = n.asText("").strip();
                    if (!s.isBlank() && out.stream().noneMatch(x -> x.equalsIgnoreCase(s))) out.add(s);
                    if (out.size() >= MAX_SUBQUERIES) break;
                }
            }
        } catch (Exception e) {
            log.warn("[web-research] 查询拓展失败，退化为单查询: {}", e.toString());
        }
        return out;
    }

    /**
     * 串行抓取候选页正文，抓满 {@code pages} 篇或候选耗尽即止；受 {@link #TOTAL_CHAR_BUDGET} 总量约束。
     * <p>不并行的原因：{@link WebPageFetcher} 的 SPA 回退走全局共享的 Playwright Browser，
     * 而 Playwright-java 有单线程约束（并发碰同一 Browser 会崩），故按序抓取以保正确。
     */
    private List<PageDoc> fetchPages(List<WebSearchClient.SearchHit> candidates, int pages,
                                     ExperienceOntologyService.StepSink step) {
        List<PageDoc> docs = new ArrayList<>();
        int total = 0;
        for (WebSearchClient.SearchHit hit : candidates) {
            if (docs.size() >= pages || total >= TOTAL_CHAR_BUDGET) break;
            step.emit("fetching", "正在抓取（" + (docs.size() + 1) + "/" + pages + "）" + hit.url() + " …");
            try {
                WebPageFetcher.Result r = WebPageFetcher.fetch(hit.url());
                if (r.text == null || r.text.isBlank()) continue;
                String text = r.text.length() > PAGE_CHAR_BUDGET
                        ? r.text.substring(0, PAGE_CHAR_BUDGET) + "\n[…truncated…]" : r.text;
                String title = (r.title != null && !r.title.isBlank()) ? r.title : hit.title();
                docs.add(new PageDoc(docs.size() + 1, title, hit.url(), text));
                total += text.length();
            } catch (Exception e) {
                log.warn("[web-research] 抓取失败 {} : {}", hit.url(), e.toString());
            }
        }
        return docs;
    }

    /** 统一的 LLM 文本调用（非 JSON、低温度）：查询拓展与归纳共用，收敛 HTTP/日志/计量样板。 */
    private String callLlm(LlmHttpClient.ResolvedConfig cfg, boolean anthropic,
                           String system, String user) throws IOException {
        callLogger.logConversation("LLM-research", cfg.modelName(), system, null, user, null);
        String body = http.buildBody(cfg, system, user, null, null, false, false, LlmHttpClient.EXTRACT_TEMPERATURE);
        HttpRequest req = http.buildHttpRequest(cfg.baseURL(), cfg.apiKey(), anthropic, body, cfg.rawUrl());
        long t0 = System.currentTimeMillis();
        HttpResponse<String> resp = http.sendHttp(req, HttpResponse.BodyHandlers.ofString());
        long elapsed = System.currentTimeMillis() - t0;
        if (resp.statusCode() != 200) {
            callLogger.logUpstreamError("research", resp.statusCode(), resp.body());
            http.metrics().recordCall(cfg.modelName(), elapsed, false);
            throw new IllegalStateException("LLM 调用失败 HTTP " + resp.statusCode() + "（详情见服务器日志）");
        }
        http.metrics().recordCall(cfg.modelName(), elapsed, true);
        JsonNode root = objectMapper.readTree(resp.body());
        String content = http.extractContent(root, anthropic);
        callLogger.logLlmResponse("LLM-research", cfg.modelName(), elapsed, content);
        return content;
    }
}
