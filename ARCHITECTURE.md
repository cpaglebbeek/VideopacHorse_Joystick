# ARCHITECTURE.md — VideopacHorse_Joystick

## Overzicht

De telefoon is een **HTTPS-client** van de VideopacHorse pairing-API. De
gebruiker koppelt met de **6-tekens sessiecode** van de host; daarna publiceert
de app joystick-input als bitmask. De host-pagina haalt die input op met
`ctrl-poll`.

Er is bewust **geen Bluetooth en geen HID-profiel**: geen enkel OS (Android
zelf, noch het OS van de machine waarop de browser draait) reageert op de
input — alleen de sessie die met het host-token pollt consumeert de data.

```
┌────────────────────── Android-telefoon ──────────────────────┐
│                                                              │
│  PairActivity (launcher, portrait, scherm aan)               │
│  ├── code-veld (A-Z 2-9, auto-uppercase, 6 tekens)           │
│  ├── server-veld (default Api.DEFAULT_BASE_URL, test-only)   │
│  └── "Koppel" → ctrl-join  ──► {ctrl_token, slot}            │
│              │                                               │
│              ▼ Intent(token, slot, baseUrl)                  │
│  JoystickActivity (portrait, fullscreen, scherm aan)         │
│  ├── kop "Speler 1"/"Speler 2" (uit slot) + status           │
│  ├── JoystickView ── 8-richting-mask (bit0-3) ─┐             │
│  └── FIRE-knop ───── fire-bit (bit4) ──────────┤             │
│                                                ▼             │
│                       pushMask() → ControllerLink            │
│                         │ 1 verzoek tegelijk, laatste wint   │
│                         │ elke maskverandering + 500 ms      │
└─────────────────────────┼────────────────────────────────────┘
                          │ HTTPS POST ctrl-input {token, mask}
                          ▼
┌──────── horsecloud55.ddns.net/videopac/api/ (SQLite) ────────┐
│  controllers: (session, slot, mask, updated_at) — UPDATE     │
│  max 2 slots per sessie; slot 1 bezet bij "Samen spelen"     │
└──────────────────────────┬───────────────────────────────────┘
                           │ ctrl-poll {host_token}
                           ▼
┌────────────── Browser: VideopacHorse_Web ────────────────────┐
│  controllers:[{slot, mask, age_ms}] → G7K_JOY_* per speler   │
│  age_ms hoog (≥ ~1,5 s) = controller stil/weg → mask 0       │
└──────────────────────────────────────────────────────────────┘
```

## Componenten

| Component | Bestand | Verantwoordelijkheid |
|---|---|---|
| `PairActivity` | `PairActivity.kt` | koppelscherm: code-invoer (InputFilter A-Z/2-9 + auto-uppercase + max 6), server-URL-veld en laatst gebruikte code (persistent in SharedPreferences), `ctrl-join` op een achtergrond-thread, Nederlandse foutvertaling (409/400+404/5xx/netwerk), toont de melding waarmee het joystick-scherm terugkeert |
| `JoystickActivity` | `JoystickActivity.kt` | joystick-scherm: kop "Speler N" uit het slot, verbindingsstatus, immersive fullscreen, keep-screen-on, samenvoegen richting+fire tot één mask, `ctrl-leave` bij onPause |
| `JoystickView` | `JoystickView.kt` | Canvas-rendering (pad, knob, richting-ticks), touch → 8 richtingen + release (dode zone 25%), haptiek bij richtingswissel |
| `ControllerLink` | `ControllerLink.kt` | verzendlus: één verzoek in-flight, laatste mask wint, minimaal 50 ms tussen verzendingen, heartbeat 500 ms, foutteller → "verbinding kwijt" + auto-retry, **her-join bij HTTP 401**, afsluiten met mask 0 + `ctrl-leave` |
| `Api` | `Api.kt` | `HttpURLConnection`-POST met JSON in/uit, 3 s time-outs, URL-normalisatie, `HttpError(code, serverMessage)` |

## Dataflow

1. Touch op `JoystickView` → hoek → 45°-sector → richtingsmask (bit0-3);
   loslaten of dode zone → mask 0.
