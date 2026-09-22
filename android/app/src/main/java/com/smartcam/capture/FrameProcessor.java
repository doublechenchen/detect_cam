package com.smartcam.capture;

import android.graphics.Rect;
import android.media.Image;

/** Runs synchronously on the camera worker. Never retain Image or block on heavy inference here. */
public final class FrameProcessor {
    public interface Listener {
        // Buffer is tightly packed I420 (Y, U, V), valid ONLY during this call and reused next frame.
        void onFrame(byte[] i420, int width, int height, long timestampNs);
    }

    private byte[] buffer = new byte[0];
    private final Listener listener;

    public FrameProcessor(Listener listener) { this.listener = listener; }

    public void process(Image image) {
        Rect crop = image.getCropRect();
        int width = crop.width(), height = crop.height();
        if ((crop.left | crop.top | width | height) % 2 != 0) {
            throw new IllegalArgumentException("I420 requires even crop origin and size");
        }
        int length = width * height * 3 / 2;
        if (buffer.length != length) buffer = new byte[length];
        Image.Plane[] planes = image.getPlanes();
        int offset = 0;
        for (int p = 0; p < 3; p++) {
            int scale = p == 0 ? 1 : 2;
            offset = Yuv420.copyPlane(planes[p].getBuffer(), planes[p].getRowStride(),
                    planes[p].getPixelStride(), crop.left / scale, crop.top / scale,
                    width / scale, height / scale, buffer, offset);
        }
        listener.onFrame(buffer, width, height, image.getTimestamp());
    }
}
