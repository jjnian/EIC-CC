package com.tuiyan.backend.service;

import com.tuiyan.backend.exception.ResourceNotFoundException;
import com.tuiyan.backend.repository.OntologyModelRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 血缘图外科手术式局部编辑：把一批操作（增/删/改 单个节点或边）<b>只落到指定行</b>，
 * 不加载、不重存整图——面向「文件多时快速修复大图」（整图 save 是删全部重插 O(N)，几千节点很慢）。
 * <p>同时是「对话驱动改图」的落地终点：LLM 据用户请求 + 相关子图产出一份 patch，经此原子应用。
 * 含归属校验（防跨工作空间改图）。
 */
@Service
public class GraphPatchService {

    private final OntologyModelRepository modelRepo;
    private final com.tuiyan.backend.service.indexing.GraphNodeIndexService nodeIndex;

    public GraphPatchService(OntologyModelRepository modelRepo,
                             com.tuiyan.backend.service.indexing.GraphNodeIndexService nodeIndex) {
        this.modelRepo = modelRepo;
        this.nodeIndex = nodeIndex;
    }

    /** patch 应用结果：实际应用的操作数 + 应用后模型的节点/边计数。 */
    public record PatchResult(int applied, int skipped, long nodeCount, long edgeCount) {}

    /**
     * 原子应用一批局部操作。op 形状：
     * <ul>
     *   <li>{@code {op:"add_node"|"update_node", node:{id,label,type,...}}}（upsert）</li>
     *   <li>{@code {op:"delete_node", id:"..."}}（连带其 props 与关联边）</li>
     *   <li>{@code {op:"add_edge"|"update_edge", edge:{id,from,to,rel_type,...}}}（upsert）</li>
     *   <li>{@code {op:"delete_edge", id:"..."}}</li>
     * </ul>
     * 未知/畸形的单条操作跳过并计入 skipped，不整体失败（对话驱动时 LLM 输出可能有噪声）。
     */
    @Transactional
    public PatchResult apply(String modelId, List<Map<String, Object>> ops) {
        if (!modelRepo.existsInWorkspace(modelId)) {
            throw new ResourceNotFoundException("模型不存在或不属于当前工作空间：" + modelId);
        }
        if (ops == null || ops.isEmpty()) {
            return new PatchResult(0, 0, modelRepo.nodeCountOf(modelId), modelRepo.edgeCountOf(modelId));
        }
        int applied = 0, skipped = 0;
        for (Map<String, Object> op : ops) {
            try {
                String kind = str(op.get("op"));
                switch (kind) {
                    case "add_node", "update_node" -> {
                        Map<String, Object> node = asMap(op.get("node"));
                        modelRepo.patchUpsertNode(modelId, node);
                        nodeIndex.upsertNode(modelId, str(node.get("id")), str(node.get("label")));  // 同步向量索引
                        applied++;
                    }
                    case "delete_node" -> {
                        String nid = str(op.get("id"));
                        modelRepo.patchDeleteNode(modelId, nid);
                        nodeIndex.deleteNode(modelId, nid);
                        applied++;
                    }
                    case "add_edge", "update_edge" -> { modelRepo.patchUpsertEdge(modelId, asMap(op.get("edge"))); applied++; }
                    case "delete_edge" -> { modelRepo.patchDeleteEdge(modelId, str(op.get("id"))); applied++; }
                    default -> skipped++;
                }
            } catch (RuntimeException e) {
                skipped++;   // 单条畸形操作不拖垮整批
            }
        }
        modelRepo.touch(modelId);
        return new PatchResult(applied, skipped, modelRepo.nodeCountOf(modelId), modelRepo.edgeCountOf(modelId));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        if (v instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw new IllegalArgumentException("操作缺少 node/edge 对象");
    }

    private static String str(Object v) { return v == null ? "" : String.valueOf(v); }
}
