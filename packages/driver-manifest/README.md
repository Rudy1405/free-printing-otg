# driver-manifest

Shared description of printer drivers. The Android app bundles `drivers.json` as an asset and can
also fetch a newer copy from a configured URL.

Entry fields:

| field | meaning |
| --- | --- |
| `id` | stable id, also the install directory name |
| `language` | output language: `PDF`, `PWG_RASTER`, `POSTSCRIPT`, `PCL_MONO` |
| `match.mfg` | IEEE-1284 `MFG` values (case-insensitive) |
| `match.mdl` | regex tested against IEEE-1284 `MDL` |
| `match.cmd` | IEEE-1284 `CMD` languages the entry applies to |
| `url`, `sha256` | downloadable pack. No `url` means the driver is built into the app. A pack is installed only if its SHA-256 matches. |

An entry must set `match.mfg` or `match.mdl`; `match.cmd` alone does not match.
