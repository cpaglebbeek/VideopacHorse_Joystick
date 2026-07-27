# VideopacHorse_Joystick

Android-app die je telefoon verandert in een **draadloze Videopac-joystick** voor
de VideopacHorse-webemulator (`VideopacHorse_Web`). De koppeling loopt via de
**sessiecode** van de webpagina en de pairing-API op HorseCloud55 — er is
**geen Bluetooth** meer.

Onderdeel van de **VideopacHorse-familie** (zie `Meta_VideopacHorse`).

## Hoe het werkt

1. De host opent de Videopac-pagina en toont daar een **6-tekens sessiecode**
   (alfabet A-Z en 2-9).
2. Je tikt die code in de app en drukt op **Koppel** → `ctrl-join`.
3. De server geeft een **ctrl-token** en een **slot** terug: slot 0 = *Speler 1*,
   slot 1 = *Speler 2*. De app toont dat als koptekst.
4. De app stuurt bij **elke** maskverandering én elke **500 ms** (heartbeat) een
   `ctrl-input` met de actuele bitmask. De host haalt die op met `ctrl-poll`.
5. Bij afsluiten of wegschakelen stuurt de app `ctrl-input mask=0` + `ctrl-leave`,
   zodat het slot vrijkomt.

**Maximaal 2 joysticks per sessie.** Een derde poging krijgt HTTP 409 en de
melding "Er zijn al 2 joysticks gekoppeld aan deze sessie." Is er een
"Samen spelen"-gast verbonden, dan is slot 1 al bezet en kan alleen slot 0
nog gekoppeld worden.

## Geen Bluetooth, geen HID — bewust

- **Geen Bluetooth.** De vorige versie was een BLE-peripheral; die hele laag is
  verwijderd (`BleJoystickServer.kt`, `DeviceId.kt` en alle BT-permissies).
  Reden: Web Bluetooth werkt alleen in Chrome/Edge, vereist BLE-peripheral-modus
  op de telefoon en werkt niet over een "Samen spelen"-sessie op afstand. De
  sessiecode werkt overal waar de pagina werkt.
- **Geen HID-profiel.** De app meldt zich nergens aan als toetsenbord of
  gamepad. **Geen enkel OS reageert dus op de input** — er beweegt geen cursor,
  er scrollt geen pagina, er start geen game elders. De enige consument is de
  Videopac-sessie die met het host-token pollt.
- **Rechtenvrij op één na.** Het manifest bevat uitsluitend `INTERNET`. Geen
  Bluetooth, geen locatie, geen opslag, geen achtergronddiensten.

## Protocol-spec v0.4.0 (bindend voor app, web én API)

Basis-URL: `https://horsecloud55.ddns.net/videopac/api/` — POST, JSON in/uit.
In de app is dit een constante (`Api.DEFAULT_BASE_URL`), maar overschrijfbaar
via het kleine server-veld op het koppelscherm (alleen bedoeld om te testen).

| Actie | Request | Response |
|---|---|---|
| `ctrl-join` | `{action:"ctrl-join", code:"ABC234"}` | `{ctrl_token (48 hex), slot: 0\|1, expires_at}` |
| `ctrl-input` | `{action:"ctrl-input", token:<ctrl_token>, mask:0..31}` | `{ok:true}` |
| `ctrl-poll` | `{action:"ctrl-poll", token:<host_token>}` | `{controllers:[{slot, mask, age_ms}]}` |
| `ctrl-leave` | `{action:"ctrl-leave", token:<ctrl_token>}` | `{ok:true}` |

- **Mask-bits:** bit0=UP (1), bit1=DOWN (2), bit2=LEFT (4), bit3=RIGHT (8),
  bit4=FIRE (16) — identiek aan `G7K_JOY_*` in
  `VideopacHorse_Core/include/g7000.h`.
- **Herstel:** krijgt de app HTTP 401 (sessie opgeruimd na 60 s stilte, host
  stopte de sessie, of TTL voorbij), dan koppelt hij zichzelf opnieuw met dezelfde
  code. Kan dat niet meer, dan keer je terug naar het koppelscherm met de reden.
