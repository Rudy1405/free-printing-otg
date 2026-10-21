# How NokoPrint 5.28.4 prints (Wi-Fi and USB-OTG)

Target: `nokoprint-5-28-4.apk`, package `com.nokoprint`, SHA-256
`176dc443ed8f87579ef83180b4709a5ffbef767aec9a289fcfec382e32d3459e` (checked with REA `open_binary`).

Method: REA `open_binary` for identity. REA's Android providers (JADX MCP jar, apktool) are not configured here,
so the code was decompiled with jadx 1.5.6 (CLI) into a scratch folder. Class names below are the obfuscated names
in that output. All of it is static analysis; nothing was run. 790 methods failed to decompile; `discovery/o.run()`
had to be re-decompiled with `--show-bad-code`.

## Observed

| # | Finding | Evidence |
|---|---|---|
| 1 | USB host is declared and any class-7 (printer) device launches the app | `AndroidManifest.xml`: `uses-feature android.hardware.usb.host`; `ActivityUSB` handles `USB_DEVICE_ATTACHED` with `res/xml/usb_device_filter.xml` = `<usb-device class="7"/>` |
| 2 | USB discovery walks every interface, keeps class 7 with protocol 1/2 (bulk) or 4 (IPP-over-USB), and picks bulk IN/OUT endpoints | `discovery/o.java` (bad-code dump): `getInterfaceClass() == 7`, `getInterfaceProtocol()` tests, `getEndpoint` loop |
| 3 | It reads the IEEE-1284 device id with a class control request and parses `MFG/MFR/MANUFACTURER`, `MDL/MODEL`, `CMD/COMMAND SET` | `discovery/o.java`: `controlTransfer(161, 0, 0, (id << 8) + altSetting, buf, 1024, 3000)` |
| 4 | A USB printer becomes a pseudo mDNS record `<name>_<vid>_<pid>._pdl-datastream._usb.local.` with URI `usb://` (or `ipp-usb://` for protocol 4) | `discovery/o.java`; `ActivityCore.java` ~144116 branches on `_usb.`; `a0.java` parses vid/pid back out of that name |
| 5 | Printing over USB: ask `UsbManager` permission with action `NOKOPRINT_USB_PERMISSION`, `openDevice`, `claimInterface`, `setInterface` when alt setting > 0 | `core/a0.java` constructor |
| 6 | Data is written in 16 KiB bulk chunks (via `UsbRequest`, falling back to `bulkTransfer`); a zero-length packet is sent when the last chunk is a multiple of the endpoint max packet size | `core/z.java` `a(boolean)`; buffer is `Http2.INITIAL_MAX_FRAME_SIZE` (16384) |
| 7 | HP vendor 0x3F0 "Smart Install" printers (LaserJet 1000/1005/1018/1020, P100x, P1505…) need a firmware `.dl` file pushed over USB first, then a 5 s wait | `core/a0.java`: `getVendorId() != 1008`, `sihp1020.dl` etc. read from the driver pack dir |
| 8 | Wi-Fi discovery is multi-protocol: mDNS `_pdl-datastream`, `_printer`, `_ipp`, `_ipps`, plus Canon BJNP, SNMP, SMB sharing, Bluetooth | `discovery/*.java` (15 classes), `discovery/m.java` service-type list, `bjnp/`, `bt/`; `ActivityCore` connect test handles `ipp:// bjnp:// pdl:// lpd:// wprt:// usb:// tpl:// bluetooth://` |
| 9 | A printer is bound to a driver by a table of model rows per driver library | `core/b.java`: four tables headed `drv_escpr|1.8.5`, `drv_gutenprint|5.2.8-pre1s`, `drv_hplip|3.25.6`, `drv_splix|2.0.0s`. Counted rows: escpr 874, gutenprint 4299, hplip 2126, splix 197 |
| 10 | Drivers are **not in the APK**. They are native `.so` files downloaded at runtime from `https://www.nokoprint.com/android_packs/<pack>/<file>` (https first, http fallback, 3 retries), then `System.load`ed and driven over JNI | `ActivityCore.java` ~144440-144930; `core/DrvWrapper.java` (`System.load`, `doExec` returns stdin/stdout/stderr fds); `DrvWrapper_{escpr,gutenprint,hplip,splix}.java` have `native doExec/doWait/doDestroy`. The APK's own `lib/` holds only SDK libs (ads, analytics) |
| 11 | The same download mechanism fetches the PDF and Office renderers (`lib_pdfrender 8.0.3`, `lib_docsrender 3.8.1`, with fonts) | `v2.java`, `ActivityPrintDocs.java` |
| 12 | Wi-Fi/IPP path posts IPP; a system `PrintService` is also registered so other apps can print through it | manifest `core.NativePrintService` with `BIND_PRINT_SERVICE` |
| 13 | Driver downloading is user-visible: a "Drivers" screen exists | `ActivityDrivers` |

## Inferred (not directly observed)

- The driver libs are the open-source Epson ESC/P-R (epson-inkjet-printer-escpr), Gutenprint, HPLIP and SpliX
  code. The names and versions say so; the packs were not downloaded or inspected.
- Pipeline: document -> render to raster/PDF (downloaded renderer) -> driver filter (stdin) -> printer byte stream ->
  transport (`usb://` bulk writer, 9100, LPD, IPP). Stdin/stdout fds in `DrvWrapper.exec` support this reading.

## Unknowns

- Exact pack file names/hashes and whether the server signs them (no hash check seen in the download code that was read, ~144440-144600).
- Contents of the `.so` packs, and `discovery/o.run()` detail beyond what the bad-code dump shows.
- Smart Install handling for vendors other than HP.

## What this app (free-printing-otg) copies, and what it does differently

| NokoPrint | This app |
|---|---|
| mDNS + proprietary probes | mDNS (`_ipp`, `_ipps`, `_pdl-datastream`, `_printer`) via `NsdManager`. BJNP/SNMP/Bluetooth/SMB not yet |
| Class-7 USB scan, 1284 id via control request | Same (`UsbDiscovery`) |
| Bulk writer, 16 KiB, ZLP | Same (`UsbBulkSink`) |
| IPP-over-USB | `UsbIppChannel`, experimental, untested on hardware |
| Closed driver packs from nokoprint.com | **Not reused.** Own manifest (`packages/driver-manifest`) + SHA-256-verified downloads. Built-in languages: PDF, PWG raster, PostScript, PCL mono |
| Gutenprint/HPLIP/escpr/SpliX native JNI drivers | Not built yet. This is the main gap for USB-only inkjets without PDF/PWG/PCL support |
| HP Smart Install firmware push | Not built yet |
