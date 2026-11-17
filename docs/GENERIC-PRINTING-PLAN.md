# Plan: imprimir sin que importe la impresora ni el modelo

Estado: **plan, sin implementar**. Aplica al puente ESP32-S3 (`esp32-bridge/`, local) y a la app.
Complementa [`BLE-APP-PLAN.md`](BLE-APP-PLAN.md).

## 1. Idea central

**El puente nunca sabe qué impresora tiene.** Solo hace dos cosas genéricas:
pasar bytes por USB y contar quién es la impresora (Device ID, VID:PID, si habla IPP-USB).
Todo lo que depende del modelo vive en la app y en un **catálogo de datos actualizable**,
nunca en el firmware. Así, soportar una impresora nueva = actualizar datos, no reflashear.

Límite honesto: 100 % no existe. Hay impresoras "solo Windows" (GDI/host-based sin driver abierto: p. ej.
Canon CAPT/UFR II LT, varias Lexmark y Brother baratas) que ningún sistema libre puede manejar. El objetivo es
que **todo lo que tenga un camino abierto funcione solo**, y que lo que no, lo diga claro en vez de imprimir basura.

## 2. Los cuatro caminos (la app y el puente eligen solos)

| # | Camino | Para qué impresoras | Quién la usa | Depende del modelo |
|---|--------|---------------------|--------------|--------------------|
| 1 | **Driverless por IPP-USB**: el puente hace túnel de TCP 631 ↔ USB (HTTP sobre bulk) y la anuncia como impresora IPP | Casi todas las vendidas desde ~2015 con logo AirPrint/Mopria/IPP Everywhere (interfaz USB protocolo 4) | **Cualquiera sin app**: iPhone (AirPrint), Android (servicio de impresión del sistema), Windows 10/11, macOS, Linux. Y nuestra app | **No** |
| 2 | **Raw + motor elegido por Device ID** (lo que la app ya hace: escpr, gutenprint, hplip, splix) | ~3.258 modelos del catálogo (`docs/COVERAGE.md`) | App Android; Windows con el driver del fabricante (como el video) | Sí, pero resuelto por catálogo automático |
| 3 | **Raw + lenguaje universal** según `CMD` del Device ID: PDF, PostScript, PCL 5/6/3GUI, ESC/P2, ESC/POS, TSPL, ZPL, EPL, PWG | Láser de oficina, tickets, etiquetas, muchas inyección Epson, y cualquiera que declare su lenguaje | App Android/iOS con codificadores propios | **No** (solo el lenguaje) |
| 4 | **Asistente de prueba** para impresoras sin Device ID útil | Lo que queda: imprime 1 hoja de prueba por lenguaje candidato y el usuario dice cuál salió bien | App | Se aprende una vez y se recuerda |

El camino 1 es la mayor ganancia: convierte una impresora USB moderna en una impresora de red **estándar**, y
el iPhone imprime sin nuestra app.

## 3. Cadena de decisión (reemplaza a la de `DriverResolver`)

```
0. ¿Hay configuración aprendida para este VID:PID+MDL?            → usarla
1. ¿La impresora habla IPP (red, IPP-USB directo o vía puente)?   → pedir document-format-supported
     PDF → PDF · PWG raster → PWG · image/urf → URF (AirPrint)    → driverless
2. ¿El catálogo tiene el MFG+MDL (tras normalizar)?               → motor (escpr/gutenprint/hplip/splix)
3. ¿El CMD del Device ID nombra un lenguaje conocido?              → codificador universal (tabla §4)
4. ¿Fabricante conocido sin modelo? (Epson→ESC/P-R genérico, HP→PCL3GUI/PCL5, Brother/Samsung→PCL6, Zebra→ZPL, TSC→TSPL…)
                                                                   → candidato, marcado "probable"
5. ¿Está en la lista de no soportadas (GDI conocidas)?             → mensaje claro, no imprimir
6. Si no                                                           → asistente de prueba (§5)
```

Mejoras de identificación (hoy la coincidencia es exacta y frágil):
- **Normalizar fabricante**: `Hewlett-Packard`/`HP`, `EPSON`/`Seiko Epson`, `Brother Industries`… (tabla de alias).
- **Coincidencia de modelo tolerante**: quitar sufijos de serie/región ("Series", "-W", espacios, mayúsculas).
- **Sin Device ID** (algunas no responden GET_DEVICE_ID): usar VID:PID → fabricante con una tabla de IDs de
  impresoras (subconjunto de `usb.ids`), y nombre del descriptor USB (`iProduct`).
- El puente entrega estos datos **igual** por OTG, mDNS (`usb_MFG/MDL/CMD`) y BLE (Info): la app decide con la misma
  información sin importar por dónde se conecte.

