# Missing points (take later)

Status of things not done in the first Android build. Source: README "Not done yet" and `docs/RE-FINDINGS.md`.

## Pending actions
- [ ] Wait for NokoPrint's answer to the permission email (sent by the user). Only if our own packs fail or leave gaps, reuse theirs. See `docs/OWN-PACKS-PLAN.md`.
- [x] Own driver packs built for 4 engines x 3 ABIs; catalogue covers 3,258 of NokoPrint's 3,268 models (`docs/COVERAGE.md`). Not yet run on a device.
- [ ] Hardware test of each engine (none run): Epson, HP, Samsung/Xerox and a Gutenprint-only printer, USB and Wi-Fi
- [ ] Make the GitHub repo public and enable Pages (the workflow's deploy step fails with 404 until then), then set `-PcatalogueUrl=https://rudy1405.github.io/free-printing-otg`. Decided: only after the hardware tests pass. Repo `Rudy1405/free-printing-otg` is private; CI builds everything and keeps `dist/` as a 30-day artifact.
- [ ] Choose the app licence and add `LICENSE` (packs are GPL, see `docs/SOURCES.md`). Same gate: after hardware tests, before going public.
- [ ] 10 Gutenprint models from NokoPrint's `5.2.8-pre1s` that 5.3.5 dropped or renamed (Brother HL-1430/2030/2035, Canon i50/i900, Epson EP 801F, PX 401, Stylus Photo S50, Pro 4600/11600)
- [ ] SpliX alias PPDs (M2070, M267x/M287x, ML-1670..1866W, ML-191x/252x, ML-331x, SCX-4623, SCX-4x21/4650) are cloned from sibling models; verify on hardware
- [ ] Newer HP models need HP's closed ImageProcessor, stubbed on ARM: they will not print through hpcups
- [ ] Generic PCL 5c/5e entries are HPLIP LaserJet PPDs (ljcolor/ljmono); Generic Epson ESC/P-R is the XP-420 PPD; Generic PostScript Level 2 is sent as plain PostScript

## Documents and formats
- [ ] Word / Excel / PowerPoint printing (needs a converter pack; today reported as unsupported)
- [ ] HTML rendered as a web page (today HTML is flattened to plain text)
- [ ] Web page printing through a built-in browser
- [ ] Print preview, page range, multiple images per sheet, borderless photo, tray and paper-type options

## Drivers
- [ ] Native driver packs for USB-only inkjets: Gutenprint, HPLIP, Epson ESC/P-R, SpliX (download and hash check exist; packs and JNI loader do not)
- [ ] HP Smart Install firmware push (LaserJet 1000/1005/1018/1020, P100x, P1505...)
- [ ] Hosted public driver catalogue (see `docs/DRIVER-PACKS.md`)

## Discovery and transport
- [ ] Canon BJNP, SNMP, WS-Discovery and Bluetooth discovery
- [ ] SMB / Windows shared printers
- [ ] Manual add by IP address
- [ ] IPP-over-USB tested on hardware (currently experimental)

## Platform
- [ ] Android system PrintService (print from any app's Print menu)
- [ ] Test on real printers: Wi-Fi IPP, raw 9100, USB bulk with OTG cable
- [ ] Gradle wrapper checked in
- [ ] App icon, localization (Spanish first), settings screen
- [ ] iOS and desktop apps under `apps/`
