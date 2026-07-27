# ARCHITECTURE.md — VideopacHorse_Joystick

## Overzicht

De telefoon is een **BLE-peripheral** die joystick-input publiceert; de
VideopacHorse-webpagina is een **GATT-client** (Web Bluetooth) die abonneert.
Er is bewust **geen HID-profiel en geen internet**: geen enkel OS (Android
zelf, noch het OS van de machine waarop de browser draait) reageert op de
input — alleen de webpagina die expliciet op de service-UUID abonneert
consumeert de data.

```
┌────────────────────── Android-telefoon ──────────────────────┐
│                                                              │
│  MainActivity (fullscreen, portrait, scherm aan)             │
│  ├── JoystickView ── 8-richting-mask (bit0-3) ─┐             │
│  └── FIRE-knop ───── fire-bit (bit4) ──────────┤             │
│                                                ▼             │
│                        pushMask() → BleJoystickServer        │
│                                       │                      │
│  DeviceId (SHA-256(ANDROID_ID)[0..8]) │                      │
│                                       ▼                      │
│   GATT-server: service 7a0b1000-…0001                        │
│     └── char "joy" 7a0b1000-…0002 (NOTIFY+READ) + CCCD 2902  │
│   Advertiser: connectable, service-UUID + naam als           │
│               service-data in scan-response                  │
└───────────────┬──────────────────────────────────────────────┘
                │ BLE-notificaties (9 bytes) + heartbeat 500 ms
                ▼
┌────────────── Browser (Chrome/Edge) ─────────────────────────┐
│  VideopacHorse_Web: Web Bluetooth → startNotifications()     │
│  byte 0-7 = speler-identiteit, byte 8 = G7K_JOY_*-mask       │
│  stilte > ~1,5 s (3 gemiste heartbeats) = verbinding weg     │
└──────────────────────────────────────────────────────────────┘
```

## Componenten

| Component | Bestand | Verantwoordelijkheid |
|---|---|---|
| `MainActivity` | `MainActivity.kt` | fullscreen UI, immersive mode, keep-screen-on, runtime-permissies (API 31+, incl. rationale + Instellingen-link), samenvoegen richting+fire tot één mask, statusregel, Bluetooth-lifecycle-herstel (`ACTION_STATE_CHANGED`-receiver + `onResume`-hercheck + retry-tik bij advertise-fout) |
| `JoystickView` | `JoystickView.kt` | Canvas-rendering (pad, knob, richting-ticks), touch → 8 richtingen + release (dode zone 25%), haptiek bij richtingswissel |
| `BleJoystickServer` | `BleJoystickServer.kt` | GATT-server, advertiser, CCCD-administratie per central, notify-bij-verandering, heartbeat elke 500 ms |
| `DeviceId` | `DeviceId.kt` | SHA-256 over `Settings.Secure.ANDROID_ID` → 8-byte ID + naam `VPH-XXXX`; bij null ANDROID_ID: persistent random UUID per installatie (SharedPreferences) zodat ook dan elk toestel een uniek ID houdt |

## Dataflow

1. Touch op `JoystickView` → hoek → 45°-sector → richtingsmask (bit0-3);
   loslaten of dode zone → mask 0.
2. FIRE-knop down/up → bit4 aan/uit (met `VIRTUAL_KEY`-haptiek).
3. `MainActivity.pushMask()` combineert beide → `BleJoystickServer.updateMask()`.
4. Bij **elke maskverandering**: onmiddellijke notify naar alle subscribed
   centrals. Daarnaast stuurt de **heartbeat elke 500 ms** dezelfde payload,
   zodat de webkant stilte (verbroken verbinding / vastgelopen app) kan
   detecteren.
5. De characteristic is ook **READ**-baar: de client kan direct na verbinden
   het apparaat-ID + actuele mask opvragen zonder op de eerste notify te
   wachten.

## Bindende protocol-spec

| Onderdeel | Waarde |
|---|---|
| Service-UUID | `7a0b1000-56e1-4d2a-9f0a-c0de00000001` |
| Characteristic "joy" | `7a0b1000-56e1-4d2a-9f0a-c0de00000002`, `PROPERTY_NOTIFY + PROPERTY_READ`, `PERMISSION_READ` |
| Descriptor | CCCD `00002902-0000-1000-8000-00805f9b34fb` (READ+WRITE) |
| Payload | exact 9 bytes |
| Byte 0-7 | eerste 8 bytes van SHA-256(`Settings.Secure.ANDROID_ID`) — stabiel per toestel |
| Byte 8 | bitmask: bit0=UP (1), bit1=DOWN (2), bit2=LEFT (4), bit3=RIGHT (8), bit4=FIRE (16) — identiek aan `G7K_JOY_*` in `VideopacHorse_Core/include/g7000.h` |
| Notify-gedrag | bij elke maskverandering + heartbeat elke 500 ms (zelfde payload) |
| Advertising | connectable, `ADVERTISE_MODE_LOW_LATENCY`, service-UUID in advertise-data; naam `VPH-<laatste 4 hex van ID>` als service-data in scan-response |
| Adapternaam | wordt **nooit** globaal gezet (Android kent geen per-advertentie local name; zie README) |

