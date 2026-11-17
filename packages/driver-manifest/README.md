# driver-manifest

Shared description of printer drivers. The Android app bundles `drivers.json` as an asset and can
also fetch a newer copy from a configured URL.

Entry fields:

| field | meaning |
| --- | --- |
| `id` | stable id, also the install directory name |
| `language` | output language: `PDF`, `PWG_RASTER`, `POSTSCRIPT`, `PCL_MONO`, `PCL_XL`, `URF`, `ESC_POS`, `TSPL`, `ZPL` |
| `match.mfg` | IEEE-1284 `MFG` values (case-insensitive) |
| `match.mdl` | regex tested against IEEE-1284 `MDL` |
| `match.cmd` | IEEE-1284 `CMD` languages the entry applies to |
| `url`, `sha256` | downloadable pack. No `url` means the driver is built into the app. A pack is installed only if its SHA-256 matches. |

An entry must set `match.mfg` or `match.mdl`; `match.cmd` alone does not match.

## quirks.json

Per-printer exceptions, shared by the app and the ESP32 bridge firmware (docs/GENERIC-PRINTING-PLAN.md §6).
Adding a printer's odd behaviour means editing this file, not code. Whoever owns the USB link (the app over
OTG, the bridge otherwise) applies the USB fields.

| field | meaning |
| --- | --- |
| `id` | stable id, unique |
| `match.usb` | `"vvvv:pppp"` USB ids (hex). When set, only these ids match |
| `match.mfg`, `match.mdl` | manufacturer (aliases such as Hewlett-Packard/HP are equal) and model regex |
| `firmware_upload` | USB: firmware file a host-based printer needs after every power-on. Not shipped; the user copies it to the app's `firmware/` folder |
| `reclaim_between_jobs` | USB: claim the interface again for each job (the app already opens one connection per job) |
| `drain_bulk_in` | USB: keep reading the bulk IN endpoint while sending |
| `needs_pjl` | wrap PCL/PostScript in PJL even if the Device ID does not list PJL |
| `force_language` | a `language` value to use instead of what the printer reports (driverless IPP still wins) |
| `media_default` | `PaperSize` used when a sheet size is left selected on a label/receipt printer |
| `unsupported` | message shown instead of printing |
| `verified` | `true` once tested on the real printer |

An entry must match something (`usb`, `mfg` or `mdl`); the app refuses entries that do not.
