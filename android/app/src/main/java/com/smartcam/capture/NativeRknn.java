package com.smartcam.capture;

import java.io.File;

/**
 * JNI boundary for the RKNN Runtime. The native library is optional in the
 * camera-only APK, so a missing NDK/RKNN build never prevents preview.
 *
 * det.rknn: RGB uint8 letterbox, nine dequantized NCHW tensors concatenated
 * in model order for YoloV8Decoder. The legacy single-output detector keeps BGR.
 * The embedding model returns [1,576] and is reserved for SKU matching.
 */
public final class NativeRknn {
    private static final boolean LOADED;
    static {
        boolean loaded;
        try {
            System.loadLibrary("smartcam_rknn");
            loaded = true;
        } catch (UnsatisfiedLinkError error) {
            loaded = false;
        }
        LOADED = loaded;
    }

    public static boolean isLibraryLoaded() { return LOADED; }

    private NativeRknn() {}

    public static long open(String modelPath) {
        if (!LOADED || modelPath == null || !new File(modelPath).isFile()) return 0;
        return nativeOpen(modelPath);
    }

    public static float[] detect(long handle, byte[] i420, int width, int height) {
        if (!LOADED || handle == 0 || i420 == null) return null;
        return nativeDetect(handle, i420, width, height);
    }

    public static boolean isSkuDetector(long handle) {
        return LOADED && handle != 0 && nativeIsSkuDetector(handle);
    }

    public static float[] embed(long handle, byte[] i420, int width, int height,
                                float left, float top, float right, float bottom) {
        if (!LOADED || handle == 0 || i420 == null) return null;
        return nativeEmbed(handle, i420, width, height, left, top, right, bottom);
    }

    public static void close(long handle) {
        if (LOADED && handle != 0) nativeClose(handle);
    }

    private static native long nativeOpen(String modelPath);
    private static native boolean nativeIsSkuDetector(long handle);
    private static native float[] nativeDetect(long handle, byte[] i420, int width, int height);
    private static native float[] nativeEmbed(long handle, byte[] i420, int width, int height,
                                               float left, float top, float right, float bottom);
    private static native void nativeClose(long handle);
}
