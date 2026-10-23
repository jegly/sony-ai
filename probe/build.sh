#!/bin/bash
# Builds the AI probe for Sony PMCA cameras.
#   ANDROID_SDK_ROOT  needs platforms/android-10, build-tools 36.0.0 and an NDK
#   SONY_STUBS        compile-only jar of the com.sony.scalar API
set -e
cd "$(dirname "$0")"

SDK=${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}
BT=$SDK/build-tools/36.0.0
ANDROID_JAR=$SDK/platforms/android-10/android.jar
NDK=${ANDROID_NDK:-$(ls -d $SDK/ndk/* | tail -1)}
CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang
STUBS=${SONY_STUBS:?set SONY_STUBS to the Sony stub jar}
NAME=AIProbe
KEYSTORE=debug.keystore

rm -rf build && mkdir -p build/gen build/classes build/dex build/apk/lib/armeabi-v7a build/assets

# Native lib. The camera runs Android 4.1, so: SysV hash table, no packed relocations,
# and plain VFP for the C file (NEON only in the file that's run after checking cpuinfo).
CFLAGS="--target=armv7a-linux-androideabi21 -O2 -fPIC"
"$CC" $CFLAGS -mfpu=vfpv3-d16 -c jni/probe.c -o build/probe.o
"$CC" $CFLAGS -mfpu=neon -c jni/probe_neon.c -o build/probe_neon.o
"$CC" $CFLAGS -O3 -mfpu=vfpv4-d16 -ffp-contract=fast -DNAME=gemm_fma -c jni/gemm.c -o build/gemm_fma.o
"$CC" $CFLAGS -O3 -mfpu=vfpv4-d16 -ffp-contract=off -DNAME=gemm_mla -c jni/gemm.c -o build/gemm_mla.o
"$CC" $CFLAGS -shared -Wl,--hash-style=both -Wl,--pack-dyn-relocs=none \
  build/probe.o build/probe_neon.o build/gemm_fma.o build/gemm_mla.o -o build/apk/lib/armeabi-v7a/libprobe.so

mkdir -p build/assets/libs
cp build/apk/lib/armeabi-v7a/libprobe.so build/assets/libs/

if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -alias debug -keyalg RSA -keysize 2048 -validity 10000 \
    -keystore "$KEYSTORE" -storepass android -keypass android -dname "CN=AI Probe Debug"
fi

"$BT/aapt" package -f -m -J build/gen -M AndroidManifest.xml -S res -I "$ANDROID_JAR"
find build/gen src -name '*.java' > build/sources.txt
javac -source 8 -target 8 -Xlint:-options -nowarn -encoding UTF-8 \
  -bootclasspath "$ANDROID_JAR" -cp "$STUBS" -d build/classes @build/sources.txt
"$BT/d8" --min-api 10 --release --lib "$ANDROID_JAR" --classpath "$STUBS" \
  --output build/dex $(find build/classes -name '*.class')
"$BT/aapt" package -f -0 so -0 bin -M AndroidManifest.xml -S res -A build/assets -I "$ANDROID_JAR" -F build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
(cd build/apk && zip -q -0 ../unsigned.apk lib/armeabi-v7a/libprobe.so)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
# Old Android only understands v1 (JAR) signatures.
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled false --v3-signing-enabled false \
  --min-sdk-version 10 --out "$NAME.apk" build/aligned.apk
echo "built $NAME.apk ($(stat -c %s "$NAME.apk") bytes)"
