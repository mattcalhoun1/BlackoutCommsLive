package com.blackoutcomms.live.service

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.blackoutcomms.live.data.ClusterRepository
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.callback.DataReceivedCallback
import no.nordicsemi.android.ble.data.Data
import no.nordicsemi.android.ble.observer.ConnectionObserver
import java.util.UUID

/**
 * Manages a BLE connection to a Blackout Comms device using Nordic's BleManager.
 *
 * Nordic's BleManager provides:
 *   - Automatic connection retry / reconnection
 *   - MTU negotiation
 *   - Request queuing (reads, writes, notifications all serialised safely)
 *   - ConnectionObserver callbacks for clean state tracking
 *   - Bonding support if the device requires pairing
 *
 * To configure for your firmware:
 *   1. Set SERVICE_UUID to the BLE service UUID advertised by the Blackout Comms device
 *   2. Set RX_CHAR_UUID to the notify/indicate characteristic that sends data to the phone
 *   3. Set TX_CHAR_UUID to the write characteristic if you need to send data back
 *   4. Update DEVICE_NAME_FILTER to match the advertised name shown in nRF Connect
 */
class BleFeedManager(context: Context) : BleManager(context) {

    companion object {
        private const val TAG = "BleFeedManager"

        val SERVICE_UUID : UUID = UUID.fromString("18aeec00-8c60-411b-b958-78c5049be0f3")
        val RX_CHAR_UUID : UUID = UUID.fromString("18aeec02-8c60-411b-b958-78c5049be0f3") // phone ← device (notify)
        val TX_CHAR_UUID : UUID = UUID.fromString("18aeec01-8c60-411b-b958-78c5049be0f3") // phone → device (write)

        // Name filter for startScan() — match what nRF Connect shows for your device
        const val DEVICE_NAME_FILTER = "BlackoutComms"
    }

    // ── State ─────────────────────────────────────────────────────────────────

    enum class BleState { IDLE, SCANNING, CONNECTING, CONNECTED, DISCONNECTED, NOT_SUPPORTED }

    private val _bleState = MutableLiveData(BleState.IDLE)
    val bleState: LiveData<BleState> = _bleState

    // ── GATT characteristics (resolved after service discovery) ───────────────

    private var rxCharacteristic: BluetoothGattCharacteristic? = null  // device → phone
    private var txCharacteristic: BluetoothGattCharacteristic? = null  // phone → device

    // ── Line assembly buffer ──────────────────────────────────────────────────

    private val lineBuffer = StringBuilder()

    private var largeMessageIncoming = false;

    // ── BleManager overrides ──────────────────────────────────────────────────

    /**
     * Nordic calls this after service discovery. Return true if the required
     * service and characteristics are present; false marks the device as
     * "not supported" and disconnects cleanly.
     */
    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(SERVICE_UUID) ?: run {
            Log.e(TAG, "Service $SERVICE_UUID not found on device")
            return false
        }

        rxCharacteristic = service.getCharacteristic(RX_CHAR_UUID) ?: run {
            Log.e(TAG, "RX characteristic $RX_CHAR_UUID not found")
            return false
        }

        txCharacteristic = service.getCharacteristic(TX_CHAR_UUID)
        // TX is optional — only needed if the app sends commands back to the device

        // Verify the RX characteristic has the NOTIFY or INDICATE property
        val props = rxCharacteristic!!.properties
        val canNotify = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
        val canIndicate = (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        if (!canNotify && !canIndicate) {
            Log.e(TAG, "RX characteristic does not support notifications or indications")
            return false
        }

        Log.i(TAG, "Required service and characteristics found")
        return true
    }

    /**
     * Called after isRequiredServiceSupported() returns true.
     * Set up the request queue: negotiate MTU, then enable notifications.
     * Nordic serialises these automatically — no manual callback chaining needed.
     */
    override fun initialize() {
        // Request a large MTU to reduce fragmentation of JSON payloads.
        // 512 is the BLE spec maximum; the actual negotiated value will be
        // whatever both sides agree on (typically 247 on modern Android + firmware).
        requestMtu(247)
            .with { _, mtu -> Log.i(TAG, "MTU negotiated: $mtu bytes") }
            .enqueue()

        // Enable notifications on the RX characteristic.
        // Nordic handles writing to the CCCD descriptor automatically.
        setNotificationCallback(rxCharacteristic)
            .with(DataReceivedCallback { _, data -> onDataReceived(data) })

        enableNotifications(rxCharacteristic)
            .done { Log.i(TAG, "Notifications enabled on RX characteristic") }
            .fail { _, status -> Log.e(TAG, "Failed to enable notifications, status=$status") }
            .enqueue()
    }

    /**
     * Called when the device disconnects or the connection is dropped.
     * Release characteristic references so they're not used after reconnect
     * before isRequiredServiceSupported() has run again.
     */
    override fun onServicesInvalidated() {
        rxCharacteristic = null
        txCharacteristic = null
        lineBuffer.clear()
        Log.i(TAG, "Services invalidated — characteristics cleared")
    }

    // ── Data reception ────────────────────────────────────────────────────────

