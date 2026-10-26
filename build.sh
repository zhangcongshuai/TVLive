#!/bin/bash
# 构建 APK。
#
# v1 用的是 aapt2 + javac + d8 手工链路，但 v2 引入了 Media3(ExoPlayer)，
# 有传递依赖和 AAR 资源，手工链路处理不了，改用 Gradle。
#
# 需要的环境：
#   JDK 17
#   Android SDK（platforms;android-34 + build-tools;34.0.0）
#   Gradle 8.7
#
# 可用环境变量覆盖：
#   JAVA_HOME     JDK 目录
#   ANDROID_HOME  Android SDK 目录
#   GRADLE        gradle 可执行文件路径（默认 PATH 里的 gradle）
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
GRADLE="${GRADLE:-gradle}"

if [ -z "$JAVA_HOME" ]; then
  echo "请设置 JAVA_HOME（需要 JDK 17）"; exit 1
fi
if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ]; then
  echo "请设置 ANDROID_HOME"; exit 1
fi

SDK="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
if [ ! -f "$ROOT/local.properties" ]; then
  echo "sdk.dir=$(echo "$SDK" | sed 's/\\/\\\\/g')" > "$ROOT/local.properties"
  echo "已生成 local.properties -> $SDK"
fi

if [ ! -f "$ROOT/tools/demo.jks" ]; then
  echo "==> 生成签名密钥 tools/demo.jks"
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$ROOT/tools/demo.jks" \
    -alias tvlive -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass tvlive123 -keypass tvlive123 \
    -dname "CN=TV Live, O=TVLive, C=CN"
fi

echo "==> 构建 release APK"
"$GRADLE" -p "$ROOT" assembleRelease

APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
mkdir -p "$ROOT/dist"
cp "$APK" "$ROOT/dist/TVLive-2.0.apk"

echo
ls -lh "$ROOT/dist/TVLive-2.0.apk"
echo "完成：$ROOT/dist/TVLive-2.0.apk"
