package com.smartcam.capture;

import java.io.*;
import java.util.*;

/** Versioned, normalized multi-view templates. No Android dependency. */
public final class FeatureStore {
    public static final String VERSION = "mobilenet576-fp16-rgb-letterbox-v1";
    public static final int DIM = 576, TARGET = 24, MAX = 40;
    private final List<float[]>[] templates;
    private final File file;
    public static final class Match {
        public final int sku; public final float score, margin;
        Match(int sku, float score, float margin) { this.sku=sku; this.score=score; this.margin=margin; }
    }
    @SuppressWarnings("unchecked") public FeatureStore(File file) throws IOException {
        this.file=file; templates=new List[]{new ArrayList<>(),new ArrayList<>(),new ArrayList<>()};
        if (file.isFile()) load();
    }
    public static float[] normalize(float[] v) {
        if(v==null || v.length!=DIM) return null;
        double norm=0; for(float x:v) { if(!Float.isFinite(x)) return null; norm+=x*x; }
        if(norm<1e-12) return null;
        float[] n=v.clone(); for(int i=0;i<n.length;i++) n[i]/=(float)Math.sqrt(norm); return n;
    }
    public static float similarity(float[] a,float[] b) {
        if(a==null || b==null || a.length!=b.length) return -1;
        float s=0; for(int i=0;i<a.length;i++) s+=a[i]*b[i]; return s;
    }
    public synchronized int count(int sku) { return templates[sku].size(); }
    public synchronized boolean ready() { return count(0)>=8 && count(1)>=8 && count(2)>=8; }
    public synchronized boolean add(int sku,float[] feature) throws IOException {
        float[] v=normalize(feature); if(v==null || count(sku)>=MAX) return false;
        for(float[] old:templates[sku]) if(similarity(old,v)>.9995f) return false;
        templates[sku].add(v);
        try { save(); } catch(IOException e) { templates[sku].remove(templates[sku].size()-1); throw e; }
        return true;
    }
    public synchronized void clear(int sku) throws IOException {
        List<float[]> backup=new ArrayList<>(templates[sku]); templates[sku].clear();
        try { save(); } catch(IOException e) { templates[sku].addAll(backup); throw e; }
    }
    public synchronized Match match(float[] feature) {
        float[] n=normalize(feature); if(n==null) return new Match(-1,0,0);
        float best=-1,second=-1; int winner=-1;
        for(int s=0;s<3;s++) {
            if(templates[s].size()<8) continue;
            float[] scores=new float[templates[s].size()];
            for(int i=0;i<scores.length;i++) scores[i]=similarity(n,templates[s].get(i));
            Arrays.sort(scores); int k=Math.min(3,scores.length); float score=0;
            for(int j=0;j<k;j++) score+=scores[scores.length-1-j]/k;
            if(score>best) { second=best;best=score;winner=s; } else second=Math.max(second,score);
        }
        float margin=best-second;
        // Starting gates, must be calibrated against independent held-out board images.
        return new Match(best>=.78f && margin>=.035f ? winner : -1, best, margin);
    }
    private void load() throws IOException {
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if(!VERSION.equals(in.readUTF()) || in.readInt()!=DIM) throw new IOException("特征版本不兼容，请重新录入");
            for(int s=0;s<3;s++) {
                int count=in.readInt(); if(count<0 || count>MAX) throw new IOException("特征文件损坏");
                for(int i=0;i<count;i++) {
                    float[] v=new float[DIM]; for(int j=0;j<DIM;j++) v[j]=in.readFloat();
                    v=normalize(v); if(v==null) throw new IOException("无效特征"); templates[s].add(v);
                }
            }
            if(in.read()!=-1) throw new IOException("特征文件尾部异常");
        }
    }
    private void save() throws IOException {
        File tmp=new File(file.getPath()+".tmp");
        try(FileOutputStream raw=new FileOutputStream(tmp); DataOutputStream out=new DataOutputStream(new BufferedOutputStream(raw))) {
            out.writeUTF(VERSION);out.writeInt(DIM);
            for(List<float[]> rows:templates) {out.writeInt(rows.size());for(float[] v:rows) for(float x:v)out.writeFloat(x);}
            out.flush();raw.getFD().sync();
        }
        if(!tmp.renameTo(file)) throw new IOException("无法原子保存特征库");
    }
}
