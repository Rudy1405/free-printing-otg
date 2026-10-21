#!/usr/bin/env bash
# Gutenprint (many inkjet, laser and dye-sub models) as libfpo_gutenprint.so + data (XML tables).
# configure only generates config.h and gutenprint.h; objects are compiled directly because libtool
# misbehaves with the NDK .cmd wrappers on Windows.
set -euo pipefail
. "$(dirname "$0")/env.sh" "${1:-arm64-v8a}"
bash "$HERE/build-cups.sh" "$ABI" >/dev/null

SRC="$WORK/gutenprint"
if [ ! -f "$SRC/config.h" ]; then
  extract "$SOURCES/gutenprint-5.3.5.tar.xz" "$SRC" ${GUTENPRINT_PATCH:-}
  ( cd "$SRC" && CONFIG_SHELL=/usr/bin/sh /usr/bin/sh ./configure --host="$TRIPLE" --disable-nls --without-gimp2 \
      --disable-libgutenprintui2 --without-ijs --disable-shared --enable-static --disable-testpattern --without-doc \
      --disable-test --without-cups >"$WORK/gutenprint-configure.log" 2>&1 )
fi
cd "$SRC"
CFLAGS="-O2 -w -fPIC -fvisibility=hidden -std=gnu99 -DHAVE_CONFIG_H -D_POSIX_C_SOURCE=200809L -Disfinite=finite"
CFLAGS="$CFLAGS -DPACKAGE_DATA_DIR=\"/data\" -DPKGXMLDATADIR=\"/data/xml\" -DPKGMODULEDIR=\"/data/modules\" -DPACKAGE_LOCALE_DIR=\"/data/locale\" -DSBINDIR=\"/\""
INC="-I. -Iinclude -Isrc/main -Isrc/cups -I$OUT/include"
LIBOBJS=""
for f in array bit-ops buffer-image channel color-conversions color curve-cache curve dither-ed dither-eventone dither-inks \
         dither-main dither-ordered dither-predithered dither-very-fast escp2-channels escp2-driver escp2-papers escp2-resolutions \
         generic-options image module mxml-attr mxml-file mxml-node mxml-search path print-canon print-color print-dither-matrices \
         print-dpl print-dyesub print-escp2-data print-escp2 print-lexmark print-list print-papers print-pcl print-ps print-raw \
         print-util print-vars print-version print-weave printers refcache sequence string-list xml xmlppd; do
  o="$WORK/gp-$f.o"; $CC $CFLAGS $INC -c "src/main/$f.c" -o "$o"; LIBOBJS="$LIBOBJS $o"
done
for f in rastertogutenprint i18n; do
  o="$WORK/gp-cups-$f.o"; EXTRA=""; [ $f = rastertogutenprint ] && EXTRA="-Dmain=fpo_filter_main"
  $CC $CFLAGS $EXTRA $INC -include "$HERE/shim/iconv_stub.h" -c "src/cups/$f.c" -o "$o"; LIBOBJS="$LIBOBJS $o"
done
$CC $CFLAGS -c "$HERE/shim/iconv_stub.c" -o "$WORK/gp-iconv.o"; LIBOBJS="$LIBOBJS $WORK/gp-iconv.o"
$CC -O2 -fPIC -c "$HERE/shim/fpo_entry.c" -o "$WORK/fpo_entry.o"
$CC -shared -o "$OUT/libfpo_gutenprint.so" $LIBOBJS "$WORK/fpo_entry.o" "$OUT/libcups.a" \
  -lz -lm -llog -Wl,--version-script="$HERE/shim/exports.map" -Wl,--gc-sections -Wl,--no-undefined
$STRIP --strip-unneeded "$OUT/libfpo_gutenprint.so"

# data: the XML tables libgutenprint reads at run time (STP_DATA_PATH)
rm -rf "$OUT/gutenprint-data"; mkdir -p "$OUT/gutenprint-data"
cp -r src/xml "$OUT/gutenprint-data/xml"
find "$OUT/gutenprint-data" \( -name 'Makefile*' -o -name '*.in' -o -name '*.o' -o -name '.deps' \) -prune -exec rm -rf {} +
ls -la "$OUT/libfpo_gutenprint.so"
