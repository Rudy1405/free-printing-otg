# Hardware test guide

Goal: confirm (1) a Wi-Fi Epson prints, (2) an old Epson with no Wi-Fi prints over a USB-OTG cable. Neither has been run on a device yet.

## Prepare

1. Install the APK (CI artifact `free-printing-otg-debug-apk`, or `apps/android/gradlew assembleDebug`). Allow "install unknown apps".
2. **Driver server** (needed for the USB-only printer; Wi-Fi/IPP printers do not need it). The repo is private, so serve the local `dist/` from the PC:
   ```
   python -m http.server 8000 --directory dist
   ```
   Phone and PC on the same Wi-Fi: in the app, "Driver server" = `http://<PC LAN IP>:8000`, tap **Save driver server**.
   With USB debugging instead: `adb reverse tcp:8000 tcp:8000`, then use `http://localhost:8000`.
   (`dist/` is built by the `driver-packs` workflow or `tools/build/build-all.sh`; the CI APK has no built-in URL.)
3. OTG: phone must support USB host, use an OTG adapter plus the printer's USB-B cable. Power the printer on first.

## Test A: Epson over Wi-Fi

1. Phone on the same Wi-Fi/subnet as the printer (no guest-network "client isolation").
2. Open the app: the printer should appear within ~10 s ("IPP" / "RAW"). If not, type its IP and tap **Add (IPP 631)**.
3. Choose a JPG, then a PDF, then Print. Expected log: `Driverless PWG raster` or `Driverless PDF`.
4. Also try Duplex/Color off, Letter vs A4.

## Test B: old Epson over USB

1. Start the driver server (above). Plug in the cable; accept the Android USB permission prompt (the app asks on launch/attach).
2. The printer entry should read like `EPSON <model>` (read from the IEEE-1284 id). If it reads `USB printer 04b8:xxxx`, permission was denied or the id could not be read: tap Rescan.
3. Print a JPG. Expected log: `Driver: <model> (escpr)` (or gutenprint for very old models), `Downloading escpr driver…`, `Running escpr…`, `Sending N KiB…`.
4. If it fails, tap **Copy log** and send it. Typical causes:
   - `Driver catalogue unavailable`: server URL wrong / PC firewall.
   - `dlopen failed` / `no fpo_entry`: pack does not load on this Android version or ABI.
   - `escpr exited with N: ...`: the filter rejected the options or PPD for that model.
   - `USB transfer error`: cable/adapter, or printer not ready (paper out, error light).

## What to record per run

Phone model, Android version, ABI, printer model, transport, the Copy-log text, and whether paper came out (and whether it looked right: margins, colour, orientation).

## Known unknowns

- The native engine runner (`fork` + `dlopen` of the packs) has never run on a real device.
- Models resolved only through a Generic PPD may not print correctly.
- Old printers that need a Gutenprint PPD with custom options are driven with generic `media`/colour options only.
