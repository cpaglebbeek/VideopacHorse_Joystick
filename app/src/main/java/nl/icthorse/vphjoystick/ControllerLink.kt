package nl.icthorse.vphjoystick

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject

/**
 * Houdt de verbinding met de sessie levend en verstuurt joystick-input.
 *
 * Protocol-spec v0.4.0:
 * - `ctrl-input {token, mask}` bij ELKE maskverandering én als heartbeat
 *   elke 500 ms (de server bewaart alleen de laatste mask per controller).
 * - `ctrl-leave {token}` geeft het slot vrij bij afsluiten/onPause.
 *
 * Serialisatie: precies één verzoek tegelijk in de lucht. Er is geen wachtrij;
 * er is één "laatst bekende mask". Verandert de mask terwijl er een verzoek
 * loopt, dan wordt die nieuwe waarde direct ná het lopende verzoek verstuurd —
 * laatste waarde wint, oudere tussenstanden worden overgeslagen. Zo kan er
 * nooit een achterstand ontstaan (dat zou de joystick laten "naslepen").
 *
 * Tempolimiet (v0.4.0-Rusch): tussen twee verzendingen zit minimaal
 * [MIN_INTERVAL_MS]. Zonder die ondergrens forceerde élke sectorwissel van de
 * stick meteen een POST — bij rondroeren gemeten 8,3 req/s per telefoon in
 * plaats van de 2 Hz waar de schrijf-hygiëne van de API op rekent. De
 * "laatste waarde wint"-regel maakt dat gratis: tussenstanden die tijdens de
 * wachttijd binnenkomen worden samengevoegd tot één verzending.
 *
 * Herstel (v0.4.0-Rusch): een HTTP 401 betekent dat de server onze controller
 * niet meer kent (>60 s stil geweest, host stopte de sessie, of de sessie-TTL
 * van 4 uur verliep). Daarvóór bleef de app eeuwig hetzelfde dode token posten.
 * Nu doet hij zelf opnieuw `ctrl-join` met de bewaarde code; lukt dat niet
 * (code weg, of beide slots bezet) dan meldt hij dat via [Listener.onFatal] en
 * gaat de app terug naar het koppelscherm.
 *
 * Bij 3 opeenvolgende fouten meldt de listener "verbinding kwijt"; de lus
 * blijft daarna gewoon doorproberen op dezelfde 500 ms-cadans (auto-retry).
 */
