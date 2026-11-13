#!/bin/bash
# Build ArcVeePN from this source tree.
# Needs: Android SDK build-tools 34 (aapt2, d8, zipalign, apksigner)
# and JDK 17. Set ANDROID_SDK and JAVA_HOME, or edit the paths below.
# Signing: set KEYSTORE to your own keystore, or leave it unset to
# generate a throwaway debug keystore (do NOT ship with it).

set -e
export JAVA_HOME=~/workspace/apk-build/tools/jdk-17.0.21+9
export PATH=$JAVA_HOME/bin:~/workspace/apk-build/android-sdk/build-tools/34.0.0:~/workspace/apk-build/android-sdk/platform-tools:$PATH
A=~/workspace/vpeen-android-singbox
SDK=~/workspace/apk-build/android-sdk
BT=$SDK/build-tools/34.0.0
OUT=$A/build/out-sb
rm -rf $OUT && mkdir -p $OUT/compiled $OUT/classes
# compile app res (mipmap icons + values)
$BT/aapt2 compile --dir $A/res -o $OUT/res.zip
ls $OUT/res.zip
# aapt2 link: assets at root (index.html + icon.jpg), NOT under www/
# --java emits the generated R class (the manifest package is
# com.vpeen.android again since sb15, the same as the Java sources,
# so R lives in com.vpeen.android and no import is needed to
# reference drawables, e.g. the sb12 notification small icon).
$BT/aapt2 link -o $OUT/app_linked.apk \
  --manifest $A/AndroidManifest.xml \
  -I $SDK/platforms/android-34/android.jar \
  --version-code 37 --version-name 2.5 \
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
rm -rf /tmp/vpeen_sb_lib && mkdir -p /tmp/vpeen_sb_lib/lib/arm64-v8a
cp $A/lib/arm64-v8a/*.so /tmp/vpeen_sb_lib/lib/arm64-v8a/
# libbox.so DEFLATED (plain zip -r, sb1 pattern): with
# extractNativeLibs=true the installer extracts it, so the
# compressed entry is fine and the APK stays ~18 MB.
(cd /tmp/vpeen_sb_lib && zip -q -r $OUT/app_unsigned.apk lib)
cp $OUT/classes.dex /tmp/vpeen_sb_lib/classes.dex
(cd /tmp/vpeen_sb_lib && zip -q $OUT/app_unsigned.apk classes.dex)
rm -rf /tmp/vpeen_sb_lib
$BT/zipalign -f 4 $OUT/app_unsigned.apk $OUT/app_aligned.apk
if [ -z "$KEYSTORE" ]; then
  KEYSTORE=$A/build/debug.keystore
  if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -v -keystore "$KEYSTORE" -storepass android -alias debug -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=ArcVeePN Debug" >/dev/null 2>&1
  fi
fi
$BT/apksigner sign --v1-signing-enabled true --v2-signing-enabled true --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android --out $OUT/ArcVeePN-2.5.apk $OUT/app_aligned.apk
$BT/apksigner verify --verbose $OUT/ArcVeePN-2.5.apk
# Verify libbox.so is really Deflated in the SIGNED apk, then deliver.
unzip -v $OUT/ArcVeePN-2.5.apk | grep libbox.so
unzip -v $OUT/ArcVeePN-2.5.apk | grep "lib/arm64-v8a/libbox.so" | grep -q "Defl" || { echo "FAIL: libbox.so is not Deflated"; exit 1; }
echo "LIBBOX DEFLATED OK"
$BT/aapt2 dump badging $OUT/ArcVeePN-2.5.apk | head -n 30
ls -lh $OUT/ArcVeePN-2.5.apk
cp $OUT/ArcVeePN-2.5.apk ~/workspace/your_files/ArcVeePN-2.5.apk
echo "DELIVERED: ~/workspace/your_files/ArcVeePN-2.5.apk"
sha256sum ~/workspace/your_files/ArcVeePN-2.5.apk

