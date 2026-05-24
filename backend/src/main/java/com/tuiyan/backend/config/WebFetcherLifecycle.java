package com.tuiyan.backend.config;

import com.tuiyan.backend.support.WebPageFetcher;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

/**
 * 应用生命周期钩子。容器关闭时清理 WebPageFetcher 持有的 Playwright + Chromium 子进程，
 * 防止开发期反复重启时残留僵尸进程。
 */
@Component
public class WebFetcherLifecycle {

    @PreDestroy
    public void onShutdown() {
        WebPageFetcher.shutdown();
    }
}