- **Slots:** het laagste vrije slot wordt toegekend; slot 0 = speler 1, slot 1 =
  speler 2. Een verbonden "Samen spelen"-gast (`sessions.guest_token` gevuld)
  bezet slot 1.
- **Cadans:** `ctrl-input` bij elke maskverandering + heartbeat elke 500 ms.
  De server bewaart alleen de láátste mask per controller (UPDATE, geen
  INSERT-groei).
- **Alleen de host pollt.** `ctrl-poll` accepteert uitsluitend het host-token;
  `age_ms` = ms sinds de laatste `ctrl-input`.
- **Fouten:** derde controller ⇒ HTTP 409 `{"error":"maximaal 2 joysticks"}`.

### Netwerkgedrag in de app

- `HttpURLConnection` + `org.json`, geen externe libraries; connect- én
  read-time-out **3 s**.
- **Eén verzoek tegelijk in de lucht** (serialisatie in `ControllerLink`): er is
  geen wachtrij, alleen een "laatst bekende mask". Verandert de stick terwijl
  een verzoek loopt, dan gaat die nieuwe waarde direct ná het lopende verzoek
  mee — laatste waarde wint. Zo kan de joystick nooit gaan naslepen.
- **3 opeenvolgende fouten** ⇒ status "Verbinding kwijt — opnieuw proberen…"
  (rood). De lus blijft doorproberen op dezelfde 500 ms-cadans; herstelt de
  verbinding, dan springt de status vanzelf terug.

## Gebruik

1. Installeer de APK (via **HorseAPK**) en open de app.
2. Tik de 6-tekens sessiecode van de Videopac-pagina in (het veld accepteert
   alleen A-Z en 2-9 en zet alles automatisch om naar hoofdletters).
3. Druk op **Koppel**. Bij succes verschijnt het joystick-scherm met
   *Speler 1* of *Speler 2* in de kop.
4. Stick bewegen + FIRE = live input. Het scherm blijft aan, portrait.
5. App sluiten of naar de achtergrond ⇒ slot komt automatisch vrij; je komt
   terug op het koppelscherm.

Vereisten: Android 8.0+ (API 26) en internet. Geen Bluetooth, geen speciale
hardware.

## Bouwen

```bash
JAVA_HOME=/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew clean assembleRelease
```

- AGP 8.7.3, Kotlin 2.1.0, Gradle 8.9, compileSdk 35, minSdk 26, targetSdk 35.
- Geen externe dependencies: alleen Kotlin-stdlib + platform-SDK (incl. `org.json`).
- Artefact: `app/build/outputs/apk/release/VideopacHorseJoystick-v0.4.0-Rusch-release.apk`

### Signing (bewust debug)

De **release-buildtype signt met de debug-signingconfig**
(`~/.android/debug.keystore`). Bewuste keuze: de app is voor persoonlijk gebruik
en wordt gedistribueerd via **HorseAPK** (private placeholder op HC55), niet via
de Play Store. Wisselen naar een echte release-keystore kan later; let op dat een
andere handtekening eerst de-installeren vereist.

## Familie

| Repo | Rol |
|---|---|
| `Meta_VideopacHorse` | regie, versie-orchestratie |
| `VideopacHorse_Core` | C11 emulator-engine (`include/g7000.h` = bron van de bitmask) |
| `VideopacHorse_Web` | WASM-frontend + pairing-API (`web/api/index.php`) — host van de sessie |
| `VideopacHorse_Android` / `_SteamDeck` | overige frontends |
| **`VideopacHorse_Joystick`** | deze repo: joystick-controller via sessiecode |

Versie: **0.4.0-Rusch** (versionCode 2, zie `version.json`). Licentie: zie `LICENSE`.
- HorseAPK-projectnaam: **VPHJoystick** (publish-gate eist package-suffix-match met nl.icthorse.vphjoystick).
