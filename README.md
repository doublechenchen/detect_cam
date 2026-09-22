# detect_cam

RK3588 / Android 13 / USB Camera2 商品录入、特征识别与拿放跟踪原型。独立应用 ID：`com.detectcam.app`，不会覆盖原 SmartCam。

最新验证状态及未完成项见 [test-results/STATUS.md](test-results/STATUS.md)。最新 INT8 / 缓存优化尚未完成板端复测。

## 使用

1. 打开 DetectCam，允许相机权限。默认选择系统外接相机，优先使用不超过 1280×720 的双输出尺寸。
2. 依次点击录入 sku1（乐事）、sku2（可口可乐）、sku3（芙丝）。把对应单件商品完整放入中央黄色框，缓慢转动，建议每件采集 24 张不同角度。框大小可调。
3. 采集自动过滤小目标、曝光异常、低纹理/模糊与近重复特征；这是基础质量筛选，不是严格的遮挡或清晰度评估。
4. 每件至少 8 张后允许开始识别。将三件商品分开放稳约一秒，建立在位基准，再依次拿起。
5. 被拿起的商品会显示对应 `SKU_info` 文本；显示框包含 track_id、SKU 和状态。短时丢失不绘制旧框，保留事件历史以等待恢复。
6. 相机或桌面移动后点击重建基准；USB 断开后重新连接。前后台切换及重连会清除临时轨迹，但保留特征库。

目前的拿起事件依据商品框持续向上位移推断，没有手部模型或深度测量。向远处平移可能误判为拿起，遮挡和高速移动可能漏触发；当前不能作为严格的拿起传感器。完全遮挡或离开画面不能保证定位。首版以三种各一件、一次拿一件为测试范围。

## 模型

默认组合：COCO YOLOv8n bottle/cup 两类定位 + 现有五类模型的薯片定位分支。模型类别只用于定位，SKU 身份来自录入的 576 维 MobileNet 特征、多模板余弦匹配与多帧确认。

默认置信度门限和特征阈值尚需独立实拍集校准。双模型 CPU/NPU 开销较大，实时性能以板端报告为准。

原有通用 640、五类 640、SKU110K 960 作为诊断选项保留，可在界面切换。换检测模型可能改变裁剪范围，需复验录入模板效果。模型和 JNI 二进制包含在项目内；模型来源与校验信息见 `models/`。

## 构建

需要 JDK 17、Android SDK 35，首次构建需要能获取 Gradle/Android 插件依赖。本地 SDK 通过 `android/local.properties` 配置（不提交）。

```bash
cd android
./gradlew :app:assembleDebug :app:lintDebug
```

默认使用随项目保存的 arm64 JNI 和 RKNN Runtime。修改 C++ 后使用匹配 SDK 重建：

```bash
./gradlew -PrknnSdkDir=/path/to/rknn-sdk :app:assembleDebug
```

SDK 目录包含 `librknn_api/include/rknn_api.h` 和 `librknn_api/arm64-v8a/librknnrt.so`。不要将应用随附 Runtime 覆盖到系统 vendor 分区。

## 安装与测试

```bash
adb -s DEVICE install -r -g android/app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE shell am start -n com.detectcam.app/com.smartcam.capture.MainActivity
./scripts/test_core.sh
cd android
./gradlew :app:assembleDebugAndroidTest
adb -s DEVICE install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s DEVICE shell am instrument -w com.detectcam.app.test/com.smartcam.capture.BoardTests
```

板端自动测试会短暂停止应用，结束后需重新启动。测试使用合成图像和独立缓存特征文件，不污染实际录入库；不能代替真实拿放准确率测试。

## 数据和工程结构

- `SKU_info/`：三份商品资料；APK 构建时同步至 assets。
- `android/`：应用、JNI 源码及原生库。
- `scripts/`：核心测试、容器检测模型转换。
- `tests/`：纯 Java 特征、状态机、解码和 YUV 测试。
- `test-results/`：本次验证记录。
- `IMPLEMENTATION_PLAN.md`：规划，包含尚未实现的手部辅助与后续验收目标。

录入特征保存在应用私有 `files/features.bin`，带版本信息并原子写入。相机画面仅内存处理；应用没有网络权限。调试截图/备份仅由本次 ADB 测试取得，不打包在应用里。
