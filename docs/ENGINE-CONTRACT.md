# Engine pack contract (our own interface)

Replaces NokoPrint's `DrvWrapper_*` JNI classes; no NokoPrint names or binaries are used.

## Pack zip: `packs/<engine>-<version>-<abi>.zip`
- `driver.json`: `{ "engine", "version", "library", "entry": "fpo_entry", "env": { NAME: value } }`. `${PACK}` in a value is replaced by the install directory (Gutenprint: `STP_DATA_PATH=${PACK}/data/xml`).
- `libfpo_<engine>.so`: one shared library per ABI (`arm64-v8a`, `armeabi-v7a`, `x86_64`). Only `fpo_entry` is exported (`tools/build/shim/exports.map`).
- Engine data files next to it (Gutenprint XML under `data/xml/`).

## Calling convention
`int fpo_entry(int argc, char **argv)` is the engine's CUPS filter `main()`:
- `argv` = `name job user title copies options`
- stdin: CUPS raster v2 / PWG raster (`RaS2`, 1796-byte page headers). The app reuses `Encoders.pwgRaster`.
- stdout: printer-ready bytes (PCL, ESC/P-R, SPL/QPDL, ...); stderr: diagnostics.
- Environment: `PPD=<file>`, `TMPDIR`, `HOME`, plus `env` from `driver.json`.

The app (`src/main/cpp/runner.c`) `dlopen`s the library, forks, `dup2`s three pipes onto fds 0-2 and calls the entry. This avoids `exec` from the app data dir, which Android 10+ blocks. Unpacked files are read-only (Android 14 requirement).

## Catalogue: `catalogue.json`
`engines.<id>.abis.<abi> = { file, sha256, size }` and `printers[] = { engine, file (ppd .gz), sha256, manufacturer, model, mfg, mdl, cmd[], generic?, experimental? }`. The app checks every download against these hashes. `generic` entries are chosen from the printer's IEEE-1284 `CMD` list when no model matches. `experimental` marks PPDs cloned from a sibling model (SpliX aliases) or rewritten (CLP/CLX queues moved from `pstoqpdl` to `rastertoqpdl`).

## Hosting
`dist/` is a static site (GitHub Pages or Releases). Build the app with `-PcatalogueUrl=<base url>`. Local test: `python -m http.server -d dist 8000` and `-PcatalogueUrl=http://10.0.2.2:8000`.
