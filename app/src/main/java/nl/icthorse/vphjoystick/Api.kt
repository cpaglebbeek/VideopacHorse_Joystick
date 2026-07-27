package nl.icthorse.vphjoystick

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimale JSON-over-HTTP-client voor de VideopacHorse pairing-API.
 *
 * Bewust dependency-vrij: alleen `HttpURLConnection` + `org.json` uit het
 * platform. Elke aanroep is één POST met een JSON-body naar de basis-URL;
 * het veld `action` bepaalt het endpoint (zie PROTOCOL in README.md).
 *
 * Alle methoden blokkeren en MOETEN op een achtergrond-thread worden
 * aangeroepen.
 */
object Api {

    /** Bindende basis-URL uit de protocol-spec v0.4.0. */
    const val DEFAULT_BASE_URL = "https://horsecloud55.ddns.net/videopac/api/"

    /** Connect- én read-time-out (spec: 3 s). */
    const val TIMEOUT_MS = 3000

    /** HTTP-fout met (indien aanwezig) de Nederlandse servermelding uit `error`. */
    class HttpError(val code: Int, val serverMessage: String?) :
        IOException(serverMessage ?: "HTTP $code")

    /**
     * POST `body` naar `baseUrl` en geef het JSON-antwoord terug.
     *
     * @throws HttpError bij een niet-2xx-status (incl. 409 = joysticks vol)
     * @throws IOException bij netwerkfouten of een onleesbaar antwoord
     */
    fun post(baseUrl: String, body: JSONObject): JSONObject {
        val conn = URL(normalizeUrl(baseUrl)).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.doOutput = true
            conn.useCaches = false
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")

            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""

            if (code !in 200..299) throw HttpError(code, extractError(text))

            return if (text.isBlank()) JSONObject() else parse(text)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Normaliseert een door de gebruiker ingetikte server-URL:
     * schema aanvullen (https) en een afsluitende slash garanderen, zodat het
     * instellingenveld ook "horsecloud55.ddns.net/videopac/api" accepteert.
     */
    fun normalizeUrl(raw: String): String {
        var url = raw.trim()
        if (url.isEmpty()) url = DEFAULT_BASE_URL
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            url = "https://$url"
        }
        if (!url.endsWith("/")) url = "$url/"
        return url
    }

    private fun parse(text: String): JSONObject = try {
        JSONObject(text)
    } catch (e: JSONException) {
        throw IOException("Onleesbaar antwoord van de server", e)
    }

    /** Haalt het `error`-veld uit een foutantwoord; null als dat er niet in staat. */
    private fun extractError(text: String): String? = try {
        JSONObject(text).optString("error").ifBlank { null }
    } catch (_: JSONException) {
        null
    }
}