2. FIRE-knop down/up → bit4 aan/uit (met `VIRTUAL_KEY`-haptiek).
3. `JoystickActivity.pushMask()` combineert beide → `ControllerLink.updateMask()`.
4. `updateMask()` zet de nieuwe waarde en wekt de verzendthread (non-blocking,
   geen netwerk-I/O op de UI-thread).
5. De verzendthread doet één `ctrl-input` tegelijk en houdt minimaal
   `MIN_INTERVAL_MS` (50 ms) tussen twee verzendingen aan; is er niets veranderd,
   dan stuurt hij dezelfde mask als **heartbeat elke 500 ms**. De host ziet
   daardoor in `age_ms` meteen of een controller stil ligt of weg is.
5b. Antwoordt de server met **401** ("controller onbekend of verlopen"), dan doet
   de app zelf opnieuw `ctrl-join` met de bewaarde sessiecode (3 pogingen,
   oplopende backoff). Lukt dat, dan gaat het spel door met een nieuw token en
   toont het scherm het (mogelijk gewisselde) slot. Wordt de her-join geweigerd
   (400 = code weg, 409 = slots vol), dan keert de app terug naar het
   koppelscherm met een uitleg — niet eeuwig een dood token blijven posten.
6. `onPause` ⇒ lus stoppen, `ctrl-input mask=0`, `ctrl-leave`, terug naar
   `PairActivity`. Het slot komt dus vrij zodra de app naar de achtergrond gaat.

## Bindende protocol-spec v0.4.0

Basis-URL: `https://horsecloud55.ddns.net/videopac/api/` (POST, JSON in/uit).

| Actie | Request | Response |
|---|---|---|
| `ctrl-join` | `{action:"ctrl-join", code:"ABC234"}` | `{ctrl_token (48 hex), slot: 0\|1, expires_at}` |
| `ctrl-input` | `{action:"ctrl-input", token:<ctrl_token>, mask:0..31}` | `{ok:true}` |
| `ctrl-poll` | `{action:"ctrl-poll", token:<host_token>}` | `{controllers:[{slot, mask, age_ms}]}` |
| `ctrl-leave` | `{action:"ctrl-leave", token:<ctrl_token>}` | `{ok:true}` |

| Onderdeel | Waarde |
|---|---|
| Sessiecode | 6 tekens, alfabet `A-Z` + `2-9` (identiek aan `CODE_ALPHABET` in de API) |
| Mask-bits | bit0=UP (1), bit1=DOWN (2), bit2=LEFT (4), bit3=RIGHT (8), bit4=FIRE (16) — `G7K_JOY_*` in `VideopacHorse_Core/include/g7000.h` |
| Slots | max **2 spelers** per sessie; laagste vrije slot; slot 0 = speler 1, slot 1 = speler 2; gevulde `sessions.guest_token` ("Samen spelen") bezet slot 1. Sinds v0.4.0-Rusch symmetrisch: `pair-join` weigert óók (409) als een telefoon slot 1 al heeft |
| Derde controller | HTTP **409** + `{"error":"maximaal 2 joysticks"}` |
| Onbekende/verlopen code | HTTP **400** + `{"error":"code verlopen of onbekend"}` (géén 404 — de app vertaalt 400 én 404 naar "vraag de host om een nieuwe code") |
| Dood ctrl-token | HTTP **401**; app doet automatisch opnieuw `ctrl-join` met dezelfde code |
| Sessie gestopt | de host roept `pair-end` aan; code, controllers en sessie verdwijnen direct |
| Cadans | `ctrl-input` bij elke maskverandering + heartbeat elke 500 ms, met een ondergrens van 50 ms tussen verzendingen |
| Serverstate | alleen de láátste mask per controller (UPDATE, geen INSERT-groei) |
| Poll | uitsluitend het host-token; `age_ms` = ms sinds laatste `ctrl-input` |
| Time-outs (app) | connect + read: 3 s |

Schrijf-hygiëne aan de API-kant (BUG-007/008-les uit `VideopacHorse_Web/docs/BUGLIST.md`):
GC blijft getrotteld én claimt zijn venster atomair, álle schrijfacties — ook die
in `gc()` — lopen via de `withRetry()`-helper, geen extra `CREATE TABLE` per
verzoek. De app helpt mee met de 50 ms-ondergrens: zonder die limiet leverde
rondroeren met de stick gemeten 8,3 POST/s per telefoon in plaats van de ~2 Hz
waar dat ontwerp op rekent.

