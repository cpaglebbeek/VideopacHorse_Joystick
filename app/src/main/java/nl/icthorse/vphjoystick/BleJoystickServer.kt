package nl.icthorse.vphjoystick

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE GATT-server + advertiser conform de VideopacHorse BLE-protocol-spec.
 *
 * - Service-UUID:        7a0b1000-56e1-4d2a-9f0a-c0de00000001
 * - Characteristic "joy": 7a0b1000-56e1-4d2a-9f0a-c0de00000002 (NOTIFY + READ)
 * - Payload exact 9 bytes: [0..7] = apparaat-ID (SHA-256(ANDROID_ID)[0..7]),
 *   [8] = joystick-bitmask (bit0=UP bit1=DOWN bit2=LEFT bit3=RIGHT bit4=FIRE).
 * - Notify bij ELKE maskverandering + heartbeat elke 500 ms (zelfde payload).
 *
 * Bewust GEEN HID-profiel en geen internet: alleen een GATT-client (de
 * webpagina via Web Bluetooth) consumeert de input; het OS doet er niets mee.
 *
 * Alle aanroepen die BLUETOOTH_ADVERTISE/CONNECT vereisen zitten achter de
 * permissiecheck in MainActivity; vandaar de klasse-brede suppress.
 */
@SuppressLint("MissingPermission")
class BleJoystickServer(
    private val context: Context,
    private val deviceId: ByteArray,
    private val deviceName: String,
    private val listener: Listener
) {

    interface Listener {
        /** Op de main-thread. subscriberCount = centrals met notificaties aan. */
        fun onAdvertisingStarted()
        fun onSubscriberCountChanged(subscriberCount: Int)
        fun onAdvertiseFailed(errorCode: Int)
        fun onPeripheralUnsupported()
        fun onBluetoothOff()
    }

    companion object {
        private const val TAG = "BleJoystickServer"

        val SERVICE_UUID: UUID = UUID.fromString("7a0b1000-56e1-4d2a-9f0a-c0de00000001")
        val JOY_CHAR_UUID: UUID = UUID.fromString("7a0b1000-56e1-4d2a-9f0a-c0de00000002")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val HEARTBEAT_MS = 500L
        private const val PAYLOAD_SIZE = 9
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var joyCharacteristic: BluetoothGattCharacteristic? = null
    private var running = false

    /** Centrals die via de CCCD notificaties hebben aangezet. */
    private val subscribers = ConcurrentHashMap.newKeySet<BluetoothDevice>()

    /** Actuele payload: 8 bytes ID + 1 byte mask. */
    private val payload = ByteArray(PAYLOAD_SIZE).also {
        require(deviceId.size == 8) { "deviceId moet exact 8 bytes zijn" }
        deviceId.copyInto(it, 0)
        it[8] = 0
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            notifyAll(payload.copyOf())
            mainHandler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    // ------------------------------------------------------------------ start

    /** Start GATT-server + advertising. Meldt fouten via de listener. */
    fun start() {
        if (running) return
        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            listener.onBluetoothOff()
            return
        }

        // Peripheral-modus check: zonder advertiser kan de app zichzelf niet
        // zichtbaar maken. Op sommige (oudere/goedkope) toestellen is dit null.
        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            listener.onPeripheralUnsupported()
            return
        }

        gattServer = bluetoothManager.openGattServer(context, gattCallback)
        if (gattServer == null) {
            listener.onPeripheralUnsupported()
            return
        }

        val characteristic = BluetoothGattCharacteristic(
            JOY_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        characteristic.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        joyCharacteristic = characteristic

        val service = BluetoothGattService(
            SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        service.addCharacteristic(characteristic)
        gattServer?.addService(service)

        startAdvertising()
        running = true
        mainHandler.postDelayed(heartbeat, HEARTBEAT_MS)
    }

    private fun startAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        // Primaire advertise-payload (max 31 bytes): flags + 128-bit service-UUID.
        // GEEN includeDeviceName: dat zou de globale adapternaam meesturen en
        // die zetten we bewust NIET om (spec: adapternaam niet globaal wijzigen).
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        // Scan-response: de naam "VPH-XXXX" als service-data bij onze eigen
        // service-UUID (Android kent geen per-advertentie local name; zie README).
        val scanResponse = AdvertiseData.Builder()
            .addServiceData(
                ParcelUuid(SERVICE_UUID),
                deviceName.toByteArray(Charsets.US_ASCII)
            )
            .build()

        advertiser?.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            mainHandler.post { listener.onAdvertisingStarted() }
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising mislukt: $errorCode")
            mainHandler.post { listener.onAdvertiseFailed(errorCode) }
        }
    }

    // ------------------------------------------------------------------- stop

    fun stop() {
        if (!running) return
        running = false
        mainHandler.removeCallbacks(heartbeat)
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stopAdvertising: ${e.message}")
        }
        try {
            gattServer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "gattServer.close: ${e.message}")
        }
        gattServer = null
        advertiser = null
        subscribers.clear()
    }

    // ------------------------------------------------------------------ input

    /**
     * Nieuw joystick-masker (bit0=UP bit1=DOWN bit2=LEFT bit3=RIGHT bit4=FIRE).
     * Bij verandering: direct notify naar alle subscribers. De heartbeat blijft
     * daarnaast elke 500 ms dezelfde payload sturen.
     */
    fun updateMask(mask: Int) {
        val b = (mask and 0x1F).toByte()
        synchronized(payload) {
            if (payload[8] == b) return
            payload[8] = b
        }
        notifyAll(payload.copyOf())
    }

    /**
     * Bewust géén notify-flow-control (wachten op onNotificationSent): een
     * gedropte notificatie wordt door de 500 ms-heartbeat sowieso opnieuw
     * verstuurd en de webkant heeft een 2 s-watchdog (mask 0 bij stilte), dus
     * een gemiste 'release' herstelt binnen max ~500 ms. Wél loggen we de
     * return-status zodat drops zichtbaar zijn in logcat.
     */
    private fun notifyAll(value: ByteArray) {
        val server = gattServer ?: return
        val characteristic = joyCharacteristic ?: return
        for (device in subscribers) {
            try {
                val ok = if (Build.VERSION.SDK_INT >= 33) {
                    server.notifyCharacteristicChanged(device, characteristic, false, value) ==
                        BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = value
                    @Suppress("DEPRECATION")
                    server.notifyCharacteristicChanged(device, characteristic, false)
                }
                if (!ok) {
                    Log.w(TAG, "notify naar ${device.address} gedropt; heartbeat herhaalt binnen 500 ms")
                }
            } catch (e: Exception) {
                Log.w(TAG, "notify naar ${device.address} mislukt: ${e.message}")
            }
        }
    }

    // --------------------------------------------------------------- callback

    private val gattCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (subscribers.remove(device)) {
                    postSubscriberCount()
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == JOY_CHAR_UUID) {
                val value = synchronized(payload) { payload.copyOf() }
                val slice = if (offset < value.size) value.copyOfRange(offset, value.size) else ByteArray(0)
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
            } else {
                gattServer?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null
                )
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                val value = if (subscribers.contains(device)) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            } else {
                gattServer?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null
                )
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            var status = BluetoothGatt.GATT_SUCCESS
            if (descriptor.uuid == CCCD_UUID) {
                when {
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) -> {
                        subscribers.add(device)
                        postSubscriberCount()
                    }
                    value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) -> {
                        subscribers.remove(device)
                        postSubscriberCount()
                    }
                    else -> status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                }
            } else {
                status = BluetoothGatt.GATT_WRITE_NOT_PERMITTED
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, status, offset, value)
            }
        }
    }

    private fun postSubscriberCount() {
        val count = subscribers.size
        mainHandler.post { listener.onSubscriberCountChanged(count) }
    }
}
