package com.blackoutcomms.live.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.blackoutcomms.live.R
import com.blackoutcomms.live.data.ClusterRepository
import com.blackoutcomms.live.databinding.ActivityMainBinding
import com.blackoutcomms.live.service.BleFeedManager
import com.blackoutcomms.live.service.ConnectionService
import com.blackoutcomms.live.ui.about.AboutFragment
import com.blackoutcomms.live.ui.map.MapFragment

class MainActivity : AppCompatActivity(), ConnectionDialog.Listener {

    private lateinit var binding: ActivityMainBinding
    private var connectionService: ConnectionService? = null
    private var bleFeedManager: BleFeedManager? = null

    // Track whether we've already shown the startup connecting dialog this session
    private var startupDialogShown = false

    companion object {
        private const val REQUEST_PERMISSIONS = 100
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? ConnectionService.LocalBinder
            connectionService = binder?.getService()

            // Show the connecting spinner on startup if we're in live mode
            // and the service is currently attempting to connect
            if (!ConnectionService.TEST_MODE && !startupDialogShown) {
                startupDialogShown = true
                val svc = connectionService ?: return
                if (svc.usbState.value == ConnectionService.UsbState.CONNECTING) {
                    showConnectingDialog(svc)
                }
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
        supportActionBar?.title = "Blackout Comms Live"

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

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_map -> {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, MapFragment())
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

    private fun showConnectingDialog(service: ConnectionService) {
        // Don't stack dialogs if one is already showing (e.g. on config change)
        val existing = supportFragmentManager.findFragmentByTag(UsbConnectingDialog.TAG)
        if (existing != null) return

        val dialog = UsbConnectingDialog()
        dialog.show(supportFragmentManager, UsbConnectingDialog.TAG)

        // Pass the service reference once the fragment is attached
        supportFragmentManager.executePendingTransactions()
        (supportFragmentManager.findFragmentByTag(UsbConnectingDialog.TAG) as? UsbConnectingDialog)
            ?.observeService(service, this)
    }

    /** Called by UsbConnectingDialog's Cancel button */
    fun cancelUsbConnect() {
        connectionService?.cancelUsbConnection()
    }

    // ── Toolbar ───────────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.toolbar_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_connect -> {
                ConnectionDialog().show(supportFragmentManager, "connect_dialog")
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

    // ── ConnectionDialog.Listener ─────────────────────────────────────────────

    override fun onConnectUsb() {
        connectionService?.connectUsb()
        connectionService?.let { showConnectingDialog(it) }
    }

    override fun onConnectBle() {
        // Location permission is required for BLE scanning on Android 6+
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Location permission required for BLE scanning", Toast.LENGTH_LONG).show()
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_PERMISSIONS)
            return
        }

        connectionService?.cancelUsbConnection()
        bleFeedManager?.closeBle()
        bleFeedManager = BleFeedManager(this).also { mgr ->
            // Show toast feedback as BLE state changes
            mgr.bleState.observe(this) { state ->
                val msg = when (state) {
                    BleFeedManager.BleState.SCANNING      -> "Scanning for BLE devices…"
                    BleFeedManager.BleState.CONNECTING    -> "BLE device found, connecting…"
                    BleFeedManager.BleState.CONNECTED     -> "BLE connected"
                    BleFeedManager.BleState.DISCONNECTED  -> "BLE disconnected"
                    BleFeedManager.BleState.NOT_SUPPORTED -> "Device does not support required BLE service"
                    else -> null
                }
                msg?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
            }
            mgr.startScan(this) { device ->
                runOnUiThread { mgr.connectBle(device) }
            }
        }
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
        bleFeedManager?.closeBle()
        unbindService(serviceConnection)
        super.onDestroy()
    }
}
