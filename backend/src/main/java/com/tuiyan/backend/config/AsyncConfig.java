package com.tuiyan.backend.config;

import com.tuiyan.backend.support.WorkspaceContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 异步任务线程池配置。
 * <p>SSE 类接口的套路：controller 收到请求后立即返回 emitter，把耗时任务丢到这里的线程池跑，
 * 避免占用 Tomcat 工作线程。
 * <ul>
 *   <li>{@code appTaskExecutor}：抽取 / 建图 / 推演 / 解释等「快进快出」的 LLM 任务；</li>
 *   <li>{@code explorationExecutor}：浏览器探索（重内存、长耗时），与推演池隔离，避免互相饿死。</li>
 * </ul>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 应用级异步任务线程池。
     * <ul>
     *   <li>core=4, max=8：单机本地工具，任务大多受限于 LLM 网络耗时，4~8 并发足够；</li>
     *   <li>queue=50：防止突发请求超出 max 后被直接拒绝；</li>
     *   <li>WaitForTasksToCompleteOnShutdown=true：JVM 退出时让正在跑的任务自然结束（最长 20s）。</li>
     * </ul>
     * <p>返回类型声明为具体的 {@link ThreadPoolTaskExecutor}：DocumentExtractionService
     * 注入时需要这个具体类型；用 TaskExecutor 接口的地方也能向上兼容。
     */
    @Bean(name = "appTaskExecutor")
    public ThreadPoolTaskExecutor appTaskExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(4);
        exec.setMaxPoolSize(8);
        exec.setQueueCapacity(50);
        exec.setThreadNamePrefix("task-");
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(20);
        // 把提交线程（Tomcat 请求线程）的 WorkspaceContext 透传到工作线程：
        // 建图 / 抽取等任务在 worker 线程上落库时需要 WorkspaceContext.required()，
        // 而 ThreadLocal 不会自动随线程池传播。worker 线程复用，故任务结束必须还原。
        exec.setTaskDecorator(workspacePropagatingDecorator());
        exec.initialize();
        return exec;
    }

    /**
     * 探索任务专用线程池，与推演池隔离。
     * <ul>
     *   <li>浏览器探索是「长耗时 + 重内存」任务：每个会话起一个无头 Chromium（约几百 MB），
     *       和「快进快出」的 LLM 推演混在同一池里会互相饿死、并把内存顶高；</li>
     *   <li>core=1, max=2：单机本地工具，同时跑 1~2 个探索足够，硬性把并发 Chromium 压在 2 个以内；</li>
     *   <li>queue=8：少量排队，超出则拒绝（AbortPolicy），由 controller 的 SSE 错误分支兜底。</li>
     * </ul>
     */
    @Bean(name = "explorationExecutor")
    public ThreadPoolTaskExecutor explorationExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(1);
        exec.setMaxPoolSize(2);
        exec.setQueueCapacity(8);
        exec.setThreadNamePrefix("explore-");
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(20);
        exec.setTaskDecorator(workspacePropagatingDecorator());
        exec.initialize();
        return exec;
    }

    /** 捕获提交时的 workspaceId，在 worker 线程上 set，任务结束后还原，避免污染下一个复用任务。 */
    private static TaskDecorator workspacePropagatingDecorator() {
        return runnable -> {
            String captured = WorkspaceContext.get();
            return () -> {
                String previous = WorkspaceContext.get();
                if (captured != null) WorkspaceContext.set(captured);
                else WorkspaceContext.clear();
                try {
                    runnable.run();
                } finally {
                    if (previous != null) WorkspaceContext.set(previous);
                    else WorkspaceContext.clear();
                }
            };
        };
    }
}
