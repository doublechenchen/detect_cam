import com.smartcam.capture.Detection;
import com.smartcam.capture.YoloV8Decoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class YoloV8DecoderTest {
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        check(YoloV8Decoder.decode(null,1280,720).isEmpty(),"null outputs");
        check(YoloV8Decoder.decode(new float[42000],1280,720).isEmpty(),"reject legacy shape");
        float[] raw = new float[YoloV8Decoder.OUTPUT_FLOATS];
        check(YoloV8Decoder.decode(raw,1280,720).isEmpty(),"no predictions");
        int i=60*120+60;
        raw[64*14400+i]=0.9f;
        raw[64*14400+i+1]=0.8f; // near-identical same-class box: suppressed
        raw[64*14400+i+30]=0.7f; // distant product: retained
        List<Detection> ds=YoloV8Decoder.decode(raw,1280,720);
        check(ds.size()==2 && ds.get(0).classId==0 && ds.get(1).classId==0,"single-class NMS");
        check("商品".equals(ds.get(0).label),"class label");
        check(Math.abs(YoloV8Decoder.maxScore(raw)-0.9f)<1e-6,"confidence channels only");
        raw[64*14400+i]=Float.NaN;
        check(Float.isFinite(YoloV8Decoder.maxScore(raw)),"NaN confidence");
        float[] five = new float[YoloV8Decoder.FIVE_CLASS_FLOATS];
        int cell = 40*80+40;
        five[64*6400+cell] = .9f;
        five[65*6400+cell+1] = .8f;
        List<Detection> multi = YoloV8Decoder.decode(five,1280,720);
        check(multi.size()==2 && multi.get(1).classId==1,"different classes survive NMS");
        check("唇釉".equals(multi.get(0).label) && "牛奶".equals(multi.get(1).label),"five-class labels");
        check(Math.abs(YoloV8Decoder.maxScore(five)-.9f)<1e-6,"five-class maximum");
        float[] container=new float[YoloV8Decoder.CONTAINER_FLOATS];
        container[64*6400+40*80+40]=.85f;
        container[65*6400+40*80+60]=.8f;
        List<Detection> containers=YoloV8Decoder.decode(container,1280,720);
        check(containers.size()==2,"two-class container output supported");
        check(Math.abs(YoloV8Decoder.maxScore(container)-.85f)<1e-6,"two-class max confidence");
        int fixtures=0;
        if (args.length>0) {
            try (var paths=Files.list(Path.of(args[0]))) {
                for (Path file : (Iterable<Path>) paths.filter(p->p.toString().endsWith(".bin"))::iterator) {
                    byte[] bytes=Files.readAllBytes(file);
                    ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                    float[] values=new float[bytes.length/4];b.asFloatBuffer().get(values);
                    List<String> lines=Files.readAllLines(Path.of(file.toString().replace(".bin",".txt")));
                    String[] shape=lines.get(0).split(" ");
                    List<Detection> actual=YoloV8Decoder.decode(values,Integer.parseInt(shape[0]),Integer.parseInt(shape[1]));
                    check(actual.size()==lines.size()-1,"prediction count: "+file+" got "+actual.size());
                    boolean[] matched = new boolean[actual.size()];
                    for (int n=1; n<lines.size(); n++) {
                        String[] row=lines.get(n).split(" ");
                        boolean found=false;
                        for(int j=0;j<actual.size();j++) {
                            if(matched[j]) continue;
                            Detection d=actual.get(j);
                            float[] valuesToCheck={d.left,d.top,d.right,d.bottom,d.confidence,d.classId};
                            boolean same=true;
                            for(int k=0;k<6;k++) same &= Math.abs(valuesToCheck[k]-Float.parseFloat(row[k]))<0.0001f;
                            if(same) { matched[j]=true;found=true;break; }
                        }
                        check(found,"unmatched reference box "+file+" row="+n);
                    }
                    fixtures++;
                }
            }
            check(fixtures>=6,"missing real model fixtures");
        }
        System.out.println("PASS: malformed/empty outputs, single-class NMS, labels, NaN handling; "+fixtures+" real model fixtures match Python.");
    }
}
