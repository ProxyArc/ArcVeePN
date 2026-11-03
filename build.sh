#!/bin/bash
set -e
export JAVA_HOME=~/workspace/apk-build/tools/jdk-17.0.21+9
export PATH=$JAVA_HOME/bin:~/workspace/apk-build/android-sdk/build-tools/34.0.0:~/workspace/apk-build/android-sdk/platform-tools:$PATH
A=~/workspace/vpeen-android
SDK=~/workspace/apk-build/android-sdk
BT=$SDK/build-tools/34.0.0
OUT=$A/build/out
rm -rf $OUT && mkdir -p $OUT/compiled $OUT/classes
# compile app res (mipmap icons + values)
$BT/aapt2 compile --dir $A/res -o $OUT/res.zip
ls $OUT/res.zip
# aapt2 link: assets at root (index.html + icon.jpg), NOT under www/
$BT/aapt2 link -o $OUT/app_linked.apk \
  --manifest $A/AndroidManifest.xml \
  -I $SDK/platforms/android-34/android.jar \
  --version-code 8 --version-name 1.7 \
  --auto-add-overlay \
  -A $A/assets \
  $OUT/res.zip
echo "LINK OK"
CP=$SDK/platforms/android-34/android.jar:$A/lib/hev.jar
find $A/src -name "*.java" > $OUT/sources.txt
cat $OUT/sources.txt
javac -encoding UTF-8 -cp "$CP" -d $OUT/classes @$OUT/sources.txt
echo "JAVAC OK"
mkdir -p $OUT/dex
(cd $OUT/classes && jar cf $OUT/app-classes.jar .)
$BT/d8 --min-api 24 --output $OUT/dex $OUT/app-classes.jar $A/lib/hev.jar
ls $OUT/dex
cp $OUT/app_linked.apk $OUT/app_unsigned.apk
cp $OUT/dex/classes.dex $OUT/classes.dex
mkdir -p /tmp/vpeen_lib/lib/arm64-v8a /tmp/vpeen_lib/lib/armeabi-v7a
cp $A/lib/arm64-v8a/*.so /tmp/vpeen_lib/lib/arm64-v8a/
cp $A/lib/armeabi-v7a/*.so /tmp/vpeen_lib/lib/armeabi-v7a/
(cd /tmp/vpeen_lib && zip -q -r $OUT/app_unsigned.apk lib)
cp $OUT/classes.dex /tmp/vpeen_lib/classes.dex
(cd /tmp/vpeen_lib && zip -q $OUT/app_unsigned.apk classes.dex)
rm -rf /tmp/vpeen_lib
$BT/zipalign -f 4 $OUT/app_unsigned.apk $OUT/app_aligned.apk
$BT/apksigner sign --v1-signing-enabled true --v2-signing-enabled true --ks YOUR_KEYSTORE --ks-pass pass:YOUR_KEYSTORE_PASSWORD --key-pass pass:YOUR_KEY_PASSWORD --out $OUT/ArcVeePN-1.7.apk $OUT/app_aligned.apk
$BT/apksigner verify --verbose $OUT/ArcVeePN-1.7.apk
$BT/aapt2 dump badging $OUT/ArcVeePN-1.7.apk | head -n 30
ls -lh $OUT/ArcVeePN-1.7.apk
