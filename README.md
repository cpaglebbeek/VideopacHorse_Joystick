# VideopacHorse_Joystick

Android-app die je telefoon verandert in een **draadloze Videopac-joystick** voor
de VideopacHorse-webemulator (`VideopacHorse_Web`). De app is een **BLE-peripheral**
(GATT-server + advertiser); de webpagina verbindt via **Web Bluetooth** en leest de
joystick-input rechtstreeks uit.

Onderdeel van de **VideopacHorse-familie** (zie `Meta_VideopacHorse`).

## Waarom geen HID?

Bewuste ontwerpkeuze: de app implementeert **géén HID-profiel** en gebruikt
**géén internet**. Het OS (Android, noch het OS van de kijker) doet dus *niets*
met de input — er beweegt geen cursor, er scrollt geen pagina. Alleen een
GATT-client die expliciet op onze service-UUID abonneert (de Videopac-webpagina
via Web Bluetooth) consumeert de data.

## BLE-protocol (bindend voor app én web)

| Onderdeel | Waarde |
|---|---|
| Service-UUID | `7a0b1000-56e1-4d2a-9f0a-c0de00000001` |
| Characteristic "joy" | `7a0b1000-56e1-4d2a-9f0a-c0de00000002` (NOTIFY + READ) |
| CCCD | `00002902-…` (standaard) |
| Payload | exact **9 bytes** |
| Byte 0-7 | stabiel apparaat-ID: eerste 8 bytes van SHA-256(`Settings.Secure.ANDROID_ID`) |
| Byte 8 | bitmask: bit0=UP, bit1=DOWN, bit2=LEFT, bit3=RIGHT, bit4=FIRE (= `G7K_JOY_*` in `g7000.h`) |
| Notify | bij **elke** maskverandering + heartbeat elke **500 ms** (zelfde payload) |
| Advertising | connectable, service-UUID in advertise-data; naam `VPH-<laatste 4 hex van ID>` |

### Advertise-naam: hoe en waarom zo

Android kent **geen per-advertentie local name**: `setIncludeDeviceName(true)`
stuurt altijd de *globale adapternaam* mee, en die globaal wijzigen doen we
bewust niet (dat zou de Bluetooth-naam van de hele telefoon veranderen).
Daarom:

- **Primaire advertentie:** flags + 128-bit service-UUID (geen naam — past ook
  niet in de 31 bytes naast een 128-bit UUID).
- **Scan-response:** de naam `VPH-XXXX` als **service-data** onder onze eigen
  service-UUID (ASCII, 8 bytes).
- De webkant filtert op de service-UUID en leest het volledige apparaat-ID uit
  bytes 0-7 van de payload (via READ of de eerste notify); `VPH-XXXX` is
  daaruit ook af te leiden (laatste 4 hex-tekens, uppercase).

De Bluetooth-chooser van de browser kan hierdoor de kale adapternaam van de
telefoon tonen in plaats van `VPH-XXXX` — dat is een gedocumenteerde
platformbeperking, geen bug.

## Gebruik

1. Installeer de APK (via **HorseAPK**) en open de app.
2. Geef Bluetooth-permissies (Android 12+: "Apparaten in de buurt").
3. Bovenin zie je je apparaatnaam (`VPH-XXXX`) en de status.
4. Open de Videopac-pagina in Chrome/Edge en kies "Joystick koppelen".
5. Stick bewegen + FIRE = live input op de webpagina. Het scherm blijft aan.

Vereisten: Android 8.0+ (API 26) met **BLE-peripheral-modus** (advertising).
Toestellen zonder peripheral-modus krijgen een duidelijke Nederlandse foutmelding.

## Bouwen

```bash
JAVA_HOME=/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew assembleDebug assembleRelease
```

- AGP 8.7.3, Kotlin 2.1.0, Gradle 8.9, compileSdk 35, minSdk 26, targetSdk 35.
- Geen externe dependencies: alleen Kotlin-stdlib + platform-SDK.

### Signing (bewust debug)

De **release-buildtype signt met de debug-signingconfig**
(`~/.android/debug.keystore`). Dit is een bewuste keuze: de app is voor
persoonlijk gebruik en wordt gedistribueerd via **HorseAPK** (private
placeholder op HC55), niet via de Play Store. Wisselen naar een echte
release-keystore kan later zonder protocol-impact; let op: dat verandert wel
de ANDROID_ID-scope niet, maar wél de updatepad-compatibiliteit (andere
handtekening = eerst de-installeren).

## Familie

| Repo | Rol |
|---|---|
| `Meta_VideopacHorse` | regie, versie-orchestratie |
| `VideopacHorse_Core` | C11 emulator-engine (`include/g7000.h` = API, bron van de bitmask) |
| `VideopacHorse_Web` | WASM-frontend + Web Bluetooth-client van deze app |
| `VideopacHorse_Android` / `_SteamDeck` | overige frontends |
| **`VideopacHorse_Joystick`** | deze repo: BLE-joystick-controller |

Versie: **0.2.0-Gust** (zie `version.json`). Licentie: zie `LICENSE`.
