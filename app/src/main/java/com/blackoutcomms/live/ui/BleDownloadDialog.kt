package com.blackoutcomms.live.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.LifecycleOwner
import com.blackoutcomms.live.R
import com.blackoutcomms.live.data.ClusterRepository
import com.blackoutcomms.live.util.themedAlertBuilder

/**
 * Modal spinner shown after BLE PIN is accepted, while waiting for the first
 * "self" message from the firmware. Dismissed automatically when selfDevice
 * LiveData receives its first non-null value, or manually by the user.
 */
class BleDownloadDialog : DialogFragment() {

    companion object {
        const val TAG = "BleDownloadDialog"
    }

    var onCancelled: (() -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_ble_download, null, false)

        view.findViewById<View>(R.id.btn_cancel_download).setOnClickListener {
            onCancelled?.invoke()
            dismissAllowingStateLoss()
        }

        return requireContext().themedAlertBuilder()
            .setView(view)
            .setCancelable(false)
            .create()
    }

    /**
     * Observe ClusterRepository.selfDevice — dismiss as soon as the first
     * self payload arrives. Called by MainActivity after the dialog is shown.
     */
    fun observeSelf(owner: LifecycleOwner) {
        ClusterRepository.selfDevice.observe(owner) { self ->
            if (self != null && isAdded && !isStateSaved) {
                dismissAllowingStateLoss()
            }
        }
    }

    fun dismissSafely() {
        if (isAdded && !isStateSaved) dismissAllowingStateLoss()
    }
}
