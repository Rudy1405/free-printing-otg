#!/usr/bin/env bash
# Build every engine for every ABI.  Usage: build-all.sh [abi...]
set -euo pipefail
cd "$(dirname "$0")"
ABIS="${*:-arm64-v8a armeabi-v7a x86_64}"
for abi in $ABIS; do
  for e in splix escpr hplip gutenprint; do
    echo "== $e $abi"; ./build-$e.sh "$abi" 2>&1 | tail -1
  done
done
