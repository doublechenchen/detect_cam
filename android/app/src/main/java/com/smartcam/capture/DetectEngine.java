package com.smartcam.capture;

import android.os.*;
import android.util.Log;
import java.io.*;
import java.util.*;

/** Single worker owns RKNN handles, enrollment store mutations and tracker. */
public final class DetectEngine {
    public interface Listener { void result(List<Detection> boxes,int selectedSku,String message,long time,String pickupHint); }
    private final HandlerThread thread=new HandlerThread("DetectCamInference");
    private final Handler worker,ui=new Handler(Looper.getMainLooper());
    private final Object lock=new Object(); private byte[] pending; private int fw,fh,fg; private long ft;
    private boolean queued; private volatile boolean active; private volatile int generation;
    private long detector,embed,companion,lastCapture,lastLog,rateStart; private int rateFrames; private float analysisFps; private boolean multiOutput;
    private final FeatureStore store; private final ObjectTracker tracker=new ObjectTracker();
    private static final class Cached {Detection box;float[] feature;FeatureStore.Match match;long time;}
    private final List<Cached> featureCache=new ArrayList<>();
    private final Map<Integer,String> loggedStates=new HashMap<>();
    private final Listener listener; private volatile int enrollment=0; private volatile float roiScale=.6f;
    private final String[] names={"sku1 乐事薯片","sku2 可口可乐","sku3 芙丝矿泉水"};
    public DetectEngine(FeatureStore store,Listener listener){this.store=store;this.listener=listener;thread.start();worker=new Handler(thread.getLooper());}
    public void load(String det,String emb) {worker.post(()->{
        if(detector!=0)NativeRknn.close(detector);if(embed!=0)NativeRknn.close(embed);if(companion!=0)NativeRknn.close(companion);companion=0;
        detector=NativeRknn.open(det);embed=NativeRknn.open(emb);multiOutput=NativeRknn.isSkuDetector(detector);
        featureCache.clear();
        if(det.contains("containers_640"))companion=NativeRknn.open(new File(new File(det).getParentFile(),det.contains("int8")?"chips_640_int8.rknn":"sku_detector_640_fp16.rknn").getPath());
        publish(Collections.emptyList(),-1,detector==0||embed==0?"模型加载失败，请查看 RKNN 日志":"模型就绪，请依次录入三件商品",SystemClock.elapsedRealtime(),generation);
    });}
    public void mode(int sku){enrollment=sku;generation++;synchronized(lock){pending=null;}worker.post(()->{tracker.reset();featureCache.clear();lastCapture=0;});}
    public void setRoiScale(float scale){roiScale=scale;}
    public void reset(){generation++;synchronized(lock){pending=null;}worker.post(()->{tracker.reset();featureCache.clear();});}
    public void clear(int sku){generation++;synchronized(lock){pending=null;}worker.post(()->{try{store.clear(sku);tracker.reset();featureCache.clear();}catch(IOException e){publish(Collections.emptyList(),-1,e.getMessage(),SystemClock.elapsedRealtime(),generation);}});}
    public void running(boolean value){active=value;generation++;synchronized(lock){pending=null;}worker.post(()->{tracker.reset();featureCache.clear();});}
    public void submit(byte[] frame,int w,int h,long ignored) {
        if(!active)return;
        synchronized(lock){pending=frame.clone();fw=w;fh=h;fg=generation;ft=SystemClock.elapsedRealtime();if(!queued){queued=true;worker.post(this::consume);}}
    }
    private void consume(){
        byte[] frame;int w,h,token;long time;
        synchronized(lock){frame=pending;pending=null;w=fw;h=fh;time=ft;token=fg;}
        try {if(frame!=null&&active&&token==generation&&detector!=0&&embed!=0)process(frame,w,h,time,token);}
        catch(Exception e){Log.e("DetectCam","Inference failed",e);tracker.reset();featureCache.clear();publish(Collections.emptyList(),-1,"推理异常："+e.getMessage(),time,token);}
        finally{synchronized(lock){if(pending!=null&&active)worker.post(this::consume);else queued=false;}}
    }
    private void process(byte[] frame,int w,int h,long time,int token) throws IOException {
        long processStart=SystemClock.elapsedRealtime();
        float[] raw=NativeRknn.detect(detector,frame,w,h);
        long primaryDone=SystemClock.elapsedRealtime();
        if(raw==null){tracker.reset();featureCache.clear();publish(Collections.emptyList(),-1,"检测推理失败",time,token);return;}
        List<Detection> found=multiOutput?YoloV8Decoder.decode(raw,w,h):LegacyDecoder.decode(raw,w,h);
        if(companion!=0){float[] extra=NativeRknn.detect(companion,frame,w,h);
            if(extra!=null)for(Detection d:YoloV8Decoder.decode(extra,w,h))if(d.classId==4)found.add(d);}
        long detectorsDone=SystemClock.elapsedRealtime();
        // Remove degenerate/tiny boxes; cap feature work to avoid unbounded latency.
        found.removeIf(d->d.right-d.left<.025f||d.bottom-d.top<.04f);
        found.sort((a,b)->Float.compare(b.confidence,a.confidence));
        List<Detection> unique=new ArrayList<>();
        for(Detection d:found){boolean duplicate=false;for(Detection k:unique){
            float area=Math.max(0,Math.min(d.right,k.right)-Math.max(d.left,k.left))*Math.max(0,Math.min(d.bottom,k.bottom)-Math.max(d.top,k.top));
            float union=(d.right-d.left)*(d.bottom-d.top)+(k.right-k.left)*(k.bottom-k.top)-area;
            if(area/Math.max(1e-6f,union)>.45f)duplicate=true;
        }if(!duplicate)unique.add(new Detection(0,d.confidence,d.left,d.top,d.right,d.bottom,"商品"));if(unique.size()==8)break;}
        found=unique;
        List<Detection> shown=new ArrayList<>();int card=-1;String message;String pickupHint="录入中";
        long featureMs=0;int featureRuns=0,cacheHits=0;
        int sku=enrollment;
        if(sku>=0) {
            float rh=roiScale,rw=Math.min(.8f,rh*(float)h/w*.9f);float l=(1-rw)/2,t=(1-rh)/2;
            List<Detection> inside=new ArrayList<>();
            for(Detection d:found)if(d.left>=l&&d.right<=1-l&&d.top>=t&&d.bottom<=1-t)inside.add(d);
            message="请将一件商品完整放入中央框，缓慢转动";
            if(inside.size()==1){
                Detection d=inside.get(0);shown.add(d);
                String quality=quality(frame,w,h,d);
                message=quality==null?"缓慢转动，采集不同角度（目标 24 张）":quality;
                if(quality==null&&time-lastCapture>=650&&store.count(sku)<FeatureStore.TARGET){
                    lastCapture=time;long featureStart=SystemClock.elapsedRealtime();float[] f=NativeRknn.embed(embed,frame,w,h,d.left,d.top,d.right,d.bottom);
                    featureMs+=SystemClock.elapsedRealtime()-featureStart;featureRuns++;
                    if(token==generation&&active){boolean added=store.add(sku,f);if(!added)message="画面重复，请转动商品或改变角度";}
                }
            }else if(inside.size()>1)message="框内检测到多个目标，请只保留一件商品";
            else for(Detection d:found)shown.add(d);
            if(store.count(sku)>=FeatureStore.TARGET)message="本件采集完成，请选择下一件或开始识别";
            message=names[sku]+" · "+store.count(sku)+"/"+FeatureStore.TARGET+"\n"+message;
        }else{
            List<ObjectTracker.Observation> observations=new ArrayList<>();
            StringBuilder matchDebug=new StringBuilder();
            List<Cached> nextCache=new ArrayList<>();
            for(Detection d:found){Cached cached=null;
                for(Cached entry:featureCache)if(time-entry.time<1000&&ObjectTracker.overlap(entry.box,d)>.90f&&tracker.confirmedAt(d,entry.match.sku)){cached=entry;break;}
                boolean freshIdentity=cached==null;
                if(cached==null){long featureStart=SystemClock.elapsedRealtime();cached=new Cached();cached.feature=NativeRknn.embed(embed,frame,w,h,d.left,d.top,d.right,d.bottom);cached.match=store.match(cached.feature);cached.time=time;featureMs+=SystemClock.elapsedRealtime()-featureStart;featureRuns++;}else cacheHits++;
                cached.box=d;nextCache.add(cached);float[] f=cached.feature;FeatureStore.Match m=cached.match;
                matchDebug.append(String.format(Locale.US," [%.2f,%.2f,%.2f,%.2f sku=%d sim=%.3f margin=%.3f]",d.left,d.top,d.right,d.bottom,m.sku,m.score,m.margin));
                observations.add(new ObjectTracker.Observation(d,f,m,freshIdentity));
            }
            featureCache.clear();featureCache.addAll(nextCache);
            if(time-lastLog>=2000)Log.i("DetectCamMatch",matchDebug.toString());
            List<ObjectTracker.Track> tracks=tracker.update(observations,time);StringBuilder states=new StringBuilder();
            pickupHint=ObjectTracker.pickupHint(tracks);
            Set<Integer> liveIds=new HashSet<>();
            for(ObjectTracker.Track tr:tracks){
                liveIds.add(tr.id);
                String signature=tr.stateText()+":"+tr.reason;
                if(!signature.equals(loggedStates.put(tr.id,signature)))Log.i("DetectCamPickup",tr.diagnostics());
                String name=tr.sku<0?"未识别":names[tr.sku];
                if(tr.visible)shown.add(new Detection(0,tr.box.confidence,tr.box.left,tr.box.top,tr.box.right,tr.box.bottom,
                    "#"+tr.id+" "+name+" · "+tr.stateText()));
                if(tr.liftHistory&&tr.sku>=0) {if(card==-1)card=tr.sku;else if(card!=tr.sku)card=-2;}
                if(states.length()>0)states.append('\n');
                states.append('#').append(tr.id).append(' ').append(name).append(' ').append(tr.stateText()).append(" · ").append(tr.reason);
                if(time-lastLog>=2000)Log.i("DetectCamPickup",tr.diagnostics());
            }
            loggedStates.keySet().retainAll(liveIds);
            message=states.length()==0?"等待商品进入画面":states.toString();
            if(!store.ready())message="请先完成三个 SKU 的录入，每件至少 8 张\n"+message;
            if(card==-2)message="检测到多件拿起，请一次展示一件\n"+message;
        }
        long now=SystemClock.elapsedRealtime();long elapsed=now-time;
        Log.i("DetectCamPerf", "total="+elapsed+" queue="+(processStart-time)+" primary="+(primaryDone-processStart)
            +" secondary_decode="+(detectorsDone-primaryDone)+" features="+featureMs
            +" other="+(now-detectorsDone-featureMs)+" embeds="+featureRuns+" cached="+cacheHits+" boxes="+found.size());
        if(rateStart==0)rateStart=now;rateFrames++;
        if(now-rateStart>=2000){analysisFps=rateFrames*1000f/(now-rateStart);rateFrames=0;rateStart=now;}
        message+="\n检测 "+found.size()+" 件 · 算法 "+String.format(Locale.US,"%.1f",analysisFps)+" FPS · 排队+分析 "+elapsed+" ms · 单目拿起推断";
        if(time-lastLog>=2000){lastLog=time;Log.i("DetectCam",message.replace('\n',' '));}
        publish(shown,card,message,time,token,pickupHint);
    }
    private static String quality(byte[] f,int w,int h,Detection d){
        int l=Math.max(1,(int)(d.left*w)),r=Math.min(w-1,(int)(d.right*w));
        int t=Math.max(1,(int)(d.top*h)),b=Math.min(h-1,(int)(d.bottom*h));
        if(r-l<70||b-t<90)return "商品太小，请靠近相机";
        double sum=0,edge=0;int n=0;
        for(int y=t;y<b;y+=3)for(int x=l;x<r;x+=3){int v=f[y*w+x]&255;sum+=v;edge+=Math.abs(v-(f[y*w+x-1]&255))+Math.abs(v-(f[(y-1)*w+x]&255));n++;}
        if(n==0)return "无有效图像";if(sum/n<35||sum/n>235)return "曝光异常，请调整光线";
        return edge/n<3?"画面纹理不足或模糊，请稳定商品":null;
    }
    private void publish(List<Detection> boxes,int sku,String message,long time,int token){publish(boxes,sku,message,time,token,message);}
    private void publish(List<Detection> boxes,int sku,String message,long time,int token,String hint){ui.post(()->{if(token==generation&&active)listener.result(boxes,sku,message,time,hint);});}
    public void destroy(){active=false;generation++;synchronized(lock){pending=null;}worker.post(()->{NativeRknn.close(detector);NativeRknn.close(embed);NativeRknn.close(companion);detector=embed=companion=0;thread.quitSafely();});}
}
