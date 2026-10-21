#!/usr/bin/env bash
# Compile HPLIP's hpcups.drv.in into PPD files with the host ppdc (built by host-ppdc.sh). Run in WSL/Linux.
set -euo pipefail
. "$(dirname "$0")/wsl-env.sh"
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC=$HERE/work/hplip-src; rm -rf $SRC; mkdir -p $SRC
tar -xzf $HERE/../sources/hplip-3.25.6.tar.gz -C $SRC --strip-components=1
OUT=$HERE/out/hplip-ppd; rm -rf $OUT; mkdir -p $OUT
CUPS=$HERE/work/host/cups
"$HERE/out/host/ppdc" -d $OUT -I $CUPS/data -I $SRC/prnt/drv $SRC/prnt/drv/hpcups.drv.in
ls $OUT | wc -l
