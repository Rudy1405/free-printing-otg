# Source inside WSL after wsl-setup.sh: host gcc/make/autotools from the user-space sysroot.
export PATH=$HOME/dev/bin:$PATH
export LD_LIBRARY_PATH=$HOME/dev/root/usr/lib/x86_64-linux-gnu:$HOME/dev/root/lib/x86_64-linux-gnu:${LD_LIBRARY_PATH:-}
export ACLOCAL_PATH=$HOME/dev/root/usr/share/aclocal
