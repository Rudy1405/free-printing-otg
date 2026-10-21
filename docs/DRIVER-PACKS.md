# NokoPrint driver packs: how they are delivered and what we can reuse

All findings are from `nokoprint-5-28-4.apk` (static) plus HTTP HEAD/GET requests against the same URLs the app uses.

## Delivery chain (observed)

`ActivityCore.J(packName, "name|version", allowDownload, ...)` (`ActivityCore.java` ~144376) tries three sources in order:

1. **APK asset**: `assets/pkg_<name>_<version with . and - as _>.zip`, e.g. `pkg_drv_escpr_1_8_5.zip`.
2. **Google Play Asset Delivery** pack (`pack_drivers`, `pack_docslib`, `pack_pdflib`).
3. **HTTP download**: `https://www.nokoprint.com/android_packs/<packName>/<same zip name>`, https first, then http, 3 retries, no auth header (only a User-Agent).

This direct-download APK already carries 5 packs in `assets/`:

| Asset | Size | Contents |
|---|---|---|
| `pkg_drv_escpr_1_8_5.zip` | 290 KB | `exec_{arm,arm_64,x86,x86_64}.txz` (each holds `libdrvescprJNI.so`), `data.txz` (empty) |
| `pkg_drv_gutenprint_5_2_8_pre1s.zip` | 1.7 MB | same 4 `exec_*.txz`, `data.txz` = Gutenprint `escp2/` model, ink, media, weave tables |
| `pkg_drv_hplip_3_25_6.zip` | 2.0 MB | same 4 `exec_*.txz`, `data.txz` = 874 HP `ppd/` files + `sihp*.dl` Smart Install firmware |
| `pkg_drv_splix_2_0_0s.zip` | 656 KB | same 4 `exec_*.txz`, `data.txz` = Samsung/Dell `ppd/` files |
| `pkg_lib_pdfrender_8_0_3.zip` | 11 KB | PDF renderer |

A pack is a zip of per-ABI executables plus shared data. The app picks the ABI at install time and `System.load`s the `.so`.

## Can we reach their catalogue? (verified)

- **Directory index:** `GET https://www.nokoprint.com/android_packs/` returns **403**. There is no public listing.
- **Individual packs:** `HEAD .../android_packs/pack_drivers/pkg_drv_escpr_1_8_5.zip` and `.../pkg_drv_hplip_3_25_6.zip` return **200** (`application/zip`, nginx). So packs are public if you know the exact name.
- **No integrity data:** no manifest, hash or signature is requested by the code I read. Version numbers are hard-coded in the APK (`core/b.java`).
- **The "map" of printer model -> driver is not on the server.** It is compiled into the APK: `core/b.java` holds 4 tables (escpr 874 rows, gutenprint 4,299, hplip 2,126, splix 197). Each row is `Model|options...|...|ppd or DeviceModel=`. A new driver version needs a new app release.

## Should we reuse their packs or catalogue?

Technically yes, **I recommend against it for our product**:

- The `.so` files are NokoPrint's own JNI builds with their private wrapper interface (`DrvWrapper_*`: `doExec/doWait/doDestroy`, stdin/stdout/stderr fds). Copying them to our host is redistribution of their binary; pointing our app at `nokoprint.com` uses their bandwidth and breaks when they rename or block it. I found no licence grant for either. Ask them if you want this route.
- The upstream engines are open source (Gutenprint GPL, HPLIP GPL/BSD/MIT mix, SpliX GPL, Epson escpr under its own licence; check each tarball). We can build the same engines ourselves and publish the source, which also meets the GPL terms we would otherwise have to meet.
- The model tables are derived from public upstream data (PPD files, Gutenprint XML, HPLIP `drivers.json`/`models.dat`, Epson lists), so we can generate our **own public map** and host it anywhere.

## Recommended plan

1. **Catalogue**: write a generator (`packages/driver-manifest/tools/`) that builds `catalogue.json` from upstream data: IEEE-1284 `MFG`/`MDL` (from PPD `*Manufacturer`, `*1284DeviceID`, HPLIP models) -> `{engine, ppd/model id, pack id}`. Host as a static file (GitHub Pages / Releases / any CDN). The app already reads `drivers.json`; extend it with the engine fields.
2. **Packs**: build `escpr`, `gutenprint`, `hplip`, `splix` for arm64-v8a, armeabi-v7a, x86_64 with the NDK; publish `pack-<engine>-<version>.zip` plus its SHA-256 in the catalogue (`DriverStore.install` already verifies it). Sign the catalogue too.
3. **Interface**: own small JNI/CLI contract (raster in, printer language out). Do not copy NokoPrint's `DrvWrapper` class names.
4. **Interim, local research only**: the 5 packs are in the APK's `assets/` and can be unzipped locally to study the layout (done into the scratch folder). Do not ship them.

## Open questions

- Licence/permission from NokoPrint for reuse of their binaries (not asked).
- Exact upstream licences per engine for the Android builds.
- Whether the packs' native entry points need anything beyond `doExec`; the `.so` files were listed, not disassembled.
