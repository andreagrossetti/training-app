package com.andreagrossetti.training.workout

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class RemoteStatus(val connected: Boolean = false, val battery: Int? = null)

/**
 * The DIY one-button Bluetooth LE remote (see hardware/remote). It advertises [SERVICE];
 * [BUTTON] notifies 1 when the button goes down and 0 when it's released. Presses are
 * turned into a click or a long press here, so the firmware never needs changing.
 *
 * While started, it scans for the remote, connects, and scans again whenever the
 * connection drops (the remote sleeps after a while and wakes on a press).
 * All callbacks are delivered on the main thread.
 */
class RemoteButton(
    private val context: Context,
    private val onClick: () -> Unit,
    private val onLongPress: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var gatt: BluetoothGatt? = null
    private var running = false
    private var scanning = false
    private var longPressFired = false

    private val _status = MutableStateFlow(RemoteStatus())
    val status: StateFlow<RemoteStatus> = _status.asStateFlow()

    private val longPress = Runnable {
        longPressFired = true
        onLongPress()
    }

    @SuppressLint("MissingPermission") // start() checks hasPermissions()
    fun start() {
        if (running || !hasPermissions(context)) return
        running = true
        scan()
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        running = false
        stopScan()
        main.removeCallbacks(longPress)
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
        _status.value = RemoteStatus()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post { if (running && gatt == null) connect(result.device) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
            scanning = false
            // Usually "scanning too frequently": try again in a bit.
            main.postDelayed({ if (running && gatt == null) scan() }, RETRY_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun scan() {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            // Bluetooth off: check again later.
            main.postDelayed({ if (running && gatt == null) scan() }, RETRY_MS)
            return
        }
        if (scanning) return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        runCatching { scanner.startScan(listOf(filter), settings, scanCallback) }
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
    private fun connect(device: BluetoothDevice) {
        stopScan()
        Log.d(TAG, "connecting to ${device.address}")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private fun dropped() {
        gatt?.let { runCatching { it.close() } }
        gatt = null
        main.removeCallbacks(longPress)
        _status.value = RemoteStatus()
        if (running) scan()
    }

    private fun onButton(down: Boolean) {
        if (down) {
            longPressFired = false
            main.postDelayed(longPress, LONG_PRESS_MS)
        } else {
            main.removeCallbacks(longPress)
            if (!longPressFired) onClick()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (gatt != this@RemoteButton.gatt) return@post
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> gatt.discoverServices()
                    BluetoothProfile.STATE_DISCONNECTED -> dropped()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            main.post {
                val button = gatt.getService(SERVICE)?.getCharacteristic(BUTTON)
                if (button == null) {
                    gatt.disconnect()
                    return@post
                }
                _status.value = _status.value.copy(connected = true)
                enableNotifications(gatt, button)
            }
        }

        // GATT allows one operation at a time: the battery is read once notifications are on.
        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (descriptor.characteristic.uuid == BUTTON) {
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
            if (c.uuid == BUTTON && value.isNotEmpty()) {
                val down = value[0].toInt() != 0
                main.post { onButton(down) }
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
        private const val TAG = "RemoteButton"
        val SERVICE: UUID = UUID.fromString("7e1a0001-5c3b-4f7e-9a2d-6b1f0c2e8a40")
        val BUTTON: UUID = UUID.fromString("7e1a0002-5c3b-4f7e-9a2d-6b1f0c2e8a40")
        private val BATTERY_SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val BATTERY_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val LONG_PRESS_MS = 700L
        private const val RETRY_MS = 10_000L

        /** Runtime permissions needed to find and connect to the remote. */
        val permissions: Array<String>
            get() = if (Build.VERSION.SDK_INT >= 31) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }

        fun hasPermissions(context: Context): Boolean = permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
