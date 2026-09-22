package com.smartcam.capture;

/** One model detection in normalized image coordinates. */
public final class Detection {
    public final int classId;
    public final float confidence;
    public final float left, top, right, bottom;
    public final String label;

    public Detection(int classId, float confidence, float left, float top, float right, float bottom) {
        this(classId, confidence, left, top, right, bottom, null);
    }

    public Detection(int classId, float confidence, float left, float top, float right, float bottom, String label) {
        this.classId = classId;
        this.confidence = confidence;
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.label = label;
    }
}
