package com.tuiyan.backend.service.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.entity.DataSourceFetchLogPO;
import com.tuiyan.backend.entity.DataSourcePO;
import com.tuiyan.backend.model.dto.DataSourceTestResponse;
import com.tuiyan.backend.model.dto.HttpExecuteResponse;
import com.tuiyan.backend.repository.DataSourceFetchLogRepository;
import com.tuiyan.backend.repository.DataSourceRepository;
import com.tuiyan.backend.service.DataSourceService;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Map;

/**
 * HTTPS 接口数据源的类型处理器:连接测试(即时执行一次)、导出「请求配置(脱敏)+ 最近一次响应」文档、
 * 以及创建/更新按 schedule 注册定时拉取、删除时取消。
 */
@Component
public class HttpKindHandler implements SourceKindHandler {

    private final HttpConnectorService http;
    private final HttpScheduler scheduler;
    private final DataSourceFetchLogRepository logRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HttpKindHandler(HttpConnectorService http, HttpScheduler scheduler,
                           DataSourceFetchLogRepository logRepo) {
        this.http = http;
        this.scheduler = scheduler;
        this.logRepo = logRepo;
    }

    @Override
    public boolean supports(String kind) {
        return SourceKind.HTTPS_API.equals(kind);
    }

    @Override
    public DataSourceTestResponse test(String kind, Map<String, Object> cfg) {
        HttpExecuteResponse r = http.execute(cfg);
        return new DataSourceTestResponse(
                r.isSuccess(),
                r.isSuccess() ? "HTTP " + r.getStatusCode() : r.getErrorMsg(),
                r.getDurationMs());
    }

    @Override
    public DataSourceService.SourceDocExport exportDoc(DataSourcePO po, Map<String, Object> cfg, int sampleRows) {
        String id = po.getId();
        Map<String, Object> maskedCfg = DataSourceRepository.maskConfig(cfg);

        Integer status; Integer ms; String body; Long when; boolean ok; String err;
        DataSourceFetchLogPO latest = logRepo.listLatest(id).stream().findFirst().orElse(null);
        if (latest != null) {
            status = latest.getStatusCode(); ms = latest.getDurationMs(); body = latest.getResponseBody();
            when = latest.getFetchedAt(); ok = Boolean.TRUE.equals(latest.getSuccess()); err = latest.getErrorMsg();
        } else {
            HttpExecuteResponse r = scheduler.runOnce(id);   // 无历史：即时拉一次拿到样例
            status = r.getStatusCode(); ms = r.getDurationMs(); body = r.getBody();
            when = System.currentTimeMillis(); ok = r.isSuccess(); err = r.getErrorMsg();
        }

        String title = "「" + po.getName() + "」HTTP 接口";
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n")
          .append("> 类型: HTTPS 接口 · 由数据源配置与最近一次响应自动生成\n\n")
          .append("## 请求配置\n\n")
          .append("```json\n").append(toPrettyJson(maskedCfg)).append("\n```\n\n")
          .append("## 最近一次响应\n\n");
        if (ok) {
            sb.append("- 状态: HTTP ").append(status).append(" · 耗时 ").append(ms).append("ms");
            if (when != null) sb.append(" · 时间 ").append(new Date(when));
            sb.append("\n\n");
            String sample = body == null ? "" : body;
            int cap = 20_000;
            if (sample.length() > cap) sample = sample.substring(0, cap) + "\n…（响应过长已截断）";
            sb.append("```json\n").append(sample).append("\n```\n");
        } else {
            sb.append("- 调用失败: ").append(err == null || err.isBlank() ? "未知错误" : err).append("\n");
        }
        return new DataSourceService.SourceDocExport(po.getName(), title, sb.toString(), "http,api", "datasource");
    }

    /** 创建/更新后按 schedule 配置注册定时拉取(先取消旧的,幂等)。 */
    @Override
    public void onPersist(DataSourcePO po, Map<String, Object> cfg) {
        String id = po.getId();
        scheduler.cancel(id);
        if (cfg == null) return;
        Object schedObj = cfg.get("schedule");
        if (!(schedObj instanceof Map<?, ?> sched)) return;
        if (!Boolean.TRUE.equals(sched.get("enabled"))) return;
        Object iv = sched.get("intervalSec");
        if (iv instanceof Number n) scheduler.register(id, n.intValue());
    }

    @Override
    public void onDelete(DataSourcePO po) {
        scheduler.cancel(po.getId());
    }

    private String toPrettyJson(Object v) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(v);
        } catch (Exception e) {
            return String.valueOf(v);
        }
    }
}
