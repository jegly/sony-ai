#!/bin/bash
# Builds Ghosts for Sony PMCA cameras (runs on the A7 II's Android 4.1).
#   ANDROID_SDK_ROOT  needs platforms/android-10, build-tools 36.0.0 and an NDK
#   SONY_STUBS        compile-only jar of the com.sony.scalar API (from OpenMemories-Framework stubs)
# The model (assets/yolo11n-seg.bin) comes from tools/lower.py.
set -e
cd "$(dirname "$0")"

SDK=${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}
BT=$SDK/build-tools/36.0.0
ANDROID_JAR=$SDK/platforms/android-10/android.jar
NDK=${ANDROID_NDK:-$(ls -d $SDK/ndk/* | tail -1)}
CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang
STUBS=${SONY_STUBS:?set SONY_STUBS to the Sony stub jar}
NAME=Ghosts
KEYSTORE=debug.keystore

[ -f assets/yolo11n-seg.bin ] || { echo "missing assets/yolo11n-seg.bin (run tools/lower.py)"; exit 1; }
rm -rf build && mkdir -p build/gen build/classes build/dex build/apk/lib/armeabi-v7a

# Native engine. The camera's CPU is a Cortex-A5 with VFPv4 and no NEON; its old Android linker needs a
# SysV hash table and no packed relocations.
"$CC" --target=armv7a-linux-androideabi21 -O3 -fPIC -mfpu=vfpv4-d16 -ffp-contract=fast -std=c99 -Wall \
  -I ../engine -shared -Wl,--hash-style=both -Wl,--pack-dyn-relocs=none \
  ../engine/yseg.c jni/yseg_jni.c -landroid -lm -o build/apk/lib/armeabi-v7a/libyseg.so

if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -alias debug -keyalg RSA -keysize 2048 -validity 10000 \
    -keystore "$KEYSTORE" -storepass android -keypass android -dname "CN=Ghosts Debug"
fi

"$BT/aapt" package -f -m -J build/gen -M AndroidManifest.xml -S res -I "$ANDROID_JAR"
find build/gen src -name '*.java' > build/sources.txt
javac -source 8 -target 8 -Xlint:-options -nowarn -encoding UTF-8 \
  -bootclasspath "$ANDROID_JAR" -cp "$STUBS" -d build/classes @build/sources.txt
"$BT/d8" --min-api 10 --release --lib "$ANDROID_JAR" --classpath "$STUBS" \
  --output build/dex $(find build/classes -name '*.class')
"$BT/aapt" package -f -0 bin -M AndroidManifest.xml -S res -A assets -I "$ANDROID_JAR" -F build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
(cd build/apk && zip -q -0 ../unsigned.apk lib/armeabi-v7a/libyseg.so)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
# Old Android only understands v1 (JAR) signatures.
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled false --v3-signing-enabled false \
  --min-sdk-version 10 --out "$NAME.apk" build/aligned.apk
echo "built $NAME.apk ($(stat -c %s "$NAME.apk") bytes)"
