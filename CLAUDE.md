# CLAUDE.md — VideopacHorse_Joystick

Android BLE-joystick-controller voor de VideopacHorse-webemulator.
Onderdeel van de **VideopacHorse-familie** — regie in `Meta_VideopacHorse`.

## Kern

- **Package/applicationId:** `nl.icthorse.vphjoystick`
- **Versie:** 0.2.0-Gust (versionCode 1) — codenaamthema "Videopac/Odyssey²-pioniers"
- **Rol:** telefoon = BLE-peripheral (GATT-server + advertiser); de webpagina
  (`VideopacHorse_Web`) is de enige consument via Web Bluetooth.
- **Bewust GEEN HID-profiel, GEEN internet** — het OS mag niets met de input doen.

## Bindende BLE-spec (NIET wijzigen zonder impact-check op VideopacHorse_Web)

- Service-UUID: `7a0b1000-56e1-4d2a-9f0a-c0de00000001`
- Characteristic "joy" (NOTIFY+READ): `7a0b1000-56e1-4d2a-9f0a-c0de00000002`
- Payload exact 9 bytes: byte 0-7 = SHA-256(ANDROID_ID)[0..7], byte 8 =
  bitmask bit0=UP bit1=DOWN bit2=LEFT bit3=RIGHT bit4=FIRE (= `G7K_JOY_*`
  in `VideopacHorse_Core/include/g7000.h`).
- Notify bij elke maskverandering + heartbeat elke 500 ms.
- Advertise-naam `VPH-<laatste 4 hex>`: als service-data in de scan-response;
  adapternaam wordt NOOIT globaal gezet (zie README).

## Bouwen

```bash
JAVA_HOME=/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew assembleDebug assembleRelease
```

AGP 8.7.3 / Kotlin 2.1.0 / Gradle 8.9 / compileSdk 35 / minSdk 26 / targetSdk 35.
Release signt met de **debug-keystore** (persoonlijk gebruik, HorseAPK) —
gedocumenteerd in README; niet "fixen" zonder overleg.

## Structuur

| Bestand | Rol |
|---|---|
| `app/src/main/java/nl/icthorse/vphjoystick/MainActivity.kt` | fullscreen UI, permissies, mask-samenvoeging |
| `.../JoystickView.kt` | Canvas-pad, 8 richtingen + release, haptiek |
| `.../BleJoystickServer.kt` | GATT-server, advertiser, CCCD, heartbeat |
| `.../DeviceId.kt` | SHA-256(ANDROID_ID) → 8-byte ID + `VPH-XXXX` |

## Familie-regels (Meta_VideopacHorse)

1. **Lock-step-versies** met de familie; codenaamthema Videopac/Odyssey²-pioniers.
   Deze repo start op 0.2.0-Gust; orkestratie van de familie-brede bump loopt
   via `Meta_VideopacHorse`.
2. Protocol-wijzigingen (UUID's/payload) beginnen in Meta_VideopacHorse met een
   impact-check op `VideopacHorse_Web`.
3. GEEN ROMs/BIOS in enige repo.
4. Meta_Master-protocollen gelden onverkort (WhatIf, prompts/, statusblok, OEU,
   ZSH-safety).
5. Geen externe dependencies toevoegen zonder noodzaak — de app is bewust
   dependency-vrij (alleen Kotlin-stdlib + platform-SDK).

## Verwijzingen

- `ARCHITECTURE.md` — componenten, dataflow, spec-tabel
- `Meta_VideopacHorse/CLAUDE.md` — familie-regels
- `VideopacHorse_Core/include/g7000.h` — bron van de joystick-bitmask
