# DetectCam 原生编译优化

当前 CMake 对原生桥启用 `-O3`（包括 Debug APK），避免每帧图像转换以 `-O0` 运行。不启用 fast-math。项目随附的 arm64 原生库已同步重建。两类容器、五类商品 640 和单类货架 960 九输出均受支持。性能计时见项目根目录 `test-results/PERFORMANCE.md`。

以下为继承自 SmartCam 的接口背景，应用模型选择以 DetectCam 代码和根 README 为准。

# RKNN Native Bridge

`rknn_bridge.cpp` links against the RK3588 `librknnrt.so`. The default `det.rknn` receives camera I420 converted to RGB UINT8 NHWC with 640×640 letterbox (padding 114). Native output attributes must match the nine-output five-class YOLOv8 contract. All nine outputs are requested with `want_float=1` and concatenated in tensor order for `YoloV8Decoder`: DFL logits (64 channels), class probabilities (5), score sum (1), at 80/40/20 spatial sizes. Java performs DFL, class-aware NMS and inverse letterbox. Score sum is not used for pruning.

The legacy single-output `[1,5,8400]` detector retains BGR input and its existing decoder/embedding path. Five-class mode directly maps class IDs to SKU names and does not load the embedding model. The application automatically loads the saved five-class path, defaulting to `/data/local/tmp/smartcam/models/det.rknn`, on startup. Model reload/close and inference are serialized to prevent freeing an active RKNN context.

The board currently exposes `/vendor/lib64/librknnrt.so`, but the application must use a model converted by the matching RKNN-Toolkit2/RKNN Runtime version. Do not copy a random runtime over the vendor image.

Build after installing Android NDK and obtaining the matching RKNN SDK:

```text
-DRKNN_SDK_DIR=/path/to/rknpu2/runtime/RK3588/Android
```

The Gradle build only enables this native target when `RKNN_SDK_DIR` is set (or
when `-PrknnSdkDir=...` is supplied). For the checked-out Rockchip SDK and the
installed local toolchain:

```bash
export RKNN_SDK_DIR=/tmp/smartcam-rknn-sdk
cd /home/cc/Documents/ChatGPT/smart_cam/android
JAVA_HOME=/home/cc/tools/jdk17 ./gradlew --offline :app:assembleDebug :app:lintDebug
```

The SDK path above is a local temporary build dependency, containing the official Toolkit2 v2.3.2 header and the previously validated Android runtime. Restore it if `/tmp` is cleared. The Java APK remains usable without this native library and reports that RKNN is unavailable. Other output layouts require matching postprocessing.

Decoder regression checks (from the project root):

```bash
python3 tests/make_yolov8_fixtures.py /tmp/smartcam-decoder-fixtures
/home/cc/tools/jdk17/bin/javac -d /tmp/smartcam-decoder-tests android/app/src/main/java/com/smartcam/capture/Detection.java android/app/src/main/java/com/smartcam/capture/YoloV8Decoder.java tests/YoloV8DecoderTest.java
/home/cc/tools/jdk17/bin/java -cp /tmp/smartcam-decoder-tests YoloV8DecoderTest /tmp/smartcam-decoder-fixtures
```

Fixtures cover each of five classes and a fisheye multi-object image, comparing decoded coordinates, confidence and class against the Python reference. Additional checks cover class-aware suppression, malformed/empty arrays and non-finite confidence.