## Ontwerpkeuzes

- **Geen HID by design.** Een HID-gamepad zou door élk OS opgepikt worden
  (cursor, focus, game-input elders). Als kale GATT-peripheral reageert het OS
  nergens op; alleen de webpagina die bewust abonneert ontvangt input.
- **Geen internet.** Er is geen netwerkverkeer en geen `INTERNET`-permissie in
  het manifest; de hele keten is lokaal BLE.
- **Dependency-vrij.** Alleen Kotlin-stdlib + platform-SDK: geen AndroidX, geen
  Compose. Kleine APK, geen supply-chain-oppervlak.
- **Heartbeat i.p.v. connection-events.** Web Bluetooth geeft niet altijd
  betrouwbaar/snel `gattserverdisconnected`; 500 ms-heartbeat maakt
  stiltedetectie op de webkant triviaal (≥3 gemiste beats = weg).
- **Multi-central.** Meerdere centrals mogen abonneren; iedere subscriber
  krijgt dezelfde notificaties. Spelertoewijzing gebeurt op de webkant op
  basis van het 8-byte ID.
- **Legacy-pad.** API 26-30 gebruikt install-time `BLUETOOTH`/`BLUETOOTH_ADMIN`;
  API 31+ vraagt `BLUETOOTH_ADVERTISE`/`BLUETOOTH_CONNECT` runtime. Toestellen
  zonder peripheral-modus (`bluetoothLeAdvertiser == null` of
  `ADVERTISE_FAILED_FEATURE_UNSUPPORTED`) krijgen een NL-foutmelding.
- **Bluetooth-lifecycle-herstel.** `MainActivity` registreert een receiver op
  `BluetoothAdapter.ACTION_STATE_CHANGED`: BT uit ⇒ `server.stop()` + status;
  BT aan ⇒ stop (dode state resetten) + herstart. `onResume()` herstart ook
  wanneer permissies alsnog via Instellingen zijn verleend (`start()` is
  idempotent). Niet-fatale advertise-fouten krijgen een retry-tik in de
  hintregel. Een app-herstart is dus nooit nodig.
- **Geen notify-flow-control (bewust, review-finding 2026-07 "laag").**
  De return-status van `notifyCharacteristicChanged` wordt gelogd, maar er
  wordt niet gewacht op `onNotificationSent`. Rationale: een gedropte notify
  wordt door de 500 ms-heartbeat (zelfde payload) automatisch herhaald en de
  webkant heeft een 2 s-watchdog die bij stilte mask 0 zet — een gemiste
  'release' herstelt daardoor binnen max ~500 ms. Een verzendwachtrij zou
  complexiteit toevoegen zonder merkbaar functioneel verschil bij een payload
  van 9 bytes op een 500 ms-cadans.
- **Advertise-naam: gedocumenteerde afwijking van de spec-intentie
  (review-finding 2026-07 "middel").** `VPH-XXXX` staat als service-data in de
  scan-response, niet als local name: Android kent geen per-advertentie local
  name en de globale adapternaam wijzigen is verboden ("geen OS-mutatie").
  Gevolg: de browser-chooser toont de kale OS-naam van de telefoon — dat is
  een geaccepteerde platformbeperking. Mitigatie op de webkant: na verbinden
  toont de pagina altijd `VPH-XXXX`, afgeleid uit bytes 0-7 van de payload
  (identiek aan wat de app zelf op het scherm toont), zodat toestellen ná het
  koppelen wél altijd uit elkaar te houden zijn. Koppel telefoons één voor
  één als twee toestellen in de chooser dezelfde OS-naam hebben.

## Familie-regels (conform Meta_VideopacHorse)

1. Lock-step-versies + codenaamthema "Videopac/Odyssey²-pioniers"
   (deze repo: **0.2.0-Gust**); orkestratie via `Meta_VideopacHorse`.
2. Wijzigingen aan de BLE-spec zijn familie-API-wijzigingen: impact-check op
   `VideopacHorse_Web` vóór de commit hier.
3. GEEN ROMs/BIOS in deze repo.
4. Meta_Master-protocollen gelden onverkort.
