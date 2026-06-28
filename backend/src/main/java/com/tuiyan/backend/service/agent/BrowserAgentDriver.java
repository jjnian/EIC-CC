package com.tuiyan.backend.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.options.LoadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 浏览器探索驱动：用 Playwright 像人一样"操作" web 系统(导航 / 点击),并把当前页编码成
 * 纯文本快照(URL、标题、面包屑、带编号的可点击元素、表格列、表单字段、可见正文)供 LLM 决策。
 * <p>设计要点:
 * <ul>
 *   <li><b>纯文本驱动</b>:不依赖视觉模型,快照是文本(可访问性/DOM 结构),普通文本模型即可用。</li>
 *   <li><b>线程隔离</b>:每个探索会话自带一个 Playwright/Browser/Context,全程在同一条探索线程上
 *       调用,规避 Playwright-java 的单线程约束。</li>
 *   <li><b>纵深只读护栏</b>:三层防护——快照阶段给"删除/提交/支付"等元素打 danger 标记;
 *       {@link Session#clickRef} 拒绝点击危险元素与"登出";登录后在<b>网络层</b>拦掉一切非幂等
 *       请求(POST/PUT/PATCH/DELETE)并锁定同源,即便误点也改不了数据、也不会跑到第三方站点。</li>
 * </ul>
 */
@Component
public class BrowserAgentDriver {

    private static final Logger log = LoggerFactory.getLogger(BrowserAgentDriver.class);
    private static final int NAV_TIMEOUT_MS = 20_000;
    private static final int CLICK_TIMEOUT_MS = 8_000;
    private static final int SETTLE_TIMEOUT_MS = 4_500;
    private static final String UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    /** 登出类元素:任何模式下都绝不点击,避免把探索会话自己注销掉。 */
    private static final Pattern LOGOUT = Pattern.compile(
            "退出登录|退 出|注销|登出|logout|log\\s*out|sign\\s*out", Pattern.CASE_INSENSITIVE);

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
            Browser.NewContextOptions opts = new Browser.NewContextOptions()
                    .setUserAgent(UA)
                    .setAcceptDownloads(false); // 只读探索不应触发任何文件下载
            if (storageStateJson != null && !storageStateJson.isBlank()) {
                opts.setStorageState(storageStateJson);
            }
            BrowserContext ctx = browser.newContext(opts);
            Page page = ctx.newPage();
            page.setDefaultTimeout(CLICK_TIMEOUT_MS);

            // 网络层护栏:登录前(未 arm)放行;arm 后拦非幂等请求 + 锁定同源主导航。
            final Session[] ref = new Session[1];
            ctx.route("**/*", route -> {
                Session s = ref[0];
                if (s == null || !s.guardArmed) { route.resume(); return; }
                Request req = route.request();
                String m = req.method() == null ? "GET" : req.method().toUpperCase();
                boolean idempotent = m.equals("GET") || m.equals("HEAD") || m.equals("OPTIONS");
                if (s.blockMutations && !idempotent) {
                    log.debug("[browser-agent] 只读护栏拦截非幂等请求 {} {}", m, req.url());
                    route.abort();
                    return;
                }
                // 仅拦主框架的跨站导航(防探索跑偏到第三方站点);子资源(CDN/字体/图)跨域放行
                if (req.isNavigationRequest() && s.lockHost != null && req.frame() == s.page.mainFrame()) {
                    String h = hostOf(req.url());
                    if (h != null && !sameSite(h, s.lockHost)) {
                        log.debug("[browser-agent] 同站护栏拦截跨站导航 {}", req.url());
                        route.abort();
                        return;
                    }
                }
                route.resume();
            });

            page.navigate(baseUrl, new Page.NavigateOptions().setTimeout(NAV_TIMEOUT_MS));
            page.waitForLoadState();
            Session session = new Session(pw, browser, ctx, page);
            ref[0] = session;
            return session;
        } catch (RuntimeException e) {
            pw.close();
            // Chromium 未安装是最常见的失败:给出可执行的修复指引
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("Executable doesn't exist") || msg.contains("install")) {
                throw new IllegalStateException("浏览器内核(Chromium)未安装。请在 backend 目录执行:"
                        + " mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args=\"install chromium\"", e);
            }
            if (msg.contains("invalid URL") || msg.contains("Cannot navigate")) {
                throw new IllegalStateException("无法访问该地址「" + baseUrl + "」，请确认它是可打开的网址", e);
            }
            // 只取首行，避免把 Playwright 的多行 Call log 长栈直接抛给用户
            int nl = msg.indexOf('\n');
            String brief = nl > 0 ? msg.substring(0, nl).trim() : msg;
            if (brief.length() > 200) brief = brief.substring(0, 200) + "…";
            throw new IllegalStateException("无法打开浏览器会话: " + brief, e);
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
        // 护栏状态(由 driver 的 route lambda 读取):arm 后生效。lockHost 为登录后落点的主机。
        volatile boolean guardArmed = false;
        volatile boolean blockMutations = false;
        volatile String lockHost = null;

        Session(Playwright pw, Browser browser, BrowserContext ctx, Page page) {
            this.pw = pw; this.browser = browser; this.ctx = ctx; this.page = page;
        }

        public String currentUrl() { return page.url(); }

        /**
         * 启用网络层护栏:以"当前所在页的主机"为同源基准(覆盖 SSO 登录后落到子域的情形),
         * 只读模式额外拦截一切非幂等请求。应在登录完成后、正式探索前调用一次。
         */
        public void armGuard(boolean readOnly) {
            this.lockHost = hostOf(page.url());
            this.blockMutations = readOnly;
            this.guardArmed = true;
            log.debug("[browser-agent] 护栏已启用 lockHost={} blockMutations={}", lockHost, readOnly);
        }

        /** 导出当前会话的 storageState(登录态 cookies/localStorage)JSON,供下次免登录复用。失败返回 null。 */
        public String exportStorageState() {
            try { return ctx.storageState(); } catch (RuntimeException e) {
                log.warn("[browser-agent] 导出 storageState 失败: {}", e.getMessage());
                return null;
            }
        }

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
         * 点击编号为 ref 的元素。
         * <ul>
         *   <li>登出类元素:任何模式都拒绝(保住探索会话);</li>
         *   <li>只读模式下危险(会改数据)元素:拒绝。</li>
         * </ul>
         * @return 实际点击的元素名;被拦截时抛 {@link BlockedActionException}
         */
        public String clickRef(int ref, boolean readOnly) {
            String name = refNames.getOrDefault(ref, "#" + ref);
            if (LOGOUT.matcher(name).find()) throw new BlockedActionException(name);
            if (readOnly && dangerRefs.contains(ref)) throw new BlockedActionException(name);
            Locator loc = page.locator("[data-agent-ref='" + ref + "']").first();
            loc.click(new Locator.ClickOptions().setTimeout(CLICK_TIMEOUT_MS));
            settle();
            return name;
        }

        /**
         * 在当前页(入口页)用账号密码自动登录:定位用户名/密码输入框填入,再点登录按钮(或回车提交)。
         * 尽量覆盖常见登录页(中英文标识)。找不到密码框时返回 false,由上层降级为未登录探索。
         * @return 是否成功提交了登录表单
         */
        public boolean login(String username, String password) {
            if (username == null || username.isBlank()) return false;
            try {
                Locator pwd = page.locator("input[type='password']").first();
                pwd.waitFor(new Locator.WaitForOptions().setTimeout(CLICK_TIMEOUT_MS));
                Locator user = page.locator(
                        "input[type='text'],input[type='email'],input[type='tel'],"
                        + "input[name*='user' i],input[name*='account' i],input[name*='login' i],input[name*='email' i],"
                        + "input[id*='user' i],input[id*='account' i],"
                        + "input[placeholder*='用户'],input[placeholder*='账号'],input[placeholder*='帐号'],input[placeholder*='邮箱']"
                ).first();
                user.fill(username);
                pwd.fill(password == null ? "" : password);
                // 优先点击登录按钮,失败则回车提交
                Locator btn = page.locator(
                        "button:has-text('登录'),button:has-text('登 录'),button:has-text('登陆'),"
                        + "button:has-text('Login'),button:has-text('Sign in'),button:has-text('Sign In'),"
                        + "button[type='submit'],input[type='submit']"
                ).first();
                try {
                    btn.click(new Locator.ClickOptions().setTimeout(CLICK_TIMEOUT_MS));
                } catch (RuntimeException e) {
                    pwd.press("Enter");
                }
                settle();
                return true;
            } catch (RuntimeException e) {
                log.warn("[browser-agent] 自动登录失败: {}", e.getMessage());
                return false;
            }
        }

        /**
         * 登录后粗略判断是否已离开登录页：当前页不再有可见的密码输入框即认为登录成功。
         * 也用于探索途中检测会话失效(被重定向回登录页)。判断失败时返回 true(不阻断探索)。
         */
        public boolean looksLoggedIn() {
            try {
                Locator pwd = page.locator("input[type='password']");
                if (pwd.count() == 0) return true;
                return !pwd.first().isVisible();
            } catch (RuntimeException e) {
                return true;
            }
        }

        /** 后退一页。 */
        public void back() {
            page.goBack(new Page.GoBackOptions().setTimeout(NAV_TIMEOUT_MS));
            settle();
        }

        /**
         * 点击 / 导航后等待页面稳定:优先等"网络空闲"(覆盖 SPA 局部刷新/异步加载,无 load 事件的情形),
         * 超时则退回 load 事件。两者都失败也不致命。
         */
        private void settle() {
            try {
                page.waitForLoadState(LoadState.NETWORKIDLE,
                        new Page.WaitForLoadStateOptions().setTimeout(SETTLE_TIMEOUT_MS));
            } catch (RuntimeException ignore) {
                try { page.waitForLoadState(); } catch (RuntimeException ignore2) { /* SPA 局部更新 */ }
            }
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

    /** 从 URL 取主机名;解析失败返回 null。 */
    private static String hostOf(String url) {
        try { return URI.create(url).getHost(); } catch (RuntimeException e) { return null; }
    }

    /** 同站判断:主机相等,或同一可注册域(末两段相同),允许 app.x.com↔sso.x.com 这类同站子域互访。 */
    private static boolean sameSite(String host, String lockHost) {
        if (host == null || lockHost == null) return true; // 无法判断时放行,避免误伤
        if (host.equalsIgnoreCase(lockHost)) return true;
        String a = registrableDomain(host), b = registrableDomain(lockHost);
        return a != null && a.equalsIgnoreCase(b);
    }

    /**
     * 常见的「二级公共后缀」：这些 TLD 的可注册域要取末三段（如 a.example.com.cn）。
     * <p>不引入完整 Public Suffix List，仅覆盖最常见的中/英/日/澳等 ccTLD，足以避免把
     * {@code a.com.cn} 与 {@code b.com.cn} 误判为同站。无匹配时退化为「末两段」。
     */
    private static final Set<String> TWO_LABEL_SUFFIXES = Set.of(
            "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "ac.cn",
            "co.uk", "org.uk", "gov.uk", "ac.uk", "me.uk",
            "co.jp", "or.jp", "ne.jp", "co.kr", "or.kr",
            "com.hk", "com.tw", "com.au", "net.au", "org.au",
            "com.sg", "com.br", "com.mx");

    /** 取可注册域;单段主机(localhost)/无点直接返回原值;识别常见二级公共后缀取末三段。 */
    private static String registrableDomain(String host) {
        if (host == null) return null;
        String h = host.toLowerCase();
        String[] p = h.split("\\.");
        if (p.length < 2) return h;
        String lastTwo = p[p.length - 2] + "." + p[p.length - 1];
        if (p.length >= 3 && TWO_LABEL_SUFFIXES.contains(lastTwo)) {
            return p[p.length - 3] + "." + lastTwo;
        }
        return lastTwo;
    }

    /**
     * 注入到页面里的快照脚本:给可点击元素编号 + 抽出表格列/表单字段/标题/正文。
     * 返回 JSON 字符串(避免跨语言类型转换)。danger 正则覆盖会改数据的操作;元素附带 href 供上层
     * 构建探索边界(frontier)与去重。
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
            const href = el.getAttribute('href') || '';
            if (!name || seen.has(name + '@' + href)) continue;
            seen.add(name + '@' + href);
            el.setAttribute('data-agent-ref', ref);
            const tag = el.tagName.toLowerCase();
            const role = el.getAttribute('role') || (tag === 'a' ? 'link' : tag === 'button' ? 'button' : tag);
            const danger = DANGER.test(name) || el.type === 'submit';
            let abs = '';
            try { abs = href ? new URL(href, location.href).href : ''; } catch (e) { abs = ''; }
            elements.push({ ref, role, name, danger, href: abs });
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
