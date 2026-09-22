package com.smartcam.capture;
import java.io.*;
import java.util.*;
public class CoreTest {
    static int checks;
    static void check(boolean condition,String name){checks++;if(!condition)throw new AssertionError(name);}
    static float[] feature(int sku,int angle){float[] f=new float[576];f[sku*100]=1;f[sku*100+1+angle]=.2f;return f;}
    static ObjectTracker.Observation o(FeatureStore store,int sku,float x,float y){float[] f=feature(sku,0);return new ObjectTracker.Observation(new Detection(0,.9f,x-.1f,y-.15f,x+.1f,y+.15f),f,store.match(f));}
    public static void main(String[] args)throws Exception{
        File file=File.createTempFile("detectcam-test-",".bin");file.delete();FeatureStore s=new FeatureStore(file);
        check(!s.ready(),"empty not ready");check(s.match(feature(0,0)).sku==-1,"empty unknown");
        check(!s.add(0,new float[576]),"zero rejected");float[] bad=feature(0,0);bad[0]=Float.NaN;check(!s.add(0,bad),"nan rejected");
        for(int sku=0;sku<3;sku++)for(int n=0;n<8;n++)check(s.add(sku,feature(sku,n)),"distinct accepted");
        check(s.ready(),"ready");check(!s.add(0,feature(0,0)),"duplicate rejected");
        s=new FeatureStore(file);check(s.count(2)==8,"persistence");for(int sku=0;sku<3;sku++)check(s.match(feature(sku,0)).sku==sku,"sku match");
        float[] unknown=new float[576];unknown[500]=1;check(s.match(unknown).sku==-1,"unknown rejected");
        float[] ambiguous=new float[576];ambiguous[0]=1;ambiguous[100]=1;check(s.match(ambiguous).sku==-1,"ambiguous rejected");
        ObjectTracker tracker=new ObjectTracker();List<ObjectTracker.Track> ts=null;
        for(long t=100;t<=1100;t+=100)ts=tracker.update(Arrays.asList(o(s,0,.3f,.7f),o(s,1,.7f,.7f)),t);
        check(ts.size()==2,"two independent tracks");check(ts.get(0).sku==0&&ts.get(1).sku==1,"stable identities");
        check(ts.get(0).state==ObjectTracker.State.RESTING,"baseline established");int id=ts.get(0).id;
        for(long t=1200;t<=1600;t+=100)ts=tracker.update(Arrays.asList(o(s,0,.38f,.7f),o(s,1,.7f,.7f)),t);
        check(!ts.get(0).liftHistory,"horizontal move not lift");
        for(long t=1700;t<=2100;t+=100)ts=tracker.update(Arrays.asList(o(s,0,.38f,.5f),o(s,1,.7f,.7f)),t);
        check(ts.get(0).liftHistory,"sustained upward lift");check(ts.get(0).id==id,"track identity retained");
        ts=tracker.update(Collections.singletonList(o(s,1,.7f,.7f)),2200);
        check(ts.get(0).state==ObjectTracker.State.LOST&&ts.get(0).liftHistory,"occlusion preserves event but no visible box");check(!ts.get(0).visible,"lost invisible");
        ts=tracker.update(Arrays.asList(o(s,0,.38f,.5f),o(s,1,.7f,.7f)),2300);
        check(ts.get(0).id==id,"recover same id");
        for(long t=2400;t<=3400;t+=100)ts=tracker.update(Arrays.asList(o(s,0,.38f,.7f),o(s,1,.7f,.7f)),t);
        check(!ts.get(0).liftHistory&&ts.get(0).state==ObjectTracker.State.RESTING,"return settled");
        tracker.update(Collections.emptyList(),3500);check(!ts.get(1).liftHistory,"disappearance not lift");
        ts=tracker.update(Collections.emptyList(),6000);check(ts.isEmpty(),"expire stale tracks");
        tracker.reset();for(long t=100;t<=1100;t+=100)ts=tracker.update(Collections.singletonList(o(s,0,.3f,.7f)),t);
        tracker.update(Collections.singletonList(o(s,0,.3f,.5f)),1200);tracker.update(Collections.emptyList(),1300);
        ts=tracker.update(Collections.singletonList(o(s,0,.3f,.5f)),1400);check(!ts.get(0).liftHistory,"missing observation clears candidate dwell");
        float[] raw=new float[42000];raw[4*8400]=Float.NaN;check(LegacyDecoder.decode(raw,640,480).isEmpty(),"invalid confidence ignored");
        s.clear(1);check(!new FeatureStore(file).ready(),"clear persisted");file.delete();
        System.out.println("PASS "+checks+" checks");
    }
}