    /**
     * Reassembles newline-delimited JSON from BLE notification fragments.
     * Because MTU limits how much fits in one notification, a single JSON
     * object may arrive across multiple calls. The buffer accumulates bytes
     * and dispatches complete lines to ClusterRepository.
     */
    private fun onDataReceived(data: Data) {
        Log.w("ble", "BLE data received ${data.size()} ${data.getStringValue(0)}")
        val chunk = data.getStringValue(0) ?: return
        var endLargeMessage = false;
        var soloMessage = false;

        // is this a large message header or ending?
        if (chunk.indexOf("largeBegin") != -1) {
            Log.i("ble", "Large message incoming")
            largeMessageIncoming = true;
        }
        else if (chunk.indexOf("largeEnd") != -1) {
            Log.i("ble", "end large message")
            largeMessageIncoming = false;
        }
        else {
            soloMessage = true;
        }

        // if this is part of a large message, append it and continue waiting
        if (largeMessageIncoming) {
            //lineBuffer.append(chunk)
        }
        else {
            if (soloMessage) {
                lineBuffer.append(chunk)
            }

            // if we have data, ingest it now
            if (lineBuffer.length > 0) {
                Log.w("ble", "BLE Data: ${lineBuffer.toString()}")
                ClusterRepository.ingest(lineBuffer.toString())
                lineBuffer.clear();
            }
            /*
            var idx: Int
            while (lineBuffer.indexOf('\n').also { idx = it } != -1) {
                val line = lineBuffer.substring(0, idx).trim()
                lineBuffer.delete(0, idx + 1)
                if (line.isNotEmpty()) {
                    Log.w("ble", "BLE Data: ${lineBuffer.toString()}")
                    ClusterRepository.ingest(lineBuffer.toString())
                }
            }*/
        }
    }

    // ── Sending data to the device (optional) ─────────────────────────────────

    /**
     * Write a string command to the device (e.g. a request or config message).
     * Does nothing if TX characteristic is not present.
     */
    fun send(text: String) {
        val tx = txCharacteristic ?: run {
            Log.w(TAG, "TX characteristic not available")
            return
        }
        writeCharacteristic(tx, text.toByteArray(Charsets.UTF_8),
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            .done { Log.d(TAG, "Sent: $text") }
            .fail { _, status -> Log.e(TAG, "Send failed, status=$status") }
            .enqueue()
    }

    // ── Scan + connect ────────────────────────────────────────────────────────

    /**
     * Scans for a device matching DEVICE_NAME_FILTER and connects to the first
     * one found. The scan is handled outside BleManager (BleManager itself only
     * manages the connection, not scanning) — pass the found device to connect().
     *
     * Recommended: use Nordic's companion library 'no.nordicsemi.android:scanner'
     * for production scan management, or use the raw BluetoothLeScanner below.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan(context: Context, onFound: (BluetoothDevice) -> Unit) {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val scanner  = adapter.bluetoothLeScanner ?: return

        _bleState.postValue(BleState.SCANNING)

        val scanCallback = object : ScanCallback() {
            @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: return
                if (name.contains(DEVICE_NAME_FILTER, ignoreCase = true)) {
                    Log.i(TAG, "Found device: $name (${result.device.address})")
                    scanner.stopScan(this)
                    onFound(result.device)
                }
            }
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed: $errorCode")
                _bleState.postValue(BleState.IDLE)
            }
        }

        scanner.startScan(scanCallback)
        Log.i(TAG, "BLE scan started, looking for '$DEVICE_NAME_FILTER'")
    }

    /**
     * Connect to a specific device. Nordic's BleManager will:
     *   - Establish the GATT connection
     *   - Discover services
     *   - Call isRequiredServiceSupported()
     *   - Call initialize() to set up notifications
     *   - Automatically reconnect if the link drops (while app is alive)
     */
    fun connectBle(device: BluetoothDevice) {
        _bleState.postValue(BleState.CONNECTING)

        connect(device)
            .timeout(15_000)          // 15 s connection timeout
            .retry(3, 1_000)    // retry up to 3 times with 1 s delay between attempts
            .useAutoConnect(false)    // false = faster initial connect; set true for background reconnect
            .done {
                Log.i(TAG, "Connected to ${device.address}")
                _bleState.postValue(BleState.CONNECTED)
            }
            .fail { _, status ->
                Log.e(TAG, "Connection failed to ${device.address}, status=$status")
                _bleState.postValue(BleState.DISCONNECTED)
            }
            .invalid {
                Log.e(TAG, "Device ${device.address} does not support required service")
                _bleState.postValue(BleState.NOT_SUPPORTED)
            }
            .enqueue()
    }

    // ── ConnectionObserver (optional detailed state tracking) ─────────────────

    init {
        setConnectionObserver(object : ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) {
                Log.d(TAG, "onDeviceConnecting: ${device.address}")
                _bleState.postValue(BleState.CONNECTING)
            }
            override fun onDeviceConnected(device: BluetoothDevice) {
                Log.d(TAG, "onDeviceConnected: ${device.address}")
                // State set to CONNECTED in connect().done callback after init completes
            }
            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
                Log.w(TAG, "onDeviceFailedToConnect: ${device.address}, reason=$reason")
                _bleState.postValue(BleState.DISCONNECTED)
            }
            override fun onDeviceReady(device: BluetoothDevice) {
                Log.i(TAG, "onDeviceReady: ${device.address} — fully initialised")
                _bleState.postValue(BleState.CONNECTED)
            }
            override fun onDeviceDisconnecting(device: BluetoothDevice) {
                Log.d(TAG, "onDeviceDisconnecting: ${device.address}")
            }
            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                Log.i(TAG, "onDeviceDisconnected: ${device.address}, reason=$reason")
                _bleState.postValue(BleState.DISCONNECTED)
                lineBuffer.clear()
            }
        })
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    fun closeBle() {
        disconnect().enqueue()
        lineBuffer.clear()
        Log.i(TAG, "BleFeedManager closed")
    }
}
