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

    /** 默认/最大抓取页数：太多会撑爆 LLM 上下文，也拖慢整轮调研。 */
    private static final int DEFAULT_PAGES = 4;
    private static final int MAX_PAGES = 6;
    /** 单页正文喂给 LLM 的字符上限（WebPageFetcher 已有 60k 上限，这里再收紧控总量）。 */
    private static final int PAGE_CHAR_BUDGET = 18_000;
    /** 搜索候选数：多于抓取页数，留出抓取失败的替补。 */
    private static final int SEARCH_HITS = 10;

    private final LlmHttpClient http;
    private final LlmCallLogger callLogger;
    private final ExperienceRepository repo;
    private final ExperienceIndexService indexService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WebResearchService(LlmHttpClient http,
                              LlmCallLogger callLogger,
                              ExperienceRepository repo,
                              ExperienceIndexService indexService) {
        this.http = http;
        this.callLogger = callLogger;
        this.repo = repo;
        this.indexService = indexService;
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

        step.emit("searching", "正在联网搜索「" + q + "」…");
        List<WebSearchClient.SearchHit> hits = WebSearchClient.search(q, SEARCH_HITS);
        if (hits.isEmpty()) {
            throw new IllegalStateException("没有搜到相关结果，请换个关键词（建议：业务域 + 流程/规则，如「汽车金融 贷后管理 流程」）");
        }

        // 逐条抓取，失败跳过用后面的候选顶上，直到抓满 pages 篇
        List<PageDoc> docs = new ArrayList<>();
        for (WebSearchClient.SearchHit hit : hits) {
            if (docs.size() >= pages) break;
            step.emit("fetching", "正在抓取（" + (docs.size() + 1) + "/" + pages + "）" + hit.url() + " …");
            try {
                WebPageFetcher.Result r = WebPageFetcher.fetch(hit.url());
                if (r.text == null || r.text.isBlank()) continue;
                String text = r.text.length() > PAGE_CHAR_BUDGET
                        ? r.text.substring(0, PAGE_CHAR_BUDGET) + "\n[…truncated…]" : r.text;
                String title = (r.title != null && !r.title.isBlank()) ? r.title : hit.title();
                docs.add(new PageDoc(docs.size() + 1, title, hit.url(), text));
            } catch (Exception e) {
                log.warn("[web-research] 抓取失败 {} : {}", hit.url(), e.toString());
            }
        }
        if (docs.isEmpty()) {
            throw new IllegalStateException("搜索命中的页面均抓取失败，请稍后重试或直接用「文档导入」贴入网址");
        }

        step.emit("synthesizing", "已抓取 " + docs.size() + " 篇材料，正在归纳为业务知识文档…");
        String markdown = synthesize(q, docs, modelOverride, configId);

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
                              String modelOverride, String configId) throws IOException {
        LlmHttpClient.ResolvedConfig cfg = http.resolveConfig(modelOverride, configId);
        boolean anthropic = http.isAnthropic(cfg.baseURL(), cfg.modelName(), cfg.protocol());

        StringBuilder user = new StringBuilder();
        user.append("业务主题：").append(topic).append("\n\n以下是联网搜索到的材料：\n\n");
        for (PageDoc d : docs) {
            user.append("===== 材料[").append(d.no()).append("] ").append(d.title())
                .append("（").append(d.url()).append("）=====\n")
                .append(d.text()).append("\n\n");
        }
        user.append("请按 system 要求归纳输出《业务知识文档》。");

        callLogger.logConversation("LLM-research", cfg.modelName(),
                ResearchPrompts.WEB_RESEARCH_SYSTEM, null, user.toString(), null);
        String body = http.buildBody(cfg, ResearchPrompts.WEB_RESEARCH_SYSTEM, user.toString(),
                null, null, false, false, LlmHttpClient.EXTRACT_TEMPERATURE);
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
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("LLM 返回空内容，请重试");
        }
        return content;
    }
}
