package com.tuiyan.backend.service;

import com.tuiyan.backend.config.ResourceNotFoundException;
import com.tuiyan.backend.model.OntologyModel;
import com.tuiyan.backend.repository.OntologyModelRepository;
import com.tuiyan.backend.repository.OntologyVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本体图谱模型服务：CRUD + 版本快照管理 + 默认种子数据。
 * <p>核心特性：
 * <ul>
 *   <li>每次保存前先把"当前模型快照"写入 {@code ontology_model_version} 系列表，最多保留 100 份；</li>
 *   <li>首次进入空工作空间时（list 为空）自动播种 3 个示例图谱，让用户能立刻上手；</li>
 *   <li>支持按时间戳恢复到任意历史版本，恢复前的当前版本也会被备份成新快照。</li>
 * </ul>
 */
@Service
public class OntologyModelService {

    private static final Logger log = LoggerFactory.getLogger(OntologyModelService.class);

    private final OntologyModelRepository modelRepository;
    private final OntologyVersionRepository versionRepository;

    public OntologyModelService(OntologyModelRepository modelRepository,
                                OntologyVersionRepository versionRepository) {
        this.modelRepository = modelRepository;
        this.versionRepository = versionRepository;
    }

    /** 全量列表，按 updatedAt 倒序。当前 ws 为空时懒种 3 个示例图谱。 */
    public List<OntologyModel> list() {
        List<OntologyModel> all = modelRepository.list();
        if (all.isEmpty()) {
            try {
                seedDefaults();
                all = modelRepository.list();
            } catch (Exception e) {
                log.warn("seed defaults failed: {}", e.toString(), e);
            }
        }
        return all;
    }

    public OntologyModel get(String id) {
        return modelRepository.get(id);
    }

    /**
     * 保存（新建或更新）。
     * <p>更新场景下会先把旧版本写入版本表作为快照，再覆盖写入新内容，保证"任何一次保存"都有回溯点。
     */
    public OntologyModel save(OntologyModel m) {
        if (m.getId() == null || m.getId().isBlank()) {
            m.setId("om_" + System.currentTimeMillis());
        }
        long now = System.currentTimeMillis();
        if (m.getCreatedAt() == 0L) m.setCreatedAt(now);
        m.setUpdatedAt(now);
        m.setUpdated("刚刚");
        // 若已存在则先快照旧版本
        OntologyModel existing = modelRepository.get(m.getId());
        if (existing != null) {
            try {
                versionRepository.snapshot(existing);
            } catch (Exception e) {
                log.warn("snapshot failed for model {}: {}", m.getId(), e.toString());
            }
        }
        modelRepository.save(m);
        return m;
    }

    /** 按指定 id 更新；id 强制覆写，保证路径与 body 里 id 一致。 */
    public OntologyModel update(String id, OntologyModel m) {
        m.setId(id);
        return save(m);
    }

    public boolean delete(String id) {
        return modelRepository.delete(id);
    }

    /** 列出版本快照（不含内容，只返回时间戳 + 节点 / 边数概要 + 文件大小）。 */
    public List<Map<String, Object>> listVersions(String modelId) {
        return versionRepository.listVersions(modelId);
    }

    /**
     * 恢复指定时间戳的版本快照。
     * <p>恢复过程调用 {@link #save}，所以当前版本会被自动备份为新的快照——恢复操作本身也是可撤销的。
     */
    public OntologyModel restoreVersion(String modelId, long timestamp) {
        OntologyModel snapshot = versionRepository.loadByTimestamp(modelId, timestamp);
        if (snapshot == null) {
            throw new ResourceNotFoundException("Version not found: " + timestamp);
        }
        snapshot.setId(modelId);
        return save(snapshot);
    }

    /** 当前工作空间下播种 3 个示例图谱，让用户能立刻进行推演体验。 */
    private void seedDefaults() {
        long base = System.currentTimeMillis();
        save(buildSeed("om_seed_supply_" + base, "供应链本体模型", "包含供应链核心实体与关系的推演模型",
                supplyChainNodes(), supplyChainEdges()));
        save(buildSeed("om_seed_finance_" + base, "财务追踪模型", "用于企业财务审批及资金流向追踪",
                new ArrayList<>(), new ArrayList<>()));
        save(buildSeed("om_seed_org_" + base, "组织架构解析", "部门架构与人员编制分析本体",
                new ArrayList<>(), new ArrayList<>()));
    }

    private OntologyModel buildSeed(String id, String title, String desc,
                                    List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        OntologyModel m = new OntologyModel();
        m.setId(id);
        m.setTitle(title);
        m.setDesc(desc);
        long t = System.currentTimeMillis();
        m.setCreatedAt(t);
        m.setUpdatedAt(t);
        m.setUpdated("刚刚");
        OntologyModel.GraphData g = new OntologyModel.GraphData();
        g.setNodes(nodes);
        g.setEdges(edges);
        m.setGraphData(g);
        return m;
    }

    /** 供应链示例节点：10 个常见实体，已预排坐标可直接渲染。 */
    private List<Map<String, Object>> supplyChainNodes() {
        List<Map<String, Object>> out = new ArrayList<>();
        Object[][] seeds = {
            {"n1","供应商","entity",130,330},
            {"n2","原材料订单","process",360,190},
            {"n3","工厂","entity",360,400},
            {"n4","产品","entity",610,280},
            {"n5","配送中心","process",860,170},
            {"n6","客户","entity",1090,260},
            {"n7","订单","process",860,360},
            {"n8","仓库","entity",610,470},
            {"n9","ERP系统","external",130,490},
            {"n10","时序数据","data",1090,450}
        };
        for (Object[] s : seeds) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", s[0]); n.put("label", s[1]); n.put("type", s[2]);
            n.put("x", s[3]); n.put("y", s[4]);
            out.add(n);
        }
        return out;
    }

    /** 供应链示例边：覆盖采购、生产、配送、入库等核心因果关系。 */
    private List<Map<String, Object>> supplyChainEdges() {
        List<Map<String, Object>> out = new ArrayList<>();
        String[][] seeds = {
            {"e1","n1","n2","提供"},{"e2","n2","n3","输入"},
            {"e3","n3","n4","生产"},{"e4","n4","n5","发货"},
            {"e5","n5","n6","送达"},{"e6","n6","n7","下单"},
            {"e7","n7","n5","触发"},{"e8","n3","n8","入库"},
            {"e9","n8","n5","调拨"},{"e10","n9","n3","驱动"},
            {"e11","n7","n10","记录"}
        };
        for (String[] s : seeds) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", s[0]); e.put("from", s[1]); e.put("to", s[2]); e.put("label", s[3]);
            out.add(e);
        }
        return out;
    }
}
