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

    // Kept only for manual BLE invocations from ConnectionDialog;
    // startup BLE is owned by ConnectionService
    private var manualBleFeedManager: BleFeedManager? = null

    private var startupDialogShown = false
    private var connectMenuItem: MenuItem? = null

    companion object {
        private const val REQUEST_PERMISSIONS = 100
        private val COLOR_BLE_CONNECTED  = Color.parseColor("#2196F3")  // blue
        private val COLOR_BLE_DEFAULT    = Color.WHITE
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? ConnectionService.LocalBinder
            connectionService = binder?.getService()

            // Show USB connecting dialog on startup (non-test mode)
            if (!ConnectionService.TEST_MODE && !startupDialogShown) {
                startupDialogShown = true
                val svc = connectionService ?: return
                if (svc.usbState.value == ConnectionService.UsbState.CONNECTING) {
                    showConnectingDialog(svc)
                }
            }

            // Observe BLE state from the service to tint the toolbar icon
            connectionService?.getCurrBleState()?.observe(this@MainActivity) { state ->
                updateConnectIcon(state)
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

    // ── Toolbar icon tinting ──────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.toolbar_menu, menu)
        connectMenuItem = menu.findItem(R.id.action_connect)
        // Apply current BLE state immediately in case it fired before menu was created
        val currentState = connectionService?.getCurrBleState()?.value ?: BleFeedManager.BleState.IDLE
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
        // Delegate BLE to the service so it survives backgrounding
        connectionService?.startBle()
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
