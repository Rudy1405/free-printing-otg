#!/usr/bin/env bash
# libcups.a (PPD + raster APIs the filters use) for one Android ABI.
set -euo pipefail
. "$(dirname "$0")/env.sh" "${1:-arm64-v8a}"
SRC="$WORK/cups"
if [ ! -f "$OUT/libcups.a" ]; then
  extract "$SOURCES/cups-2.4.14-source.tar.gz" "$SRC" cups-android.patch
  cd "$SRC"
  ./configure --host="$TRIPLE" --with-tls=no --disable-gssapi --disable-dbus --without-dnssd \
    --without-rcdir --disable-shared --disable-libusb --disable-pam --with-components=libcups >"$WORK/cups-configure.log" 2>&1
  make -C cups -j"$JOBS" libcups.a >"$WORK/cups-make.log" 2>&1
  mkdir -p "$OUT/include"
  cp cups/libcups.a "$OUT/libcups.a"
  cp -r cups/*.h "$OUT/include/" 2>/dev/null || true
  mkdir -p "$OUT/include/cups"; cp cups/*.h "$OUT/include/cups/"; cp config.h "$OUT/include/cups/config.h"
fi
echo "libcups ok: $OUT/libcups.a"
