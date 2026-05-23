package com.tuiyan.backend.support;

import com.tuiyan.backend.model.PredictRequest;

import java.util.List;
import java.util.Map;

/**
 * 推演纯数学 / 坐标计算助手。所有方法静态、无副作用，便于单测与复用。
 * <p>从 {@link com.tuiyan.backend.service.PredictionOrchestrator} 抽出来，保持业务编排逻辑的纯净。
 */
public final class PredictionMath {

    private PredictionMath() {}

    /** 把值钳制到 [0,1]；NaN 视为 0，便于把"未知概率"安全地参与运算。 */
    public static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.0;
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    /** 保留 3 位小数。前端展示概率类字段统一只显示 3 位，落盘也用同一精度减少噪声。 */
    public static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /**
     * 根据 req.seeds 在 req.nodes 中的坐标平均值，确定推演链的起始锚点。
     * <p>这样新生成的预测节点会出现在种子附近，而非画布左上角原点，符合用户视觉预期。
     * <p>seeds 为空 / 节点找不到时回退到 (800, 300)：经验值，画布中央偏左。
     */
    public static double[] computeOrigin(PredictRequest req) {
        if (req.getSeeds() == null || req.getSeeds().isEmpty() || req.getNodes() == null) {
            return new double[]{800, 300};
        }
        double sx = 0, sy = 0;
        int n = 0;
        for (String sid : req.getSeeds()) {
            for (Map<String, Object> node : req.getNodes()) {
                if (sid.equals(node.get("id"))) {
                    Object xo = node.get("x"), yo = node.get("y");
                    if (xo instanceof Number && yo instanceof Number) {
                        sx += ((Number) xo).doubleValue();
                        sy += ((Number) yo).doubleValue();
                        n++;
                    }
                    break;
                }
            }
        }
        if (n == 0) return new double[]{800, 300};
        return new double[]{sx / n, sy / n};
    }

    /**
     * 在 req.nodes 中查找第一个 seed 的 label，用于场景命名兜底
     * （Scenario.name 未指定时拼成 "种子标签 · MM-dd HH:mm"）。
     */
    public static String lookupSeedLabel(PredictRequest req) {
        if (req.getSeeds() == null || req.getSeeds().isEmpty() || req.getNodes() == null) return null;
        String first = req.getSeeds().get(0);
        for (Map<String, Object> n : req.getNodes()) {
            if (first.equals(n.get("id"))) {
                Object lbl = n.get("label");
                return lbl == null ? null : String.valueOf(lbl);
            }
        }
        return null;
    }
}
