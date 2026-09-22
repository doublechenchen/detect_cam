package com.smartcam.capture;

import java.util.ArrayList;
import java.util.List;

/** Rockchip 960 single-class / 640 five-class YOLOv8: concatenated, dequantized NCHW outputs. */
public final class YoloV8Decoder {
    public static final int OUTPUT_FLOATS = 66 * 18900;
    public static final int CONTAINER_FLOATS = 67 * 8400;
    public static final int FIVE_CLASS_FLOATS = 70 * 8400;
    private static final String[] NAMES = {"唇釉", "牛奶", "面霜", "水瓶", "罐装薯片"};
    private YoloV8Decoder() {}

    public static List<Detection> decode(float[] raw, int width, int height) {
        List<Detection> candidates = new ArrayList<>();
        if (raw == null || (raw.length != OUTPUT_FLOATS && raw.length != FIVE_CLASS_FLOATS && raw.length != CONTAINER_FLOATS) || width <= 0 || height <= 0) return candidates;
        int size = raw.length == OUTPUT_FLOATS ? 960 : 640;
        int classes = raw.length == CONTAINER_FLOATS ? 2 : (size == 640 ? 5 : 1);
        float gain = Math.min((float) size / width, (float) size / height);
        int leftPad = (size - Math.round(width * gain)) / 2;
        int topPad = (size - Math.round(height * gain)) / 2;
        int offset = 0;
        for (int stride : new int[]{8, 16, 32}) {
            int side = size / stride, cells = side * side;
            int clsOffset = offset + 64 * cells;
            for (int i = 0; i < cells; i++) {
                float score = 0f;
                int cls = -1;
                for (int c = 0; c < classes; c++) {
                    float value = raw[clsOffset + c * cells + i];
                    if (Float.isFinite(value) && value > score && value <= 1f) { score = value; cls = c; }
                }
                if (score < 0.25f || cls < 0) continue;
                float[] distances = new float[4];
                for (int axis = 0; axis < 4; axis++) {
                    float maximum = -Float.MAX_VALUE;
                    for (int bin = 0; bin < 16; bin++) maximum = Math.max(maximum, raw[offset + (axis * 16 + bin) * cells + i]);
                    double sum = 0, weighted = 0;
                    for (int bin = 0; bin < 16; bin++) {
                        double value = Math.exp(raw[offset + (axis * 16 + bin) * cells + i] - maximum);
                        sum += value; weighted += bin * value;
                    }
                    distances[axis] = (float) (weighted / sum);
                }
                float cx = i % side + 0.5f, cy = i / side + 0.5f;
                float x1 = (cx - distances[0]) * stride;
                float y1 = (cy - distances[1]) * stride;
                float x2 = (cx + distances[2]) * stride;
                float y2 = (cy + distances[3]) * stride;
                if (Float.isFinite(x1) && Float.isFinite(y1) && Float.isFinite(x2) && Float.isFinite(y2)
                        && x2 > x1 && y2 > y1) {
                    candidates.add(new Detection(cls, score, x1, y1, x2, y2, classes == 5 ? NAMES[cls] : "商品"));
                }
            }
            // score_sum is intentionally unused: class probabilities are the validated gate.
            offset += (65 + classes) * cells;
        }
        candidates.sort((a, b) -> Float.compare(b.confidence, a.confidence));
        List<Detection> result = new ArrayList<>();
        for (Detection candidate : candidates) {
            boolean suppressed = false;
            for (Detection kept : result) {
                if (candidate.classId == kept.classId && iou(candidate, kept) > (classes == 5 ? 0.45f : 0.7f)) { suppressed = true; break; }
            }
            if (!suppressed) result.add(candidate);
            if (result.size() >= 1000) break;
        }
        List<Detection> restored = new ArrayList<>();
        for (Detection d : result) {
            float x1 = clamp((d.left-leftPad)/(gain*width)), y1 = clamp((d.top-topPad)/(gain*height));
            float x2 = clamp((d.right-leftPad)/(gain*width)), y2 = clamp((d.bottom-topPad)/(gain*height));
            if (x2 > x1 && y2 > y1) restored.add(new Detection(d.classId,d.confidence,x1,y1,x2,y2,d.label));
        }
        return restored;
    }

    public static float maxScore(float[] raw) {
        if (raw == null || (raw.length != OUTPUT_FLOATS && raw.length != FIVE_CLASS_FLOATS && raw.length != CONTAINER_FLOATS)) return -1f;
        int classes = raw.length == CONTAINER_FLOATS ? 2 : (raw.length == FIVE_CLASS_FLOATS ? 5 : 1);
        int firstSide = classes == 1 ? 120 : 80;
        float max = 0f;
        int offset = 0;
        for (int side : new int[]{firstSide, firstSide/2, firstSide/4}) {
            int cells = side * side;
            for (int i = offset + 64 * cells; i < offset + (64 + classes) * cells; i++) {
                if (Float.isFinite(raw[i])) max = Math.max(max, raw[i]);
            }
            offset += (65 + classes) * cells;
        }
        return max;
    }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }
    private static float iou(Detection a, Detection b) {
        float intersection = Math.max(0f, Math.min(a.right,b.right)-Math.max(a.left,b.left))
                * Math.max(0f, Math.min(a.bottom,b.bottom)-Math.max(a.top,b.top));
        return intersection / Math.max(1e-9f, (a.right-a.left)*(a.bottom-a.top)
                + (b.right-b.left)*(b.bottom-b.top)-intersection);
    }
}
