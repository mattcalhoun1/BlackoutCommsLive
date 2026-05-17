package com.blackoutcomms.live.service
import java.util.jar.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Build
import android.content.Context
import android.content.Intent
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

        val SERVICE_UUID: UUID = UUID.fromString("18aeec00-8c60-411b-b958-78c5049be0f3")
        val RX_CHAR_UUID: UUID =
            UUID.fromString("18aeec02-8c60-411b-b958-78c5049be0f3") // phone ← device (notify)
        val TX_CHAR_UUID: UUID =
            UUID.fromString("18aeec01-8c60-411b-b958-78c5049be0f3") // phone → device (write)

        // Name filter for startScan() — match what nRF Connect shows for your device
        const val DEVICE_NAME_PREFIX = "BC-"
    }
    // Use BluetoothManager instead of deprecated getDefaultAdapter()
    private val btAdapter: android.bluetooth.BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    // ── State ─────────────────────────────────────────────────────────────────

    enum class BleState { IDLE, SCANNING, NEEDS_SCAN, CONNECTING, CONNECTED, DISCONNECTED, NOT_SUPPORTED }

    private val _bleState = MutableLiveData(BleState.IDLE)
    val bleState: LiveData<BleState> = _bleState

    // ── GATT characteristics (resolved after service discovery) ───────────────

    private var rxCharacteristic: BluetoothGattCharacteristic? = null  // device → phone
    private var txCharacteristic: BluetoothGattCharacteristic? = null  // phone → device

    // ── Line assembly buffer + reconnect state ───────────────────────────────

    private val lineBuffer = StringBuilder()

    // Remembers the last connected device so we can reconnect after a drop
    private var lastDevice: BluetoothDevice? = null

    // Set to false by close() so that an intentional disconnect does not
    // trigger the auto-reconnect logic in onDeviceDisconnected
    private var requestedDump = false
    // trigger the auto-reconnect logic in onDeviceDisconnected
    private var shouldReconnect = false

    private val reconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())

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

        txCharacteristic = service.getCharacteristic(TX_CHAR_UUID) ?: run {
            Log.e(TAG, "TX characteristic $TX_CHAR_UUID not found")
            return false;
        }
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
     *
     * By this point bonding has already been handled externally by
     * connectDevice() — if the device needed bonding, we waited for
     * BOND_BONDED before ever calling connect(). So initialize() can
     * proceed directly to MTU + notifications with no bonding logic here.
     */
    override fun initialize() {
        // Register data callback — not a queued operation
        setNotificationCallback(rxCharacteristic)
            .with { _, data -> onDataReceived(data) }

        // MTU negotiation — .fail() prevents queue stall if firmware rejects
        requestMtu(247)
            .with   { _, mtu    -> Log.i(TAG, "MTU negotiated: $mtu bytes") }
            .fail   { _, status -> Log.w(TAG, "MTU rejected (status $status), using default") }
            .enqueue()

        // Enable notifications — by now bonding is complete so this should succeed
        enableNotifications(rxCharacteristic)
            .done { Log.i(TAG, "Notifications enabled on RX characteristic") }
            .fail { _, status -> Log.e(TAG, "enableNotifications failed, status=$status") }
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
        //Log.w("ble", "BLE data received ${data.size()} ${data.getStringValue(0)}")
        val chunk = data.getStringValue(0) ?: return

        // if this was a pin accepted message, bypass the normal json processing
        // to notify the pin is good
        if (chunk.equals("success\n")) {
            ClusterRepository.ingest(chunk)
        }

        lineBuffer.append(chunk)
        // keep accepting messages until carriage return is received
        var idx: Int

        // did we find the end of the larger message
        if (lineBuffer.indexOf('\n') != -1) {
            //Log.w("ble", "Full String: ${lineBuffer.toString()}")
            ClusterRepository.ingest(lineBuffer.toString())
            lineBuffer.clear();
        }
        else {
            //Log.w("ble", "Partial: ${lineBuffer.toString()}")
        }

        /*if (!requestedDump) {
            if (txCharacteristic != null) {
                Log.i("ble", "Request data dump")
                txCharacteristic?.setValue("dump");
                requestedDump = true;
            }
            else {
                Log.i("ble", "tx Characteristic is null")
            }
        }
        else {
            Log.i("ble", "Dump already requested")
        }*/
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

    private var activeScanCallback: android.bluetooth.le.ScanCallback? = null

    /**
     * Scans for all devices whose advertised name starts with DEVICE_NAME_PREFIX.
     * Results accumulate for [durationMs] ms then [onResults] is called with the
     * full deduplicated list so the UI can show a picker. Call stopScan() to cancel.
     */
    fun startScan(
        context: Context,
        durationMs: Long = 8_000L,
        onResults: (List<android.bluetooth.le.ScanResult>) -> Unit
    ) {
        val scanner = btAdapter?.bluetoothLeScanner ?: return

        _bleState.postValue(BleState.SCANNING)
        val found = mutableMapOf<String, android.bluetooth.le.ScanResult>()

        val callback = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                if (matchesPrefix(result)) {
                    val display = resolveDisplayName(result)
                    Log.i(TAG, "Scan hit: ${'$'}display (${'$'}{result.device.address})")
                    found[result.device.address] = result
                }
            }
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed: ${'$'}errorCode")
                _bleState.postValue(BleState.IDLE)
                onResults(emptyList())
            }
        }
        activeScanCallback = callback
        scanner.startScan(callback)

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            scanner.stopScan(callback)
            activeScanCallback = null
            if (_bleState.value == BleState.SCANNING) _bleState.postValue(BleState.IDLE)
            Log.i(TAG, "Scan complete, found ${found.size} device(s)")
            onResults(found.values.toList())
        }, durationMs)

        Log.i(TAG, "BLE scan started for prefix '$DEVICE_NAME_PREFIX'")
    }

    fun stopScan() {
        val scanner = btAdapter?.bluetoothLeScanner ?: return
        activeScanCallback?.let { scanner.stopScan(it) }
        activeScanCallback = null
    }

    /**
     * Connect to a device by MAC address.
     * [autoConnect] = true uses the Android OS background reconnect mechanism:
     *   - slower initial connection (~5-30s) but the OS keeps trying indefinitely
     *   - survives app restarts and phone reboots (while Bluetooth is on)
     *   - correct choice for a saved/known device
     * [autoConnect] = false makes a direct one-shot attempt — faster but no retry.
     *   - correct choice for a freshly scanned device the user just picked
     */
    fun connectByAddress(address: String, autoConnect: Boolean = false) {
        val device = try { btAdapter?.getRemoteDevice(address) ?: return }
                      catch (e: Exception) { Log.e(TAG, "Invalid address: $address"); return }
        connectBle(device, autoConnect)
    }



    /**
     * Connect to a specific device (used from the picker after a fresh scan).
     * Uses direct (non-auto) connect — fast one-shot attempt, no OS background retry.
     * After this succeeds, [lastDevice] is stored so the reconnect logic can use it.
     */
    fun connectBle(device: BluetoothDevice, autoConnect: Boolean) = connectDevice(device, autoConnect)

    /**
     * Internal connect that honours the [autoConnect] flag.
     *
     * If the device is not yet bonded, we bond it BEFORE opening the GATT
     * connection. This keeps bonding completely separate from the GATT
     * request queue, avoiding any race between ensureBond() and
     * enableNotifications() inside initialize().
     *
     * Flow for unbonded device:
     *   1. device.createBond() → OS pairing dialog shown to user
     *   2. BroadcastReceiver waits for BOND_BONDED
     *   3. Only then calls doConnect() to open GATT
     *   4. initialize() runs with clean queue: MTU → enableNotifications
     *
     * Flow for already-bonded device:
     *   1. doConnect() immediately — no bonding step needed
     */
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    private fun connectDevice(device: BluetoothDevice, autoConnect: Boolean) {
        lastDevice      = device
        shouldReconnect = true
        _bleState.postValue(BleState.CONNECTING)

        val bondState = device.bondState
        val bondDesc  = when (bondState) {
            BluetoothDevice.BOND_NONE    -> "BOND_NONE (10)"
            BluetoothDevice.BOND_BONDING -> "BOND_BONDING (11)"
            BluetoothDevice.BOND_BONDED  -> "BOND_BONDED (12)"
            else -> "UNKNOWN ($bondState)"
        }
        Log.i(TAG, "connectDevice: ${device.address}, bondState=$bondDesc, autoConnect=$autoConnect")

        // Always attempt GATT connection directly regardless of bond state.
        //
        // Rationale: we don't know upfront whether the firmware requires bonding.
        //   - If no bonding required (BOND_NONE): doConnect() works immediately.
        //   - If bonding required (BOND_NONE):     firmware returns GATT_INSUF_AUTHENTICATION
        //     during enableNotifications(); the OS then initiates bonding automatically
        //     via the standard pairing dialog. Nordic's queue resumes after bonding.
        //   - If already bonded (BOND_BONDED):     doConnect() works immediately.
        //
        // Previously we called createBond() proactively, which caused the OS to send
        // a pairing request even when the firmware doesn't require it — resulting in
        // the firmware rejecting the bond and the connection never being established.
        doConnect(device, autoConnect)
    }

    private fun doConnect(device: BluetoothDevice, autoConnect: Boolean) {
        val req = connect(device).useAutoConnect(autoConnect)

        if (!autoConnect) {
            req.timeout(20_000).retry(3, 2_000)
        } else {
            req.timeout(0)
        }

        req
            .done {
                Log.i(TAG, "Connected to ${device.address} (autoConnect=$autoConnect)")
                _bleState.postValue(BleState.CONNECTED)
            }
            .fail { _, status ->
                Log.e(TAG, "Connection failed to ${device.address}, status=$status")
                _bleState.postValue(BleState.DISCONNECTED)
                if (!autoConnect && shouldReconnect) {
                    Log.i(TAG, "Direct connect failed (status $status) — switching to OS autoConnect")
                    reconnectHandler.postDelayed({ doConnect(device, autoConnect = true) }, 3_000)
                }
            }
            .invalid {
                Log.e(TAG, "Device ${device.address} does not support required service")
                shouldReconnect = false
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

                if (shouldReconnect) {
                    Log.i(TAG, "Unexpected disconnect — scheduling reconnect in 5 s")
                    reconnectHandler.postDelayed({
                        if (!shouldReconnect) return@postDelayed
                        // Re-fetch the device from the adapter rather than using the
                        // stale callback object. Bond state can transiently read
                        // BOND_NONE during disconnection; waiting 5 s lets it settle.
                        val freshDevice = try {
                            btAdapter?.getRemoteDevice(device.address)
                        } catch (_: Exception) { null } ?: device

                        Log.i(TAG, "Reconnecting to ${freshDevice.address}, bondState=${freshDevice.bondState}")
                        connectDevice(freshDevice, autoConnect = true)
                    }, 5_000)
                }
            }
        })
    }

    // ── Name matching helpers ─────────────────────────────────────────────────

    /**
     * Returns true if any of the names the device advertises matches the
     * BlackoutComms prefix. Checks (in order):
     *   1. device.name       — the primary advertised name
     *   2. device.alias      — user-set alias (API 30+); may carry the prefix too
     *   3. scanRecord.deviceName — the raw BLE local-name field in the ad payload
     *
     * Any one match is sufficient.
     */
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    private fun matchesPrefix(result: ScanResult): Boolean {
        // 1. Primary advertised name
        val name = result.device.name
        if (!name.isNullOrBlank() && name.startsWith(DEVICE_NAME_PREFIX, ignoreCase = true))
            return true

        // 2. Raw local name from the BLE advertisement payload
        // Note: device.alias is intentionally NOT checked here — it is a user-set
        // phone-side label (not firmware-advertised) and accessing it from the
        // ScanCallback Binder thread causes "FLAG_ONEWAY" transaction errors.
        val localName = result.scanRecord?.deviceName
        if (!localName.isNullOrBlank() && localName.startsWith(DEVICE_NAME_PREFIX, ignoreCase = true))
            return true

        return false
    }

    /**
     * Returns the best human-readable name for a matching device to show in
     * the picker UI. Prefers whichever name contains the BlackoutComms prefix,
     * falling back to address if nothing useful is found.
     */
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    fun resolveDisplayName(result: android.bluetooth.le.ScanResult): String {
        // Prefer the name that actually matches the prefix.
        // device.alias is intentionally omitted — it is a phone-side user label
        // and accessing it from scan callbacks causes FLAG_ONEWAY Binder errors.
        val name = result.device.name
        if (!name.isNullOrBlank() && name.startsWith(DEVICE_NAME_PREFIX, ignoreCase = true))
            return name

        val localName = result.scanRecord?.deviceName
        if (!localName.isNullOrBlank() && localName.startsWith(DEVICE_NAME_PREFIX, ignoreCase = true))
            return localName

        // Fall back to primary name, scan record name, or address
        return name?.takeIf { it.isNotBlank() }
            ?: localName?.takeIf { it.isNotBlank() }
            ?: result.device.address
    }

    // ── Application-level PIN ────────────────────────────────────────────────

    /**
     * Send an application-level PIN to the firmware via the TX characteristic.
     * Called after GATT is fully ready (onDeviceReady) if a PIN was saved.
     * Format: "PIN:<pin>\n" — adjust if firmware expects a different format.
     */
    fun sendPin(pin: String) {
        if (pin.isBlank()) return
        val tx = txCharacteristic ?: run {
            Log.w(TAG, "TX characteristic not available — cannot send PIN")
            return
        }
        writeCharacteristic(tx, "PIN:$pin".toByteArray(Charsets.UTF_8),
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            .done { Log.i(TAG, "PIN $pin sent successfully") }
            .fail { _, status -> Log.e(TAG, "PIN send failed, status=$status") }
            .enqueue()
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    fun closeBle() {
        shouldReconnect = false          // prevent onDeviceDisconnected from retrying
        reconnectHandler.removeCallbacksAndMessages(null)
        disconnect().enqueue()
        lineBuffer.clear()
        Log.i(TAG, "BleFeedManager closed")
    }
}
