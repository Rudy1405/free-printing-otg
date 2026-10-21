#!/usr/bin/env bash
# Run inside WSL (or any Linux with gcc): build the CUPS `ppdc` PPD compiler for the host.
set -euo pipefail
. "$(dirname "$0")/wsl-env.sh"
HERE="$(cd "$(dirname "$0")" && pwd)"
W=$HERE/work/host; mkdir -p $W/cups $HERE/out/host
tar -xzf $HERE/../sources/cups-2.4.14-source.tar.gz -C $W/cups --strip-components=1
cd $W/cups
./configure --with-tls=no --disable-gssapi --disable-dbus --without-dnssd --disable-shared --disable-libusb --disable-pam --without-rcdir >$W/cups-configure.log 2>&1
make -C cups -j8 libcups.a >$W/cups-make.log 2>&1
make -C ppdc -j8 ppdc ppdhtml ppdi ppdmerge ppdpo >>$W/cups-make.log 2>&1 || make -C ppdc ppdc >>$W/cups-make.log 2>&1
cp ppdc/ppdc $HERE/out/host/ppdc
ls -la $HERE/out/host/ppdc
