package com.tuiyan.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuiyan.backend.config.AppPaths;
import com.tuiyan.backend.model.OntologyModel;
import com.tuiyan.backend.util.JsonAtomic;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.tuiyan.backend.config.ResourceNotFoundException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class OntologyModelService {

    private static final Logger log = LoggerFactory.getLogger(OntologyModelService.class);
    // 版本快照最大保留数量
    private static final int MAX_VERSIONS = 100;

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
        // 如果文件已存在（即是更新而非新建），保存旧版本快照
        File modelFile = fileFor(m.getId());
        if (modelFile.exists()) {
            saveVersionSnapshot(m.getId(), modelFile);
        }
        JsonAtomic.write(objectMapper, fileFor(m.getId()), m);
        return m;
    }

    public boolean delete(String id) {
        File f = fileFor(id);
        return f.exists() && f.delete();
    }

    /**
     * 获取指定模型的版本快照列表
     */
    public List<Map<String, Object>> listVersions(String modelId) {
        File versionsDir = new File(appPaths.ontologyModelsDir(),
                "versions" + File.separator + modelId.replaceAll("[^a-zA-Z0-9_\\-]", "_"));
        if (!versionsDir.exists()) return List.of();

        File[] files = versionsDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null || files.length == 0) return List.of();

        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());

        List<Map<String, Object>> versions = new ArrayList<>();
        for (File f : files) {
            String ts = f.getName().replace(".json", "");
            try {
                long timestamp = Long.parseLong(ts);
                // 读取文件获取节点/边数量摘要
                OntologyModel snapshot = objectMapper.readValue(f, OntologyModel.class);
                int nodeCount = snapshot.getGraphData() != null && snapshot.getGraphData().getNodes() != null
                        ? snapshot.getGraphData().getNodes().size() : 0;
                int edgeCount = snapshot.getGraphData() != null && snapshot.getGraphData().getEdges() != null
                        ? snapshot.getGraphData().getEdges().size() : 0;
                versions.add(Map.of(
                        "timestamp", timestamp,
                        "nodeCount", nodeCount,
                        "edgeCount", edgeCount,
                        "fileSize", f.length()
                ));
            } catch (Exception e) {
                // 跳过损坏的版本文件
            }
        }
        return versions;
    }

    /**
     * 恢复指定时间戳的版本快照
     */
    public OntologyModel restoreVersion(String modelId, long timestamp) throws IOException {
        File versionsDir = new File(appPaths.ontologyModelsDir(),
                "versions" + File.separator + modelId.replaceAll("[^a-zA-Z0-9_\\-]", "_"));
        File versionFile = new File(versionsDir, timestamp + ".json");
        if (!versionFile.exists()) {
            throw new ResourceNotFoundException("Version not found: " + timestamp);
        }
        OntologyModel snapshot = objectMapper.readValue(versionFile, OntologyModel.class);
        snapshot.setId(modelId);
        // save 会先保存当前版本为快照，再写入恢复的版本
        return save(snapshot);
    }

    /**
     * 保存版本快照到 versions 子目录
     */
    private void saveVersionSnapshot(String modelId, File currentFile) {
        try {
            File versionsDir = new File(appPaths.ontologyModelsDir(),
                    "versions" + File.separator + modelId.replaceAll("[^a-zA-Z0-9_\\-]", "_"));
            if (!versionsDir.exists()) versionsDir.mkdirs();

            // 用时间戳命名版本文件
            String versionName = System.currentTimeMillis() + ".json";
            File versionFile = new File(versionsDir, versionName);
            Files.copy(currentFile.toPath(), versionFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

            // 清理超过 MAX_VERSIONS 的旧版本
            File[] versions = versionsDir.listFiles((dir, name) -> name.endsWith(".json"));
            if (versions != null && versions.length > MAX_VERSIONS) {
                Arrays.sort(versions, Comparator.comparingLong(File::lastModified));
                for (int i = 0; i < versions.length - MAX_VERSIONS; i++) {
                    versions[i].delete();
                }
            }
        } catch (IOException e) {
            // 版本快照保存失败不应阻断主流程
            log.warn("Failed to save version snapshot for {}: {}", modelId, e.getMessage());
        }
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
