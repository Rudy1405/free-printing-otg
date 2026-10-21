#!/usr/bin/env bash
# One-time: user-space (no root) Linux toolchain inside WSL, used for host-side steps
# (generating PPDs with ppdc / cups-genppd). Not needed on CI, where apt installs these.
set -euo pipefail
D=$HOME/dev; mkdir -p $D/lists/partial $D/cache/archives/partial $D/debs $D/root
O="-o Dir::State::lists=$D/lists -o Dir::Cache=$D/cache -o Debug::NoLocking=1"
cd $D/debs
PK=$(apt-cache $O depends --recurse --no-recommends --no-suggests --no-conflicts --no-breaks --no-replaces --no-enhances --no-pre-depends \
     build-essential libtool pkg-config zlib1g-dev flex bison gettext autoconf automake m4 | grep '^\w' | sort -u)
: > need.txt
for p in $PK; do dpkg -s "$p" >/dev/null 2>&1 || echo "$p" >> need.txt; done
wc -l need.txt
xargs -a need.txt apt-get $O download 2>&1 | tail -2 || true
for f in *.deb; do dpkg -x "$f" $D/root; done
ls $D/root/usr/bin | grep -E '^(gcc|g\+\+|make|ld|as)$' || true

# --- wrappers so ./configure sees a working compiler without root ---
mkdir -p $D/bin
for t in gcc g++ cpp; do
  real=$D/root/usr/bin/$t
  cat > $D/bin/$t <<W
#!/bin/sh
R=$D/root
export LD_LIBRARY_PATH=\$R/usr/lib/x86_64-linux-gnu:\$R/lib/x86_64-linux-gnu:\$LD_LIBRARY_PATH
exec $real --sysroot=\$R -B\$R/usr/lib/gcc/x86_64-linux-gnu/9 -B\$R/usr/bin -isystem \$R/usr/include/x86_64-linux-gnu "\$@"
W
  chmod +x $D/bin/$t
done
for f in make ld as ar ranlib strip nm autoconf automake autoreconf libtoolize m4 pkg-config flex bison; do
  [ -e $D/root/usr/bin/$f ] && ln -sf $D/root/usr/bin/$f $D/bin/$f
done
# lib symlinks into the sysroot (system libc etc. live outside the extracted debs)
for d in lib/x86_64-linux-gnu usr/lib/x86_64-linux-gnu; do
  mkdir -p $D/root/$d
  for f in /$d/*; do b=$(basename "$f"); [ -e "$D/root/$d/$b" ] || ln -s "$f" "$D/root/$d/$b"; done
done
[ -e $D/root/lib64 ] || ln -s /lib64 $D/root/lib64
echo "host toolchain: add $D/bin to PATH"
