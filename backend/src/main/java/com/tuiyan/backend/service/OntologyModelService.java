package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.config.AppPaths;
import com.tuiyan.backend.model.OntologyModel;
import com.tuiyan.backend.util.JsonAtomic;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class OntologyModelService {

    private static final Logger log = LoggerFactory.getLogger(OntologyModelService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AppPaths appPaths;

    public OntologyModelService(AppPaths appPaths) {
        this.appPaths = appPaths;
    }

    @PostConstruct
    public void init() throws IOException {
        File d = appPaths.ontologyModelsDir();
        if (!d.exists()) d.mkdirs();
        File[] existing = d.listFiles((f, n) -> n.endsWith(".json"));
        if (Objects.requireNonNullElse(existing, new File[0]).length == 0) {
            seedDefaults();
        }
    }

    public List<OntologyModel> list() throws IOException {
        File d = appPaths.ontologyModelsDir();
        File[] files = d.listFiles((f, n) -> n.endsWith(".json"));
        List<OntologyModel> out = new ArrayList<>();
        if (files == null) return out;
        for (File f : files) {
            try {
                out.add(objectMapper.readValue(f, OntologyModel.class));
            } catch (IOException ioe) {
                log.warn("skip malformed ontology-model file {}: {}", f, ioe.toString());
            }
        }
        out.sort(Comparator.comparingLong(OntologyModel::getUpdatedAt).reversed());
        return out;
    }

    public OntologyModel get(String id) throws IOException {
        File f = fileFor(id);
        if (!f.exists()) return null;
        return objectMapper.readValue(f, OntologyModel.class);
    }

    public OntologyModel save(OntologyModel m) throws IOException {
        if (m.getId() == null || m.getId().isBlank()) {
            m.setId("om_" + System.currentTimeMillis());
        }
        long now = System.currentTimeMillis();
        if (m.getCreatedAt() == 0L) m.setCreatedAt(now);
        m.setUpdatedAt(now);
        m.setUpdated("刚刚");
        JsonAtomic.write(objectMapper, fileFor(m.getId()), m);
        return m;
    }

    public boolean delete(String id) {
        File f = fileFor(id);
        return f.exists() && f.delete();
    }

    private File fileFor(String id) {
        String safe = id.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        return new File(appPaths.ontologyModelsDir(), safe + ".json");
    }

    private void seedDefaults() throws IOException {
        OntologyModel m1 = buildSeed("1", "供应链本体模型", "包含供应链核心实体与关系的推演模型",
                supplyChainNodes(), supplyChainEdges());
        OntologyModel m2 = buildSeed("2", "财务追踪模型", "用于企业财务审批及资金流向追踪",
                new ArrayList<>(), new ArrayList<>());
        OntologyModel m3 = buildSeed("3", "组织架构解析", "部门架构与人员编制分析本体",
                new ArrayList<>(), new ArrayList<>());
        save(m1); save(m2); save(m3);
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
