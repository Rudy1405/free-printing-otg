#!/usr/bin/env bash
# SpliX (Samsung SPL/QPDL laser printers) as libfpo_splix.so + data.zip with the PPDs.
set -euo pipefail
. "$(dirname "$0")/env.sh" "${1:-arm64-v8a}"
bash "$HERE/build-cups.sh" "$ABI" >/dev/null
CUPSOUT="$OUT"

# libjbig (JBIG1 compression, needed by CLP-600 class printers)
JB="$WORK/jbigkit"
extract "$SOURCES/jbigkit-2.1.tar.gz" "$JB"
( cd "$JB/libjbig" && $CC -O2 -c jbig.c jbig_ar.c jbig85.c && $AR rcs libjbig.a jbig.o jbig_ar.o jbig85.o )

SRC="$WORK/splix"
extract "$SOURCES/splix-76268c4dd7dbc8218ea7426401104c3b40cc707a.tar.bz2" "$SRC"
cd "$SRC/splix"
FILES="rastertoqpdl request printer qpdl document core compress algorithm ppdfile page colors band bandplane cache rendering semaphore algo0x0d algo0x0e algo0x11 algo0x13 algo0x15"
OBJS=""
for f in $FILES; do
  [ "$f" = rastertoqpdl ] && DEF="-Dmain=fpo_filter_main" || DEF=""
  $CXX -std=gnu++14 -O2 -w -fPIC -fvisibility=hidden -DTHREADS=2 -DCACHESIZE=30 $DEF \
    -Iinclude -I"$CUPSOUT/include" -I"$JB/libjbig" -c "src/$f.cpp" -o "$WORK/splix-$f.o"
  OBJS="$OBJS $WORK/splix-$f.o"
done
$CXX -x c++ -O2 -fPIC -c "$HERE/shim/fpo_entry.c" -o "$WORK/fpo_entry.o"
$CXX -shared -o "$OUT/libfpo_splix.so" $OBJS "$WORK/fpo_entry.o" "$CUPSOUT/libcups.a" "$JB/libjbig/libjbig.a" \
  -static-libstdc++ -lz -lm -llog -Wl,--version-script="$HERE/shim/exports.map" -Wl,--gc-sections -Wl,--no-undefined
$STRIP --strip-unneeded "$OUT/libfpo_splix.so"

mkdir -p "$OUT/splix-data/ppd" && cp ppd/*.ppd "$OUT/splix-data/ppd/"
ls -la "$OUT/libfpo_splix.so"
