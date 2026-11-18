# Plan: conexión por BLE al puente ESP32-S3 (Android, y base para iOS)

Estado: **fases A, B y C implementadas (app y firmware), sin probar en hardware.** Código: `ble/BridgeProtocol.kt`, `ble/BridgeClient.kt`, `transport/BleSink.kt`, `discovery/BleDiscovery.kt`, pestaña Bluetooth en `ui/Screens.kt`; firmware `esp32-bridge/firmware/src/ble_bridge.c`. Pendiente: fase D (L2CAP, medir velocidad) e iOS. El hardware/firmware del puente se planifica aparte (carpeta local
`esp32-bridge/`, ignorada por git).

Base: el video *"I Made a USB Printer Wireless with ESP32-S3"* (Tech Panda) y su firmware
[arslansadiq87/print-server-esp32-s3](https://github.com/arslansadiq87/print-server-esp32-s3).
Ese firmware es un servidor **RAW TCP 9100** que reenvía bytes a una impresora USB. Windows imprime con el
driver original agregando una "impresora TCP/IP, Raw, 9100". El nuestro hace lo mismo, añade BLE y
descubrimiento, y no está atado a un solo modelo.

## 1. Objetivo

Hoy la app imprime por IPP, raw 9100, LPD y USB-OTG. Se agrega una opción de **conectarse por Bluetooth
Low Energy a un puente ESP32-S3** (impresora USB conectada al chip). Con eso:

- **Android:** imprime sin cable OTG ni Wi-Fi compartido (el teléfono puede ni tener host USB).
- **iOS:** la futura app iOS (no existe aún; `apps/` ya está preparada) imprime a impresoras USB viejas,
  algo imposible por USB en iOS. AirPrint no cubre impresoras antiguas.
- BLE además sirve para **configurar el Wi-Fi** del puente; después, los trabajos pesados van por TCP 9100
  (ya soportado como `Transport.RAW_9100`).

No se usa Bluetooth Classic/SPP: el ESP32-S3 no lo tiene.

## 2. Hallazgos del análisis del repo

| Hecho | Archivo | Consecuencia |
|-------|---------|--------------|
| `Transport` es un enum cerrado: IPP, RAW_9100, LPD, USB_BULK, IPP_USB | `model.kt` | Añadir `BLE_BRIDGE` |
| `Printer` ya lleva `deviceId` (IEEE-1284) | `model.kt` | El puente expone el mismo Device ID → **la elección de driver existente funciona sin cambios** |
| `PrintSink` (`write/close`) abstrae el destino de bytes | `transport/Transports.kt` | Un `BleSink` encaja directo; no hay que tocar renderizado ni drivers |
| `PrintJob.openSink` decide el sink por transporte; ramas de engine usan "raw" para todo lo que no es IPP | `PrintJob.kt` | Añadir `BLE_BRIDGE -> BleSink(...)`; el resto del flujo (PWG/PCL/engines) no cambia |
| Lógica USB está atada a `printer.usb != null` (permiso, relectura de Device ID, pestañas UI) | `PrintJob.kt:33`, `MainActivity.kt:56`, `Screens.kt:130`, `Components.kt:109,213` | Generalizar con un helper `printer.isLocalCable`/`printer.isBle` en vez de `usb != null` |
| Descubrimiento separado por tipo (`NetworkDiscovery`, `UsbDiscovery`) | `discovery/` | Nuevo `BleDiscovery` con la misma forma |
| `minSdk 26`, sin permisos Bluetooth | `AndroidManifest.xml`, `app/build.gradle.kts` | Permisos por nivel de API (≤30 vs ≥31) y `uses-feature bluetooth_le required=false` |
| Solo tests unitarios JVM, sin instrumentación | `src/test` | Capa de protocolo en Kotlin puro para probarla sin teléfono |
| Si el puente ya tiene IP, el 9100 existente funciona hoy | `NetworkDiscovery.kt` | BLE debe **preferir Wi-Fi cuando hay IP** y usarse para lo que Wi-Fi no puede |
| `_pdl-datastream._tcp` se convierte en `RAW_9100` **sin `deviceId`**; solo lee `ty` y `pdl` del TXT | `NetworkDiscovery.kt:toPrinter` | Por Wi-Fi el puente caería en "PCL genérico". **Arreglo pequeño:** leer las claves TXT estándar `usb_MFG`, `usb_MDL`, `usb_CMD` y armar `DeviceId`. Sirve también para cualquier servidor de impresión que las anuncie |
| `Language` solo tiene PDF, PWG, PostScript, PCL mono | `driver/Drivers.kt` | Impresoras de etiquetas (TSPL/ZPL) y tickets (ESC/POS), como la del video, no tienen codificador → Fase E |

## 3. Realidad de rendimiento (para no sorprenderse)

GATT con *write without response*, MTU 247–512, DLE y (si hay) PHY 2M: **~20–100 KB/s** en la práctica,
muy variable por teléfono. Estimaciones por trabajo:

| Trabajo | Tamaño aprox. | Tiempo por BLE |
|---------|---------------|----------------|
| Texto / PCL monocromo A4 300 dpi | 50–500 KB | segundos |
| PDF ya "driverless" simple | 100 KB–2 MB | segundos–medio minuto |
| Foto color PWG/escpr A4 | 10–30 MB | **varios minutos** |
| Etiqueta TSPL/ZPL o ticket ESC/POS (texto/código QR) | 0.2–20 KB | **instantáneo** (el trabajo de prueba del video pesaba 159 bytes) |

Por eso:
1. **v1 = GATT** (portable, minSdk 26, igual en iOS).
2. **v2 = L2CAP CoC** (Android 10+/iOS 11+): flujo de bytes más rápido y sin créditos manuales.
3. UI honesta: antes de enviar por BLE, mostrar tamaño y tiempo estimado; si el puente ya está en Wi-Fi,
   ofrecer "Imprimir por Wi-Fi (rápido)".

## 4. Protocolo (fuente de verdad, compartida con el firmware)

Servicio GATT propio (UUID v4 generados una vez; **cambiar estos si colisionan**):

| Elemento | UUID | Propiedades |
|----------|------|-------------|
| Servicio `PrintBridge` | `9b7f0001-4f6e-4c1a-8d3b-5a2c7e1f0a10` | anunciado en publicidad |
| `Info` | `9b7f0002-…` | READ |
| `Control` | `9b7f0003-…` | WRITE + NOTIFY |
| `Data` | `9b7f0004-…` | WRITE_NO_RESPONSE |

**Info** (lectura, TLV o JSON corto UTF-8 ≤ 512 B):
`{"v":1,"fw":"0.1.0","id":"MFG:EPSON;MDL:...;CMD:...","vid":1208,"pid":4352,"usb_mfr":"EPSON","usb_product":"...","protos":[2,4],"ipp":true,"state":"ready|no_printer|busy|error","ip":"192.168.1.40","mtu":247}`

`vid/pid/usb_*` permiten identificar impresoras sin Device ID; `protos` e `ipp` dicen si existe el camino
driverless (túnel IPP-USB en el puerto 631). Ver `GENERIC-PRINTING-PLAN.md` §3.

**Control** (cada mensaje: `opcode u8` + payload; enteros little-endian):

| Op | Dirección | Payload | Significado |
|----|-----------|---------|-------------|
| `0x01 OPEN_JOB` | app→fw | `jobId u16, totalBytes u32` | Reserva la impresora |
| `0x02 END_JOB` | app→fw | `jobId u16` | Fin de datos (el fw añade el ZLP si procede) |
| `0x03 ABORT` | app→fw | `jobId u16` | Cancelar |
| `0x04 WIFI_SET` | app→fw | `ssidLen u8, ssid, passLen u8, pass` | Provisión de Wi-Fi |
| `0x05 GET_INFO` | app→fw | — | Fuerza notificación de Info |
| `0x81 CREDIT` | fw→app | `jobId u16, bytes u32` | "Puedes enviar N bytes más" (control de flujo) |
| `0x82 DONE` | fw→app | `jobId u16` | Todo salió por USB |
| `0x83 ERROR` | fw→app | `jobId u16, code u8` | `1 busy, 2 no_printer, 3 usb_error, 4 timeout, 5 overflow, 6 wifi_fail, 7 needs_pairing, 8 not_in_setup` |
| `0x84 WIFI_STATE` | fw→app | `state u8, ip[4]` | Resultado de provisión |
| `0x85 STATUS` | fw→app | `flags u8` | Estado de la impresora (USB GET_PORT_STATUS): bit0 sin papel, bit1 seleccionada, bit2 sin error, bit3 trabajo RAW 9100 en curso |

**Un solo dueño por trabajo** (regla del video, "no mezclar trabajos"): si un trabajo RAW 9100 está en curso,
`OPEN_JOB` responde `ERROR busy` y la app reintenta o avisa "Ocupado imprimiendo desde otro equipo".

**Data**: bytes crudos del trabajo, sin encabezado, trozos ≤ MTU−3. La app **nunca** excede los créditos
recibidos. El firmware cierra el trabajo por `END_JOB`, `ABORT`, desconexión BLE o 10 s sin datos.

Seguridad v1: el puente solo acepta trabajos de un cliente conectado. `WIFI_SET` exige dos cosas: enlace cifrado
(emparejamiento "Just Works" con LE Secure Connections; si no, `ERROR 7` y el puente pide emparejar) y modo de
configuración (AP de configuración activo o 5 minutos tras una pulsación corta de BOOT; si no, `ERROR 8`). Así nadie
cercano puede cambiar el Wi-Fi del puente. PIN de emparejamiento en v2.

**Créditos:** al abrir el trabajo el puente concede el tamaño de su anillo (24 KiB) y luego devuelve crédito a
medida que pasa bytes a USB (cada 2 KiB o al vaciarse). La app nunca tiene más bytes en vuelo que el anillo.

## 5. Cambios en la app Android

### Fase A — Protocolo puro y descubrimiento (sin hardware)
1. `ble/BridgeProtocol.kt` (Kotlin puro, **sin** dependencias Android): construir/parsear mensajes de
   Control, parsear Info, calcular troceo por MTU y contabilidad de créditos.
2. `model.kt`: `Transport.BLE_BRIDGE`; `data class BleRef(val address: String)`; campo
   `Printer.ble: BleRef? = null`.
3. `discovery/BleDiscovery.kt`: `BluetoothLeScanner` con filtro por UUID de servicio, emite
   `Printer(id="ble:<addr>", name=<nombre>, transport=BLE_BRIDGE, ble=…)`. Al seleccionar, conecta una vez,
   lee **Info** y rellena `deviceId` (para que `DriverResolver` funcione igual que con USB).
4. Permisos (`AndroidManifest.xml`):
   - `BLUETOOTH_SCAN` (con `neverForLocation`) y `BLUETOOTH_CONNECT` para API ≥ 31.
   - `BLUETOOTH` / `BLUETOOTH_ADMIN` `maxSdkVersion=30` y `ACCESS_FINE_LOCATION` `maxSdkVersion=30`.
   - `<uses-feature android:name="android.hardware.bluetooth_le" android:required="false"/>`.
   - Solicitud en tiempo de ejecución desde `MainActivity` con un texto que explique para qué.
5. Tests JVM: `BridgeProtocolTest` (codificación/decodificación, créditos, troceo, errores).
6. `NetworkDiscovery.toPrinter`: para `_pdl-datastream._tcp` (y `_printer._tcp`) construir
   `DeviceId` desde `usb_MFG`/`usb_MDL`/`usb_CMD` cuando existan. Test JVM con un mapa TXT de ejemplo.
   Con esto el puente **ya funciona por Wi-Fi con elección de driver correcta antes de tener BLE**.

### Fase B — Enviar bytes (`BleSink`)
1. `transport/BleSink.kt : PrintSink` con `BluetoothGatt`:
   - `connectGatt` → `requestMtu(247+)` → `discoverServices` → habilitar notificaciones en Control.
   - `write()` encola; un hilo escritor envía trozos con `WRITE_TYPE_NO_RESPONSE` respetando créditos;
     espera `onCharacteristicWrite`/cola vacía antes del siguiente (el stack Android pierde datos si se
     satura).
   - `close()` envía `END_JOB`, espera `DONE` (o `ERROR`) con timeout, desconecta.
   - Reintento único si la conexión cae antes de enviar el primer byte.
2. `PrintJob.kt`: `openSink` → `Transport.BLE_BRIDGE -> BleSink(ctx, p.ble!!, jobId, bytes.size)`.
   Como `PrintSink.write` es bloqueante y `PrintJob.print` es `suspend`, ejecutar en `Dispatchers.IO`.
3. Mostrar progreso real (`bytes enviados / total`) en `onStatus`.
4. Si el puente anuncia `ip` en Info y la red es alcanzable → ofrecer/usar `RAW_9100` en lugar de BLE.

### Fase C — UI
1. `Screens.kt`/`Components.kt`: tercera pestaña o filtro "Bluetooth (puente)" junto a Wi-Fi y USB
   (hoy el filtro es `usb != null`; pasar a un `enum Origin { WIFI, USB, BLE }`).
   Icono y subtítulo: `"Bluetooth · PrintBridge-1A2B"`.
2. Estados claros: "Buscando…", "Sin puente cerca", "Puente sin impresora", "Ocupado".
3. Pantalla/diálogo **Configurar Wi-Fi del puente** (envía `WIFI_SET`, muestra IP resultante).
4. Aviso de tiempo estimado antes de imprimir por BLE (ver sección 3).
5. `Settings.kt`: "Preferir Wi-Fi si está disponible" (por defecto sí).

### Fase D — Rendimiento (opcional, tras medir)
- Medir KB/s reales en 2–3 teléfonos; ajustar tamaño de trozo y créditos.
- L2CAP CoC (`createInsecureL2capChannel` / PSM publicado en Info) con *fallback* a GATT.
- Compresión: preferir PCL/PWG comprimidos cuando el driver lo permita; bajar dpi en modo "borrador BLE".

### Fase E — Impresoras de etiquetas y tickets
> Esta fase forma parte de la fase G2 de [`GENERIC-PRINTING-PLAN.md`](GENERIC-PRINTING-PLAN.md), que además
> agrega PCL 6, PCL3GUI, URF, la nueva cadena de decisión y el asistente de prueba. Ese plan manda.

La impresora del video es de etiquetas (TSC, lenguaje TSPL). Estas impresoras y las de tickets son el
**mejor caso para BLE** porque los trabajos son diminutos.
1. `Language` += `TSPL`, `ZPL`, `ESC_POS`. `DriverResolver` los elige por `CMD` del Device ID
   (`TSPL`, `ZPL`, `ESC/POS`/`ESCPOS`) o por fabricante (TSC, Zebra, Epson TM, Xprinter…).
2. Codificadores en `render/Encoders.kt` usando la página ya rasterizada (sirve para cualquier documento):
   - TSPL: `SIZE w mm,h mm` · `GAP` · `CLS` · `BITMAP x,y,wBytes,h,0,<1 bpp>` · `PRINT n`.
   - ESC/POS: `ESC @` · `GS v 0` raster por bandas · `GS V` corte.
   - ZPL: `^XA ^GFA,… ^XZ`.
3. Opciones nuevas de tamaño: etiqueta (p. ej. 50×25 mm, como el video) o rollo de 58/80 mm.
4. Tests JVM de cada codificador con imágenes de 8×8 px (bytes esperados exactos).

## 5bis. Windows y otras PCs (sin app)

No requiere cambios de la app, pero va en la guía de usuario: *Agregar impresora → por dirección IP → Dispositivo
TCP/IP → IP del puente → Personalizado → Raw, puerto 9100, SNMP desmarcado → driver del fabricante*
(probado en el video). macOS/Linux: "Impresora IP / AppSocket-HP JetDirect" al mismo puerto.

## 6. iOS (cuando exista `apps/ios`)

- CoreBluetooth: `CBCentralManager` escanea por el mismo UUID; `CBPeripheral.writeValue(.withoutResponse)`
  con `canSendWriteWithoutResponse` / `peripheralIsReady(toSendWriteWithoutResponse:)` para flujo.
- Permiso `NSBluetoothAlwaysUsageDescription`.
- iOS no expone MAC: identificar el puente por `identifier` + nombre + Device ID de Info.
- Reutiliza el **mismo protocolo** (sección 4). Mover la especificación a
  `packages/ble-protocol/PROTOCOL.md` cuando empiece iOS, con vectores de prueba en JSON que consuman
  los tests de Kotlin y de Swift.
- El motor de drivers (escpr, gutenprint…) es nativo Android hoy; en iOS el camino realista es **enviar
  PDF/PWG a un puente que sea IPP** o generar PCL/PostScript propio. Decisión pendiente (ver riesgos).

## 7. Pruebas y criterios de aceptación

| Prueba | Criterio |
|--------|----------|
| JVM `BridgeProtocolTest` | Pasa en CI (`./gradlew testDebugUnitTest`) |
| Descubrimiento | Con el puente a 3 m aparece en < 10 s; permisos denegados dan mensaje claro, no crash |
| Impresión por BLE, texto 1 página | Sale correcta; log muestra créditos y `DONE` |
| Foto por BLE | Termina o falla con error entendible; muestra progreso y tiempo |
| Cancelar | `ABORT` libera la impresora; siguiente trabajo funciona |
| Apagar el puente en medio | La app muestra error, no se cuelga (timeouts) |
| Regresión | IPP, 9100, LPD y USB-OTG siguen igual (tests existentes + prueba manual Test A/B de `HARDWARE-TEST.md`) |

Agregar a `docs/HARDWARE-TEST.md` una **Prueba C: puente ESP32 por BLE** al terminar la Fase B.

## 8. Riesgos y decisiones abiertas

| Tema | Notas |
|------|-------|
| Estabilidad de GATT en Android | Variabilidad entre fabricantes; mitigar con cola de escritura estricta, MTU explícito, reintentos y L2CAP en v2 |
| Velocidad en fotos | Aceptado; Wi-Fi es la ruta rápida |
| Driver en iOS | Los engines nativos son Android; definir si iOS usa PDF/PCL propio o depende del puente |
| Emparejamiento/seguridad | v1 Just Works; PIN y lista de puentes de confianza en v2 |
| Rotación de UUID | Los UUID de la sección 4 son provisionales; congelarlos antes de publicar el firmware |
| CI | Sin hardware, CI solo cubre protocolo; BLE real se prueba a mano |

## 9. Orden sugerido de implementación

1. Fase A (protocolo, descubrimiento, TXT `usb_*` en mDNS, tests): no necesita hardware.
2. Firmware H0–H3: con el arreglo de mDNS, la app ya imprime al puente por Wi-Fi.
3. Fase B + firmware H4–H5 (primera hoja impresa por BLE).
4. Fase C (UI) y firmware H6 (Wi-Fi por BLE).
5. Fase E si la impresora es de etiquetas/tickets; Fase D según mediciones. iOS cuando se inicie `apps/ios`.
