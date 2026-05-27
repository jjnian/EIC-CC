package com.tuiyan.backend.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tuiyan.backend.entity.DataSourceFetchLogPO;
import com.tuiyan.backend.mapper.DataSourceFetchLogMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * HTTPS 数据源执行历史仓储：写入后清理超出 20 条的旧日志。
 */
@Repository
public class DataSourceFetchLogRepository {

    public static final int KEEP_LATEST = 20;
    private final DataSourceFetchLogMapper mapper;

    public DataSourceFetchLogRepository(DataSourceFetchLogMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public DataSourceFetchLogPO append(String dataSourceId, Integer statusCode, boolean success,
                                       String responseBody, String errorMsg, int durationMs) {
        DataSourceFetchLogPO po = new DataSourceFetchLogPO();
        po.setDataSourceId(dataSourceId);
        po.setFetchedAt(System.currentTimeMillis());
        po.setStatusCode(statusCode);
        po.setSuccess(success);
        po.setResponseBody(responseBody);
        po.setErrorMsg(errorMsg);
        po.setDurationMs(durationMs);
        mapper.insert(po);
        trim(dataSourceId);
        return po;
    }

    public List<DataSourceFetchLogPO> listLatest(String dataSourceId) {
        return mapper.selectList(new LambdaQueryWrapper<DataSourceFetchLogPO>()
                .eq(DataSourceFetchLogPO::getDataSourceId, dataSourceId)
                .orderByDesc(DataSourceFetchLogPO::getFetchedAt)
                .last("LIMIT " + KEEP_LATEST));
    }

    /** 仅保留最近 KEEP_LATEST 条，多余删除。 */
    private void trim(String dataSourceId) {
        List<DataSourceFetchLogPO> all = mapper.selectList(
                new LambdaQueryWrapper<DataSourceFetchLogPO>()
                        .eq(DataSourceFetchLogPO::getDataSourceId, dataSourceId)
                        .orderByDesc(DataSourceFetchLogPO::getFetchedAt));
        if (all.size() <= KEEP_LATEST) return;
        for (int i = KEEP_LATEST; i < all.size(); i++) {
            mapper.deleteById(all.get(i).getId());
        }
    }
}
