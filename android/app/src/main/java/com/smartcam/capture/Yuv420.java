package com.smartcam.capture;

import java.nio.ByteBuffer;

/** Copies a plane without assuming contiguous rows or contiguous chroma samples. */
public final class Yuv420 {
    private Yuv420() {}

    public static int copyPlane(ByteBuffer source, int rowStride, int pixelStride,
                                int left, int top, int width, int height,
                                byte[] destination, int offset) {
        if (width <= 0 || height <= 0 || left < 0 || top < 0
                || rowStride <= 0 || pixelStride <= 0 || offset < 0) {
            throw new IllegalArgumentException("Invalid plane dimensions/strides");
        }
        long start = (long) source.position() + (long) top * rowStride + (long) left * pixelStride;
        long last = start + (long) (height - 1) * rowStride + (long) (width - 1) * pixelStride;
        if (last >= source.limit() || (long) offset + (long) width * height > destination.length) {
            throw new IllegalArgumentException("Plane or destination too small");
        }
        for (int y = 0; y < height; y++) {
            int row = (int) (start + (long) y * rowStride);
            for (int x = 0; x < width; x++) {
                destination[offset++] = source.get(row + x * pixelStride);
            }
        }
        return offset;
    }
}
