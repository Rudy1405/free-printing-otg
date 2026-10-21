# Sub plan: build our own driver packs

Goal: same printer coverage as NokoPrint 5.28.4, shipped as our own packs, hosted on GitHub, downloaded by the app on demand.
Background and reasons: `docs/DRIVER-PACKS.md`. NokoPrint's own binaries are **not** used (see Pending action below).

## What has to be matched

NokoPrint ships four engines. Sizes are table rows in `core/b.java` and include a few non-model rows (paper sizes), so
treat them as an upper bound; step 1 measures real model counts.

| Engine | NokoPrint version | Rows | Mostly covers | Upstream source (found) | Licence (to confirm per tarball) |
|---|---|---|---|---|---|
| Epson ESC/P-R | `escpr 1.8.5` | 874 | Epson inkjets (Epson ~614 rows) | Epson Linux driver download center (`download.ebz.epson.net`, OS "LX"); ALT Linux SRPM `epson-inkjet-printer-escpr-1.8.5-alt1` (Mar 2024); GitHub mirror of 1.8.6 `vmartins/epson-inkjet-printer-escpr`; Debian carries 1.8.8 | GPL-2.0-or-later per ALT Linux package |
| Gutenprint | `5.2.8-pre1s` | 4,299 | Epson, HP, Canon, plus many PCL/PS laser brands (Xerox, Kyocera, Ricoh, Lanier, Infotec) | Gutenprint on SourceForge; pre1 listed on OSDN (2011-12-27); Debian/Ubuntu `gutenprint_5.2.8~pre1` orig tarballs | GPL |
| HPLIP | `3.25.6` | 2,126 | HP inkjets and lasers (863 HP rows) | `developers.hp.com/hp-linux-imaging-and-printing`; distro source packages (SUSE, ALT 3.25.6-alt3.2) | GPL/BSD/MIT mix |
| SpliX | `2.0.0s` | 197 | Samsung (70), Xerox (20), Dell SPL printers | `splix.sourceforge.net` (2.0.0, 6 Feb 2009); GitLab snapshot `76268c4` used by SlackBuilds; openSUSE `splix-2.0.0.tar.bz2`; Ubuntu `2.0.0+svn315` | GPL |

Unknown: what the trailing `s` means in `5.2.8-pre1s` and `2.0.0s` (likely NokoPrint patches); the `.so` files were not disassembled.
Newer upstream releases (Gutenprint 5.3.x, escpr 1.8.8, HPLIP 3.26.x) should be a superset. Recommendation: build the newest stable of each, then check coverage.
None of these links was downloaded or checksum-verified yet.

## Steps

1. **Target list.** Extract the model names from NokoPrint's 4 tables into `tools/coverage/target-models.txt` (internal test target, not shipped). Split real models from paper-size rows. Done when we have an exact per-engine count.
2. **Fetch sources.** `tools/fetch-sources.sh` downloads each upstream tarball from its official URL, pins the SHA-256 in `tools/sources.lock`, and records the licence file. Done when all four verify.
3. **Engine builds (NDK).** Cross-compile for `arm64-v8a`, `armeabi-v7a`, `x86_64`. Order: SpliX (smallest), escpr, Gutenprint, HPLIP.
   - Gutenprint, HPLIP and escpr are autotools and CUPS-filter oriented. We need only the raster-to-printer-language filter, not CUPS. Expect to build a small static wrapper around `libgutenprint`, HPLIP's `hpcups`/`hpijs`-style raster code, `epsonepl`/`escpr` filter and SpliX `rastertospl`.
   - Risk: HPLIP's printer code depends on CUPS headers. Plan is to stub the CUPS raster API (`cups/raster.h`) with a tiny compatible library.
4. **Own interface.** One contract for every engine: a small executable or JNI library that reads raster (our own PWG-raster-like input) on stdin and writes printer bytes on stdout. Packs expose `driver.json` (engine, supported models, options). This replaces NokoPrint's `DrvWrapper`; no NokoPrint class names are reused.
5. **Catalogue generator.** `packages/driver-manifest/tools/gen-catalogue.*` builds `catalogue.json` from upstream data (PPDs and `*1284DeviceID`, Gutenprint XML, HPLIP model files, Epson model lists). Entry: `MFG`/`MDL` -> `{engine, model id, pack}`. The app's `drivers.json` format grows these fields.
6. **Packaging.** Per engine and ABI: `pack-<engine>-<version>-<abi>.zip` + data zip. SHA-256 goes into the catalogue.
7. **Hosting and CI.** GitHub Actions workflow builds all packs, attaches them to a Release, and publishes `catalogue.json` (Releases or Pages). The app downloads only from this base URL. The GPL source offer (links to the exact tarballs and our patches) lives in the same repo.
8. **App changes.** Pack loader (load library or spawn executable), ABI selection, catalogue refresh, per-printer driver install prompt, the existing `DriverStore.install` hash check.
9. **Coverage check.** For every row in `target-models.txt`, assert the catalogue resolves it to a pack. Report gaps per engine. Anything missing goes to a "missing packages" list; if it stays missing, use the pending action below.
10. **Hardware test.** At least one Epson, one HP, one Samsung/Xerox and one Gutenprint-only printer over USB and Wi-Fi.

Order of delivery: 1, 2, SpliX through 3-6-7 as a vertical slice, then the other three engines, then 8-10.

## Status (2026-10-20)

Steps 1, 2, 3, 4 (`docs/ENGINE-CONTRACT.md`), 5, 6, 8 (app: `Catalogue`, `EngineStore`, `NativeRunner`) and 9 are done; 7 is a draft workflow, not pushed; 10 not started. Versions actually used: Gutenprint 5.3.5 (not 5.2.8-pre1s), escpr 1.8.8 (1.8.5 not found), HPLIP 3.25.6, SpliX svn315 snapshot (2.0.0 covered only 44/94 models). Coverage: `docs/COVERAGE.md`.

## Pending action (do not lose)

- **Ask NokoPrint for permission to reuse their driver packs** if our own builds fail or leave gaps. The user already sent them an email (status: no reply yet). Until they answer yes in writing, do not download their packs for hosting.
  - Technical note if they agree: their `.so` files only load as `com.nokoprint.core.DrvWrapper_<engine>` (entry points `Java_com_nokoprint_core_DrvWrapper_1<engine>_doExec/doWait/doDestroy`), so reuse would also need that class name.

## Open questions

- Per-engine licence of each tarball, and what GPL obligations apply to hosting binaries.
- Whether `s` builds differ from upstream, and what NokoPrint patched.
- Which of the 3 ABIs we need (x86 builds were in NokoPrint's packs; likely only for emulators).
