package com.smartcam.capture;
import android.Manifest;
import android.app.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.usb.*;
import android.os.*;
import android.util.Size;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.io.*;

public final class MainActivity extends Activity implements TextureView.SurfaceTextureListener {
    private TextureView texture;private EnrollmentOverlay roi;private DetectionOverlay detections;
    private TextView status,inferenceStatus,card;private CameraManager manager;private CameraCapture capture;
    private DetectEngine inference;private FeatureStore store;private int selectedSku=0;
    private final List<String> ids=new ArrayList<>(),labels=new ArrayList<>();private final List<Size> sizes=new ArrayList<>();
    private final String[] modelFiles={"det_rk3588_fp16_norm.rknn","sku_detector_640_fp16.rknn","sku110k_yolov8n_960_fp16.rknn","containers_640_fp16.rknn","containers_640_int8.rknn"};
    private String selectedId;private Size selectedSize;private int sensorOrientation;private boolean resumed;
    private final String[] names={"sku1 乐事原味薯片","sku2 可口可乐","sku3 芙丝矿泉水"};
    private final String[] info=new String[3];private long lastResult;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable watchdog=new Runnable(){public void run(){
        if(lastResult>0 && SystemClock.elapsedRealtime()-lastResult>1500){detections.setDetections(null);card.setText("画面分析已中断，请检查相机连接");}
        if(resumed)handler.postDelayed(this,500);
    }};
    private void resetTracking(){if(inference!=null)inference.reset();if(detections!=null)detections.setDetections(null);lastResult=0;if(card!=null)card.setText("等待商品拿起；短时遮挡不等于拿起");}
    @Override public void onCreate(Bundle saved){super.onCreate(saved);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        manager=getSystemService(CameraManager.class);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(15,23,35));
        LinearLayout top=new LinearLayout(this);addButton(top,"选择相机",this::selectCamera);addButton(top,"分辨率",this::selectResolution);
        addButton(top,"重新连接",this::refresh);addButton(top,"诊断",this::showDiagnostics);addButton(top,"检测模型",this::selectModel);root.addView(top);
        LinearLayout actions=new LinearLayout(this);
        for(int i=0;i<3;i++){final int sku=i;addButton(actions,"录入 sku"+(i+1),()->setMode(sku));}
        addButton(actions,"开始识别",()->{if(store.ready())setMode(-1);
        if((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0 && getIntent().hasExtra("test_detector"))loadDetector(getIntent().getIntExtra("test_detector",0));else new AlertDialog.Builder(this).setMessage("每件至少录入 8 张不同角度图片，建议 24 张").setPositiveButton("知道了",null).show();});
        addButton(actions,"重建基准",this::resetTracking);root.addView(actions);
        LinearLayout tools=new LinearLayout(this);addButton(tools,"清空当前录入",()->{if(selectedSku>=0){final int sku=selectedSku;new AlertDialog.Builder(this).setMessage("清空 sku"+(sku+1)+" 的特征并重新录入？").setNegativeButton("取消",null).setPositiveButton("清空",(d,v)->inference.clear(sku)).show();}});
        TextView sizeLabel=new TextView(this);sizeLabel.setText("录入框大小");sizeLabel.setTextColor(Color.WHITE);tools.addView(sizeLabel);
        SeekBar slider=new SeekBar(this);slider.setMax(40);slider.setProgress(20);tools.addView(slider,new LinearLayout.LayoutParams(0,-2,2));root.addView(tools);
        FrameLayout area=new FrameLayout(this);texture=new TextureView(this);texture.setSurfaceTextureListener(this);area.addView(texture,new FrameLayout.LayoutParams(-1,-1));
        detections=new DetectionOverlay(this);area.addView(detections,new FrameLayout.LayoutParams(-1,-1));roi=new EnrollmentOverlay(this);area.addView(roi,new FrameLayout.LayoutParams(-1,-1));
        root.addView(area,new LinearLayout.LayoutParams(-1,0,1));
        card=text(root,18,Color.YELLOW);card.setText("请依次录入三件商品");card.setMaxLines(3);card.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        inferenceStatus=text(root,14,Color.rgb(150,230,180));inferenceStatus.setMinLines(2);inferenceStatus.setMaxLines(5);
        status=text(root,12,Color.LTGRAY);status.setMaxLines(2);setContentView(root);
        try{store=new FeatureStore(new File(getFilesDir(),"features.bin"));}
        catch(IOException e){inferenceStatus.setText("特征库读取失败："+e.getMessage());new AlertDialog.Builder(this).setMessage("特征库损坏或版本不匹配，已保留原文件。请通过应用设置清除数据后重新录入。").setPositiveButton("关闭",(d,v)->finish()).show();return;}
        for(int i=0;i<3;i++){try{info[i]=readAsset("SKU_info/sku"+(i+1)+"_info.txt");}catch(IOException e){info[i]="商品资料缺失";}}
        inference=new DetectEngine(store,(boxes,sku,message,time,pickupHint)->{
            if(SystemClock.elapsedRealtime()-time>1500){detections.setDetections(null);inferenceStatus.setText("分析延迟过高，暂不更新拿放结果");return;}
            lastResult=SystemClock.elapsedRealtime();detections.setDetections(boxes);inferenceStatus.setText(message);
            if(selectedSku>=0)card.setText(names[selectedSku]+" · 已录入 "+store.count(selectedSku)+" 张\n"+info[selectedSku]);
            else if(sku>=0)card.setText(names[sku]+" · "+pickupHint+"\n"+info[sku]);
            else if(sku==-2)card.setText("多件商品被拿起，请一次展示一件");
            else card.setText(pickupHint);
        });
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean u){float scale=.4f+p/100f;roi.scale(scale);inference.setRoiScale(scale);}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
        try{copyAsset("sku_detector_640_fp16.rknn");copyAsset("chips_640_int8.rknn");String det=copyAsset(modelFiles[getPreferences(MODE_PRIVATE).getInt("detectorIndex",3)]),emb=copyAsset("emb_rk3588_fp16_norm.rknn");inference.load(det,emb);}
        catch(IOException e){inferenceStatus.setText("模型复制失败："+e.getMessage());}
        capture=new CameraCapture(this,message->{status.setText(message);if(message.contains("失败")||message.contains("断开")||message.contains("错误")){inference.running(false);resetTracking();card.setText("相机中断，请重新连接");}},(data,w,h,t)->inference.submit(data,w,h,t));
        selectedId=getPreferences(MODE_PRIVATE).getString("cameraId",null);
        String savedSize=getPreferences(MODE_PRIVATE).getString("streamSize",null);if(savedSize!=null)try{selectedSize=Size.parseSize(savedSize);}catch(Exception ignored){}
        if(store.ready())setMode(-1);
        if((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0 && getIntent().hasExtra("test_detector"))loadDetector(getIntent().getIntExtra("test_detector",0));
    }
    private void selectModel(){new AlertDialog.Builder(this).setTitle("检测模型（仅用于定位，SKU 由特征决定）").setItems(new String[]{"通用商品 640","已有五类商品 640","货架商品 960","瓶 / 杯 + 薯片 FP16","瓶 / 杯 + 薯片 INT8"},(dialog,index)->loadDetector(index)).show();}
    private void loadDetector(int index){if(index<0||index>=modelFiles.length)return;resetTracking();try{copyAsset("sku_detector_640_fp16.rknn");copyAsset("chips_640_int8.rknn");getPreferences(MODE_PRIVATE).edit().putInt("detectorIndex",index).apply();inference.load(copyAsset(modelFiles[index]),copyAsset("emb_rk3588_fp16_norm.rknn"));}catch(IOException e){inferenceStatus.setText(e.toString());}}
    private TextView text(LinearLayout root,int size,int color){TextView t=new TextView(this);t.setTextSize(size);t.setTextColor(color);t.setPadding(12,2,12,2);root.addView(t);return t;}
    private String readAsset(String name)throws IOException{try(InputStream in=getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");}}
    private String copyAsset(String name)throws IOException{File target=new File(getFilesDir(),name);try(InputStream in=getAssets().open(name);OutputStream out=new FileOutputStream(target)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}return target.getAbsolutePath();}
    private void setMode(int sku){if(inference==null)return;selectedSku=sku;roi.mode(sku>=0);inference.mode(sku);resetTracking();}
    private void addButton(LinearLayout row, String title, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setOnClickListener(v -> action.run());
        row.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
    }

    @Override protected void onResume() {
        super.onResume(); if(inference==null)return; resumed = true;
        handler.post(watchdog);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 10);
        } else refresh();
    }

