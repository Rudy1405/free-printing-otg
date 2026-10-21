#!/usr/bin/env bash
# Download upstream driver-engine sources and verify them against tools/sources.lock.
# First run with --pin records the SHA-256 values; later runs fail on any mismatch.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p sources
LOCK=sources.lock

# name|version|url   (cups is a build dependency: PPD and raster APIs the engines use)
ENGINES=(
  "splix|2.0.0-svn315|https://gitlab.com/ScumCoder/splix/-/archive/76268c4dd7dbc8218ea7426401104c3b40cc707a/splix-76268c4dd7dbc8218ea7426401104c3b40cc707a.tar.bz2"
  "hplip|3.25.6|https://downloads.sourceforge.net/project/hplip/hplip/3.25.6/hplip-3.25.6.tar.gz"
  "gutenprint|5.3.5|https://downloads.sourceforge.net/project/gimp-print/gutenprint-5.3/5.3.5/gutenprint-5.3.5.tar.xz"
  "escpr|1.8.8|https://deb.debian.org/debian/pool/main/e/epson-inkjet-printer-escpr/epson-inkjet-printer-escpr_1.8.8.orig.tar.gz"
  "cups|2.4.14|https://github.com/OpenPrinting/cups/releases/download/v2.4.14/cups-2.4.14-source.tar.gz"
  "jbigkit|2.1|https://www.cl.cam.ac.uk/~mgk25/jbigkit/download/jbigkit-2.1.tar.gz"
  "libjpeg-turbo|3.0.4|https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/3.0.4/libjpeg-turbo-3.0.4.tar.gz"
)

pin=false; [ "${1:-}" = "--pin" ] && pin=true
$pin && : > "$LOCK.new"
for e in "${ENGINES[@]}"; do
  IFS='|' read -r name ver url <<<"$e"
  file="sources/$(basename "$url")"
  [ -s "$file" ] || curl -fsSL -o "$file" "$url"
  sum=$(sha256sum "$file" | cut -d' ' -f1)
  if $pin; then
    echo "$name $ver $sum $(basename "$file") $url" >> "$LOCK.new"
  else
    want=$(awk -v n="$name" '$1==n{print $3}' "$LOCK")
    [ "$sum" = "$want" ] || { echo "MISMATCH $name: $sum != $want" >&2; exit 1; }
    echo "ok $name $ver"
  fi
done
if $pin; then mv "$LOCK.new" "$LOCK"; cat "$LOCK"; fi
