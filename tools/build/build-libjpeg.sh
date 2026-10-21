#!/usr/bin/env bash
# libjpeg-turbo static (libjpeg.a + headers) for one ABI. HPLIP's hpcups needs the libjpeg 6b API.
set -euo pipefail
. "$(dirname "$0")/env.sh" "${1:-arm64-v8a}"
if [ ! -f "$OUT/libjpeg.a" ]; then
  SRC="$WORK/libjpeg-turbo"; extract "$SOURCES/libjpeg-turbo-3.0.4.tar.gz" "$SRC"
  CM="${CMAKE:-$(ls -d "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$LOCALAPPDATA/Android/Sdk}}"/cmake/*/bin | sort -V | head -1)}"
  export PATH="$CM:$PATH"
  cmake -S "$SRC" -B "$WORK/libjpeg-build" -G Ninja -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-$API -DENABLE_SHARED=OFF -DENABLE_STATIC=ON -DWITH_JPEG8=OFF \
    -DWITH_TURBOJPEG=OFF -DWITH_SIMD=OFF -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON >"$WORK/libjpeg-cmake.log" 2>&1
  cmake --build "$WORK/libjpeg-build" >"$WORK/libjpeg-build.log" 2>&1
  mkdir -p "$OUT/jpeg-include"
  cp "$WORK/libjpeg-build/libjpeg.a" "$OUT/libjpeg.a"
  cp "$SRC"/jpegint.h "$SRC"/jpeglib.h "$SRC"/jmorecfg.h "$SRC"/jerror.h "$WORK/libjpeg-build/jconfig.h" "$OUT/jpeg-include/"
fi
echo "libjpeg ok: $OUT/libjpeg.a"