class ControllerLink(
    private val baseUrl: String,
    initialToken: String,
    private val code: String,
    private val listener: Listener
) {

    interface Listener {
        /** @param connected false zodra 3 verzoeken op rij faalden. */
        fun onConnectionState(connected: Boolean)

        /** Na een 401 opnieuw gekoppeld; het slot kan gewisseld zijn. */
        fun onRejoined(slot: Int)

        /** Definitief einde: terug naar het koppelscherm met deze reden. */
        fun onFatal(reason: Reason)
    }

    /** Waarom er niet meer te herstellen valt (de UI kiest de tekst). */
    enum class Reason { SESSION_GONE, SLOTS_FULL }

    companion object {
        private const val TAG = "VPHLink"

        /** Heartbeat-interval uit de spec. */
        const val HEARTBEAT_MS = 500L

        /** Ondergrens tussen twee ctrl-inputs (laatste waarde wint). */
        const val MIN_INTERVAL_MS = 50L

        /** Aantal opeenvolgende fouten voordat we "verbinding kwijt" melden. */
        const val ERRORS_BEFORE_LOST = 3

        /** Aantal her-join-pogingen na een 401 voordat we opgeven. */
        const val REJOIN_ATTEMPTS = 3

        /** Basis-wachttijd tussen her-join-pogingen (loopt op per poging). */
        const val REJOIN_BACKOFF_MS = 1000L

        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_CONFLICT = 409
    }

    private val ui = Handler(Looper.getMainLooper())
    private val lock = Object()

    private var worker: Thread? = null
    private var running = false

    /** Actief ctrl-token; wisselt bij een geslaagde her-join. */
    @Volatile
    private var token: String = initialToken

    /** Laatst door de UI gezette mask (0..31). */
    private var currentMask = 0

    /** true = er staat een nog niet verstuurde maskverandering klaar. */
    private var pending = true

    private var consecutiveErrors = 0
    private var reportedConnected = true

    // ------------------------------------------------------------------ API

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            pending = true
            consecutiveErrors = 0
            reportedConnected = true
        }
        worker = Thread({ loop() }, "vph-ctrl-input").apply {
            isDaemon = true
            start()
        }
    }

    /** Nieuwe mask vanuit de UI-thread; wekt de verzendlus direct. */
    fun updateMask(mask: Int) {
        synchronized(lock) {
            if (mask == currentMask) return
            currentMask = mask
            pending = true
            lock.notifyAll()
        }
    }

    /**
     * Stopt de lus en geeft het slot vrij: eerst `ctrl-input mask=0` (zodat een
     * ingedrukte richting/FIRE gegarandeerd loslaat), daarna `ctrl-leave`.
     * Blokkeert niet: het afscheid gaat over een eigen korte thread.
     */
    fun stopAndLeave() {
        synchronized(lock) {
            if (!running) return
            running = false
            lock.notifyAll()
        }
        val t = worker
        worker = null
        Thread({
            try {
                t?.join(Api.TIMEOUT_MS.toLong() + 500L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            runCatching { sendMask(0) }
                .onFailure { Log.w(TAG, "afsluitende mask 0 mislukt: ${it.message}") }
            runCatching {
                Api.post(baseUrl, JSONObject().put("action", "ctrl-leave").put("token", token))
            }.onFailure { Log.w(TAG, "ctrl-leave mislukt: ${it.message}") }
        }, "vph-ctrl-leave").apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------- verzendlus

    private fun loop() {
        var nextBeat = SystemClock.elapsedRealtime()
        var notBefore = 0L        // ondergrens: MIN_INTERVAL_MS na de vorige verzending
        while (true) {
            var mask = 0
            synchronized(lock) {
                while (running) {
                    val now = SystemClock.elapsedRealtime()
                    /* Vroegste moment waarop we mogen versturen: bij een verse
                     * maskverandering meteen, anders op de heartbeat — maar
                     * nooit vóór de tempolimiet. */
                    val target = if (pending) maxOf(notBefore, now) else maxOf(nextBeat, notBefore)
                    val wait = target - now
                    if (wait <= 0L) break
                    try {
                        lock.wait(wait)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
                if (!running) return
                pending = false
                mask = currentMask          // laatste waarde wint
            }

            // Buiten het slot versturen: hierdoor is er per constructie precies
            // één verzoek tegelijk onderweg en blijft updateMask() non-blocking.
            var ok = false
            try {
                sendMask(mask)
                ok = true
            } catch (e: Api.HttpError) {
                if (e.code == HTTP_UNAUTHORIZED) {
                    Log.w(TAG, "ctrl-input 401: controller onbekend, opnieuw koppelen")
                    when (rejoin()) {
                        true -> {
                            /* Nieuw token: huidige stand opnieuw versturen,
                             * anders staat de server op 0 tot de volgende
                             * beweging van de stick. */
                            synchronized(lock) { pending = true }
                            ok = true
                        }
                        false -> return          // definitief; onFatal is gemeld
                        null -> ok = false       // alleen netwerkfouten: blijven proberen
                    }
                } else {
                    Log.w(TAG, "ctrl-input mislukt: ${e.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "ctrl-input mislukt: ${e.message}")
            }

            val now = SystemClock.elapsedRealtime()
            nextBeat = now + HEARTBEAT_MS
            notBefore = now + MIN_INTERVAL_MS
            reportResult(ok)
        }
    }

    /**
     * Opnieuw koppelen met de bewaarde sessiecode.
     *
     * @return true = gelukt (nieuw token actief), false = definitief einde
     *         ([Listener.onFatal] is gemeld), null = uitsluitend netwerkfouten,
     *         dus later gewoon opnieuw proberen.
     */
    private fun rejoin(): Boolean? {
        var networkOnly = true
        for (attempt in 1..REJOIN_ATTEMPTS) {
            synchronized(lock) { if (!running) return false }
            try {
                val resp = Api.post(
                    baseUrl,
                    JSONObject().put("action", "ctrl-join").put("code", code)
                )
                val fresh = resp.optString("ctrl_token")
                val slot = resp.optInt("slot", -1)
                if (fresh.isNotBlank() && slot >= 0) {
                    token = fresh
                    ui.post { listener.onRejoined(slot) }
                    return true
                }
                networkOnly = false          // server antwoordt, maar onbruikbaar
            } catch (e: Api.HttpError) {
                /* 400 = code weg/verlopen (host stopte de sessie), 409 = beide
                 * slots bezet. Beide zijn definitief: doorproberen heeft geen
                 * zin en zou de gebruiker in het ongewisse laten. */
                val reason = if (e.code == HTTP_CONFLICT) Reason.SLOTS_FULL else Reason.SESSION_GONE
                Log.w(TAG, "her-join geweigerd (${e.code}): ${e.message}")
                ui.post { listener.onFatal(reason) }
                return false
            } catch (e: Exception) {
                Log.w(TAG, "her-join mislukt: ${e.message}")
            }
            try {
                Thread.sleep(REJOIN_BACKOFF_MS * attempt)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        if (!networkOnly) {
            ui.post { listener.onFatal(Reason.SESSION_GONE) }
            return false
        }
        return null
    }

    private fun sendMask(mask: Int) {
        Api.post(
            baseUrl,
            JSONObject()
                .put("action", "ctrl-input")
                .put("token", token)
                .put("mask", mask)
        )
    }

    private fun reportResult(ok: Boolean) {
        val notify: Boolean
        val connected: Boolean
        synchronized(lock) {
            consecutiveErrors = if (ok) 0 else consecutiveErrors + 1
            connected = consecutiveErrors < ERRORS_BEFORE_LOST
            notify = connected != reportedConnected
            if (notify) reportedConnected = connected
        }
        if (notify) ui.post { listener.onConnectionState(connected) }
    }
}