## 4. Codificadores universales (camino 3)

Todos parten de la página ya rasterizada o del PDF, así que sirven para cualquier documento.

| Lenguaje | `CMD` típicos | Estado en la app | Trabajo |
|----------|---------------|------------------|---------|
| PDF / PWG raster | `PDF`, `PWG` | existe | — |
| PostScript | `POSTSCRIPT`, `PS` | existe | — |
| PCL 5 mono | `PCL`, `PCL5`, `PCLXL`? | existe | — |
| PCL 6 / PCL XL (raster) | `PCLXL`, `PCL6` | falta | Láser Brother/Samsung/HP modernas |
| PCL 3 GUI (inyección HP) | `PCL3GUI`, `PCL3` | falta | HP DeskJet/OfficeJet sin hplip |
| ESC/P2 raster | `ESCPL2`, `ESC/P2`, `ESCP2E` | falta (escpr cubre muchas) | Epson antiguas |
| ESC/POS | `ESC/POS`, `ESCPOS` | falta | Tickets 58/80 mm |
| TSPL | `TSPL`, `TSPL2` | falta | Etiquetas TSC y clones (la del video) |
| ZPL / EPL | `ZPL`, `EPL` | falta | Etiquetas Zebra y clones |
| URF (AirPrint) | vía IPP | falta | Para camino 1 cuando no hay PDF/PWG |

Cada codificador lleva un **test JVM con bytes exactos** sobre una imagen de 8×8 px.
Antes de cada trabajo raw se manda **PJL UEL** (`ESC %-12345X`) cuando la impresora declara PJL, para que
cambie de lenguaje sola.

## 5. Asistente de prueba (camino 4)

1. Candidatos ordenados por probabilidad (fabricante, clase USB, VID) y por **menos papel desperdiciado**
   (primero lenguajes que fallan "en silencio": PJL/PCL/PS; los de etiquetas solo si la impresora parece de etiquetas).
2. Cada prueba es **una** página/etiqueta con un texto grande: "Si lees esto, elige: PCL 6".
   Entre pruebas: reset (UEL + `ESC E`/soft reset de clase USB) para no dejar a la impresora a medias.
3. El usuario toca el lenguaje que salió bien → se guarda por `VID:PID + MDL`.
4. Botón "Compartir configuración" que exporta un JSON pequeño; si muchos coinciden, se agrega al catálogo.
5. Si nada funciona: mostrar "Esta impresora parece ser solo-Windows" con el log para reportar.

## 6. Particularidades como datos (`packages/driver-manifest/quirks.json`)

Una sola tabla por `VID:PID` (o MFG+MDL) que leen app **y** firmware:

| Campo | Lo usa | Ejemplo |
|-------|--------|---------|
| `firmware_upload` | puente (o app por OTG) | HP LaserJet 1018/1020/1022/P1005 → subir `sihp10xx.dl` al conectar |
| `reclaim_between_jobs` | puente / OTG | impresoras que se cuelgan sin re-claim |
| `drain_bulk_in` | puente / OTG | impresoras que se detienen si nadie lee el canal de vuelta |
| `needs_uel` | app | cambiar de lenguaje con PJL |
| `force_language` | app | modelos que mienten en `CMD` |
| `media_default` | app | etiquetas 50×25 mm, rollo 58 mm… |
| `unsupported_gdi` | app | mostrar mensaje en vez de imprimir |

El firmware solo aplica quirks de **nivel USB** (firmware_upload, reclaim, drain); se los pasa la app por BLE o
HTTP, o vienen en un pequeño archivo en la partición de datos. Nada del modelo queda compilado.

## 7. Qué tiene que hacer el puente para ser genérico

- Aceptar interfaz clase 7 con protocolo **1, 2, 3 (IEEE 1284.4) y 4 (IPP-USB)**; elegir la mejor alternativa
  disponible (preferir 4 para camino 1 y 2 para raw; si solo hay 1, unidireccional).
- **Túnel IPP-USB**: escuchar TCP 631, reenviar HTTP por el par bulk de la interfaz protocolo 4 (como el demonio
  `ipp-usb` de Linux), y anunciar `_ipp._tcp` + subtipo `_universal._sub._ipp._tcp` con TXT construido desde
  `Get-Printer-Attributes` de la propia impresora (`pdl`, `URF`, `Color`, `Duplex`, `rp=ipp/print`, `ty`).
  Así es AirPrint/Mopria sin que el puente entienda nada.
- Raw 9100 y BLE siempre disponibles (camino 2-4).
- Leer y **drenar Bulk IN**, exponer `GET_PORT_STATUS` y usar `SOFT_RESET` de clase para recuperarse.
- Reconocer y aplicar quirks USB (§6).
- Dar a la app toda la identidad: Device ID, VID:PID, `iManufacturer`/`iProduct`, protocolos disponibles.

