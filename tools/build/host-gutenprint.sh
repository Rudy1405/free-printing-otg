#!/usr/bin/env bash
# Native (WSL/Linux) build of Gutenprint: gives us cups-genppd to generate PPDs, and a reference for the data tree.
set -euo pipefail
. "$(dirname "$0")/wsl-env.sh"
HERE="$(cd "$(dirname "$0")" && pwd)"
W=$HERE/work/host-gutenprint; rm -rf $W; mkdir -p $W
tar -xJf $HERE/../sources/gutenprint-5.3.5.tar.xz -C $W --strip-components=1
cd $W
CUPSSRC=$HERE/work/host/cups
export CUPS_CONFIG=$CUPSSRC/cups-config
./configure --prefix=$HERE/out/host/gutenprint --disable-nls --without-gimp2 --disable-libgutenprintui2 \
  --without-ijs --disable-shared --enable-static --disable-testpattern --without-doc --disable-test \
  --with-cups=$CUPSSRC --disable-cups-1_2-enhancements >$W/configure.log 2>&1 || { tail -20 $W/configure.log; exit 1; }
make -j8 >$W/make.log 2>&1 || { tail -30 $W/make.log; exit 1; }
make install >$W/install.log 2>&1 || tail -10 $W/install.log
ls $HERE/out/host/gutenprint/sbin $HERE/out/host/gutenprint/lib 2>/dev/null | head

# Simplified PPDs for every model (the catalogue ships these; the engine reads STP_DATA_PATH at runtime)
GEN=$W/src/cups/cups-genppd.5.3
XML=$W/src/xml
rm -rf "$HERE/out/gutenprint-ppd"; mkdir -p "$HERE/out/gutenprint-ppd"
STP_DATA_PATH=$XML "$GEN" -q -s -p "$HERE/out/gutenprint-ppd"
ls "$HERE/out/gutenprint-ppd" | wc -l
