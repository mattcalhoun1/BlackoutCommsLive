package com.blackoutcomms.live.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.LifecycleOwner
import com.blackoutcomms.live.R
import com.blackoutcomms.live.service.ConnectionService
import com.blackoutcomms.live.util.themedAlertBuilder

/**
 * Modal spinner shown while the app is retrying USB serial connection.
 *
 * Uses a plain AlertDialog with a manually-inflated view to avoid any
 * View Binding inflation timing issues inside DialogFragment.
 */
class UsbConnectingDialog : DialogFragment() {

    companion object {
        const val TAG = "UsbConnectingDialog"
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_usb_connecting, null, false)

        view.findViewById<View>(R.id.btn_cancel).setOnClickListener {
            (activity as? MainActivity)?.cancelUsbConnect()
            dismiss()
        }

        return requireContext().themedAlertBuilder()
            .setView(view)
            .setCancelable(false)
            .create()
    }

    /** Observe the service state; dismiss when connected or cancelled. */
    fun observeService(service: ConnectionService, owner: LifecycleOwner) {
        service.usbState.observe(owner) { state ->
            when (state) {
                ConnectionService.UsbState.CONNECTED,
                ConnectionService.UsbState.CANCELLED,
                ConnectionService.UsbState.IDLE -> {
                    if (isAdded && !isStateSaved) dismiss()
                }
                else -> { /* CONNECTING — keep showing */ }
            }
        }
    }
}
