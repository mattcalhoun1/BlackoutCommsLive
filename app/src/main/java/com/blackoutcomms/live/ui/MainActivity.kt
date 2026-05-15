package com.blackoutcomms.live.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.blackoutcomms.live.R
import com.blackoutcomms.live.data.ClusterRepository
import com.blackoutcomms.live.data.MapSaveManager
import com.blackoutcomms.live.databinding.ActivityMainBinding
import com.blackoutcomms.live.service.BleFeedManager
import com.blackoutcomms.live.service.ConnectionService
import com.blackoutcomms.live.util.BlePreferences
import com.blackoutcomms.live.util.themedAlertBuilder
import com.blackoutcomms.live.ui.BleDevicePickerDialog
import com.blackoutcomms.live.ui.BleScanningDialog
import com.blackoutcomms.live.ui.WelcomeDialog
import com.blackoutcomms.live.ui.about.AboutFragment
import com.blackoutcomms.live.ui.help.HelpFragment
import com.blackoutcomms.live.ui.traffic.TrafficFragment
import com.blackoutcomms.live.ui.map.MapFragment

class MainActivity : AppCompatActivity(), ConnectionDialog.Listener {

    private lateinit var binding: ActivityMainBinding
    private var connectionService: ConnectionService? = null

    // Kept only for manual BLE invocations from ConnectionDialog;
    // startup BLE is owned by ConnectionService
    private var manualBleFeedManager: BleFeedManager? = null

    private var startupDialogShown = false
    private var notConnectedDialogShown = false
    private var connectMenuItem: MenuItem? = null

    companion object {
        private const val REQUEST_PERMISSIONS = 100
        private val COLOR_BLE_CONNECTED  = Color.parseColor("#2196F3")  // blue
        private val COLOR_BLE_DEFAULT    = Color.WHITE
        private const val PREFS_NAME         = "app_prefs"
        private const val PREF_SHOW_MESSAGES = "show_messages"
    }

    // Persisted preference — read once on startup, kept in sync with the menu item
    private var showMessages: Boolean = true

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? ConnectionService.LocalBinder
            connectionService = binder?.getService()

            // Show USB connecting dialog on startup (non-test mode)
            if (!ConnectionService.TEST_MODE && !startupDialogShown) {
                startupDialogShown = true
                val svc = connectionService
                if (svc != null && svc.usbState.value == ConnectionService.UsbState.CONNECTING) {
                    showConnectingDialog(svc)
                }
            }

            // Observe the service's stable BLE state LiveData.
            connectionService?.getCurrBleState()?.observe(this@MainActivity) { state ->
                updateConnectIcon(state)
                when (state) {
                    BleFeedManager.BleState.CONNECTED  -> activeMapViewModel()?.onBleConnected()
                    BleFeedManager.BleState.NEEDS_SCAN -> startBleScanFlow()
                    else -> {}
                }
            }

