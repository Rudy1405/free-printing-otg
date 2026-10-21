# Shared build environment. Source it: . tools/build/env.sh <abi>
# ABI: arm64-v8a | armeabi-v7a | x86_64
ABI="${1:-arm64-v8a}"
API=26
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS="$(cd "$HERE/.." && pwd)"
WORK="$HERE/work/$ABI"
OUT="$HERE/out/$ABI"
mkdir -p "$WORK" "$OUT"

case "$ABI" in
  arm64-v8a)   TRIPLE=aarch64-linux-android;   CLANG_TRIPLE=aarch64-linux-android ;;
  armeabi-v7a) TRIPLE=arm-linux-androideabi;   CLANG_TRIPLE=armv7a-linux-androideabi ;;
  x86_64)      TRIPLE=x86_64-linux-android;    CLANG_TRIPLE=x86_64-linux-android ;;
  *) echo "unknown ABI $ABI" >&2; return 1 ;;
esac

if [ -z "${ANDROID_NDK:-}" ]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$LOCALAPPDATA/Android/Sdk}}"
  ANDROID_NDK="$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | grep -v 30\. | tail -1)"
fi
case "$(uname -s)" in
  MINGW*|MSYS*) HOST_TAG=windows-x86_64; EXT=.cmd; PRE="$ANDROID_NDK/prebuilt/windows-x86_64/bin" ;;
  *)            HOST_TAG=linux-x86_64;   EXT="";   PRE="" ;;
esac
TC="$ANDROID_NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
export PATH="$TC${PRE:+:$PRE}:$PATH"
export PATH="$HERE/shim:$PATH"
export CONFIG_SHELL=/usr/bin/sh
export CC="${CLANG_TRIPLE}${API}-clang$EXT" CXX="${CLANG_TRIPLE}${API}-clang++$EXT"
export AR=llvm-ar RANLIB=llvm-ranlib STRIP=llvm-strip
export SOURCES="$TOOLS/sources" PATCHES="$TOOLS/patches"
JOBS="${JOBS:-8}"

# extract <archive> <dest-dir> [patch]: fresh copy of a source tree, optionally patched
extract() {
  rm -rf "$2"; mkdir -p "$2"
  tar --force-local -xf "$1" -C "$2" --strip-components=1
  if [ -n "${3:-}" ]; then (cd "$2" && patch -p1 < "$PATCHES/$3"); fi
}
