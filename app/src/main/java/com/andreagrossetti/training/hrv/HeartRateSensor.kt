package com.andreagrossetti.training.hrv

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.andreagrossetti.training.workout.RemoteButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class SensorStatus(
    val connected: Boolean = false,
    val name: String? = null,
    val bpm: Int? = null,
    /** False when the sensor reports no finger; null if it doesn't say. */
    val contact: Boolean? = null,
    val battery: Int? = null,
)

/**
 * One notification of the standard Heart Rate Measurement characteristic.
 * [stale] means the intervals are repeats, not new beats: the CorSense sends 0 in the
 * "energy expended" field when its signal is poor, with made-up intervals that drift towards the last one.
 */
data class HeartRatePacket(val bpm: Int, val contact: Boolean?, val rrMs: List<Int>, val energy: Int? = null, val stale: Boolean = false)

/**
 * Any Bluetooth LE sensor with the standard Heart Rate service (Elite HRV CorSense, chest straps).
 * Scans, connects to the first one found and delivers beat-to-beat intervals to [onPacket].
 * [onDisconnect] fires when a connection drops; it then scans again until [stop].
 * All callbacks are delivered on the main thread.
 */
class HeartRateSensor(
    private val context: Context,
    private val onPacket: (HeartRatePacket) -> Unit,
    private val onDisconnect: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var gatt: BluetoothGatt? = null
    private var running = false
    private var scanning = false

    private val _status = MutableStateFlow(SensorStatus())
    val status: StateFlow<SensorStatus> = _status.asStateFlow()

    @SuppressLint("MissingPermission") // start() checks hasPermissions()
    fun start() {
        if (running || !RemoteButton.hasPermissions(context)) return
        running = true
        scan()
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        running = false
        stopScan()
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
        _status.value = SensorStatus()
    }

    // No scan filter: some sensors only list the service in the scan response, so match by hand.
    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val uuids = result.scanRecord?.serviceUuids.orEmpty()
            val name = result.scanRecord?.deviceName ?: result.device.name
            val isHr = ParcelUuid(HR_SERVICE) in uuids ||
                name?.contains("corsense", ignoreCase = true) == true
            if (!isHr) return
            Log.d(TAG, "found ${result.device.address} \"$name\" rssi=${result.rssi} uuids=$uuids")
            main.post { if (running && gatt == null) connect(result.device, name) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
            scanning = false
            main.postDelayed({ if (running && gatt == null) scan() }, RETRY_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun scan() {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            main.postDelayed({ if (running && gatt == null) scan() }, RETRY_MS)
            return
        }
        if (scanning) return
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(null, settings, scanCallback) }
            .onSuccess { scanning = true }
            .onFailure { Log.w(TAG, "startScan", it) }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice, name: String?) {
        stopScan()
        Log.d(TAG, "connecting to ${device.address}")
        _status.value = SensorStatus(name = name)
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun dropped() {
        gatt?.let { runCatching { it.close() } }
        gatt = null
        val wasConnected = _status.value.connected
        _status.value = SensorStatus()
        if (wasConnected) onDisconnect()
        if (running) scan()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (gatt != this@HeartRateSensor.gatt) return@post
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        // The default balanced interval can drop notifications, and every lost one is lost beats.
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> dropped()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            main.post {
                val hr = gatt.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT)
                if (hr == null) {
                    Log.w(TAG, "no heart rate characteristic; services=${gatt.services.map { it.uuid }}")
                    gatt.disconnect()
                    return@post
                }
                _status.value = _status.value.copy(connected = true)
                enableNotifications(gatt, hr)
            }
        }

        // GATT allows one operation at a time: the battery is read once notifications are on.
        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (descriptor.characteristic.uuid == HR_MEASUREMENT) {
                    gatt.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_LEVEL)?.let { gatt.readCharacteristic(it) }
                }
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (c.uuid == BATTERY_LEVEL && value.isNotEmpty()) {
                val level = value[0].toInt() and 0xFF
                main.post { _status.value = _status.value.copy(battery = level) }
            }
        }

        @Deprecated("Before API 33")
        override fun onCharacteristicRead(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) onCharacteristicRead(gatt, c, c.value ?: return, status)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid != HR_MEASUREMENT) return
            val packet = parseHeartRate(value)?.let { p ->
                val corsense = _status.value.name?.contains("corsense", ignoreCase = true) == true
                if (corsense && p.energy == 0) p.copy(stale = true) else p
            } ?: return
            Log.d(TAG, "raw=${value.joinToString("") { "%02x".format(it) }} hr=${packet.bpm} stale=${packet.stale} rr=${packet.rrMs}")
            main.post {
                if (gatt != this@HeartRateSensor.gatt) return@post
                _status.value = _status.value.copy(bpm = packet.bpm, contact = packet.contact)
                onPacket(packet)
            }
        }

        @Deprecated("Before API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) onCharacteristicChanged(gatt, c, c.value ?: return)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt, c: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(CCCD) ?: return
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(cccd, value)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(cccd)
        }
    }

    companion object {
        private const val TAG = "HeartRateSensor"
        val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        private val HR_MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        private val BATTERY_SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val BATTERY_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val RETRY_MS = 5_000L

        /**
         * Parses a Heart Rate Measurement value (Bluetooth GATT spec): flags, then the heart rate
         * (8 or 16 bit), optional energy expended, then RR intervals in 1/1024 s.
         */
        fun parseHeartRate(value: ByteArray): HeartRatePacket? {
            if (value.isEmpty()) return null
            val flags = value[0].toInt()
            fun u8(i: Int) = value[i].toInt() and 0xFF
            fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
            var i = 1
            val wide = flags and 0x01 != 0
            if (value.size < i + if (wide) 2 else 1) return null
            val bpm = if (wide) u16(i) else u8(i)
            i += if (wide) 2 else 1
            val contact = if (flags and 0x04 != 0) flags and 0x02 != 0 else null
            var energy: Int? = null
            if (flags and 0x08 != 0) {
                if (value.size >= i + 2) energy = u16(i)
                i += 2
            }
            val rr = mutableListOf<Int>()
            if (flags and 0x10 != 0) {
                while (i + 1 < value.size) {
                    rr += Math.round(u16(i) * 1000.0 / 1024).toInt()
                    i += 2
                }
            }
            return HeartRatePacket(bpm, contact, rr, energy)
        }
    }
}
