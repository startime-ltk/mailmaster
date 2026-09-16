#!/usr/bin/env bash
# 邮箱大师 · 安卓端手动/CI 构建脚本
# 用法:
#   本地:  ANDROID_HOME=/path/to/sdk bash build_apk.sh
#   CI:   环境变量 ANDROID_HOME 由 workflow 注入
set -e

# 工程根目录 = 本脚本所在目录，保证无论从哪调用都正确
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="$SCRIPT_DIR"
LIBS="$APP/libs"

# Android SDK 位置，允许外部注入（GitHub Actions 会注入）
ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
BT="$ANDROID_HOME/build-tools/34.0.0"
AAPT2="$BT/aapt2"
ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"

# 前置检查
for f in "$AAPT2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$ANDROID_JAR"; do
  if [ ! -e "$f" ]; then
    echo "✗ 缺少构建依赖: $f"
    echo "  请先安装 Android SDK (build-tools;34.0.0 与 platforms;android-34)"
    echo "  export ANDROID_HOME=你的sdk路径 后重试"
    exit 1
  fi
done

# 清理旧产物（含历史 apk，避免校验时选错文件）
rm -f "$APP/base.apk" "$APP/aligned.apk" "$APP/classes.dex" "$APP"/MailMaster*.apk
rm -rf "$APP/dexout" "$APP/out" "$APP/gen" "$APP/compiled_res.zip" "$APP/.buildtmp"

# ---- 版本号：唯一数据源是 AndroidManifest，产出的 apk 文件名自动带上版本号 ----
MANIFEST="$APP/AndroidManifest.xml"
# 支持外部注入（CI 常用 github.run_number 让 versionCode 每次构建自动递增）
if [ -n "$APP_VERSION_CODE" ] || [ -n "$APP_VERSION" ]; then
  mkdir -p "$APP/.buildtmp"
  cp "$MANIFEST" "$APP/.buildtmp/AndroidManifest.xml"
  MANIFEST="$APP/.buildtmp/AndroidManifest.xml"
  [ -n "$APP_VERSION_CODE" ] && \
    sed -i "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$APP_VERSION_CODE\"/" "$MANIFEST"
  [ -n "$APP_VERSION" ] && \
    sed -i "s/android:versionName=\"[^\"]*\"/android:versionName=\"$APP_VERSION\"/" "$MANIFEST"
fi
VERSION=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$MANIFEST" | head -1)
VCODE=$(sed -n 's/.*android:versionCode="\([^"]*\)".*/\1/p' "$MANIFEST" | head -1)
[ -z "$VERSION" ] && VERSION="unknown"
DEBUG_NAME="MailMaster-v${VERSION}-debug.apk"
RELEASE_NAME="MailMaster-v${VERSION}.apk"
echo "版本: v$VERSION (versionCode ${VCODE:-?})"

echo "== 1. 编译资源 =="
"$AAPT2" compile --dir "$APP/res" -o "$APP/compiled_res.zip"

echo "== 2. 链接资源 + 生成 R.java =="
"$AAPT2" link -o "$APP/base.apk" -I "$ANDROID_JAR" \
  --manifest "$MANIFEST" \
  -R "$APP/compiled_res.zip" \
  --java "$APP/gen" --auto-add-overlay

echo "== 3. 编译 Java =="
mkdir -p "$APP/out"
# -encoding UTF-8 必须显式写：源码里有大量中文注释与中文字符串，
# 不同机器上 javac 的默认编码不一致会导致乱码甚至编译失败
javac -encoding UTF-8 -source 1.8 -target 1.8 \
  -cp "$ANDROID_JAR:$LIBS/android-mail-1.6.2.jar:$LIBS/android-activation-1.6.2.jar" \
  -d "$APP/out" \
  $(find "$APP/gen" "$APP/src" -name '*.java')

echo "== 4. d8 转 dex =="
mkdir -p "$APP/dexout"
"$BT/d8" --lib "$ANDROID_JAR" --output "$APP/dexout" \
  $(find "$APP/out" -name '*.class') \
  "$LIBS/android-mail-1.6.2.jar" \
  "$LIBS/android-activation-1.6.2.jar"

echo "== 5. 把 dex 加进 base.apk（根目录 classes.dex）=="
cp "$APP/dexout/classes.dex" "$APP/classes.dex"
zip -d "$APP/base.apk" 'classes*.dex' 'dexout/*' >/dev/null 2>&1 || true
cd "$APP" && zip -g base.apk classes.dex && cd -

echo "== 6. zipalign =="
"$BT/zipalign" -p 4 "$APP/base.apk" "$APP/aligned.apk"

echo "== 7. 签名 =="
# 优先使用仓库内/环境变量提供的 keystore（release），否则用调试 keystore
if [ -n "$KEYSTORE_PATH" ] && [ -f "$KEYSTORE_PATH" ]; then
  echo "  使用 release keystore: $KEYSTORE_PATH"
  "$BT/apksigner" sign --ks "$KEYSTORE_PATH" \
    --ks-key-alias "${KEY_ALIAS:-androiddebugkey}" \
    --ks-pass env:KEYSTORE_PASS --key-pass env:KEY_PASS \
    --out "$APP/$RELEASE_NAME" "$APP/aligned.apk"
else
  echo "  未提供 keystore，使用调试 keystore"
  if [ ! -f "$APP/debug.keystore" ]; then
    keytool -genkeypair -v -keystore "$APP/debug.keystore" -alias androiddebugkey \
      -keyalg RSA -keysize 2048 -validity 10000 \
      -storepass android -keypass android \
      -dname "CN=MailMaster, OU=Dev, O=MailMaster, L=Jinan, ST=Shandong, C=CN" >/dev/null 2>&1
  fi
  "$BT/apksigner" sign --ks "$APP/debug.keystore" \
    --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android \
    --out "$APP/$DEBUG_NAME" "$APP/aligned.apk"
fi

echo "== 8. 校验 =="
OUT_APK=$(ls "$APP"/MailMaster*.apk | head -1)
"$BT/apksigner" verify --verbose "$OUT_APK"
echo "APK 大小: $(wc -c < "$OUT_APK") bytes"
echo "产出: $OUT_APK"
