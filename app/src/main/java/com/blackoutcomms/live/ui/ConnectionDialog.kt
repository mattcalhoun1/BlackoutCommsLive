package com.blackoutcomms.live.ui

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.blackoutcomms.live.R

/**
 * Dialog presented when the user wants to change the connection source.
 * Shows three options: USB Serial, BLE, Test Mode.
 */
class ConnectionDialog : DialogFragment() {

    interface Listener {
        fun onConnectUsb()
        fun onConnectBle()
        fun onConnectTest()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val listener = activity as? Listener
            ?: parentFragment as? Listener
            ?: throw IllegalStateException("Host must implement ConnectionDialog.Listener")

        val options = arrayOf("USB Serial", "Bluetooth (BLE)", "Test Mode (replay files)")

        return AlertDialog.Builder(requireContext())
            .setTitle("Connect to Device")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> listener.onConnectUsb()
                    1 -> listener.onConnectBle()
                    2 -> listener.onConnectTest()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
    }
}
