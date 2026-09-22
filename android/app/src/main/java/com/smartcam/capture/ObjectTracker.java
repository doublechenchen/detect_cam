package com.smartcam.capture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Single-camera motion inference, with bounded identity retention during partial occlusion. */
public final class ObjectTracker {
    public enum State { LEARNING, RESTING, MOVING, LIFTED, RETURNING, LOST }
    private static final long EXPIRE_MS = 2400;
    private static final long UNKNOWN_IDENTITY_MS = 1200;
    private static final long STABLE_MS = 800;
    private static final long LIFT_MS = 250;
    private static final float LIFT_DISTANCE = .32f;
    private static final float LIFT_UP = .18f;

    public static final class Observation {
        public final Detection box;
        public final float[] feature;
        public final FeatureStore.Match match;
        public final boolean freshIdentity;

        public Observation(Detection b, float[] f, FeatureStore.Match m) {
            this(b, f, m, true);
        }

        public Observation(Detection b, float[] f, FeatureStore.Match m, boolean fresh) {
            box = b;
            feature = FeatureStore.normalize(f);
            match = m;
            freshIdentity = fresh;
        }
    }

    public static final class Track {
        public int id, sku = -1, votes, candidate = -1;
        public Detection box;
        public float[] feature;
        public long seen, recognizedAt, stableSince = -1, candidateSince = -1, lastTime;
        public int candidateSamples;
        public float baseX, baseY, baseH, baseW, baseBottom, lastX, lastY, vx, vy;
        public float displacement, upward, bottomUp, score;
        public State state = State.LEARNING;
        public boolean visible, baseline, liftHistory, identityUncertain;
        public String reason = "等待稳定识别";

        public String stateText() {
            switch (state) {
                case RESTING: return "桌面静置";
                case MOVING: return "拿起候选";
                case LIFTED: return "拿起推断";
                case RETURNING: return "放回候选";
                case LOST: return "暂时丢失";
                default: return "建立基准";
            }
        }

        public String diagnostics() {
            return String.format(Locale.US, "#%d sku=%d %s %s 位移=%.2f 上移=%.2f 底边上移=%.2f",
                    id, sku + 1, stateText(), reason, displacement, upward, bottomUp);
        }
    }

    private final List<Track> tracks = new ArrayList<>();
    private int next = 1;
    private long previous = -1;
    private float framePeriodMs = 500;

    public void reset() {
        tracks.clear();
        previous = -1;
        framePeriodMs = 500;
    }

    public boolean confirmedAt(Detection b, int sku) {
        if (sku < 0) return false;
        for (Track t : tracks) {
            // Moving, occluded and not-yet-identified objects always get fresh embedding evidence.
            if (t.visible && t.sku == sku && t.state == State.RESTING
                    && !t.identityUncertain && overlap(t.box, b) > .90f) return true;
        }
        return false;
    }

