package com.smartcam.capture;
import java.util.*;
public final class LegacyDecoder {
    /** Decode the actual det_rk3588 output: [1, 5, 8400] -> cx,cy,w,h,score. */
    public static List<Detection> decode(float[] raw, int sourceWidth, int sourceHeight) {
        List<Detection> result = new ArrayList<>();
        if (raw == null) return result;
        if (raw.length == 42000) {
            int anchors = raw.length / 5;
            List<Detection> candidates = new ArrayList<>();
            for (int i = 0; i < anchors; i++) {
                float score = raw[4 * anchors + i];
                if (!Float.isFinite(score) || score < 0.25f || score > 1) continue;
                float cx = raw[i];
                float cy = raw[anchors + i];
                float w = raw[2 * anchors + i];
                float h = raw[3 * anchors + i];
                // Convert boxes from the 640x640 letterbox back to the source image.
                float scale = Math.min(640f / sourceWidth, 640f / sourceHeight);
                float leftPad = (640f - sourceWidth * scale) / 2f;
                float topPad = (640f - sourceHeight * scale) / 2f;
                float x1 = (cx - w / 2f - leftPad) / (scale * sourceWidth);
                float y1 = (cy - h / 2f - topPad) / (scale * sourceHeight);
                float x2 = (cx + w / 2f - leftPad) / (scale * sourceWidth);
                float y2 = (cy + h / 2f - topPad) / (scale * sourceHeight);
                if (!Float.isFinite(x1+y1+x2+y2) || x2<=x1 || y2<=y1) continue;
                candidates.add(new Detection(0, score,
                        clamp(x1), clamp(y1), clamp(x2), clamp(y2)));
            }
            candidates.sort((a, b) -> Float.compare(b.confidence, a.confidence));
            for (Detection candidate : candidates) {
                boolean suppressed = false;
                for (Detection kept : result) {
                    if (iou(candidate, kept) > 0.45f) { suppressed = true; break; }
                }
                if (!suppressed) result.add(candidate);
                if (result.size() >= 32) break;
            }
            return result;
        }
        // Keep support for the bridge's simple six-float contract during bring-up.
        int rows = raw.length / 6;
        for (int i = 0; i < rows; i++) {
            int offset = i * 6;
            float score = raw[offset + 4];
            if (score >= 0.25f) result.add(new Detection((int) raw[offset + 5], score,
                    clamp(raw[offset]), clamp(raw[offset + 1]), clamp(raw[offset + 2]), clamp(raw[offset + 3])));
        }
        return result;
    }

    private static float clamp(float value) { return Math.max(0f, Math.min(1f, value)); }

    private static float maxScore(float[] raw) {
        if (raw == null || raw.length == 0) return -1f;
        if (raw.length == 42000) {
            int anchors = raw.length / 5;
            float max = -Float.MAX_VALUE;
            for (int i = 4 * anchors; i < raw.length; i++) max = Math.max(max, raw[i]);
            return max;
        }
        float max = -Float.MAX_VALUE;
        for (float value : raw) max = Math.max(max, value);
        return max;
    }

    private static float iou(Detection a, Detection b) {
        float left = Math.max(a.left, b.left), top = Math.max(a.top, b.top);
        float right = Math.min(a.right, b.right), bottom = Math.min(a.bottom, b.bottom);
        float intersection = Math.max(0, right - left) * Math.max(0, bottom - top);
        float areaA = Math.max(0, a.right - a.left) * Math.max(0, a.bottom - a.top);
        float areaB = Math.max(0, b.right - b.left) * Math.max(0, b.bottom - b.top);
        return intersection / Math.max(1e-6f, areaA + areaB - intersection);
    }

}
