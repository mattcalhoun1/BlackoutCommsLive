package com.blackoutcomms.live.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.blackoutcomms.live.R
import com.blackoutcomms.live.data.ClusterRepository
import com.blackoutcomms.live.util.BlePreferences
import com.blackoutcomms.live.ui.MainActivity
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.*

/**
 * Foreground service managing the data connection (USB serial, BLE, or test mode).
 *
 * Set TEST_MODE = false for live hardware. On startup (non-test mode) the service
 * immediately begins retrying USB connection until it succeeds or is cancelled.
 */
class ConnectionService : Service() {

    companion object {
        const val TEST_MODE = false   // ← flip to false for live hardware
        private const val TAG = "ConnectionService"
        private const val CHANNEL_ID = "blackout_comms_channel"
        private const val NOTIF_ID = 1001
        private const val RESTART_DELAY_MS = 500L
        private const val RETRY_INTERVAL_MS = 2000L  // time between connection attempts

        const val ACTION_CONNECT_USB = "com.blackoutcomms.live.CONNECT_USB"
        const val ACTION_CONNECT_BLE = "com.blackoutcomms.live.CONNECT_BLE"
        const val ACTION_DISCONNECT  = "com.blackoutcomms.live.DISCONNECT"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ConnectionService::class.java))
        }
    }

    // ── Connection state (observed by UI) ─────────────────────────────────────

    enum class UsbState { IDLE, CONNECTING, CONNECTED, CANCELLED }

    private val _usbState = MutableLiveData(UsbState.IDLE)
    val usbState: LiveData<UsbState> = _usbState

    // ── Internal ──────────────────────────────────────────────────────────────

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var usbIoManager: SerialInputOutputManager? = null
    private var activeUsbPort: UsbSerialPort? = null
    private var usbJob: Job? = null
    private var testJob: Job? = null
    private var bleFeedManager: BleFeedManager? = null

    inner class LocalBinder : Binder() {
        fun getService(): ConnectionService = this@ConnectionService
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Blackout Comms Live running"))
        if (TEST_MODE) {
            startTestMode()
        } else {
            // Auto-start both USB and BLE connection attempts on launch
            //connectUsb()
            startBle()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT_USB -> connectUsb()
            ACTION_CONNECT_BLE -> { /* initiated via activity binding */ }
            ACTION_DISCONNECT  -> cancelUsbConnection()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        disconnect()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── Test Mode ─────────────────────────────────────────────────────────────

    private fun startTestMode() {
        testJob?.cancel()
        testJob = serviceScope.launch {
            try {
                val files = assets.list("test_data")?.sorted() ?: emptyList()
                if (files.isEmpty()) {
                    Log.w(TAG, "No test data files found in assets/test_data/")
                    return@launch
                }
                for (fileName in files) {
                    delay(800)
                    try {
                        assets.open("test_data/$fileName").bufferedReader().use { reader ->
                            reader.readText().trim().split("\n").forEach { line ->
                                val trimmed = line.trim()
                                if (trimmed.isNotEmpty()) {
                                    ClusterRepository.ingest(trimmed)
                                    delay(200)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading test file $fileName", e)
                    }
                }
                Log.i(TAG, "Test data replay complete.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Test mode error", e)
            }
        }
    }

    // ── USB Serial ────────────────────────────────────────────────────────────

    /**
     * Starts a retry loop that attempts USB connection every RETRY_INTERVAL_MS.
     * Posts UsbState.CONNECTING immediately so the UI can show the spinner.
     * Transitions to CONNECTED on success, or stays in CONNECTING until
     * cancelUsbConnection() is called (which sets CANCELLED).
     */
    fun connectUsb() {
        usbJob?.cancel()
        _usbState.postValue(UsbState.CONNECTING)

        usbJob = serviceScope.launch {
            Log.i(TAG, "USB connection loop started")
            while (isActive) {
                val connected = attemptUsbConnect()
                if (connected) {
                    _usbState.postValue(UsbState.CONNECTED)
                    Log.i(TAG, "USB connected successfully")
                    break
                }
                // Not connected yet — wait before retrying
                Log.d(TAG, "USB not available, retrying in ${RETRY_INTERVAL_MS}ms…")
                delay(RETRY_INTERVAL_MS)
            }
        }
    }

    /**
     * Single USB connection attempt. Returns true if the port was opened and
     * the IO manager started successfully, false for any failure (device not
     * present, no permission, open error).
     */
    private fun attemptUsbConnect(): Boolean {
        return try {
            val usbManager = getSystemService(USB_SERVICE) as UsbManager
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            if (drivers.isEmpty()) return false

            val driver = drivers.first()
            val connection = usbManager.openDevice(driver.device) ?: return false

            val port = driver.ports.first()
            port.open(connection)
            port.setParameters(115200, 8, 1, 0)
            activeUsbPort = port
            startIoManager(port)
            true
        } catch (e: Exception) {
            Log.d(TAG, "USB attempt failed: ${e.message}")
            false
        }
    }

    /** Cancel the retry loop and mark state as CANCELLED. */
    fun cancelUsbConnection() {
        usbJob?.cancel()
        usbJob = null
        _usbState.postValue(UsbState.CANCELLED)
        Log.i(TAG, "USB connection cancelled by user")
    }

    /**
     * Creates and starts a SerialInputOutputManager configured for continuous,
     * reliable listening:
     *
     *   readTimeout = 0  →  blocking read mode. The thread blocks inside read()
     *   until bytes arrive rather than polling with a short timeout (the default
     *   100 ms timeout causes onRunError every time the device goes quiet).
     *
     *   onRunError auto-restart  →  genuine I/O errors recreate the manager
     *   after a short delay as long as the port is still open.
     */
    private fun startIoManager(port: UsbSerialPort) {
        usbIoManager?.stop()
        usbIoManager = null

        val lineBuffer = StringBuilder()

        val listener = object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                lineBuffer.append(String(data, Charsets.UTF_8))
                var idx: Int
                while (lineBuffer.indexOf('\n').also { idx = it } != -1) {
                    val line = lineBuffer.substring(0, idx).trim()
                    lineBuffer.delete(0, idx + 1)
                    if (line.isNotEmpty()) {
                        Log.w("usb", "USB Data: ${line}")
                        ClusterRepository.ingest(line)
                    }
                }
            }

            override fun onRunError(e: Exception) {
                Log.w(TAG, "IO manager stopped: ${e.message} — scheduling restart")
                usbIoManager = null
                val p = activeUsbPort ?: return
                if (p.isOpen) {
                    serviceScope.launch {
                        delay(RESTART_DELAY_MS)
                        if (isActive && p.isOpen) {
                            Log.i(TAG, "Restarting IO manager")
                            startIoManager(p)
                        }
                    }
                }
            }
        }

        usbIoManager = SerialInputOutputManager(port, listener).also { mgr ->
            mgr.readTimeout = 0   // blocking reads; never times out between bursts
            mgr.start()
            Log.i(TAG, "SerialInputOutputManager started (blocking read mode)")
        }
    }

    // ── BLE ───────────────────────────────────────────────────────────────────

    /**
     * Stable, persistent BLE state LiveData owned by the service.
     * Lives for the entire service lifetime so the activity always observes
     * the same instance regardless of how many times startBle() is called.
     * startBle() mirrors the BleFeedManager's own bleState into this via an
     * observer, so the UI never needs to re-subscribe.
     */
    private val _bleState = MutableLiveData(BleFeedManager.BleState.IDLE)
    val bleState: LiveData<BleFeedManager.BleState> = _bleState

    /**
     * If a BLE device has been saved previously, connect directly to it (with
     * saved PIN if any). Otherwise post NEEDS_SCAN so the UI can start the
     * scan-and-pick flow.
     */
    fun startBle() {
        // Guard: BLUETOOTH_CONNECT is required on Android 12+ before any BLE operation.
        // On first launch this permission may not be granted yet when the service starts.
        // Posting NEEDS_SCAN defers to the UI flow which only runs after the user
        // has accepted the permission dialog.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "BLUETOOTH_CONNECT not yet granted — deferring BLE start")
                _bleState.postValue(BleFeedManager.BleState.NEEDS_SCAN)
                return
            }
        }
        val saved = BlePreferences.load(this)
        if (saved != null) {
            Log.i(TAG, "Reconnecting to saved BLE device: ${saved.address}")
            connectBleWithPin(saved.address, autoConnect = true)
        } else {
            // Signal UI to show the device picker flow
            _bleState.postValue(BleFeedManager.BleState.NEEDS_SCAN)
        }
    }

    /**
     * Connect to a specific address. Bonding handled automatically by the Android OS.
     *
     * [autoConnect] = true (default for saved devices) delegates to the Android OS
     * background reconnect mechanism — the connection is re-established automatically
     * whenever the device comes into range, even after app restarts.
     */
    fun connectBleWithPin(address: String, pin: String? = null, autoConnect: Boolean = false) {
        bleFeedManager?.closeBle()
        bleFeedManager = BleFeedManager(this).also { mgr ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                mgr.bleState.observeForever { state -> _bleState.postValue(state) }
            }
            mgr.connectByAddress(address, autoConnect = autoConnect)
        }
    }

    /**
     * Start a scan and return results via callback so the UI can show a picker.
     */
    fun scanForBleDevices(onResults: (List<android.bluetooth.le.ScanResult>) -> Unit) {
        bleFeedManager?.closeBle()
        bleFeedManager = BleFeedManager(this).also { mgr ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                mgr.bleState.observeForever { state -> _bleState.postValue(state) }
            }
            mgr.startScan(this, onResults = onResults)
        }
    }

    /** Stop an in-progress scan — called when the user cancels the scan dialog. */
    fun stopBleScan() {
        bleFeedManager?.stopScan()
        _bleState.postValue(BleFeedManager.BleState.IDLE)
    }

    fun connectBle(deviceAddress: String) = connectBleWithPin(deviceAddress)

    /** Single stable accessor — always returns the same LiveData instance. */
    fun getCurrBleState(): LiveData<BleFeedManager.BleState> = bleState

    // ── Disconnect ────────────────────────────────────────────────────────────

    fun disconnect() {
        usbIoManager?.stop()
        usbIoManager = null
        try { activeUsbPort?.close() } catch (_: Exception) {}
        activeUsbPort = null
        usbJob?.cancel()
        usbJob = null
        testJob?.cancel()
        testJob = null
        bleFeedManager?.closeBle()
        bleFeedManager = null
        _usbState.postValue(UsbState.IDLE)
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Blackout Comms Live",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Connection status" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Blackout Comms Live")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}