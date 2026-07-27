package nl.icthorse.vphjoystick

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.util.UUID

/**
 * Stabiel apparaat-ID conform de BLE-protocol-spec:
 * de eerste 8 bytes van SHA-256 over Settings.Secure.ANDROID_ID.
 *
 * ANDROID_ID is stabiel per (app-signing-key, gebruiker, toestel) en overleeft
 * app-herinstallatie niet per se, maar is stabiel genoeg om spelers op de
 * webkant uit elkaar te houden — precies wat de spec vraagt.
 */
object DeviceId {

    private const val PREFS = "vph_device_id"
    private const val KEY_FALLBACK = "fallback_id"

    /** Eerste 8 bytes van SHA-256(ANDROID_ID) — payload-bytes 0-7. */
    @SuppressLint("HardwareIds")
    fun idBytes(context: Context): ByteArray {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: fallbackId(context)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(androidId.toByteArray(Charsets.UTF_8))
        return digest.copyOfRange(0, 8)
    }

    /**
     * ANDROID_ID kan (zeldzaam) null zijn. Een vaste fallback-string zou álle
     * getroffen toestellen hetzelfde ID geven, waardoor twee zulke telefoons
     * op de webkant tot één speler samensmelten. Daarom: één random UUID,
     * eenmalig gegenereerd en persistent per installatie (SharedPreferences) —
     * even stabiel als de spec vraagt en per toestel uniek.
     */
    private fun fallbackId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_FALLBACK, null)?.let { return it }
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_FALLBACK, fresh).apply()
        return fresh
    }

    /** Hex-weergave van het 8-byte ID (16 hex-tekens, lowercase). */
    fun idHex(id: ByteArray): String =
        id.joinToString("") { "%02x".format(it) }

    /** Apparaatnaam "VPH-XXXX": laatste 4 hex-tekens van het ID, uppercase. */
    fun shortName(id: ByteArray): String =
        "VPH-" + idHex(id).takeLast(4).uppercase()
}