## 8. Matriz final: qué funciona sin importar la impresora

| Cliente | Impresora moderna (IPP-USB) | Impresora vieja en catálogo | Láser/tickets/etiquetas por lenguaje | Desconocida |
|---------|-----------------------------|-----------------------------|----------------------------------------|-------------|
| iPhone **sin** app | ✅ AirPrint (camino 1) | ❌ | ❌ | ❌ |
| iPhone con app (BLE o Wi-Fi) | ✅ | ⚠️ ver §9 | ✅ codificadores puros | ✅ asistente |
| Android sin app | ✅ servicio de impresión del sistema | ❌ | ❌ | ❌ |
| Android con app (OTG, Wi-Fi o BLE) | ✅ | ✅ motores | ✅ | ✅ asistente |
| Windows / macOS | ✅ IPP | ✅ driver del fabricante + Raw 9100 (video) | ✅ driver del fabricante | según driver |

## 9. iOS: la limitación real

Los motores de la app (escpr, gutenprint, hplip, splix) son **GPL** y hoy solo existen para Android. Meter
código GPL en una app de la App Store choca con los términos de Apple. Opciones (decisión pendiente):
1. iOS usa solo caminos 1, 3 y 4 (codificadores propios, sin GPL). Cubre modernas, láser, tickets y etiquetas.
2. iOS se distribuye fuera de la App Store (TestFlight/sideload) con motores incluidos.
3. Para inyección antigua desde iPhone sin otra opción: imprimir desde Android/PC, o un puente más potente (no ESP32).

## 10. Fases

| Fase | Contenido | Necesita hardware |
|------|-----------|-------------------|
| G1 ✅ | Normalización de MFG/MDL, tabla VID→fabricante, nueva cadena de decisión con tests. Hecho: `driver/PrinterIdentity.kt`, `DriverResolver`, `Catalogue.match`, `Plan.blocker` en `PrintJob`, TXT `usb_*` en mDNS, `GenericResolverTest` (17 tests). El paso 0 (ajuste guardado) acepta un mapa pero aún no se persiste: eso llega con G4 | No |
| G2 ✅ (parcial) | Codificadores con tests de bytes. Hecho: `render/Raster.kt` (raster puro), `render/RasterEncoders.kt` con ESC/POS, TSPL, ZPL, PCL XL (códigos tomados de Ghostscript), URF (cabecera de CUPS) y envoltura PJL; tamaños de etiqueta y rollo en `PaperSize`; resolvedor y `PrintJob` conectados; `RasterEncodersTest` (9 tests). **Fuera por ahora:** PCL3GUI (lo cubre hplip; el formato es propietario) y EPL (polaridad de `GW` sin confirmar). **Sin probar en hardware:** polaridad TSPL, separación de etiqueta de 2 mm, corte ESC/POS, PCL XL | No |
| G3 ✅ (app) | Hecho: `packages/driver-manifest/quirks.json` (7 HP host-based por VID:PID y Canon CAPT, todo `verified:false`), `driver/Quirks.kt`, resolvedor (`unsupported`, `force_language`), `PrintJob` (`needs_pjl`, `media_default`), `UsbBulkSink` (`drain_bulk_in`), `transport/FirmwareUpload.kt` (sube firmware HP por OTG si el Device ID no trae `FWVER`), `QuirksTest` (7 tests). Formato documentado en `packages/driver-manifest/README.md`. **Falta:** que el firmware del puente lea el mismo archivo (va con H6/H7) y una pantalla para importar el archivo de firmware HP (hoy se copia a mano a la carpeta `firmware/` de la app) | No |
| G4 | Asistente de prueba + guardar/compartir configuración | Sí (1 impresora) |
| G5 | Firmware: protocolos 1-4, drenar Bulk IN, soft reset, túnel IPP-USB + mDNS AirPrint | Sí |
| G6 | Firmware: subida de firmware HP host-based | Sí (HP 1020 o similar) |
| G7 | Matriz de pruebas reales (§11) y reporte de cobertura por camino en `tools/coverage` | Sí |

## 11. Cómo se demuestra que es genérico

Una impresora de cada familia, por OTG, Wi-Fi y BLE, y desde iPhone/Windows sin app cuando aplique:
Epson inyección (escpr) · HP inyección (hplip/PCL3GUI) · HP láser PCL · HP host-based 1020 · Brother/Samsung láser (PCL6/splix) ·
Canon inyección (gutenprint) · impresora moderna con AirPrint por USB (camino 1) · ticket ESC/POS · etiquetas TSC (como el video) ·
una "desconocida" para el asistente. Se registra el resultado en `docs/HARDWARE-TEST.md`.
