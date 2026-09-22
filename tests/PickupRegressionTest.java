package com.smartcam.capture;

import java.util.*;

/** Reproduces the deployed 2 FPS cadence, occlusion and zero-timer failure modes. */
public final class PickupRegressionTest {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static float[] feature(int sku){float[] f=new float[576];f[sku]=1;return f;}
    private static ObjectTracker.Observation o(int sku,float x,float y,boolean known){
        return new ObjectTracker.Observation(new Detection(0,.9f,x-.1f,y-.15f,x+.1f,y+.15f),
                feature(sku),new FeatureStore.Match(known?sku:-1,.85f,known?.1f:.01f));
    }
    private static List<ObjectTracker.Track> update(ObjectTracker tracker,long time,ObjectTracker.Observation... observations){
        return tracker.update(Arrays.asList(observations),time);
    }
    private static ObjectTracker.Track base(ObjectTracker tracker){
        List<ObjectTracker.Track> rows=null;
        for(long t=100;t<=1300;t+=100)rows=update(tracker,t,o(0,.3f,.7f,true));
        return rows.get(0);
    }
    public static void main(String[] args){
        ObjectTracker tracker=new ObjectTracker();List<ObjectTracker.Track> rows=null;
        for(long t=100;t<=2300;t+=550)rows=update(tracker,t,o(0,.3f,.7f,true));
        ObjectTracker.Track track=rows.get(0);
        check(track.baseline,"550ms normal frame cadence must build baseline");
        update(tracker,2850,o(0,.3f,.585f,true));check(!track.liftHistory,"one displaced frame is not a lift");
        update(tracker,3400,o(0,.3f,.585f,true));check(track.liftHistory,"modest upward lift confirmed at 2 FPS");
        update(tracker,3950,o(0,.3f,.7f,true));check(track.liftHistory,"returning motion must not immediately confirm put-back");
        update(tracker,4500,o(0,.3f,.7f,true));check(track.liftHistory,"return not yet stable for 800ms");
        update(tracker,5050,o(0,.3f,.7f,true));check(track.liftHistory,"stable return still needs full dwell");
        update(tracker,5600,o(0,.3f,.7f,true));check(!track.liftHistory,"return confirmed with observed dwell");

        tracker.reset();track=base(tracker);int id=track.id;
        update(tracker,1400,o(0,.3f,.60f,false));
        check(track.visible&&track.id==id&&track.sku==0,"partial occlusion retains known identity");
        check(track.identityUncertain&&!track.liftHistory,"unknown alone cannot trigger lift");
        check(track.reason.contains("身份"),"uncertain identity has specific explanation");
        update(tracker,1700,o(0,.3f,.58f,false));check(!track.liftHistory,"multiple unknown frames still not a lift");
        update(tracker,1900,o(0,.3f,.58f,true));check(track.liftHistory,"fresh identity recovery confirms observed motion");
        check(Arrays.equals(track.feature,feature(0)),"uncertain features do not contaminate identity anchor");

        tracker.reset();track=base(tracker);
        update(tracker,1400,o(0,.3f,.58f,true));update(tracker,1500);
        update(tracker,1700,o(0,.3f,.58f,true));check(!track.liftHistory,"fully missing detection clears candidate continuity");
        update(tracker,2000,o(0,.3f,.58f,true));check(track.liftHistory,"two new observed positions can confirm after occlusion");

        tracker.reset();track=base(tracker);
        update(tracker,1400,o(0,.3f,.58f,true));update(tracker,3000,o(0,.3f,.58f,true));
        check(!track.liftHistory&&track.candidateSamples==1,"long gap cannot turn zero timer into immediate lift");
        update(tracker,3300,o(0,.3f,.58f,true));check(track.liftHistory,"fresh candidate dwell after gap");
        update(tracker,3400,o(0,.3f,.7f,true));update(tracker,5000,o(0,.3f,.7f,true));
        check(track.liftHistory&&track.candidateSamples==1,"long gap cannot immediately confirm put-back");

        tracker.reset();track=base(tracker);
        update(tracker,1400,o(0,.43f,.7f,true));update(tracker,1800,o(0,.43f,.7f,true));
        check(!track.liftHistory,"horizontal table movement rejected");
        check(track.reason.contains("上抬证据不足"),"table movement has actionable reason");
        check(ObjectTracker.pickupHint(Arrays.asList(track)).contains("上抬证据不足"),"UI exposes missing lift evidence");

        tracker.reset();track=base(tracker);
        // Occlusion can shrink a box upwards while its bottom stays on the table.
        ObjectTracker.Observation shrink=new ObjectTracker.Observation(new Detection(0,.9f,.2f,.40f,.4f,.85f),
                feature(0),new FeatureStore.Match(0,.9f,.1f));
        update(tracker,1400,shrink);update(tracker,1800,shrink);
        check(!track.liftHistory,"bottom stationary is not lifting");
        tracker.reset();update(tracker,100,o(0,.3f,.6f,false));
        check(update(tracker,500,o(0,.3f,.5f,false)).isEmpty(),"unknown objects never create identities");

        tracker.reset();track=base(tracker);
        update(tracker,1400,o(0,.3f,.68f,false));
        update(tracker,2700,o(0,.3f,.66f,false));check(!track.visible,"unknown fallback expires without fresh SKU evidence");
        tracker.reset();track=base(tracker);
        ObjectTracker.Observation alien=new ObjectTracker.Observation(new Detection(0,.9f,.2f,.55f,.4f,.85f),
                feature(2),new FeatureStore.Match(-1,.7f,.01f));
        update(tracker,1400,alien);check(!track.visible,"unrelated nearby appearance cannot inherit identity");
        check(ObjectTracker.pickupHint(Arrays.asList(track)).contains("跟踪暂时丢失"),"lost state not generic wait");

        tracker.reset();track=base(tracker);
        ObjectTracker.Observation wrong=o(1,.3f,.6f,true);update(tracker,1400,wrong);
        check(!track.visible&&track.sku==0,"conflicting known SKU cannot replace a track identity");
        long seen=track.seen;update(tracker,1200,o(0,.3f,.5f,true));check(track.seen==seen,"out of order samples ignored");
        System.out.println("PASS "+checks+" pickup regression checks");
    }
}