## Ontwerpkeuzes

- **Sessiecode i.p.v. Bluetooth (0.4.0).** De BLE-laag is volledig verwijderd.
  Web Bluetooth beperkte de app tot Chrome/Edge met BLE-peripheral-modus op de
  telefoon en werkte niet samen met "Samen spelen"-sessies op afstand. De
  sessiecode werkt overal waar de webpagina werkt en gebruikt dezelfde
  code-infrastructuur als 🎭 Samen spelen.
- **Geen HID by design.** Een HID-gamepad zou door élk OS opgepikt worden
  (cursor, focus, game-input elders). Nu is de app een gewone HTTPS-client:
  buiten de Videopac-sessie merkt niets iets van de input.
- **Rechtenvrij op `INTERNET` na.** Geen Bluetooth, geen locatie, geen opslag.
- **Dependency-vrij.** Alleen Kotlin-stdlib + platform-SDK (`HttpURLConnection`,
  `org.json`): geen AndroidX, geen Compose, geen OkHttp. Kleine APK, geen
  supply-chain-oppervlak.
- **Eén verzoek in-flight, laatste waarde wint.** Een wachtrij zou bij
  netwerkvertraging een achterstand opbouwen: de speler laat los, maar de server
  verwerkt nog oude standen ("naslepen"). Daarom houdt `ControllerLink` maar
  één waarde vast en stuurt hij na elk antwoord de dán actuele mask. Verloren
  tussenstanden zijn functioneel irrelevant — alleen de huidige stand telt.
- **Heartbeat i.p.v. verbindingsevents.** HTTP kent geen verbindingsnotie; de
  500 ms-heartbeat maakt zowel "app leeft nog" (via `age_ms` op de host) als
  foutdetectie in de app triviaal.
- **Tempolimiet van 50 ms met "laatste waarde wint".** Elke sectorwissel van de
  stick forceerde een POST; bij rondroeren gaf dat 8,3 req/s per telefoon (4× de
  aanname van de API) en met twee telefoons ~17 schrijfverzoeken/s op een SQLite
  die bij ~4 writes/s al tegen zijn retry-plafond loopt. De ondergrens kost geen
  responsiviteit: wat er tijdens die 50 ms binnenkomt wordt samengevoegd tot de
  actuele stand — een mens haalt geen 20 betekenisvolle richtingswissels/s.
- **Herstel na 401 in plaats van eeuwig doorposten.** Een controller-rij wordt na
  60 s stilte of bij `pair-end` opgeruimd; daarna is elk `ctrl-input` een 401.
  De app kende dat verschil niet (alle fouten in één generieke `catch`) en bleef
  hetzelfde dode token posten terwijl het scherm "Verbonden" toonde. Nu: her-join
  met de bewaarde code, of terug naar het koppelscherm met uitleg.
- **3 fouten = "verbinding kwijt", maar niet stoppen.** Een enkele mislukte POST
  (wifi-hik) mag de speler niet storen; de heartbeat corrigeert die vanzelf. Pas
  bij 3 op rij verandert de status, en de lus blijft doorproberen zodat herstel
  automatisch is.
- **`ctrl-leave` bij onPause.** Een slot bezet houden terwijl de app op de
  achtergrond staat zou de tweede speler buitensluiten (max 2). Daarom geeft de
  app het slot direct terug en keert hij terug naar het koppelscherm; opnieuw
  koppelen kost één tik op "Koppel".
- **Server-URL overschrijfbaar.** Alleen om tegen een testserver te kunnen
  draaien; de waarde wordt genormaliseerd (schema + afsluitende slash) en
  bewaard in SharedPreferences.

## Familie-regels (conform Meta_VideopacHorse)

1. Lock-step-versies + codenaamthema "Videopac/Odyssey²-pioniers"
   (deze repo: **0.4.0-Rusch**, versionCode 2); orkestratie via
   `Meta_VideopacHorse`.
2. Wijzigingen aan de `ctrl-*`-spec zijn familie-API-wijzigingen: impact-check
   op `VideopacHorse_Web` (frontend én `web/api/index.php`) vóór de commit hier.
3. GEEN ROMs/BIOS in deze repo.
4. Meta_Master-protocollen gelden onverkort.
