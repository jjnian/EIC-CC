package com.tuiyan.backend.support;

import com.tuiyan.backend.model.PredictRequest;

import java.util.List;
import java.util.Map;

/**
 * 推演纯数学/坐标计算助手。所有方法静态、无副作用。
 */
public final class PredictionMath {

    private PredictionMath() {}

    public static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.0;
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    public static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /**
     * 根据 req.seeds 在 req.nodes 中的坐标平均值,确定推演链的起始锚点。
     * seeds 为空或找不到时,回退到 (800, 300)。
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
     * 在 req.nodes 中查找第一个 seed 的 label,用于场景命名兜底。
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
