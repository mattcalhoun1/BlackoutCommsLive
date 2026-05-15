package com.blackoutcomms.live.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.blackoutcomms.live.R
import com.blackoutcomms.live.util.themedAlertBuilder

/**
 * Shows a list of nearby BlackoutComms BLE devices found during a scan.
 * The user picks one; the result is delivered via [onDeviceSelected].
 * If no devices were found, displays a "no devices" message with a retry option.
 */
class BleDevicePickerDialog : DialogFragment() {

    companion object {
        const val TAG = "BleDevicePickerDialog"

        private const val ARG_NAMES     = "names"
        private const val ARG_ADDRESSES = "addresses"

        fun newInstance(
            names: Array<String>,
            addresses: Array<String>
        ) = BleDevicePickerDialog().apply {
            arguments = Bundle().apply {
                putStringArray(ARG_NAMES, names)
                putStringArray(ARG_ADDRESSES, addresses)
            }
        }
    }

    var onDeviceSelected: ((address: String, name: String) -> Unit)? = null
    var onRescan: (() -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val names     = arguments?.getStringArray(ARG_NAMES)     ?: emptyArray()
        val addresses = arguments?.getStringArray(ARG_ADDRESSES) ?: emptyArray()

        if (names.isEmpty()) {
            // No devices found — offer to rescan
            return requireContext().themedAlertBuilder()
                .setTitle("No Devices Found")
                .setMessage("No BlackoutComms devices were found nearby. Make sure the device is powered on and in range.")
                .setPositiveButton("Scan Again") { _, _ -> onRescan?.invoke() }
                .setNegativeButton("Cancel", null)
                .create()
        }

        return requireContext().themedAlertBuilder()
            .setTitle("Select Device")
            .setItems(names) { _, which ->
                onDeviceSelected?.invoke(addresses[which], names[which])
            }
            .setNegativeButton("Cancel", null)
            .create()
    }
}
