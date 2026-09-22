package com.smartcam.capture;
import android.app.*;
import android.os.*;
import android.content.*;
import android.hardware.camera2.*;
import java.io.*;
import java.util.*;

/** Real-device smoke tests. Synthetic templates never touch the live enrollment database. */
public final class BoardTests extends Instrumentation {
    private final StringBuilder report=new StringBuilder();private int passed;
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private void require(boolean ok,String text){if(!ok)throw new AssertionError(text);passed++;report.append("PASS ").append(text).append('\n');}
    private String asset(String name)throws IOException{
        File f=new File(getTargetContext().getCacheDir(),name);
        try(InputStream in=getTargetContext().getAssets().open(name);OutputStream out=new FileOutputStream(f)){
            byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
        }return f.getPath();
    }
    @Override public void onStart(){Bundle out=new Bundle();long det=0,emb=0;
        try{
            require(Build.VERSION.SDK_INT==33,"Android 13 target device");
            CameraManager camera=getTargetContext().getSystemService(CameraManager.class);
            boolean external=false;for(String id:camera.getCameraIdList())external|=Objects.equals(camera.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING),CameraCharacteristics.LENS_FACING_EXTERNAL);
            require(external,"Camera2 external camera enumerated");require(NativeRknn.isLibraryLoaded(),"JNI library loaded");
            det=NativeRknn.open(asset("containers_640_int8.rknn"));emb=NativeRknn.open(asset("emb_rk3588_fp16_norm.rknn"));
            require(det!=0 && emb!=0,"RKNN detector and embedding initialized");
            byte[] frame=new byte[640*480*3/2];Arrays.fill(frame,(byte)128);
            long start=SystemClock.elapsedRealtime();
            for(int n=0;n<5;n++){
                float[] boxes=NativeRknn.detect(det,frame,640,480);require(boxes!=null && boxes.length==YoloV8Decoder.CONTAINER_FLOATS,"native detector tensor iteration "+n);
                require(YoloV8Decoder.decode(boxes,640,480)!=null,"decoder accepts actual NPU tensor "+n);
                float[] f=NativeRknn.embed(emb,frame,640,480,.2f,.1f,.8f,.9f);require(FeatureStore.normalize(f)!=null,"finite nonzero 576-D embedding "+n);
            }
            report.append("5 detector+embedding cycles ms=").append(SystemClock.elapsedRealtime()-start).append('\n');
            require(NativeRknn.detect(det,new byte[1],640,480)==null,"short camera buffer rejected");
            require(NativeRknn.embed(emb,frame,640,480,Float.NaN,0,1,1)==null,"NaN crop rejected");
            require(NativeRknn.embed(emb,frame,640,480,.8f,0,.2f,1)==null,"reversed crop rejected");
            NativeRknn.close(det);det=NativeRknn.open(asset("chips_640_int8.rknn"));
            require(det!=0,"INT8 chips model initialized");
            float[] chips=NativeRknn.detect(det,frame,640,480);
            require(chips!=null&&chips.length==YoloV8Decoder.FIVE_CLASS_FLOATS,"INT8 chips output contract");
            File file=new File(getTargetContext().getCacheDir(),"board-test-features.bin");file.delete();FeatureStore store=new FeatureStore(file);
            for(int s=0;s<3;s++)for(int n=0;n<8;n++){float[] f=new float[576];f[s*100]=1;f[s*100+1+n]=.2f;store.add(s,f);}
            require(new FeatureStore(file).ready(),"Android filesystem feature persistence");file.delete();
            for(int s=1;s<=3;s++)try(InputStream in=getTargetContext().getAssets().open("SKU_info/sku"+s+"_info.txt")){require(in.available()>0,"SKU info asset "+s);}
            out.putString("stream", "\n"+report+"PASS total="+passed+"\n");finish(Activity.RESULT_OK,out);
        }catch(Throwable e){out.putString("stream","\n"+report+"FAIL "+e+"\n");finish(Activity.RESULT_CANCELED,out);}
        finally{NativeRknn.close(det);NativeRknn.close(emb);}
    }
}
