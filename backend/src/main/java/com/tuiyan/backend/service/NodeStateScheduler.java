package com.tuiyan.backend.service;

import com.tuiyan.backend.entity.NodeDataBindingPO;
import com.tuiyan.backend.repository.NodeDataBindingRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 态势层·状态刷新调度器：为启用了状态查询的供血绑定定时执行采集，把节点状态保持"活"的。
 * 与 {@link com.tuiyan.backend.service.connector.HttpScheduler} 同一模式：
 * 独立小线程池、每绑定最多一个任务、连续失败 {@value #MAX_CONSECUTIVE_FAILS} 次自动停用。
 * 采集本身只读（executeSql 白名单 + 强制 LIMIT），不会写用户业务库。
 */
@Service
public class NodeStateScheduler {

    private static final Logger log = LoggerFactory.getLogger(NodeStateScheduler.class);
    public static final int MIN_INTERVAL_SEC = 60;
    public static final int MAX_ACTIVE_TASKS = 50;
    public static final int MAX_CONSECUTIVE_FAILS = 5;

    private final ThreadPoolTaskScheduler scheduler;
    private final NodeDataBindingRepository bindingRepo;
    private final NodeStateService stateService;

    private final Map<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();
    private final Map<String, Integer> failCounters = new ConcurrentHashMap<>();

    public NodeStateScheduler(NodeDataBindingRepository bindingRepo, NodeStateService stateService) {
        this.bindingRepo = bindingRepo;
        this.stateService = stateService;
        this.scheduler = new ThreadPoolTaskScheduler();
        this.scheduler.setPoolSize(2);
        this.scheduler.setThreadNamePrefix("node-state-");
        this.scheduler.setDaemon(true);
        this.scheduler.initialize();
    }

    /** 启动时加载全部 status_enabled=true 的绑定注册任务。 */
    @PostConstruct
    public void bootstrap() {
        for (NodeDataBindingPO b : bindingRepo.listStatusEnabled()) {
            try {
                register(b.getId(), b.getStatusIntervalSec() == null ? MIN_INTERVAL_SEC : b.getStatusIntervalSec());
            } catch (Exception e) {
                log.warn("[node-state-sched] bootstrap register failed id={} err={}", b.getId(), e.toString());
            }
        }
        if (!tasks.isEmpty()) log.info("[node-state-sched] bootstrap 注册 {} 个状态刷新任务", tasks.size());
    }

    /** 注册或更新某绑定的定时刷新。已存在则先取消。 */
    public synchronized void register(String bindingId, int intervalSec) {
        if (intervalSec < MIN_INTERVAL_SEC) {
            throw new IllegalArgumentException("刷新间隔不得小于 " + MIN_INTERVAL_SEC + " 秒");
        }
        if (!tasks.containsKey(bindingId) && tasks.size() >= MAX_ACTIVE_TASKS) {
            throw new IllegalStateException("活跃状态刷新任务已达上限 " + MAX_ACTIVE_TASKS);
        }
        cancel(bindingId);
        ScheduledFuture<?> f = scheduler.scheduleAtFixedRate(
                () -> runOnce(bindingId), Instant.now().plusSeconds(2), Duration.ofSeconds(intervalSec));
        tasks.put(bindingId, f);
        failCounters.put(bindingId, 0);
        log.info("[node-state-sched] registered binding={} every {}s", bindingId, intervalSec);
    }

    public synchronized void cancel(String bindingId) {
        ScheduledFuture<?> f = tasks.remove(bindingId);
        if (f != null) f.cancel(false);
        failCounters.remove(bindingId);
    }

    public int activeCount() { return tasks.size(); }

    /** 一次采集 + 失败计数（error 级也算失败）。连续失败自动停用并落库 status_enabled=false。 */
    private void runOnce(String bindingId) {
        Map<String, Object> state;
        try {
            state = stateService.refresh(bindingId);
        } catch (IllegalArgumentException e) {
            // 绑定被删 / 状态查询被清空：任务失去意义，直接注销
            log.info("[node-state-sched] binding={} 不再可刷新（{}），注销任务", bindingId, e.getMessage());
            cancel(bindingId);
            return;
        } catch (Exception e) {
            log.warn("[node-state-sched] refresh 异常 binding={} err={}", bindingId, e.toString());
            state = Map.of("level", "error");
        }
        if ("error".equals(state.get("level"))) {
            int n = failCounters.getOrDefault(bindingId, 0) + 1;
            failCounters.put(bindingId, n);
            if (n >= MAX_CONSECUTIVE_FAILS) {
                log.warn("[node-state-sched] auto-disable binding={} after {} fails", bindingId, n);
                bindingRepo.disableStatusAny(bindingId);
                cancel(bindingId);
            }
        } else {
            failCounters.put(bindingId, 0);
        }
    }
}
