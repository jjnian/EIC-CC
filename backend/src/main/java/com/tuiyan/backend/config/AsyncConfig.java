package com.tuiyan.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
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
     */
    @Bean(name = "predictionExecutor")
    public TaskExecutor predictionExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(4);
        exec.setMaxPoolSize(8);
        exec.setQueueCapacity(50);
        exec.setThreadNamePrefix("predict-");
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(20);
        exec.initialize();
        return exec;
    }
}
