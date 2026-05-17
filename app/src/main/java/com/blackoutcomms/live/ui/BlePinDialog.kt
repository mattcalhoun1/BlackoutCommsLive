package com.blackoutcomms.live.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import androidx.fragment.app.DialogFragment
import com.blackoutcomms.live.R
import com.blackoutcomms.live.util.themedAlertBuilder

/**
 * Prompts the user for an application-level PIN after a BLE device is selected.
 *
 * "Connect"  → delivers the entered PIN string (may be blank).
 * "No PIN"   → delivers null, meaning no PIN will be sent to the firmware.
 * "Cancel"   → dismisses, no connection attempt made.
 *
 * [onResult] is called with:
 *   - a non-null String (possibly empty) when Connect is tapped
 *   - null when No PIN is tapped
 * It is NOT called when Cancel is tapped.
 */
class BlePinDialog : DialogFragment() {

    companion object {
        const val TAG = "BlePinDialog"

        fun newInstance(deviceName: String) = BlePinDialog().apply {
            arguments = Bundle().apply { putString("device_name", deviceName) }
        }
    }

    var onResult: ((pin: String?) -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val deviceName = arguments?.getString("device_name") ?: "device"
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_ble_pin, null, false)
        val editPin = view.findViewById<EditText>(R.id.edit_pin)

        return requireContext().themedAlertBuilder()
            .setTitle("Enter PIN")
            .setMessage("Enter the PIN for $deviceName, or tap \"No PIN\" if none is required.")
            .setView(view)
            .setPositiveButton("Connect") { _, _ ->
                onResult?.invoke(editPin.text.toString().trim().ifBlank { null })
            }
            .setNeutralButton("No PIN") { _, _ ->
                onResult?.invoke(null)
            }
            .setNegativeButton("Cancel", null)
            .create()
    }
}
