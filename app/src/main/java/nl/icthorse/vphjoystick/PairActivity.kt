package nl.icthorse.vphjoystick

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.json.JSONObject

/**
 * Koppelscherm: de gebruiker tikt de 6-tekens sessiecode over van de
 * Videopac-pagina en drukt op "Koppel". De app doet `ctrl-join` en gaat bij
 * succes door naar [JoystickActivity] met het ctrl-token en het slot.
 *
 * Geen Bluetooth, geen HID: de enige koppeling is deze HTTPS-sessie.
 */
class PairActivity : Activity() {

    companion object {
        const val EXTRA_TOKEN = "nl.icthorse.vphjoystick.TOKEN"
        const val EXTRA_SLOT = "nl.icthorse.vphjoystick.SLOT"
        const val EXTRA_BASE_URL = "nl.icthorse.vphjoystick.BASE_URL"

        /** Sessiecode; [JoystickActivity] heeft 'm nodig om na een 401 te herkoppelen. */
        const val EXTRA_CODE = "nl.icthorse.vphjoystick.CODE"

        /** Melding waarmee [JoystickActivity] ons terugroept (sessie weg / vol). */
        const val EXTRA_MESSAGE = "nl.icthorse.vphjoystick.MESSAGE"

        private const val PREFS = "vph_joystick"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_LAST_CODE = "last_code"
        private const val CODE_LEN = 6
    }

    private lateinit var codeInput: EditText
    private lateinit var urlInput: EditText
    private lateinit var joinButton: Button
    private lateinit var messageText: TextView

    private val ui = Handler(Looper.getMainLooper())
    private var busy = false

    /** Alleen het spec-alfabet A-Z en 2-9, automatisch naar hoofdletters. */
    private val codeFilter = InputFilter { source, start, end, _, _, _ ->
        val out = StringBuilder(end - start)
        for (i in start until end) {
            val c = source[i].uppercaseChar()
            if (c in 'A'..'Z' || c in '2'..'9') out.append(c)
        }
        if (out.contentEquals(source.subSequence(start, end))) null else out.toString()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_pair)

        codeInput = findViewById(R.id.codeInput)
        urlInput = findViewById(R.id.urlInput)
        joinButton = findViewById(R.id.joinButton)
        messageText = findViewById(R.id.messageText)

        codeInput.filters = arrayOf(codeFilter, InputFilter.LengthFilter(CODE_LEN))
        urlInput.setText(prefs().getString(KEY_BASE_URL, Api.DEFAULT_BASE_URL))
        /* Laatst gebruikte code voorinvullen: na "sessie weg" is opnieuw
         * koppelen dan één tik werk zodra de host een nieuwe sessie start —
         * en bij een tijdelijke onderbreking is het exact dezelfde code. */
        codeInput.setText(prefs().getString(KEY_LAST_CODE, ""))

        joinButton.setOnClickListener { join() }
        showHandoffMessage(intent)
    }

    override fun onNewIntent(newIntent: Intent?) {
        super.onNewIntent(newIntent)
        newIntent?.let { setIntent(it); showHandoffMessage(it) }
    }

    override fun onResume() {
        super.onResume()
        /* Terug van het joystick-scherm (of na ctrl-leave): schoon startpunt. */
        setBusy(false)
    }

    /** Melding tonen waarmee [JoystickActivity] ons heeft teruggeroepen. */
    private fun showHandoffMessage(source: Intent?) {
        val msg = source?.getStringExtra(EXTRA_MESSAGE)?.takeIf { it.isNotBlank() } ?: return
        showMessage(msg, error = true)
        source.removeExtra(EXTRA_MESSAGE)     // niet nogmaals tonen bij rotatie
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun join() {
        if (busy) return

        val code = codeInput.text.toString().trim().uppercase()
        if (code.length != CODE_LEN) {
            showMessage(getString(R.string.error_code_length), error = true)
            return
        }

        val baseUrl = Api.normalizeUrl(urlInput.text.toString())
        urlInput.setText(baseUrl)
        prefs().edit().putString(KEY_BASE_URL, baseUrl).putString(KEY_LAST_CODE, code).apply()

        hideKeyboard()
        setBusy(true)
        showMessage(getString(R.string.status_joining), error = false)

        Thread({
            try {
                val response = Api.post(
                    baseUrl,
                    JSONObject().put("action", "ctrl-join").put("code", code)
                )
                val token = response.optString("ctrl_token")
                val slot = response.optInt("slot", -1)
                if (token.isBlank() || slot < 0) {
                    throw IllegalStateException(getString(R.string.error_bad_response))
                }
                ui.post { openJoystick(token, slot, baseUrl, code) }
            } catch (e: Exception) {
                val text = describe(e)
                ui.post {
                    setBusy(false)
                    showMessage(text, error = true)
                }
            }
        }, "vph-ctrl-join").apply { isDaemon = true }.start()
    }

    private fun openJoystick(token: String, slot: Int, baseUrl: String, code: String) {
        startActivity(
            Intent(this, JoystickActivity::class.java)
                .putExtra(EXTRA_TOKEN, token)
                .putExtra(EXTRA_SLOT, slot)
                .putExtra(EXTRA_BASE_URL, baseUrl)
                .putExtra(EXTRA_CODE, code)
        )
    }

    /** Vertaalt een fout naar één duidelijke Nederlandse regel. */
    private fun describe(e: Exception): String = when {
        e is Api.HttpError && e.code == 409 -> getString(R.string.error_slots_full)
        /* De API antwoordt 400 op een onbekende/verlopen code (index.php:
         * 'code verlopen of onbekend'); 404 komt van tussenliggende proxy's of
         * een verkeerd server-pad. De app filtert lengte én alfabet al, dus een
         * 400 hier betekent in de praktijk altijd: deze code doet het niet meer.
         * Vóór v0.4.0-Rusch stond hier alleen 404 en was de vriendelijke tekst
         * dus onbereikbaar. */
        e is Api.HttpError && (e.code == 400 || e.code == 404) ->
            getString(R.string.error_unknown_code)
        e is Api.HttpError && e.code in 500..599 ->
            getString(R.string.error_server, e.code)
        e is Api.HttpError ->
            e.serverMessage ?: getString(R.string.error_server, e.code)
        e is IllegalStateException -> e.message ?: getString(R.string.error_bad_response)
        else -> e.message?.takeIf { it.isNotBlank() && it != "null" }
            ?.let { getString(R.string.error_network_detail, it) }
            ?: getString(R.string.error_network)
    }

    private fun setBusy(value: Boolean) {
        busy = value
        joinButton.isEnabled = !value
        joinButton.setText(if (value) R.string.joining else R.string.join)
        codeInput.isEnabled = !value
        urlInput.isEnabled = !value
    }

    private fun showMessage(text: String, error: Boolean) {
        messageText.text = text
        messageText.setTextColor(
            getColor(if (error) R.color.vph_error else R.color.vph_text_dim)
        )
        messageText.visibility = View.VISIBLE
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(codeInput.windowToken, 0)
    }
}
