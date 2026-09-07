#!/usr/bin/env bash
# dy-dl Android App 构建 (无 Gradle, 纯 SDK 工具链)
set -euo pipefail

SDK="E:/Tools/android-sdk"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
JDK="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot"
export PATH="$JDK/bin:$PATH"
PROJ="$(cd "$(dirname "$0")" && pwd)"
OUT="$PROJ/build"
KS="$PROJ/debug.keystore"

cd "$PROJ"
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "==> 1/7 aapt2 compile 资源"
"$BT/aapt2.exe" compile --dir res -o "$OUT/res.zip"

echo "==> 2/7 aapt2 link 生成 R.java 与基础 APK"
"$BT/aapt2.exe" link -o "$OUT/base.apk" \
  --manifest AndroidManifest.xml \
  -I "$PLATFORM" "$OUT/res.zip" \
  --java "$OUT/gen" \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code 27 --version-name 2.16

echo "==> 3/7 javac 编译"
javac --release 8 -encoding UTF-8 \
  -classpath "$PLATFORM" \
  -d "$OUT/classes" \
  src/cc/haoran/dydl/MainActivity.java \
  src/cc/haoran/dydl/SettingsActivity.java \
  src/cc/haoran/dydl/DownloadService.java \
  src/cc/haoran/dydl/FloatBallService.java \
  src/cc/haoran/dydl/ClipboardWatcherService.java \
  "$OUT/gen/cc/haoran/dydl/R.java"

echo "==> 4/7 d8 转 dex"
"$BT/d8.bat" --release --lib "$PLATFORM" \
  --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

echo "==> 5/7 classes.dex 注入 APK"
python - "$OUT/base.apk" "$OUT/dex/classes.dex" << 'PYEOF'
import sys, zipfile
base, dex = sys.argv[1], sys.argv[2]
data = open(dex, 'rb').read()
with zipfile.ZipFile(base, 'a', zipfile.ZIP_DEFLATED) as apk:
    apk.writestr('classes.dex', data)
print('classes.dex injected:', len(data), 'bytes')
PYEOF

echo "==> 6/7 zipalign"
"$BT/zipalign.exe" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "==> 7/7 生成签名密钥并签名"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v -keystore "$KS" -storepass dydl2025 -alias dydl \
    -keypass dydl2025 -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=dy-dl, OU=hr, O=haoran.cc, L=Seoul, C=KR"
fi
"$BT/apksigner.bat" sign --ks "$KS" --ks-pass pass:dydl2025 \
  --key-pass pass:dydl2025 --out "$PROJ/dy-dl.apk" "$OUT/aligned.apk"
"$BT/apksigner.bat" verify --print-certs "$PROJ/dy-dl.apk" | head -4

ls -la "$PROJ/dy-dl.apk"
echo "BUILD OK: dy-dl.apk"
