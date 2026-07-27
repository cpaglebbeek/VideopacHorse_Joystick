package nl.icthorse.vphjoystick

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Eén fullscreen scherm: statusregel + joystick-pad + FIRE-knop.
 * Portrait, scherm blijft aan. Alle teksten in het Nederlands.
 *
 * Lifecycle-herstel: een BroadcastReceiver op ACTION_STATE_CHANGED herstart de
 * BLE-server automatisch als Bluetooth aan gaat (en stopt hem netjes bij uit);
 * onResume() probeert start() opnieuw zodra permissies (alsnog) verleend zijn.
 * Geen app-herstart nodig.
 */
class MainActivity : Activity(), BleJoystickServer.Listener {

    companion object {
        private const val REQUEST_BLE_PERMISSIONS = 1
        private const val MASK_FIRE = 0x10
    }

    private lateinit var deviceNameText: TextView
    private lateinit var statusText: TextView
    private lateinit var hintText: TextView
    private lateinit var joystickView: JoystickView
    private lateinit var fireButton: TextView

    private var server: BleJoystickServer? = null
    private lateinit var deviceName: String

    private var directionMask = 0
    private var firePressed = false
    private var btReceiverRegistered = false

    /** Bluetooth uit/aan tijdens gebruik: server netjes stoppen resp. herstarten. */
    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> {
                    server?.stop()          /* eventuele dode GATT/advertiser-state resetten */
                    startServerIfAllowed()
                }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    server?.stop()
                    statusText.text = getString(R.string.error_bluetooth_off)
                    hintText.text = ""
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        deviceNameText = findViewById(R.id.deviceNameText)
        statusText = findViewById(R.id.statusText)
        hintText = findViewById(R.id.hintText)
        joystickView = findViewById(R.id.joystickView)
        fireButton = findViewById(R.id.fireButton)

        val id = DeviceId.idBytes(this)
        deviceName = DeviceId.shortName(id)
        deviceNameText.text = deviceName

        server = BleJoystickServer(this, id, deviceName, this)

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

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            statusText.text = getString(R.string.error_no_bluetooth)
            return
        }

        /* ACTION_STATE_CHANGED is een protected system-broadcast: geen
         * RECEIVER_EXPORTED-vlag nodig, ook niet op API 33+. */
        registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        btReceiverRegistered = true

        ensurePermissionsAndStart()
    }

    override fun onResume() {
        super.onResume()
        /* Herstelpad: permissies alsnog verleend via Instellingen, of Bluetooth
         * inmiddels aan. start() is idempotent (no-op als hij al draait) en
         * meldt Bluetooth-uit opnieuw via onBluetoothOff(). */
        if (btReceiverRegistered && hasBlePermissions()) server?.start()
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
        val mask = directionMask or (if (firePressed) MASK_FIRE else 0)
        server?.updateMask(mask)
    }

    // ------------------------------------------------------------ permissies

    private val blePermissions = arrayOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT
    )

    /** API 26-30: BLUETOOTH/BLUETOOTH_ADMIN zijn install-time permissies. */
    private fun hasBlePermissions(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            blePermissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun startServerIfAllowed() {
        if (hasBlePermissions()) {
            hintText.setOnClickListener(null)
            server?.start()
        } else {
            statusText.text = getString(R.string.error_permissions)
        }
    }

    private fun ensurePermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = blePermissions.filter {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) {
                /* Rationale: uitleggen wat "Apparaten in de buurt" hier doet
                 * (alleen advertising naar de Videopac-pagina; geen scan, geen internet). */
                if (needed.any { shouldShowRequestPermissionRationale(it) }) {
                    hintText.text = getString(R.string.hint_permission_rationale)
                }
                requestPermissions(needed.toTypedArray(), REQUEST_BLE_PERMISSIONS)
                return
            }
        }
        server?.start()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_BLE_PERMISSIONS) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            server?.start()
        } else {
            /* Ook na "niet meer vragen": één tik opent de app-instellingen;
             * bij terugkeer met verleende permissie start onResume() de server. */
            statusText.text = getString(R.string.error_permissions)
            hintText.text = getString(R.string.hint_open_settings)
            hintText.setOnClickListener { openAppSettings() }
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
        )
    }

    // ---------------------------------------------- BleJoystickServer.Listener

    override fun onAdvertisingStarted() {
        hintText.setOnClickListener(null)
        statusText.text = getString(R.string.status_advertising, deviceName)
        hintText.text = getString(R.string.hint_player)
    }

    override fun onSubscriberCountChanged(subscriberCount: Int) {
        if (subscriberCount > 0) {
            statusText.text = getString(R.string.status_connected, deviceName)
            hintText.text = getString(R.string.hint_connected)
        } else {
            statusText.text = getString(R.string.status_advertising, deviceName)
            hintText.text = getString(R.string.hint_player)
        }
    }

    override fun onAdvertiseFailed(errorCode: Int) {
        if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED) {
            statusText.text = getString(R.string.error_no_peripheral)
            return
        }
        statusText.text = getString(R.string.error_advertise_failed, errorCode)
        /* Retry-pad: één tik probeert het opnieuw (server volledig resetten). */
        hintText.text = getString(R.string.hint_retry)
        hintText.setOnClickListener {
            hintText.setOnClickListener(null)
            hintText.text = ""
            server?.stop()
            startServerIfAllowed()
        }
    }

    override fun onPeripheralUnsupported() {
        statusText.text = getString(R.string.error_no_peripheral)
    }

    override fun onBluetoothOff() {
        /* Geen doodlopende straat: de btStateReceiver herstart de server
         * automatisch zodra Bluetooth weer aan gaat. */
        statusText.text = getString(R.string.error_bluetooth_off)
    }

    override fun onDestroy() {
        if (btReceiverRegistered) {
            unregisterReceiver(btStateReceiver)
            btReceiverRegistered = false
        }
        server?.stop()
        server = null
        super.onDestroy()
    }
}
