package com.tuiyan.backend.config;

import com.tuiyan.backend.support.WorkspaceContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 异步任务线程池配置。
 * <p>当前只有推演任务使用：controller 收到 SSE 请求后立即返回 emitter，
 * 把耗时的 LLM 调用 + 流式推送丢到本线程池，避免占用 Tomcat 工作线程。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 推演任务专用线程池。
     * <ul>
     *   <li>core=4, max=8：单机本地工具，推演大多受限于 LLM 网络耗时，4~8 并发足够；</li>
     *   <li>queue=50：防止突发请求超出 max 后被直接拒绝；</li>
     *   <li>WaitForTasksToCompleteOnShutdown=true：JVM 退出时让正在跑的推演自然结束（最长 20s）。</li>
     * </ul>
     * <p>返回类型声明为具体的 {@link ThreadPoolTaskExecutor}：DocumentExtractionService
     * 注入时需要这个具体类型；ScenarioController 等用 TaskExecutor 接口的地方也能向上兼容。
     */
    @Bean(name = "predictionExecutor")
    public ThreadPoolTaskExecutor predictionExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(4);
        exec.setMaxPoolSize(8);
        exec.setQueueCapacity(50);
        exec.setThreadNamePrefix("predict-");
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(20);
        // 把提交线程（Tomcat 请求线程）的 WorkspaceContext 透传到工作线程：
        // 推演 / 解释等任务在 worker 线程上落盘 scenario 时需要 WorkspaceContext.required()，
        // 而 ThreadLocal 不会自动随线程池传播。worker 线程复用，故任务结束必须还原。
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
