package com.tuiyan.backend.service.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.entity.DataSourcePO;
import com.tuiyan.backend.model.dto.HttpExecuteResponse;
import com.tuiyan.backend.repository.DataSourceFetchLogRepository;
import com.tuiyan.backend.repository.DataSourceRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * https_api 数据源定时拉取调度器。单线程池避免占用业务任务线程；
 * 每个数据源最多注册一个任务；连续失败 5 次自动停用。
 */
@Service
@EnableScheduling
public class HttpScheduler {

    private static final Logger log = LoggerFactory.getLogger(HttpScheduler.class);
    public static final int MIN_INTERVAL_SEC = 60;
    public static final int MAX_ACTIVE_TASKS = 20;
    public static final int MAX_CONSECUTIVE_FAILS = 5;

    private final ThreadPoolTaskScheduler scheduler;
    private final DataSourceRepository repo;
    private final DataSourceFetchLogRepository logRepo;
    private final HttpConnectorService http;
    private final ObjectMapper mapper;

    private final Map<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();
    private final Map<String, Integer> failCounters = new ConcurrentHashMap<>();

    public HttpScheduler(DataSourceRepository repo,
                         DataSourceFetchLogRepository logRepo,
                         HttpConnectorService http,
                         ObjectMapper mapper) {
        this.repo = repo;
        this.logRepo = logRepo;
        this.http = http;
        this.mapper = mapper;
        this.scheduler = new ThreadPoolTaskScheduler();
        this.scheduler.setPoolSize(2);
        this.scheduler.setThreadNamePrefix("ds-http-");
        this.scheduler.setDaemon(true);
        this.scheduler.initialize();
    }

    /** 启动时加载 DB 中所有 https_api 且 schedule.enabled=true 的记录注册任务。 */
    @PostConstruct
    public void bootstrap() {
        for (DataSourcePO po : repo.listAllByKind("https_api")) {
            Map<String, Object> cfg = repo.readConfig(po);
            Map<?, ?> sched = cfg.get("schedule") instanceof Map<?, ?> m ? m : null;
            if (sched == null) continue;
            Object enabled = sched.get("enabled");
            Object interval = sched.get("intervalSec");
            if (Boolean.TRUE.equals(enabled) && interval instanceof Number n) {
                try {
                    register(po.getId(), n.intValue());
                } catch (Exception e) {
                    log.warn("[ds-sched] bootstrap register failed id={} err={}", po.getId(), e.toString());
                }
            }
        }
    }

    /** 注册或更新某数据源的定时任务。已存在则先取消。 */
    public synchronized void register(String dataSourceId, int intervalSec) {
        if (intervalSec < MIN_INTERVAL_SEC) {
            throw new IllegalArgumentException("间隔不得小于 " + MIN_INTERVAL_SEC + " 秒");
        }
        if (!tasks.containsKey(dataSourceId) && tasks.size() >= MAX_ACTIVE_TASKS) {
            throw new IllegalStateException("活跃定时任务已达上限 " + MAX_ACTIVE_TASKS);
        }
        cancel(dataSourceId);
        Runnable r = () -> runOnce(dataSourceId);
        ScheduledFuture<?> f = scheduler.scheduleAtFixedRate(
                r, Instant.now().plusSeconds(intervalSec), Duration.ofSeconds(intervalSec));
        tasks.put(dataSourceId, f);
        failCounters.put(dataSourceId, 0);
        log.info("[ds-sched] registered id={} every {}s", dataSourceId, intervalSec);
    }

    public synchronized void cancel(String dataSourceId) {
        ScheduledFuture<?> f = tasks.remove(dataSourceId);
        if (f != null) f.cancel(false);
        failCounters.remove(dataSourceId);
    }

    public int activeCount() { return tasks.size(); }

    /** 一次执行 + 落 fetch_log + 失败计数。供注册任务和"手动立即执行"复用。 */
    public HttpExecuteResponse runOnce(String dataSourceId) {
        DataSourcePO po = repo.findById(dataSourceId);
        if (po == null) {
            cancel(dataSourceId);
            return null;
        }
        Map<String, Object> cfg = repo.readConfig(po);
        HttpExecuteResponse r = http.execute(cfg);
        logRepo.append(dataSourceId, r.getStatusCode(), r.isSuccess(),
                r.getBody(), r.getErrorMsg(), r.getDurationMs());
        if (r.isSuccess()) {
            failCounters.put(dataSourceId, 0);
            repo.markStatus(dataSourceId, "connected", null);
        } else {
            int n = failCounters.getOrDefault(dataSourceId, 0) + 1;
            failCounters.put(dataSourceId, n);
            repo.markStatus(dataSourceId, "error", r.getErrorMsg());
            if (n >= MAX_CONSECUTIVE_FAILS) {
                log.warn("[ds-sched] auto-disable id={} after {} fails", dataSourceId, n);
                // 落配置 enabled=false
                if (cfg.get("schedule") instanceof Map<?, ?> sched) {
                    var newCfg = new LinkedHashMap<String, Object>(cfg);
                    var newSched = new LinkedHashMap<String, Object>();
                    sched.forEach((k, v) -> newSched.put(String.valueOf(k), v));
                    newSched.put("enabled", false);
                    newCfg.put("schedule", newSched);
                    repo.updateConfig(po.getId(), null, newCfg);
                }
                cancel(dataSourceId);
            }
        }
        return r;
    }
}
