package com.tuiyan.backend.service;

import com.tuiyan.backend.model.PredictRequest;
import com.tuiyan.backend.model.PredictionDag;
import com.tuiyan.backend.model.Scenario;
import com.tuiyan.backend.support.PredictionMath;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Scenario 组装器：承载原 {@code PredictionOrchestrator.buildScenario} 的纯组装逻辑。
 * <p>把一次推演产出的节点 / 边 / chain 打包为 {@link Scenario}（分支）用于持久化，
 * 不涉及 SSE、不做落盘（落盘仍由编排层调用 ScenarioService 完成）。
 */
@Component
public class ScenarioAssembler {

    /**
     * 把推演结果打包为 {@link Scenario}（分支）用于持久化。
     * 若 req.name 为空则自动生成"溯因·种子标签 · MM-dd HH:mm"格式的默认名称。
     */
    public Scenario buildScenario(PredictRequest req, String intent, boolean backward, long now,
                                  String scenarioId, int chainSize,
                                  List<Map<String, Object>> predictedNodes,
                                  List<Map<String, Object>> predictedEdges,
                                  List<Map<String, Object>> chainList) {
        Scenario s = new Scenario();
        s.setId(scenarioId);
        s.setModelId(req.getModelId());
        s.setParentBranchId(req.getParentBranchId());
        s.setCreatedAt(now);
        s.setIntent(intent);
        s.setSeeds(req.getSeeds());
        s.setSteps(req.getSteps() == null ? chainSize : req.getSteps());
        s.setPrompt(req.getPrompt());

        String name = req.getName();
        if (name == null || name.isBlank()) {
            String seedLabel = PredictionMath.lookupSeedLabel(req);
            String prefix = backward ? "溯因·" : "";
            name = prefix + (seedLabel != null ? seedLabel : "推演") + " · "
                    + new SimpleDateFormat("MM-dd HH:mm").format(new Date());
        }
        s.setName(name);

        // 把推演链同时挂在 dag 和 chain 上：dag 给画布渲染，chain 给时间轴 / 详情用
        PredictionDag dag = new PredictionDag();
        dag.setIntent(intent);
        dag.setNodes(predictedNodes);
        dag.setEdges(predictedEdges);
        dag.setChain(chainList);
        dag.setConstraints(req.getConstraints());
        s.setDag(dag);
        s.setChain(chainList);
        return s;
    }
}
