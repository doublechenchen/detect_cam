package com.smartcam.capture;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Draws the latest detector result in the same normalized image coordinate system as the ROI. */
public final class DetectionOverlay extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF content = new RectF();
    private List<Detection> detections = new ArrayList<>();

    public DetectionOverlay(Context context) {
        super(context);
        setWillNotDraw(false);
        setClickable(false);
        setContentDescription("商品检测结果");
    }

    public void setContentBounds(RectF bounds) {
        content.set(bounds);
        invalidate();
    }

    public void setDetections(List<Detection> value) {
        detections = value == null ? new ArrayList<>() : new ArrayList<>(value);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (content.isEmpty()) return;
        float density = getResources().getDisplayMetrics().density;
        canvas.save();
        canvas.clipRect(content);
        paint.setColor(Color.rgb(0, 255, 80));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3 * density);
        paint.setTextSize(16 * density);
        for (Detection detection : detections) {
            RectF box = new RectF(
                    content.left + detection.left * content.width(),
                    content.top + detection.top * content.height(),
                    content.left + detection.right * content.width(),
                    content.top + detection.bottom * content.height());
            canvas.drawRect(box, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setShadowLayer(3 * density, 0, 0, Color.BLACK);
            String title = String.format(Locale.US, "%s %.2f",
                    detection.label == null ? "商品" : detection.label, detection.confidence);
            canvas.drawText(title,
                    box.left + 4 * density, Math.max(content.top + 18 * density, box.top - 4 * density), paint);
            paint.clearShadowLayer();
            paint.setStyle(Paint.Style.STROKE);
        }
        canvas.restore();
    }
}