    public static float overlap(Detection a, Detection b) {
        float intersection = Math.max(0, Math.min(a.right, b.right) - Math.max(a.left, b.left))
                * Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top));
        return intersection / Math.max(1e-6f, width(a) * height(a) + width(b) * height(b) - intersection);
    }

    static float x(Detection b) { return (b.left + b.right) / 2; }
    static float y(Detection b) { return (b.top + b.bottom) / 2; }
    static float width(Detection b) { return Math.max(.02f, b.right - b.left); }
    static float height(Detection b) { return Math.max(.02f, b.bottom - b.top); }

    private static float distance(float x, float y, float a, float b, float w, float h) {
        return (float) Math.hypot((x - a) / w, (y - b) / h);
    }

    private long continuityMs() {
        // 2 FPS is a normal observation cadence, not a dropout. Never bridge an unbounded gap.
        return (long) Math.max(700, Math.min(1500, framePeriodMs * 2.5f));
    }

    public List<Track> update(List<Observation> observations, long time) {
        if (time <= previous) return tracks;
        long interval = previous < 0 ? 0 : time - previous;
        long continuity = continuityMs(); // Use the prior cadence; a stall must not relax its own gate.
        if (interval > 0 && interval <= 1000) framePeriodMs = .8f * framePeriodMs + .2f * interval;
        previous = time;
        tracks.removeIf(t -> time - t.seen > EXPIRE_MS);
        for (Track t : tracks) t.visible = false;

        // Compute all associations before changing any track to keep decisions independent of order.
        float[][] costs = new float[tracks.size()][observations.size()];
        int[] choices = new int[tracks.size()];
        Arrays.fill(choices, -1);
        for (int row = 0; row < tracks.size(); row++) {
            float best = Float.POSITIVE_INFINITY, second = Float.POSITIVE_INFINITY;
            for (int col = 0; col < observations.size(); col++) {
                float value = cost(tracks.get(row), observations.get(col), time);
                costs[row][col] = value;
                if (value < best) { second = best; best = value; choices[row] = col; }
                else second = Math.min(second, value);
            }
            if (!Float.isFinite(best) || second - best <= .18f) choices[row] = -1;
        }
        boolean[] used = new boolean[observations.size()];
        for (int row = 0; row < tracks.size(); row++) {
            int col = choices[row];
            if (col < 0 || used[col]) continue;
            boolean ambiguous = false;
            for (int other = 0; other < tracks.size(); other++) {
                if (other != row && costs[other][col] < costs[row][col] + .15f) ambiguous = true;
            }
            if (!ambiguous) {
                accept(tracks.get(row), observations.get(col), time, continuity);
                used[col] = true;
            }
        }
        for (int i = 0; i < observations.size(); i++) {
            Observation o = observations.get(i);
            // Unknown detections may continue an existing identity, but never create a new one.
            if (used[i] || o.match.sku < 0 || tracks.size() >= 12) continue;
            boolean near = false;
            for (Track t : tracks) if (Float.isFinite(cost(t, o, time))) near = true;
            if (!near) {
                Track t = new Track();
                t.id = next++;
                t.box = o.box;
                t.seen = time;
                t.lastTime = time;
                t.lastX = x(o.box);
                t.lastY = y(o.box);
                t.stableSince = time;
                tracks.add(t);
                accept(t, o, time, continuity);
            }
        }
        for (Track t : tracks) {
            if (!t.visible) {
                clearCandidate(t);
                t.stableSince = -1;
                t.state = State.LOST;
                t.reason = "未找到可安全关联的商品，暂停拿放确认";
            }
        }
        return tracks;
    }

    public static String pickupHint(List<Track> tracks) {
        for (Track t : tracks) if (t.liftHistory)
            return t.visible ? "拿起推断，持续跟踪" : "暂时遮挡，保留拿起记录";
        for (Track t : tracks) if (t.state == State.MOVING)
            return "#" + t.id + " " + t.reason;
        for (Track t : tracks) if (t.state == State.LEARNING)
            return "#" + t.id + " " + t.reason;
        for (Track t : tracks) if (t.state == State.LOST)
            return "#" + t.id + " 跟踪暂时丢失，等待重新识别";
        for (Track t : tracks) if (t.state == State.RESTING && t.displacement > LIFT_DISTANCE)
            return "#" + t.id + " " + t.reason;
        return tracks.isEmpty() ? "尚未识别到已录入商品，请检查位置与遮挡" : "桌面基准已建立，等待商品上抬";
    }

    private float cost(Track t, Observation o, long time) {
        if (t.sku >= 0 && o.match.sku >= 0 && t.sku != o.match.sku) return Float.POSITIVE_INFINITY;
        float dt = Math.min(.8f, Math.max(0, (time - t.seen) / 1000f));
        float predicted = distance(x(o.box), y(o.box), x(t.box) + t.vx * dt,
                y(t.box) + t.vy * dt, width(t.box), height(t.box));
        float direct = distance(x(o.box), y(o.box), x(t.box), y(t.box), width(t.box), height(t.box));
        // A sudden stop should not be lost solely because constant-velocity prediction overshoots.
        float dist = Math.min(predicted, direct + .25f);
        float appearance = FeatureStore.similarity(t.feature, o.feature);
        float size = width(o.box) * height(o.box) / (width(t.box) * height(t.box));
        if (size < .35f || size > 2.8f || appearance < .55f) return Float.POSITIVE_INFINITY;
        float gate = Math.min(3.0f, 1.2f + dt * 3);
        if (o.match.sku < 0) {
            if (t.sku < 0 || time - t.recognizedAt > UNKNOWN_IDENTITY_MS || appearance < .72f
                    || direct > 1.3f || size < .55f || size > 1.8f) return Float.POSITIVE_INFINITY;
            gate = Math.min(gate, 1.5f);
        }
        float value = dist + (1 - appearance) * 1.2f;
        return value <= gate ? value : Float.POSITIVE_INFINITY;
    }

    private static void clearCandidate(Track t) {
        t.candidateSince = -1;
        t.candidateSamples = 0;
    }

    private static void evidence(Track t, State state, long time) {
        if (t.state != state || t.candidateSince < 0) {
            t.candidateSince = time;
            t.candidateSamples = 0;
        }
        t.candidateSamples++;
        t.state = state;
    }

    private void accept(Track t, Observation o, long time, long continuity) {
        long gap = time - t.seen;
        float nx = x(o.box), ny = y(o.box), dt = Math.max(.001f, (time - t.lastTime) / 1000f);
        float movement = distance(nx, ny, t.lastX, t.lastY, width(o.box), height(o.box));
        if (gap > continuity) {
            clearCandidate(t);
            t.stableSince = time;
            t.vx = t.vy = 0;
            if (t.sku < 0) t.votes = 0;
        } else {
            t.vx = .4f * t.vx + .6f * (nx - t.lastX) / dt;
            t.vy = .4f * t.vy + .6f * (ny - t.lastY) / dt;
        }
        t.box = o.box;
        t.visible = true;
        t.seen = time;
        t.lastTime = time;
        t.lastX = nx;
        t.lastY = ny;
        t.identityUncertain = o.match.sku < 0;
        if (o.match.sku >= 0 && o.freshIdentity) {
            t.recognizedAt = time;
            if (o.feature != null) t.feature = o.feature;
        }
        t.score = o.match.score;
        if (t.sku < 0 && o.freshIdentity) {
            if (o.match.sku >= 0 && o.match.sku == t.candidate) t.votes++;
            else { t.candidate = o.match.sku; t.votes = o.match.sku < 0 ? 0 : 1; }
            if (t.votes >= 3) t.sku = t.candidate;
        }
        if (movement > .12f || t.stableSince < 0) t.stableSince = time;
        if (!t.baseline) {
            t.state = State.LEARNING;
            t.reason = t.sku < 0 ? "等待三次独立身份确认" : "请放稳约一秒建立桌面基准";
            if (t.sku >= 0 && !t.identityUncertain && time - t.stableSince >= STABLE_MS) {
                t.baseX = nx; t.baseY = ny;
                t.baseW = width(o.box); t.baseH = height(o.box); t.baseBottom = o.box.bottom;
                t.baseline = true;
                t.state = State.RESTING;
                t.reason = "基准已建立，可以拿起";
            }
            return;
        }
        t.displacement = distance(nx, ny, t.baseX, t.baseY, t.baseW, t.baseH);
        t.upward = (t.baseY - ny) / t.baseH;
        t.bottomUp = (t.baseBottom - o.box.bottom) / t.baseH;
        boolean identityFresh = !t.identityUncertain && o.freshIdentity;
        if (t.liftHistory) {
            boolean support = Math.abs(ny - t.baseY) / t.baseH < .24f
                    && Math.abs(o.box.bottom - t.baseBottom) / t.baseH < .20f
                    && Math.abs(height(o.box) / t.baseH - 1) < .30f;
            if (support && movement < .12f) {
                evidence(t, State.RETURNING, time);
                t.reason = "等待回到桌面并稳定";
                if (identityFresh && t.candidateSamples >= 2 && time - t.candidateSince >= STABLE_MS) {
                    t.liftHistory = false;
                    t.state = State.RESTING;
                    t.baseX = nx; t.baseY = ny; t.baseBottom = o.box.bottom;
                    clearCandidate(t);
                    t.reason = "已放回";
                }
            } else {
                t.state = State.LIFTED;
                clearCandidate(t);
                t.reason = t.identityUncertain ? "包装部分遮挡，保留已确认身份" : "持续跟踪拿起商品";
            }
        } else if (t.displacement > LIFT_DISTANCE && t.upward > LIFT_UP && t.bottomUp > LIFT_UP) {
            evidence(t, State.MOVING, time);
            t.reason = t.identityUncertain ? "位置已变化，等待身份恢复后确认" : "等待连续观测确认";
            if (identityFresh && t.candidateSamples >= 2 && time - t.candidateSince >= LIFT_MS) {
                t.state = State.LIFTED;
                t.liftHistory = true;
                clearCandidate(t);
                t.reason = "中心及底边持续上移，单目拿起推断";
            }
        } else {
            t.state = State.RESTING;
            clearCandidate(t);
            t.reason = t.identityUncertain ? "身份暂不确定，保持短时跟踪"
                    : t.displacement > LIFT_DISTANCE ? "已移动，但上抬证据不足" : "基准已建立，等待足够上抬位移";
        }
    }
}
