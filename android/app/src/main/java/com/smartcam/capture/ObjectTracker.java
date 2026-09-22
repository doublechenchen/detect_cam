package com.smartcam.capture;

import java.util.*;

/** Conservative one-to-one motion/appearance association; pickup is a visual inference. */
public final class ObjectTracker {
    public enum State { LEARNING, RESTING, MOVING, LIFTED, RETURNING, LOST }
    public static final class Observation {
        public final Detection box; public final float[] feature; public final FeatureStore.Match match;
        public Observation(Detection b,float[] f,FeatureStore.Match m) {box=b;feature=FeatureStore.normalize(f);match=m;}
    }
    public static final class Track {
        public int id,sku=-1,votes,candidate=-1; public Detection box;
        public float[] feature; public long seen,stableSince,candidateSince,lastTime;
        public float baseX,baseY,baseH,baseW,lastX,lastY,vx,vy;
        public State state=State.LEARNING;
        public boolean visible,baseline,liftHistory; public float score;
        public String stateText() {
            switch(state) {
                case RESTING:return "桌面静置";case MOVING:return "移动候选";case LIFTED:return "拿起推断";
                case RETURNING:return "放回候选";case LOST:return "暂时丢失";default:return "建立基准";
            }
        }
    }
    private final List<Track> tracks=new ArrayList<>(); private int next=1; private long previous=-1;
    public void reset() { tracks.clear();previous=-1; }
    public boolean confirmedAt(Detection b,int sku) {
        if(sku<0)return false;
        for(Track t:tracks)if(t.visible&&t.sku==sku&&overlap(t.box,b)>.85f)return true;
        return false;
    }
    public static float overlap(Detection a,Detection b){
        float intersection=Math.max(0,Math.min(a.right,b.right)-Math.max(a.left,b.left))*Math.max(0,Math.min(a.bottom,b.bottom)-Math.max(a.top,b.top));
        return intersection/Math.max(1e-6f,width(a)*height(a)+width(b)*height(b)-intersection);
    }
    static float x(Detection b){return (b.left+b.right)/2;}
    static float y(Detection b){return (b.top+b.bottom)/2;}
    static float width(Detection b){return Math.max(.02f,b.right-b.left);}
    static float height(Detection b){return Math.max(.02f,b.bottom-b.top);}
    private static float distance(float x,float y,float a,float b,float w,float h) {return (float)Math.hypot((x-a)/w,(y-b)/h);}
    public List<Track> update(List<Observation> observations,long time) {
        if(time<=previous) return tracks; previous=time;
        for(Track t:tracks) t.visible=false;
        tracks.removeIf(t -> time-t.seen>1800);
        Set<Integer> used=new HashSet<>();
        // Reject ambiguous association rather than inheriting an arbitrary identity.
        for(Track t:tracks) {
            float best=Float.MAX_VALUE,second=Float.MAX_VALUE; int index=-1;
            for(int i=0;i<observations.size();i++) if(!used.contains(i)) {
                float cost=cost(t,observations.get(i),time);
                if(cost<best){second=best;best=cost;index=i;}else second=Math.min(second,cost);
            }
            if(index>=0 && best<2.0f && second-best>.18f) {
                boolean contested=false;
                for(Track other:tracks) if(other!=t && !other.visible && cost(other,observations.get(index),time)<best+.15f) contested=true;
                if(!contested){used.add(index);accept(t,observations.get(index),time);}
            }
        }
        for(int i=0;i<observations.size();i++) if(!used.contains(i)) {
            Observation o=observations.get(i); boolean near=false;
            for(Track t:tracks) if(cost(t,o,time)<2f) near=true;
            if(!near && tracks.size()<12) { Track t=new Track();t.id=next++;t.box=o.box;t.seen=time;t.lastTime=time;
                t.lastX=x(o.box);t.lastY=y(o.box);t.stableSince=time;tracks.add(t);accept(t,o,time); }
        }
        for(Track t:tracks) if(!t.visible) { t.candidateSince=0;t.stableSince=0;t.state=State.LOST; }
        return tracks;
    }
    private float cost(Track t,Observation o,long time) {
        if(t.sku>=0 && o.match.sku>=0 && t.sku!=o.match.sku) return Float.MAX_VALUE;
        float dt=Math.min(.4f,(time-t.seen)/1000f);
        float dist=distance(x(o.box),y(o.box),x(t.box)+t.vx*dt,y(t.box)+t.vy*dt,width(t.box),height(t.box));
        float appearance=FeatureStore.similarity(t.feature,o.feature);
        float size=width(o.box)*height(o.box)/(width(t.box)*height(t.box));
        if(size<.25f || size>4 || dist>2.5f || (t.feature!=null && o.feature!=null && appearance<.55f)) return Float.MAX_VALUE;
        return dist + (appearance<0? .3f:(1-appearance)*1.5f);
    }
    private void accept(Track t,Observation o,long time) {
        long gap=time-t.seen;
        float nx=x(o.box),ny=y(o.box),dt=Math.max(.001f,(time-t.lastTime)/1000f);
        float movement=distance(nx,ny,t.lastX,t.lastY,width(o.box),height(o.box));
        if(gap>500){t.candidateSince=0;t.stableSince=time;t.vx=t.vy=0;}
        else {t.vx=.5f*t.vx+.5f*(nx-t.lastX)/dt;t.vy=.5f*t.vy+.5f*(ny-t.lastY)/dt;}
        t.box=o.box;t.visible=true;t.seen=time;t.lastTime=time;t.lastX=nx;t.lastY=ny;
        if(o.feature!=null)t.feature=o.feature;
        t.score=o.match.score;
        if(t.sku<0) {
            if(o.match.sku>=0 && o.match.sku==t.candidate)t.votes++; else {t.candidate=o.match.sku;t.votes=o.match.sku<0?0:1;}
            if(t.votes>=3)t.sku=t.candidate;
        }
        if(movement>.10f || t.stableSince==0)t.stableSince=time;
        if(!t.baseline) {
            t.state=State.LEARNING;
            if(t.sku>=0 && time-t.stableSince>=800) {
                t.baseX=nx;t.baseY=ny;t.baseW=width(o.box);t.baseH=height(o.box);t.baseline=true;t.state=State.RESTING;
            }
            return;
        }
        float displacement=distance(nx,ny,t.baseX,t.baseY,t.baseW,t.baseH);
        float upward=(t.baseY-ny)/t.baseH;
        if(t.liftHistory) {
            // Only settle near the original support level; an elevated held object is not a return.
            boolean support=Math.abs(ny-t.baseY)/t.baseH<.28f && Math.abs(height(o.box)/t.baseH-1)<.35f;
            if(support && movement<.10f) {
                if(t.state!=State.RETURNING)t.candidateSince=time;
                t.state=State.RETURNING;
                if(time-t.candidateSince>=800){t.liftHistory=false;t.state=State.RESTING;t.baseX=nx;t.baseY=ny;t.candidateSince=0;}
            } else {t.state=State.LIFTED;t.candidateSince=0;}
        } else if(displacement>.5f && upward>.30f) {
            if(t.state!=State.MOVING)t.candidateSince=time;
            t.state=State.MOVING;
            if(time-t.candidateSince>=250){t.state=State.LIFTED;t.liftHistory=true;t.candidateSince=0;}
        } else {t.state=State.RESTING;t.candidateSince=0;}
    }
}
