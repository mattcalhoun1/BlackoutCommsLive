package com.blackoutcomms.live.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.blackoutcomms.live.R

/**
 * Non-cancellable spinner shown for the duration of a BLE device scan.
 * Dismissed programmatically by MainActivity once scan results are ready.
 * The Cancel button aborts the scan via the [onCancel] callback.
 */
class BleScanningDialog : DialogFragment() {

    companion object {
        const val TAG = "BleScanningDialog"
    }

    var onCancel: (() -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_ble_scanning, null, false)

        view.findViewById<View>(R.id.btn_cancel_scan).setOnClickListener {
            onCancel?.invoke()
            dismissAllowingStateLoss()
        }

        return AlertDialog.Builder(requireContext())
            .setView(view)
            .setCancelable(false)
            .create()
    }

    fun dismissSafely() {
        if (isAdded && !isStateSaved) dismissAllowingStateLoss()
    }
}
