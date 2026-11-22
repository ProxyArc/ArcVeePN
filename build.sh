#!/bin/bash
# ArcVeePN 3.1 — build script.
# Package com.vpeen.android, versionCode 40, versionName 3.1.
# sing-box core (libbox). Two ABIs: arm64-v8a (default) and armeabi-v7a.
# Usage: ./build.sh [arm64-v8a|armeabi-v7a]
# Needs Android SDK 34 and JDK 17 — set JAVA_HOME and SDK below to
# your own install. Sign with your own keystore.
set -e
export JAVA_HOME=~/workspace/apk-build/tools/jdk-17.0.21+9
export PATH=$JAVA_HOME/bin:~/workspace/apk-build/android-sdk/build-tools/34.0.0:~/workspace/apk-build/android-sdk/platform-tools:$PATH
ABI=${1:-arm64-v8a}
case "$ABI" in
  arm64-v8a|armeabi-v7a) ;;
  *) echo "unknown ABI: $ABI (use arm64-v8a or armeabi-v7a)"; exit 1 ;;
esac
A="$(cd "$(dirname "$0")" && pwd)"
SDK=~/workspace/apk-build/android-sdk
BT=$SDK/build-tools/34.0.0
OUT=$A/build/out-$ABI
rm -rf $OUT && mkdir -p $OUT/compiled $OUT/classes
# compile app res (mipmap icons + values)
$BT/aapt2 compile --dir $A/res -o $OUT/res.zip
ls $OUT/res.zip
# aapt2 link: assets at root (index.html + icon.jpg), NOT under www/
# --java emits the generated R class (manifest package == Java
# package, so R lives in com.vpeen.android).
$BT/aapt2 link -o $OUT/app_linked.apk \
  --manifest $A/AndroidManifest.xml \
  -I $SDK/platforms/android-34/android.jar \
  --version-code 40 --version-name 3.1 \
  --auto-add-overlay \
  --java $OUT/gen \
  -A $A/assets \
  $OUT/res.zip
echo "LINK OK"
CP=$SDK/platforms/android-34/android.jar:$A/lib/libbox-classes.jar
find $A/src -name "*.java" > $OUT/sources.txt
find $OUT/gen -name "*.java" >> $OUT/sources.txt
cat $OUT/sources.txt
javac -encoding UTF-8 -cp "$CP" -d $OUT/classes @$OUT/sources.txt
echo "JAVAC OK"
mkdir -p $OUT/dex
(cd $OUT/classes && jar cf $OUT/app-classes.jar .)
$BT/d8 --min-api 24 --output $OUT/dex $OUT/app-classes.jar $A/lib/libbox-classes.jar
ls $OUT/dex
cp $OUT/app_linked.apk $OUT/app_unsigned.apk
cp $OUT/dex/classes.dex $OUT/classes.dex
rm -rf /tmp/vpeen_sb_lib && mkdir -p /tmp/vpeen_sb_lib/lib/$ABI
cp $A/lib/$ABI/*.so /tmp/vpeen_sb_lib/lib/$ABI/
# libbox.so DEFLATED: with
# extractNativeLibs=true the installer extracts it, so the
# compressed entry is fine and the APK stays ~16 MB.
(cd /tmp/vpeen_sb_lib && zip -q -r $OUT/app_unsigned.apk lib)
cp $OUT/classes.dex /tmp/vpeen_sb_lib/classes.dex
(cd /tmp/vpeen_sb_lib && zip -q $OUT/app_unsigned.apk classes.dex)
rm -rf /tmp/vpeen_sb_lib
$BT/zipalign -f 4 $OUT/app_unsigned.apk $OUT/app_aligned.apk
$BT/apksigner sign --v1-signing-enabled true --v2-signing-enabled true --ks YOUR_KEYSTORE --ks-pass pass:YOUR_KEYSTORE_PASSWORD --key-pass pass:YOUR_KEY_PASSWORD --out $OUT/ArcVeePN-3.1-$ABI.apk $OUT/app_aligned.apk
$BT/apksigner verify --verbose $OUT/ArcVeePN-3.1-$ABI.apk
# Verify libbox.so is really Deflated in the SIGNED apk.
unzip -v $OUT/ArcVeePN-3.1-$ABI.apk | grep libbox.so
unzip -v $OUT/ArcVeePN-3.1-$ABI.apk | grep "lib/$ABI/libbox.so" | grep -q "Defl" || { echo "FAIL: libbox.so is not Deflated"; exit 1; }
echo "LIBBOX DEFLATED OK"
$BT/aapt2 dump badging $OUT/ArcVeePN-3.1-$ABI.apk | head -n 30
ls -lh $OUT/ArcVeePN-3.1-$ABI.apk
