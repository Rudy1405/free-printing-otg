# free-printing-otg

Monorepo for a printing app family modelled on NokoPrint: find printers on Wi-Fi, print over a USB-OTG
cable, pick a compatible driver (or a generic one), and print documents and images.

```
apps/android/            Android app (Kotlin, Compose)  <- first app
packages/driver-manifest shared driver catalogue (drivers.json)
docs/RE-FINDINGS.md      evidence of how NokoPrint 5.28.4 works
```

## Build (Android)

Needs JDK 17+ and the Android SDK (platform 35). From `apps/android`:

```
gradle testDebugUnitTest assembleDebug
```

Set `sdk.dir` in `apps/android/local.properties`. There is no Gradle wrapper checked in yet; run
`gradle wrapper` once with a local Gradle 9.x.

## What works

- Discovery: mDNS (IPP, IPPS, raw 9100, LPD) and USB printer class with IEEE-1284 id.
- Delivery: IPP, raw 9100, LPD, USB bulk, IPP-over-USB (experimental).
- Formats in: JPG, PNG, GIF, WEBP, BMP, HEIC (device dependent), PDF, TXT, HTML (as text). Share menu, Open-with and file picker.
- Formats out: PDF (passthrough or wrapped), PWG raster, PostScript, PCL 5 monochrome.
- Driver choice: printer-advertised PDF, then PWG raster, then manifest match on MFG/MDL, then IEEE-1284 `CMD`, then generic PCL.
- Verified: compiles, 4 unit tests pass (IEEE-1284 parsing, IPP codec, driver choice). **Not tested on a real printer or an OTG cable.**

## Not done yet

- Word/Excel/PowerPoint: needs a document converter; `UNSUPPORTED` is reported today.
- Native driver packs (Gutenprint, HPLIP, Epson ESC/P-R, SpliX) for USB-only printers. Pack download and hash check exist; the packs and loader do not.
- HP Smart Install firmware push, Canon BJNP, SNMP and Bluetooth discovery, Android system `PrintService`, print preview, page range.
- iOS/desktop apps (the `apps/` folder is ready for them).
