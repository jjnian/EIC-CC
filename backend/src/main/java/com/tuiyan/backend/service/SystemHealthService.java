package com.tuiyan.backend.service;

import com.tuiyan.backend.model.dto.HealthResponse;
import org.springframework.stereotype.Service;

import java.io.File;
import java.lang.management.ManagementFactory;

/**
 * 系统健康探测：数据目录可用性 + JVM 内存 + 启动时长。
 * <p>从 SystemController 下沉过来，避免 controller 直接读文件系统。
 */
@Service
public class SystemHealthService {

    public HealthResponse getHealth() {
        String dataDir = System.getProperty("user.home") + File.separator + ".tuiyan";
        File dataDirFile = new File(dataDir);
        boolean dataDirOk = dataDirFile.exists() && dataDirFile.canWrite();
        long freeMemMb = Runtime.getRuntime().freeMemory() / (1024 * 1024);
        long totalMemMb = Runtime.getRuntime().totalMemory() / (1024 * 1024);

        return new HealthResponse(
                dataDirOk ? "UP" : "DEGRADED",
                dataDirOk,
                freeMemMb,
                totalMemMb,
                ManagementFactory.getRuntimeMXBean().getUptime()
        );
    }
}
