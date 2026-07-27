---
date: 2026-07-27
project: VideopacHorse_Joystick
type: newp
status: done
---

# 2026-07-27 — newp VideopacHorse_Joystick (v0.2.0-Gust)

## Samenvatting

Nieuwe repo in de VideopacHorse-familie: Android-app die de telefoon tot
BLE-joystick maakt voor de webemulator. Volledig werkend gebouwd (geen
placeholder):

- **BLE-peripheral** conform de bindende spec: service
  `7a0b1000-…0001`, characteristic "joy" `7a0b1000-…0002` (NOTIFY+READ),
  payload 9 bytes (8-byte SHA-256(ANDROID_ID)-ID + 1 byte G7K_JOY_*-mask),
  notify bij elke maskverandering + heartbeat 500 ms, CCCD correct,
  advertising met service-UUID + naam `VPH-XXXX` als service-data in de
  scan-response (adapternaam bewust niet globaal gezet — gedocumenteerd).
- **UI:** één fullscreen portrait-Activity, scherm aan; custom `JoystickView`
  (Canvas, 8 richtingen + release, Videopac-oranje #f5a623 op #14151c) +
  grote FIRE-knop met haptiek; NL-statusregel met `VPH-XXXX` +
  advertising/verbonden-status + speler-hint.
- **Permissies:** API 31+ runtime BLUETOOTH_ADVERTISE/CONNECT, daaronder
  legacy install-time; NL-foutmelding bij ontbrekende peripheral-modus.
- **Bewust geen HID, geen internet** — alleen de webpagina consumeert.
- **Gradle:** AGP 8.7.3 / Kotlin 2.1.0 / Gradle 8.9, compileSdk 35,
  minSdk 26, targetSdk 35, patroon RandomRingtone; release signt met
  debug-keystore (persoonlijk gebruik via HorseAPK, in README verantwoord).
- **Bouwbewijs:** `assembleDebug` + `assembleRelease` groen met
  JAVA_HOME=openjdk@21.
- Docs: README.md, CLAUDE.md, ARCHITECTURE.md (componenten/dataflow/
  spec-tabel/familie-regels).

## Kruisverwijzing

Hoofd-sessie-MD van deze newp-ronde: zie
`Meta_VideopacHorse/prompts/` (familie-orchestratie 0.2.0-Gust).
Bitmask geverifieerd tegen `VideopacHorse_Core/include/g7000.h`
(G7K_JOY_UP=1, DOWN=2, LEFT=4, RIGHT=8, FIRE=16).