            // After a short delay, if still not connected and not actively
            // connecting, show the "not connected" help popup once per launch.
            if (!notConnectedDialogShown) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    maybeShowNotConnectedDialog()
                }, 3_000)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            connectionService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)  // title shown via custom view

        // Load persisted Show Messages preference
        showMessages = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_SHOW_MESSAGES, true)

        requestRequiredPermissions()

        ConnectionService.start(this)
        bindService(
            Intent(this, ConnectionService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, MapFragment())
                .commit()
        }

        // Show first-run welcome dialog if the user hasn't dismissed it permanently
        if (WelcomeDialog.shouldShow(this)) {
            WelcomeDialog().show(supportFragmentManager, WelcomeDialog.TAG)
        }

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_map -> {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, MapFragment())
                        .commitNow()
                    // Push current showMessages preference to the newly created fragment
                    activeMapViewModel()?.setShowMessages(showMessages)
                    true
                }
                R.id.nav_traffic -> {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, TrafficFragment())
                        .commit()
                    true
                }
                R.id.nav_help -> {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, HelpFragment())
                        .commit()
                    true
                }
                R.id.nav_about -> {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, AboutFragment())
                        .commit()
                    true
                }
                else -> false
            }
        }
    }

    // ── Toolbar icon tinting ──────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.toolbar_menu, menu)
        connectMenuItem = menu.findItem(R.id.action_connect)
        menu.findItem(R.id.action_show_messages)?.isChecked = showMessages
        // Enable Reload Map only when a saved snapshot exists
        menu.findItem(R.id.action_reload_map)?.isEnabled = MapSaveManager.hasSavedSnapshot(this)
        val currentState = connectionService?.bleState?.value ?: BleFeedManager.BleState.IDLE
        updateConnectIcon(currentState)
        return true
    }

    private fun updateConnectIcon(state: BleFeedManager.BleState) {
        val icon = connectMenuItem?.icon
        if (icon == null) {
            // Menu not inflated yet — invalidate so onCreateOptionsMenu re-runs,
            // which will call updateConnectIcon() again with the current state.
            invalidateOptionsMenu()
            return
        }
        if (state == BleFeedManager.BleState.CONNECTED) {
            icon.setColorFilter(COLOR_BLE_CONNECTED, PorterDuff.Mode.SRC_IN)
        } else {
            icon.clearColorFilter()
            icon.setColorFilter(COLOR_BLE_DEFAULT, PorterDuff.Mode.SRC_IN)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_connect -> {
                onConnectBle()
                true
            }
            R.id.action_show_messages -> {
                // Toggle checked state and persist
                showMessages = !item.isChecked
                item.isChecked = showMessages
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(PREF_SHOW_MESSAGES, showMessages).apply()
                // Push to the active MapFragment's ViewModel if present
                activeMapViewModel()?.setShowMessages(showMessages)
                true
            }
            R.id.action_save_map -> {
                val ok = MapSaveManager.save(this)
                if (ok) {
                    // Enable Reload Map now that a snapshot exists
                    invalidateOptionsMenu()
                    val savedAt = MapSaveManager.savedAt(this)
                    val timeStr = savedAt?.let {
                        java.text.SimpleDateFormat("MM/dd/yyyy HH:mm:ss", java.util.Locale.US)
                            .format(java.util.Date(it))
                    } ?: "now"
                    Toast.makeText(this, "Map saved at $timeStr", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show()
                }
                true
            }
            R.id.action_reload_map -> {
                this.themedAlertBuilder()
                    .setTitle("Reload Map")
                    .setMessage(
                        MapSaveManager.savedAt(this)?.let { ms ->
                            val timeStr = java.text.SimpleDateFormat("MM/dd/yyyy HH:mm:ss",
                                java.util.Locale.US).format(java.util.Date(ms))
                            "Restore the map state saved at $timeStr? Current live data will be replaced."
                        } ?: "Restore the last saved map state?"
                    )
                    .setPositiveButton("Reload") { _, _ ->
                        val ok = MapSaveManager.restore(this)
                        Toast.makeText(
                            this,
                            if (ok) "Map state restored" else "Reload failed",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
            R.id.action_forget_ble -> {
                BlePreferences.clear(this)
                connectionService?.disconnect()
                Toast.makeText(this, "BLE device forgotten", Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_clear -> {
                ClusterRepository.reset()
                Toast.makeText(this, "Cluster data cleared", Toast.LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ── USB connecting dialog ─────────────────────────────────────────────────

    private fun showConnectingDialog(service: ConnectionService) {
        val existing = supportFragmentManager.findFragmentByTag(UsbConnectingDialog.TAG)
        if (existing != null) return

        val dialog = UsbConnectingDialog()
        dialog.show(supportFragmentManager, UsbConnectingDialog.TAG)
        supportFragmentManager.executePendingTransactions()
        (supportFragmentManager.findFragmentByTag(UsbConnectingDialog.TAG) as? UsbConnectingDialog)
            ?.observeService(service, this)
    }

    fun cancelUsbConnect() {
        connectionService?.cancelUsbConnection()
    }

    /**
     * Shows a one-time "not connected" help popup if the app has launched but
     * BLE is neither connected nor in the process of connecting/scanning.
     * Suppressed when the USB connecting spinner is already visible, and only
     * shown once per process lifetime so it doesn't reappear on tab switches.
     */
    private fun maybeShowNotConnectedDialog() {
        if (notConnectedDialogShown) return
        val bleState = connectionService?.getCurrBleState()?.value ?: BleFeedManager.BleState.IDLE
        val isActive = bleState == BleFeedManager.BleState.CONNECTED
                    || bleState == BleFeedManager.BleState.CONNECTING
                    || bleState == BleFeedManager.BleState.SCANNING
        val spinnerShowing = supportFragmentManager
            .findFragmentByTag(UsbConnectingDialog.TAG) != null
            || supportFragmentManager.findFragmentByTag(BleScanningDialog.TAG) != null

        android.util.Log.d("MainActivity", "maybeShowNotConnected: bleState=$bleState isActive=$isActive spinnerShowing=$spinnerShowing")

        if (!isActive && !spinnerShowing) {
            notConnectedDialogShown = true
            showNotConnectedDialog()
        }
    }

    private fun showNotConnectedDialog() {
        this.themedAlertBuilder()
            .setTitle("Not Connected")
            .setMessage(
                "You are not currently connected to a Blackout Comms device.\n\n" +
                "1. Enable Bluetooth on your Blackout Comms device\n" +
                "2. Restart your Blackout Comms device\n" +
                "3. In this app, touch the connect button (BLE icon)\n" +
                "4. Choose your Blackout Comms device from the list\n" +
                "5. For pairing, use PIN shown on the Blackout Comms device"
            )
            .setPositiveButton("Connect Now") { _, _ -> onConnectBle() }
            .setNeutralButton("More Info") { _, _ ->
                startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://chatters.io")
                    )
                )
            }
            .setNegativeButton("Dismiss", null)
            .show()
    }

    /** Returns the ViewModel of the currently active MapFragment, or null. */
    private fun activeMapViewModel(): com.blackoutcomms.live.ui.map.MapViewModel? {
        val frag = supportFragmentManager.findFragmentById(R.id.fragment_container)
        return if (frag is com.blackoutcomms.live.ui.map.MapFragment) {
            androidx.lifecycle.ViewModelProvider(frag)[com.blackoutcomms.live.ui.map.MapViewModel::class.java]
        } else null
    }

    // ── ConnectionDialog.Listener ─────────────────────────────────────────────

    override fun onConnectUsb() {
        connectionService?.connectUsb()
        connectionService?.let { showConnectingDialog(it) }
    }

    override fun onConnectBle() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Location permission required for BLE scanning", Toast.LENGTH_LONG).show()
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_PERMISSIONS)
            return
        }
        connectionService?.cancelUsbConnection()
        connectionService?.startBle()
    }

    /** Triggered when BLE state transitions to NEEDS_SCAN — start the scan/pick/pin flow. */
    private fun startBleScanFlow() {
        // Show spinner for the duration of the 8-second scan
        val scanningDialog = BleScanningDialog().also { dlg ->
            dlg.onCancel = { connectionService?.stopBleScan() }
            dlg.show(supportFragmentManager, BleScanningDialog.TAG)
        }

        connectionService?.scanForBleDevices { results ->
            runOnUiThread {
                scanningDialog.dismissSafely()
                showBleDevicePicker(results)
            }
        }
    }

    private fun showBleDevicePicker(results: List<android.bluetooth.le.ScanResult>) {
        val mgr   = com.blackoutcomms.live.service.BleFeedManager(this)
        val names     = results.map { mgr.resolveDisplayName(it) }.toTypedArray()
        val addresses = results.map { it.device.address }.toTypedArray()
        mgr.close()

        val dialog = BleDevicePickerDialog.newInstance(names, addresses)
        dialog.onDeviceSelected = { address, name ->
            // Save for future auto-reconnect; bonding PIN handled by Android OS
            BlePreferences.save(this, address, name)
            connectionService?.connectBleWithPin(address)
        }
        dialog.onRescan = { startBleScanFlow() }
        dialog.show(supportFragmentManager, BleDevicePickerDialog.TAG)
    }

    override fun onConnectTest() {
        connectionService?.disconnect()
        ClusterRepository.reset()
        ConnectionService.start(this)
        Toast.makeText(this, "Replaying test data…", Toast.LENGTH_SHORT).show()
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun requestRequiredPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    override fun onDestroy() {
        manualBleFeedManager?.close()
        unbindService(serviceConnection)
        super.onDestroy()
    }
}