    @Override protected void onPause() { resumed = false; handler.removeCallbacks(watchdog); if(inference==null){super.onPause();return;} inference.running(false); resetTracking(); capture.stop(); super.onPause(); }
    @Override protected void onDestroy() { if(capture!=null)capture.destroy(); if(inference!=null)inference.destroy(); super.onDestroy(); }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 10) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) refresh();
            else status.setText("需要相机权限。请在系统设置 → 应用 → DetectCam 商品识别 → 权限中允许相机，再返回。");
        }
    }

    private void refresh() {
        inference.running(false);
        resetTracking();
        capture.stop();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            status.setText("请先在系统设置中授予相机权限。"); return;
        }
        ids.clear(); labels.clear();
        try {
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                boolean external = Objects.equals(facing, CameraCharacteristics.LENS_FACING_EXTERNAL);
                int index = external ? 0 : ids.size();
                ids.add(index, id);
                labels.add(index, "ID " + id + (external ? " · 外置 USB 候选" : " · facing=" + facing));
            }
            if (ids.isEmpty()) {
                selectedId = null; selectedSize = null;
                status.setText("Camera2 未发现相机。若 USB 专用 App 可以预览，需要核实 USB/UVC 直连方案。\n请点击“设备诊断”，此版本不会直接访问 USB 视频接口。");
                return;
            }
            if (!ids.contains(selectedId)) selectedId = ids.get(0);
            loadSizes();
            startIfReady();
        } catch (CameraAccessException | RuntimeException error) {
            status.setText("枚举相机失败：" + error.getMessage());
        }
    }

    private void loadSizes() throws CameraAccessException {
        sizes.clear();
        CameraCharacteristics characteristics = manager.getCameraCharacteristics(selectedId);
        Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        sensorOrientation = orientation == null ? 0 : orientation;
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map != null) {
            Size[] yuv = map.getOutputSizes(ImageFormat.YUV_420_888);
            Size[] preview = map.getOutputSizes(SurfaceTexture.class);
            if (yuv != null && preview != null) {
                List<Size> supportedPreview = Arrays.asList(preview);
                for (Size size : yuv) {
                    if (supportedPreview.contains(size) && size.getWidth() % 2 == 0 && size.getHeight() % 2 == 0) sizes.add(size);
                }
            }
        }
        sizes.sort(Comparator.comparingLong(s -> (long) s.getWidth() * s.getHeight()));
        if (!sizes.contains(selectedSize)) {
            selectedSize = null;
            for (Size size : sizes) {
                if (size.getWidth() <= 1280 && size.getHeight() <= 720) selectedSize = size;
            }
            if (selectedSize == null && !sizes.isEmpty()) selectedSize = sizes.get(0);
        }
    }

    private void selectCamera() {
        if (ids.isEmpty()) { refresh(); return; }
        new AlertDialog.Builder(this).setTitle("选择相机（请确认是 USB 鱼眼画面）")
                .setItems(labels.toArray(new String[0]), (dialog, index) -> {
                    capture.stop(); selectedId = ids.get(index); selectedSize = null;
                    try { loadSizes(); startIfReady(); }
                    catch (CameraAccessException | RuntimeException error) { status.setText(error.toString()); }
                }).show();
    }

    private void selectResolution() {
        if (sizes.isEmpty()) { status.setText("没有可用的预览 + YUV 分辨率，请查看设备诊断。"); return; }
        String[] choices = new String[sizes.size()];
        for (int i = 0; i < choices.length; i++) choices[i] = sizes.get(i).toString();
        new AlertDialog.Builder(this).setTitle("采集分辨率（默认不超过 720p）")
                .setItems(choices, (dialog, index) -> { selectedSize = sizes.get(index); startIfReady(); }).show();
    }

    private void startIfReady() {
        if (!resumed || !texture.isAvailable() || selectedId == null) return;
        if (selectedSize == null) { status.setText("相机没有共同的 TextureView / YUV_420_888 输出尺寸，请查看诊断。"); return; }
        inference.running(false);
        resetTracking();
        SurfaceTexture surface = texture.getSurfaceTexture();
        surface.setDefaultBufferSize(selectedSize.getWidth(), selectedSize.getHeight());
        updateTransform();
        getPreferences(MODE_PRIVATE).edit().putString("cameraId", selectedId)
                .putString("streamSize", selectedSize.toString()).apply();
        capture.start(selectedId, selectedSize, new Surface(surface));
        inference.running(true);
    }

    private void updateTransform() {
        if (selectedSize == null || texture.getWidth() == 0 || texture.getHeight() == 0) return;
        float viewWidth = texture.getWidth(), viewHeight = texture.getHeight();
        float scale = Math.min(viewWidth / selectedSize.getWidth(), viewHeight / selectedSize.getHeight());
        float left = (viewWidth - selectedSize.getWidth() * scale) / 2;
        float top = (viewHeight - selectedSize.getHeight() * scale) / 2;
        float right = viewWidth - left, bottom = viewHeight - top;
        // Cache metadata during enumeration: a layout callback must not query a
        // camera that may have disappeared asynchronously after USB removal.
        // TextureView already applies the HAL's sensor orientation. Undo it so the
        // preview and normalized ROI use the same unrotated axes as ImageReader.
        float[] source = {0, 0, viewWidth, 0, 0, viewHeight};
        float[] destination;
        switch (sensorOrientation) {
            case 90: destination = new float[]{left, bottom, left, top, right, bottom}; break;
            case 180: destination = new float[]{right, bottom, left, bottom, right, top}; break;
            case 270: destination = new float[]{right, top, right, bottom, left, top}; break;
            default: destination = new float[]{left, top, right, top, left, bottom}; break;
        }
        Matrix matrix = new Matrix();
        matrix.setPolyToPoly(source, 0, destination, 0, 3);
        texture.setTransform(matrix);
        roi.bounds(new RectF(left, top, viewWidth - left, viewHeight - top));
        detections.setContentBounds(new RectF(left, top, viewWidth - left, viewHeight - top));
    }

    private void showDiagnostics() {
        StringBuilder text = new StringBuilder("Android ").append(android.os.Build.VERSION.RELEASE)
                .append(" / API ").append(android.os.Build.VERSION.SDK_INT).append("\n");
        try {
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                text.append("\nCamera2 ID=").append(id)
                        .append(" facing=").append(c.get(CameraCharacteristics.LENS_FACING))
                        .append(" orientation=").append(c.get(CameraCharacteristics.SENSOR_ORIENTATION))
                        .append(" level=").append(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL));
                StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map != null) text.append("\nYUV: ").append(Arrays.toString(map.getOutputSizes(ImageFormat.YUV_420_888)));
            }
            UsbManager usb = getSystemService(UsbManager.class);
            text.append("\n\nUSB 设备（不代表已支持 Camera2）：\n");
            for (UsbDevice device : usb.getDeviceList().values()) {
                text.append(device.getDeviceName()).append(" VID=").append(device.getVendorId())
                        .append(" PID=").append(device.getProductId()).append("\n");
            }
        } catch (CameraAccessException | RuntimeException error) { text.append("\n").append(error); }
        TextView contents = new TextView(this);
        contents.setText(text); contents.setTextIsSelectable(true); contents.setPadding(24, 16, 24, 16);
        ScrollView scroll = new ScrollView(this); scroll.addView(contents);
        new AlertDialog.Builder(this).setTitle("设备诊断").setView(scroll).setPositiveButton("关闭", null).show();
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) { startIfReady(); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) { updateTransform(); }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { if(inference!=null)inference.running(false); resetTracking(); if(capture!=null)capture.stop(); return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
}
