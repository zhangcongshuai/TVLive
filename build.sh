#!/bin/bash
# 不依赖 Gradle / Android Studio，直接用 aapt2 + javac + d8 + apksigner 打包
#
# 可用环境变量覆盖：
#   BUILD_TOOLS  build-tools 目录（默认自动找 30.0.3，其次 SDK 里最新的）
#   ANDROID_JAR  android.jar 路径（默认自动找 platforms 里最新版）
#   PYTHON       生成图标用的 python（默认 python3，需要 Pillow）
#   KS           签名用的 keystore（默认 tools/demo.jks，不存在会自动生成）
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/build"
DIST="$ROOT/dist"
VERSION="$(grep -o 'versionName="[^"]*"' "$ROOT/AndroidManifest.xml" | head -1 | cut -d'"' -f2)"

# ---- 找 build-tools 和 android.jar ----
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"

BT="${BUILD_TOOLS:-}"
if [ -z "$BT" ]; then
  for base in "$SDK/build-tools" /tmp/androidsdk/bt_raw/android-11; do
    [ -d "$base" ] || continue
    # base 自己就是 build-tools 目录的情况
    if [ -x "$base/aapt2" ] && [ -x "$base/d8" ]; then BT="$base"; break; fi
    for v in 30.0.3 30.0.2 30.0.1 29.0.3; do
      if [ -x "$base/$v/aapt2" ] && [ -x "$base/$v/d8" ]; then BT="$base/$v"; break 2; fi
    done
    latest="$(ls -d "$base"/* 2>/dev/null | sort -V | tail -1)"
    if [ -n "$latest" ] && [ -x "$latest/aapt2" ]; then BT="$latest"; break; fi
  done
fi

PLAT="${ANDROID_JAR:-}"
if [ -z "$PLAT" ]; then
  for base in "$SDK/platforms" /tmp/androidsdk/plat_raw; do
    [ -d "$base" ] || continue
    latest="$(ls -d "$base"/* 2>/dev/null | sort -V | tail -1)"
    if [ -n "$latest" ] && [ -f "$latest/android.jar" ]; then PLAT="$latest/android.jar"; break; fi
  done
fi

[ -x "$BT/aapt2" ] || { echo "找不到 aapt2，请设置 BUILD_TOOLS"; exit 1; }
[ -f "$PLAT" ]     || { echo "找不到 android.jar，请设置 ANDROID_JAR"; exit 1; }

PY="${PYTHON:-python3}"
APK="$DIST/TVLive-$VERSION.apk"

echo "build-tools : $BT"
echo "android.jar : $PLAT"
echo "version     : $VERSION"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$DIST"

echo "==> 1/6 生成图标"
# 图标已随仓库提交，正常构建跳过；需要重新生成时删掉这两个 png 或手动跑 tools/gen_icons.py
if [ -f "$ROOT/res/mipmap-xhdpi/ic_launcher.png" ] && [ -f "$ROOT/res/drawable-xhdpi/banner.png" ]; then
  echo "    已存在，跳过（重新生成：$PY tools/gen_icons.py，需要 Pillow）"
else
  "$PY" "$ROOT/tools/gen_icons.py"
fi

echo "==> 2/6 编译资源"
"$BT/aapt2" compile --dir "$ROOT/res" -o "$OUT/res.zip"

echo "==> 3/6 链接 APK 壳"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$PLAT" \
  --manifest "$ROOT/AndroidManifest.xml" \
  -R "$OUT/res.zip" \
  -A "$ROOT/assets" \
  --min-sdk-version 21 \
  --target-sdk-version 30 \
  --auto-add-overlay

echo "==> 4/6 编译 Java"
find "$ROOT/src" -name '*.java' > "$OUT/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -nowarn \
  -bootclasspath "$PLAT" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt"

echo "==> 5/6 dex + 组装"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --min-api 21 --lib "$PLAT" --output "$OUT/dex" @"$OUT/classes.txt"
( cd "$OUT/dex" && zip -q "$OUT/base.apk" classes.dex )

"$BT/zipalign" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "==> 6/6 签名"
KS="${KS:-$ROOT/tools/demo.jks}"
if [ ! -f "$KS" ]; then
  echo "    生成演示用 keystore：$KS"
  keytool -genkeypair -keystore "$KS" -alias tvlive -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass tvlive123 -keypass tvlive123 \
    -dname "CN=TV Live Demo, O=Demo, C=CN" 2>/dev/null
fi

"$BT/apksigner" sign \
  --ks "$KS" --ks-pass pass:tvlive123 --key-pass pass:tvlive123 \
  --min-sdk-version 21 \
  --out "$APK" "$OUT/aligned.apk"
rm -f "$APK.idsig"

"$BT/apksigner" verify --print-certs "$APK" | head -3
ls -lh "$APK"
echo "完成：$APK"
