package com.tuiyan.backend.config;

import com.tuiyan.backend.service.indexing.DataSourceIndexService;
import com.tuiyan.backend.service.indexing.ExperienceIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动后自动补索引：把历史「未索引」(index_status≠indexed)的经验与数据源排队重建。
 * <p>解决「配置 embedding 晚于内容上传」的存量数据无法被检索的问题——重启后自动跟上，无需手动逐条触发。
 * 通过 {@code app.embedding.backfill-on-startup=false} 可关闭。
 * <p>实际索引提交到各服务的有界线程池后台排队消化（并发度 = {@code app.embedding.concurrency}），
 * 不阻塞应用就绪；embedding 未配置时静默跳过。
 */
@Component
public class IndexBackfillRunner {

    private static final Logger log = LoggerFactory.getLogger(IndexBackfillRunner.class);

    private final ExperienceIndexService expIndex;
    private final DataSourceIndexService dsIndex;
    private final EmbeddingProperties props;

    public IndexBackfillRunner(ExperienceIndexService expIndex,
                               DataSourceIndexService dsIndex,
                               EmbeddingProperties props) {
        this.expIndex = expIndex;
        this.dsIndex = dsIndex;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!props.isBackfillOnStartup()) {
            log.info("[Backfill] 启动补索引已关闭(app.embedding.backfill-on-startup=false)，跳过");
            return;
        }
        if (!expIndex.isConfigured()) {
            log.info("[Backfill] 未配置 embedding，跳过启动补索引");
            return;
        }
        // 单独线程：枚举待索引项可能涉及较大查询，避免拖慢应用就绪；实际索引本就在各服务的线程池后台跑
        Thread t = new Thread(() -> {
            try {
                int exp = expIndex.backfillUnindexed();
                int ds = dsIndex.backfillUnindexed();
                log.info("[Backfill] 启动补索引完成调度：经验 {} 条 + 数据源 {} 个", exp, ds);
            } catch (Exception e) {
                log.warn("[Backfill] 启动补索引调度异常: {}", e.getMessage());
            }
        }, "index-backfill");
        t.setDaemon(true);
        t.start();
    }
}
