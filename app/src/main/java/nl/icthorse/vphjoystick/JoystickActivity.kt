package nl.icthorse.vphjoystick

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Joystick-scherm: kop met het spelernummer (uit het slot), verbindingsstatus,
 * [JoystickView] en FIRE-knop. Portrait, fullscreen, scherm blijft aan.
 *
 * Elke maskverandering gaat direct naar [ControllerLink] (die ook de 500 ms-
 * heartbeat verzorgt). Bij onPause wordt het slot netjes vrijgegeven
 * (`ctrl-input mask=0` + `ctrl-leave`) en keren we terug naar het koppelscherm:
 * een sessie mag geen slot bezet houden terwijl de app op de achtergrond staat.
 *
 * v0.4.0-Rusch: de sessiecode reist mee, zodat [ControllerLink] na een HTTP 401
 * zelf opnieuw kan koppelen. Kan dat niet meer, dan gaan we met een duidelijke
 * melding terug naar het koppelscherm in plaats van eeuwig een dood token te
 * blijven posten terwijl het scherm "Verbonden" toont.
 */
class JoystickActivity : Activity(), ControllerLink.Listener {

    companion object {
        private const val MASK_FIRE = 0x10
    }

    private lateinit var playerText: TextView
    private lateinit var statusText: TextView
    private lateinit var sharedHint: TextView
    private lateinit var joystickView: JoystickView
    private lateinit var fireButton: TextView

    private var link: ControllerLink? = null
    private var directionMask = 0
    private var firePressed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_joystick)

        playerText = findViewById(R.id.playerText)
        statusText = findViewById(R.id.statusText)
        sharedHint = findViewById(R.id.sharedHint)
        joystickView = findViewById(R.id.joystickView)
        fireButton = findViewById(R.id.fireButton)

        val token = intent.getStringExtra(PairActivity.EXTRA_TOKEN)
        val slot = intent.getIntExtra(PairActivity.EXTRA_SLOT, 0)
        val baseUrl = intent.getStringExtra(PairActivity.EXTRA_BASE_URL) ?: Api.DEFAULT_BASE_URL
        val code = intent.getStringExtra(PairActivity.EXTRA_CODE).orEmpty()

        if (token.isNullOrBlank() || code.isBlank()) {
            finish()
            return
        }

        playerText.text = getString(R.string.player_n, slot + 1)
        statusText.text = getString(R.string.status_connected)
        showSharedHint(slot)

        joystickView.onDirectionChanged = { mask ->
            directionMask = mask
            pushMask()
        }

        fireButton.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    firePressed = true
                    pushMask()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    firePressed = false
                    pushMask()
                }
            }
            true
        }

        link = ControllerLink(baseUrl, token, code, this).apply { start() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    @Suppress("DEPRECATION")
    private fun enterImmersiveMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
    }

    private fun pushMask() {
        link?.updateMask(directionMask or (if (firePressed) MASK_FIRE else 0))
    }

    // ------------------------------------------------- ControllerLink.Listener

    override fun onConnectionState(connected: Boolean) {
        statusText.setTextColor(getColor(if (connected) R.color.vph_text else R.color.vph_error))
        statusText.text = getString(
            if (connected) R.string.status_connected else R.string.status_lost
        )
    }

    /* Speler 2 kan ook door een gast van "Samen spelen" worden bestuurd; sinds
     * v0.5.0 sluiten die twee elkaar niet meer uit maar tellen ze op. Wie op deze
     * plek zit hoort dat te weten, anders lijkt het alsof zijn stick "vanzelf"
     * beweegt. */
    private fun showSharedHint(slot: Int) {
        sharedHint.visibility = if (slot == 1) android.view.View.VISIBLE else android.view.View.GONE
    }

    /** Na een 401 opnieuw gekoppeld — mogelijk in een ander slot. */
    override fun onRejoined(slot: Int) {
        playerText.text = getString(R.string.player_n, slot + 1)
        showSharedHint(slot)
        statusText.setTextColor(getColor(R.color.vph_text))
        statusText.text = getString(R.string.status_rejoined)
    }

    /** Niet meer te herstellen: terug naar het koppelscherm mét uitleg. */
    override fun onFatal(reason: ControllerLink.Reason) {
        if (isFinishing) return
        val msg = getString(
            when (reason) {
                ControllerLink.Reason.SLOTS_FULL -> R.string.error_slots_full
                ControllerLink.Reason.SESSION_GONE -> R.string.error_session_gone
            }
        )
        startActivity(
            Intent(this, PairActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(PairActivity.EXTRA_MESSAGE, msg)
        )
        finish()
    }

    // ------------------------------------------------------------- lifecycle

    override fun onPause() {
        super.onPause()
        link?.stopAndLeave()
        link = null
        if (!isFinishing) finish()
    }
}
