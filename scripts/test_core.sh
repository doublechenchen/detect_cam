#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
test_build="$(mktemp -d)"
trap 'rm -rf "$test_build"' EXIT
javac -d "$test_build" "$project_dir"/android/app/src/main/java/com/smartcam/capture/{Detection,FeatureStore,ObjectTracker,LegacyDecoder,YoloV8Decoder,Yuv420}.java "$project_dir"/tests/{CoreTest,YoloV8DecoderTest,Yuv420Test}.java
java -cp "$test_build" com.smartcam.capture.CoreTest
java -cp "$test_build" YoloV8DecoderTest
java -cp "$test_build" Yuv420Test
