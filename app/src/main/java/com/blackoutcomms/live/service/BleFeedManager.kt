package com.blackoutcomms.live.service

import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import com.blackoutcomms.live.data.ClusterRepository
import java.util.UUID

/**
 * Manages a BLE connection to a Blackout Comms device.
 *
 * Usage:
 *   val ble = BleFeedManager(context)
 *   ble.connect(deviceAddress)
 *   // ...
 *   ble.disconnect()
 *
 * Adjust SERVICE_UUID and CHARACTERISTIC_UUID to match the Blackout Comms firmware.
 */
class BleFeedManager(private val context: Context) {

    companion object {
        private const val TAG = "BleFeedManager"

        // TODO: Replace with actual Blackout Comms BLE service/characteristic UUIDs
        val SERVICE_UUID        : UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E") // Nordic UART
        val CHARACTERISTIC_UUID : UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // RX notify
        val CCCD_UUID           : UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private val lineBuffer = StringBuilder()

    // ── Scan ──────────────────────────────────────────────────────────────────

    fun startScan(onFound: (BluetoothDevice) -> Unit) {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val scanner = adapter.bluetoothLeScanner ?: return

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: return
                if (name.contains("BlackoutComms", ignoreCase = true) ||
                    name.contains("BCL", ignoreCase = true)) {
                    scanner.stopScan(this)
                    onFound(result.device)
                }
            }
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed: $errorCode")
            }
        }

        scanner.startScan(scanCallback)
        Log.i(TAG, "BLE scan started")
    }

    // ── Connect ───────────────────────────────────────────────────────────────

    fun connect(address: String) {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val device  = adapter.getRemoteDevice(address)
        connect(device)
    }

    fun connect(device: BluetoothDevice) {
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
        Log.i(TAG, "Connecting to BLE device: ${device.address}")
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        lineBuffer.clear()
        Log.i(TAG, "BLE disconnected")
    }

    // ── GATT callbacks ────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "BLE connected; discovering services…")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "BLE disconnected")
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                return
            }
            val characteristic = gatt
                .getService(SERVICE_UUID)
                ?.getCharacteristic(CHARACTERISTIC_UUID)

            if (characteristic == null) {
                Log.e(TAG, "Target characteristic not found")
                return
            }

            // Enable notifications
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CCCD_UUID)
            descriptor?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(it)
                Log.i(TAG, "BLE notifications enabled")
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val chunk = characteristic.value?.toString(Charsets.UTF_8) ?: return
            processChunk(chunk)
        }
    }

    // ── Line assembly ─────────────────────────────────────────────────────────

    private fun processChunk(chunk: String) {
        lineBuffer.append(chunk)
        var idx: Int
        while (lineBuffer.indexOf('\n').also { idx = it } != -1) {
            val line = lineBuffer.substring(0, idx).trim()
            lineBuffer.delete(0, idx + 1)
            if (line.isNotEmpty()) {
                ClusterRepository.ingest(line)
            }
        }
    }
}
