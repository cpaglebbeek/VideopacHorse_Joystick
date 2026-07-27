# CLAUDE.md — VideopacHorse_Joystick

Android joystick-controller voor de VideopacHorse-webemulator, gekoppeld via de
**6-tekens sessiecode** en de pairing-API. Onderdeel van de
**VideopacHorse-familie** — regie in `Meta_VideopacHorse`.

## Kern

- **Package/applicationId:** `nl.icthorse.vphjoystick`
- **Versie:** 0.4.0-Rusch (versionCode 2) — codenaamthema "Videopac/Odyssey²-pioniers"
- **Rol:** telefoon = HTTPS-client van `https://horsecloud55.ddns.net/videopac/api/`;
  de host-pagina (`VideopacHorse_Web`) haalt de input op met `ctrl-poll`.
- **GEEN Bluetooth (sinds 0.4.0), GEEN HID, GEEN OS-integratie.** Geen enkel OS
  reageert op de input; alleen de Videopac-sessie consumeert hem.
- **Max 2 joysticks per sessie** (slot 0 = speler 1, slot 1 = speler 2).
- **Enige permissie: `INTERNET`.**

## Bindende protocol-spec v0.4.0 (NIET wijzigen zonder impact-check op VideopacHorse_Web)

Basis-URL `https://horsecloud55.ddns.net/videopac/api/`, POST met JSON in/uit:

- `ctrl-join {action, code}` → `{ctrl_token (48 hex), slot: 0|1, expires_at}`;
  code = 6 tekens uit `A-Z2-9`. Derde controller ⇒ HTTP **409**
  `{"error":"maximaal 2 joysticks"}`. Onbekende/verlopen code ⇒ HTTP **400**
  (géén 404). Een verbonden "Samen spelen"-gast (`sessions.guest_token`) bezet
  slot 1 — en omgekeerd weigert `pair-join` sinds 0.4.0-Rusch met **409** als een
  telefoon slot 1 al heeft: max 2 spelers, symmetrisch bewaakt.
- `ctrl-input {action, token, mask 0..31}` → `{ok:true}`; bit0=UP bit1=DOWN
  bit2=LEFT bit3=RIGHT bit4=FIRE (= `G7K_JOY_*` in
  `VideopacHorse_Core/include/g7000.h`). Bij ELKE maskverandering + heartbeat
  elke 500 ms; server bewaart alleen de laatste mask (UPDATE).
- `ctrl-poll {action, token: host_token}` → `{controllers:[{slot, mask, age_ms}]}`;
  alleen de host mag pollen.
- `ctrl-leave {action, token}` → `{ok:true}`.
- `pair-end {action, token: host_token}` → `{ok:true}`; de host stopt de sessie,
  code + controllers + signalen verdwijnen meteen (telefoons krijgen daarna 401).

App-kant: `HttpURLConnection` + `org.json`, time-outs 3 s, **één verzoek
tegelijk in de lucht** (geen wachtrij — laatste mask wint) met een ondergrens van
**50 ms** tussen verzendingen, 3 opeenvolgende fouten ⇒ status "verbinding kwijt"
+ auto-retry, **HTTP 401 ⇒ automatisch opnieuw `ctrl-join` met de bewaarde code**
(3 pogingen; geweigerd ⇒ terug naar het koppelscherm met uitleg), `onPause` ⇒
mask 0 + `ctrl-leave`.

API-kant (in `VideopacHorse_Web`): schrijf-hygiëne conform BUG-007/008 — GC
getrotteld én atomair geclaimd, **álle** schrijfacties (ook die in `gc()`) via
`withRetry()`, geen `CREATE TABLE` per verzoek.

## Bouwen

```bash
JAVA_HOME=/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew clean assembleRelease
```

AGP 8.7.3 / Kotlin 2.1.0 / Gradle 8.9 / compileSdk 35 / minSdk 26 / targetSdk 35.
Release signt met de **debug-keystore** (persoonlijk gebruik, HorseAPK) —
gedocumenteerd in README; niet "fixen" zonder overleg.

## Structuur

| Bestand | Rol |
|---|---|
| `app/src/main/java/nl/icthorse/vphjoystick/PairActivity.kt` | koppelscherm: code-veld (A-Z/2-9, auto-uppercase), server-veld, `ctrl-join`, NL-foutvertaling |
| `.../JoystickActivity.kt` | joystick-scherm: "Speler N", status, mask-samenvoeging, `ctrl-leave` bij onPause |
| `.../JoystickView.kt` | Canvas-pad, 8 richtingen + release, haptiek |
| `.../ControllerLink.kt` | verzendlus: 1 in-flight, min. 50 ms, heartbeat 500 ms, foutteller, her-join na 401, afscheid |
| `.../Api.kt` | JSON-POST via HttpURLConnection, 3 s time-outs, `HttpError` |

Verwijderd in 0.4.0: `BleJoystickServer.kt`, `DeviceId.kt`, alle
Bluetooth-permissies en `uses-feature bluetooth_le`.

## Familie-regels (Meta_VideopacHorse)

1. **Lock-step-versies** met de familie; codenaamthema Videopac/Odyssey²-pioniers.
   Orkestratie van de familie-brede bump loopt via `Meta_VideopacHorse`.
2. Protocol-wijzigingen (`ctrl-*`, mask-bits, slots) beginnen in
   Meta_VideopacHorse met een impact-check op `VideopacHorse_Web` (frontend +
   `web/api/index.php`).
3. GEEN ROMs/BIOS in enige repo.
4. Meta_Master-protocollen gelden onverkort (WhatIf, prompts/, statusblok, OEU,
   ZSH-safety).
5. Geen externe dependencies toevoegen zonder noodzaak — de app is bewust
   dependency-vrij (alleen Kotlin-stdlib + platform-SDK).

## Verwijzingen

- `ARCHITECTURE.md` — componenten, dataflow, spec-tabel, ontwerpkeuzes
- `Meta_VideopacHorse/CLAUDE.md` — familie-regels
- `VideopacHorse_Core/include/g7000.h` — bron van de joystick-bitmask
- `VideopacHorse_Web/web/api/index.php` — de pairing-API zelf
