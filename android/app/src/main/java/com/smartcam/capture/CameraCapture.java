package com.smartcam.capture;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/** All device/session/reader ownership stays on one worker thread. */
public final class CameraCapture {
    public interface Status { void update(String message); }
    private final CameraManager manager;
    private final HandlerThread thread = new HandlerThread("SmartCamCapture");
    private final Handler worker;
    private final Handler ui = new Handler(android.os.Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();
    private final Status status;
    private final FrameProcessor.Listener frameListener;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private Surface preview;

    public CameraCapture(Context context, Status status) {
        this(context, status, null);
    }

    public CameraCapture(Context context, Status status, FrameProcessor.Listener frameListener) {
        manager = context.getSystemService(CameraManager.class);
        this.status = status;
        this.frameListener = frameListener;
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    private void report(int token, String message) {
        ui.post(() -> { if (generation.get() == token) status.update(message); });
    }

    private void fail(int token, String message) {
        if (generation.get() != token) return;
        closeResources();
        report(token, message + "\n关闭其他相机应用后，点击“重新连接”；必要时选择较低分辨率。");
    }

    @SuppressLint("MissingPermission") // Activity checks runtime permission before calling start.
    public void start(String cameraId, Size size, Surface ownedPreview) {
        int token = generation.incrementAndGet();
        worker.post(() -> {
            if (token != generation.get()) { ownedPreview.release(); return; }
            closeResources();
            preview = ownedPreview;
            report(token, "正在打开相机 " + cameraId + " · " + size);
            try {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 3);
                final long[] window = {SystemClock.elapsedRealtimeNanos(), 0, 0};
                final long[] acquisition = {0, 0};
                final double[] captureFps = {0};
                FrameProcessor processor = new FrameProcessor((data, width, height, timestampNs) -> {
                    // Future synchronous JNI entry point: nativeProcessI420(data, width, height, timestampNs).
                    // Copy into a bounded, owned buffer if inference moves to another thread.
                    window[1]++;
                    window[2]++;
                    long now = SystemClock.elapsedRealtimeNanos();
                    if (now - window[0] >= 1_000_000_000L) {
                        double fps = window[1] * 1e9 / (now - window[0]);
                        report(token, String.format(Locale.US,
                                "相机 %s · %dx%d · I420\n采集回调 %.1f FPS · 实际处理 %.1f FPS · 累计 %d 帧\n图像时间戳 %d ns · 仅内存处理",
                                cameraId, width, height, captureFps[0], fps, window[2], timestampNs));
                        window[0] = now;
                        window[1] = 0;
                    }
                    if (frameListener != null) frameListener.onFrame(data, width, height, timestampNs);
                });
                reader.setOnImageAvailableListener(source -> {
                    if (token != generation.get()) return;
                    try (Image image = source.acquireLatestImage()) {
                        if (image != null) processor.process(image);
                    } catch (RuntimeException error) {
                        fail(token, "读取图像失败：" + error.getMessage());
                    }
                }, worker);
                manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                    @Override public void onOpened(CameraDevice camera) {
                        if (token != generation.get()) { camera.close(); return; }
                        device = camera;
                        try {
                            final Surface yuvSurface = reader.getSurface();
                            final Surface previewSurface = preview;
                            camera.createCaptureSession(Arrays.asList(previewSurface, yuvSurface),
                                    new CameraCaptureSession.StateCallback() {
                                @Override public void onConfigured(CameraCaptureSession configured) {
                                    if (token != generation.get() || device != camera || reader == null) {
                                        configured.close(); return;
                                    }
                                    session = configured;
                                    try {
                                        CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                        request.addTarget(previewSurface);
                                        request.addTarget(yuvSurface);
                                        request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                                        Range<Integer>[] ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                                        Range<Integer> selected = chooseFps(ranges);
                                        if (selected != null) request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selected);
                                        configured.setRepeatingRequest(request.build(), new CameraCaptureSession.CaptureCallback() {
                                            @Override public void onCaptureCompleted(CameraCaptureSession session,
                                                    CaptureRequest request, TotalCaptureResult result) {
                                                if (token != generation.get()) return;
                                                long now = SystemClock.elapsedRealtimeNanos();
                                                if (acquisition[0] == 0) { acquisition[0] = now; return; }
                                                acquisition[1]++;
                                                if (now - acquisition[0] >= 1_000_000_000L) {
                                                    captureFps[0] = acquisition[1] * 1e9 / (now - acquisition[0]);
                                                    acquisition[0] = now;
                                                    acquisition[1] = 0;
                                                }
                                            }
                                        }, worker);
                                        report(token, "已启动 " + size + "，等待图像帧…");
                                    } catch (CameraAccessException | RuntimeException error) {
                                        fail(token, "启动采集失败：" + error.getMessage());
                                    }
                                }
                                @Override public void onConfigureFailed(CameraCaptureSession configured) {
                                    configured.close();
                                    fail(token, "预览 + YUV 双输出配置失败。请尝试 640×480 或其他分辨率。");
                                }
                            }, worker);
                        } catch (CameraAccessException | RuntimeException error) {
                            fail(token, "创建会话失败：" + error.getMessage());
                        }
                    }
                    @Override public void onDisconnected(CameraDevice camera) {
                        camera.close(); fail(token, "相机已断开，请检查 USB 连接。");
                    }
                    @Override public void onError(CameraDevice camera, int error) {
                        camera.close(); fail(token, "相机错误码：" + error);
                    }
                }, worker);
            } catch (CameraAccessException | RuntimeException error) {
                fail(token, "打开失败：" + error.getMessage());
            }
        });
    }

    private static Range<Integer> chooseFps(Range<Integer>[] ranges) {
        if (ranges == null) return null;
        Range<Integer> best = null;
        int score = Integer.MAX_VALUE;
        for (Range<Integer> range : ranges) {
            int next = Math.abs(range.getUpper() - 30) * 100 + Math.abs(range.getLower() - 30);
            if (next < score) { best = range; score = next; }
        }
        return best;
    }

    public void stop() {
        generation.incrementAndGet();
        worker.post(this::closeResources);
    }

    public void destroy() {
        generation.incrementAndGet();
        worker.post(() -> { closeResources(); thread.quitSafely(); });
    }

    private void closeResources() {
        if (session != null) { session.close(); session = null; }
        if (device != null) { device.close(); device = null; }
        if (reader != null) { reader.close(); reader = null; }
        if (preview != null) { preview.release(); preview = null; }
    }
}
