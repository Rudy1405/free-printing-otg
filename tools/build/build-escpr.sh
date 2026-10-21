#!/usr/bin/env bash
# Epson ESC/P-R (Epson inkjets) as libfpo_escpr.so + PPD data.
set -euo pipefail
. "$(dirname "$0")/env.sh" "${1:-arm64-v8a}"
bash "$HERE/build-cups.sh" "$ABI" >/dev/null

SRC="$WORK/escpr"
extract "$SOURCES/epson-inkjet-printer-escpr_1.8.8.orig.tar.gz" "$SRC" ${ESCPR_PATCH:-}
cd "$SRC"
mkdir -p "$WORK/escpr-inc"; cp "$HERE/shim/escpr-config.h" "$WORK/escpr-inc/config.h"
DEFS="-DGCOMSW_PRT_USE_USB -DGCOMSW_PRT_USE_LPR -DGCOMSW_PRT_USE_RAW -DGCOMSW_EL_CDLABEL -DGCOMSW_EF_MAINTE -DEPS_FILTER -DGCOMSW_ASYNCJOB_SUPPORT -DGCOMSW_RAWFILE_SUPPORT -DHAVE_CONFIG_H"
INC="-I$WORK/escpr-inc -Ilib -Isrc -I$OUT/include"
OBJS=""
for f in lib/epson-cbt lib/epson-escpr-api lib/epson-escpr-dbg lib/epson-escpr-mem lib/epson-escpr-pm \
         lib/epson-escpr-services lib/epson-layout lib/epson-net-lpr lib/epson-net-raw lib/epson-net-snmp \
         lib/epson-protocol lib/epson-usb src/filter src/debug src/str src/err src/mem src/custompage \
         src/linux_cmn src/xfifo; do
  o="$WORK/escpr-$(basename $f).o"
  EXTRA=""; [ "$f" = src/filter ] && EXTRA="-Dmain=fpo_filter_main"
  $CC -O2 -w -Wno-implicit-function-declaration -Wno-int-conversion -fcommon -fPIC -fvisibility=hidden $DEFS $EXTRA $INC -c "$f.c" -o "$o"
  OBJS="$OBJS $o"
done
$CC -O2 -fPIC -c "$HERE/shim/fpo_entry.c" -o "$WORK/fpo_entry.o"
$CC -shared -o "$OUT/libfpo_escpr.so" $OBJS "$WORK/fpo_entry.o" "$OUT/libcups.a" \
  -lz -lm -llog -Wl,--version-script="$HERE/shim/exports.map" -Wl,--gc-sections -Wl,--no-undefined
$STRIP --strip-unneeded "$OUT/libfpo_escpr.so"
mkdir -p "$OUT/escpr-data/ppd" && cp ppd/*.ppd "$OUT/escpr-data/ppd/"
ls -la "$OUT/libfpo_escpr.so"
