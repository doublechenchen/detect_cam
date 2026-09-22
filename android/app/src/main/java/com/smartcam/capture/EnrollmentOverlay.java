package com.smartcam.capture;
import android.content.Context;
import android.graphics.*;
import android.view.View;
public final class EnrollmentOverlay extends View {
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);private final RectF content=new RectF();
    private boolean enabled=true;private float scale=.6f;
    public EnrollmentOverlay(Context c){super(c);setClickable(false);setContentDescription("中央商品录入框");}
    public void bounds(RectF r){content.set(r);invalidate();}
    public void mode(boolean value){enabled=value;invalidate();}
    public void scale(float value){scale=value;invalidate();}
    @Override protected void onDraw(Canvas c){if(!enabled||content.isEmpty())return;
        float h=content.height()*scale,w=Math.min(content.width()*.8f,h*.9f);
        RectF r=new RectF(content.centerX()-w/2,content.centerY()-h/2,content.centerX()+w/2,content.centerY()+h/2);
        p.setColor(Color.YELLOW);p.setStrokeWidth(3*getResources().getDisplayMetrics().density);p.setStyle(Paint.Style.STROKE);c.drawRoundRect(r,12,12,p);
        p.setStyle(Paint.Style.FILL);p.setTextSize(16*getResources().getDisplayMetrics().density);p.setShadowLayer(3,0,0,Color.BLACK);
        c.drawText("单件商品完整放入框内",r.left,Math.max(content.top+24,r.top-8),p);p.clearShadowLayer();
    }
}
