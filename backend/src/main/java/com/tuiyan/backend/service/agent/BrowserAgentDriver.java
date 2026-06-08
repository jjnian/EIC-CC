package com.tuiyan.backend.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 浏览器探索驱动：用 Playwright 像人一样"操作" web 系统(导航 / 点击),并把当前页编码成
 * 纯文本快照(URL、标题、面包屑、带编号的可点击元素、表格列、表单字段、可见正文)供 LLM 决策。
 * <p>设计要点:
 * <ul>
 *   <li><b>纯文本驱动</b>:不依赖视觉模型,快照是文本(可访问性/DOM 结构),普通文本模型即可用。</li>
 *   <li><b>线程隔离</b>:每个探索会话自带一个 Playwright/Browser/Context,全程在同一条探索线程上
 *       调用,规避 Playwright-java 的单线程约束。</li>
 *   <li><b>只读护栏</b>:快照阶段给"删除/提交/支付"等会改数据的元素打 danger 标记;只读模式下
 *       {@link Session#clickRef} 会拒绝点击它们,做到尽量零副作用的探索。</li>
 * </ul>
 */
@Component
public class BrowserAgentDriver {

    private static final Logger log = LoggerFactory.getLogger(BrowserAgentDriver.class);
    private static final int NAV_TIMEOUT_MS = 20_000;
    private static final int CLICK_TIMEOUT_MS = 8_000;
    private static final String UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    private final ObjectMapper om = new ObjectMapper();

    /**
     * 打开一个探索会话并导航到入口页。
     * @param baseUrl          系统入口 URL
     * @param storageStateJson 可选:预登录的 storageState(cookies/localStorage)JSON,绕过登录
     */
    public Session open(String baseUrl, String storageStateJson) {
        Playwright pw = Playwright.create();
        try {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Browser.NewContextOptions opts = new Browser.NewContextOptions().setUserAgent(UA);
            if (storageStateJson != null && !storageStateJson.isBlank()) {
                opts.setStorageState(storageStateJson);
            }
            BrowserContext ctx = browser.newContext(opts);
            Page page = ctx.newPage();
            page.setDefaultTimeout(CLICK_TIMEOUT_MS);
            page.navigate(baseUrl, new Page.NavigateOptions().setTimeout(NAV_TIMEOUT_MS));
            page.waitForLoadState();
            return new Session(pw, browser, ctx, page);
        } catch (RuntimeException e) {
            pw.close();
            // Chromium 未安装是最常见的失败:给出可执行的修复指引
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("Executable doesn't exist") || msg.contains("install")) {
                throw new IllegalStateException("浏览器内核(Chromium)未安装。请在 backend 目录执行:"
                        + " mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args=\"install chromium\"", e);
            }
            throw new IllegalStateException("无法打开浏览器会话: " + msg, e);
        }
    }

    /** 一个探索会话:封装 Playwright 资源 + 当前页元素编号映射。非线程安全,只在探索线程使用。 */
    public final class Session implements AutoCloseable {
        private final Playwright pw;
        private final Browser browser;
        private final BrowserContext ctx;
        private final Page page;
        // 最近一次快照里:ref → 元素名 / 是否危险元素
        private final Map<Integer, String> refNames = new HashMap<>();
        private final Set<Integer> dangerRefs = new HashSet<>();

        Session(Playwright pw, Browser browser, BrowserContext ctx, Page page) {
            this.pw = pw; this.browser = browser; this.ctx = ctx; this.page = page;
        }

        public String currentUrl() { return page.url(); }

        /**
         * 把当前页编码成文本快照(JSON 字符串),并刷新 ref→元素 的映射。
         * 通过注入 data-agent-ref 属性给可点击元素编号,后续 {@link #clickRef} 按编号点击。
         */
        public JsonNode snapshot() {
            Object raw = page.evaluate(SNAPSHOT_JS);
            refNames.clear();
            dangerRefs.clear();
            try {
                JsonNode snap = om.readTree(String.valueOf(raw));
                for (JsonNode el : snap.path("elements")) {
                    int ref = el.path("ref").asInt(-1);
                    if (ref < 0) continue;
                    refNames.put(ref, el.path("name").asText(""));
                    if (el.path("danger").asBoolean(false)) dangerRefs.add(ref);
                }
                return snap;
            } catch (Exception e) {
                throw new IllegalStateException("页面快照解析失败: " + e.getMessage(), e);
            }
        }

        /**
         * 点击编号为 ref 的元素。只读模式下,若该元素被标记为危险(会改数据)则拒绝点击。
         * @return 实际点击的元素名;被拦截时抛 {@link BlockedActionException}
         */
        public String clickRef(int ref, boolean readOnly) {
            String name = refNames.getOrDefault(ref, "#" + ref);
            if (readOnly && dangerRefs.contains(ref)) {
                throw new BlockedActionException(name);
            }
            Locator loc = page.locator("[data-agent-ref='" + ref + "']").first();
            loc.click(new Locator.ClickOptions().setTimeout(CLICK_TIMEOUT_MS));
            settle();
            return name;
        }

        /** 后退一页。 */
        public void back() {
            page.goBack(new Page.GoBackOptions().setTimeout(NAV_TIMEOUT_MS));
            settle();
        }

        /** 点击 / 导航后等待页面稳定;失败不致命(SPA 局部刷新可能无 load 事件)。 */
        private void settle() {
            try { page.waitForLoadState(); } catch (RuntimeException ignore) { /* SPA 局部更新 */ }
        }

        @Override
        public void close() {
            try { ctx.close(); } catch (RuntimeException ignore) {}
            try { browser.close(); } catch (RuntimeException ignore) {}
            try { pw.close(); } catch (RuntimeException ignore) {}
        }
    }

    /** 只读模式下点击到危险元素时抛出,供上层记录"已拦截"而不中断探索。 */
    public static class BlockedActionException extends RuntimeException {
        public BlockedActionException(String elementName) { super(elementName); }
    }

    /**
     * 注入到页面里的快照脚本:给可点击元素编号 + 抽出表格列/表单字段/标题/正文。
     * 返回 JSON 字符串(避免跨语言类型转换)。danger 正则覆盖会改数据的操作。
     */
    private static final String SNAPSHOT_JS = """
        () => {
          const vis = el => { const r = el.getBoundingClientRect(); const s = getComputedStyle(el);
            return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none'; };
          const clean = s => (s || '').replace(/\\s+/g, ' ').trim();
          const txt = el => clean(el.getAttribute('aria-label') || el.innerText || el.value
            || el.getAttribute('placeholder') || el.getAttribute('title')).slice(0, 80);
          const DANGER = /(删除|删 |移除|清空|提交|保存|确认|新建|新增|创建|编辑|修改|支付|付款|下单|发送|审批|通过|拒绝|驳回|重置|退出|注销|delete|remove|submit|save|confirm|create|edit|update|pay|approve|reject|reset|logout|sign\\s*out)/i;
          const sel = 'a,button,[role=button],[role=link],[role=menuitem],[role=tab],summary,input[type=submit],input[type=button]';
          const seen = new Set();
          const elements = [];
          let ref = 0;
          for (const el of document.querySelectorAll(sel)) {
            if (ref >= 80) break;
            if (!vis(el)) continue;
            const name = txt(el);
            if (!name || seen.has(name + '@' + (el.getAttribute('href') || ''))) continue;
            seen.add(name + '@' + (el.getAttribute('href') || ''));
            el.setAttribute('data-agent-ref', ref);
            const tag = el.tagName.toLowerCase();
            const role = el.getAttribute('role') || (tag === 'a' ? 'link' : tag === 'button' ? 'button' : tag);
            const danger = DANGER.test(name) || el.type === 'submit';
            elements.push({ ref, role, name, danger });
            ref++;
          }
          const forms = Array.from(document.querySelectorAll('form')).slice(0, 6).map(f => ({
            fields: Array.from(f.querySelectorAll('input,select,textarea')).slice(0, 30).map(i =>
              clean((i.labels && i.labels[0] && i.labels[0].innerText) || i.getAttribute('aria-label')
                || i.getAttribute('placeholder') || i.name).slice(0, 40)).filter(Boolean)
          })).filter(f => f.fields.length);
          const tables = Array.from(document.querySelectorAll('table')).slice(0, 6).map(t => ({
            caption: clean(t.caption && t.caption.innerText).slice(0, 40),
            cols: Array.from(t.querySelectorAll('thead th, tr:first-child th')).slice(0, 20)
              .map(h => clean(h.innerText).slice(0, 30)).filter(Boolean),
            rows: t.querySelectorAll('tbody tr').length || Math.max(0, t.querySelectorAll('tr').length - 1)
          })).filter(t => t.cols.length);
          const headings = Array.from(document.querySelectorAll('h1,h2')).slice(0, 8)
            .map(h => clean(h.innerText).slice(0, 60)).filter(Boolean);
          const crumb = Array.from(document.querySelectorAll('[class*=breadcrumb] a, [aria-label*=readcrumb] a, [class*=breadcrumb] span'))
            .map(b => clean(b.innerText)).filter(Boolean).slice(0, 8).join(' / ');
          const text = clean(document.body.innerText).slice(0, 1200);
          return JSON.stringify({ url: location.href, title: document.title, breadcrumb: crumb, headings, elements, forms, tables, text });
        }
        """;
}
