# Source offer and licences (GPL)

Packs are built from unmodified upstream tarballs pinned in `tools/sources.lock` (URL and SHA-256), with our patches under `tools/patches/` and the build scripts under `tools/build/`. Anyone can rebuild with `tools/fetch-sources.sh` and `tools/build/build-all.sh`.

| Engine | Upstream | Version | Licence |
|---|---|---|---|
| SpliX | gitlab.com/ScumCoder/splix | svn315 snapshot `76268c4` | GPL-2.0 |
| Epson ESC/P-R | Debian orig of epson-inkjet-printer-escpr | 1.8.8 | GPL-2.0+ |
| HPLIP | hplip.sourceforge.net | 3.25.6 | GPL/BSD/MIT mix |
| Gutenprint | gimp-print.sourceforge.net | 5.3.5 | GPL-2.0+ |
| CUPS (libcups only), jbigkit, libjpeg-turbo | upstream | see lock | Apache-2.0 / GPL-2.0 / BSD |

Confirm each tarball's COPYING before the first public release. Hosting binaries means offering the matching source: link this page from the catalogue site.
