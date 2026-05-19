package com.blackoutcomms.live.ui.messages

import android.app.Dialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.LifecycleOwner
import com.blackoutcomms.live.data.ClusterRepository
import com.blackoutcomms.live.util.themedAlertBuilder

/**
 * Non-cancellable spinner shown after a message is sent.
 *
 * Dismissal rules:
 *   - Always shows for at least [MIN_SHOW_MS] (5 seconds), regardless of BLE data
 *   - After the minimum time, dismisses on the next BLE data received
 *   - Hard timeout at [TIMEOUT_MS] (15 seconds) as a safety net
 *   - User can always tap Close to dismiss immediately
 */
class SendingDialog : DialogFragment() {

    companion object {
        const val TAG        = "SendingDialog"
        private const val MIN_SHOW_MS = 5_000L    // minimum display time
        private const val TIMEOUT_MS  = 15_000L   // hard outer timeout
    }

    private val handler             = Handler(Looper.getMainLooper())
    private val hardTimeoutRunnable = Runnable { dismissSafely() }
    private var minTimeElapsed      = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return requireContext().themedAlertBuilder()
            .setTitle("Sending...")
            .setMessage("Please wait...")
            .setNegativeButton("Close") { _, _ -> dismissSafely() }
            .setCancelable(false)
            .create()
    }

    override fun onStart() {
        super.onStart()

        minTimeElapsed = false

        // Hard timeout — always dismiss after 15 seconds
        handler.postDelayed(hardTimeoutRunnable, TIMEOUT_MS)

        // After minimum show time, register the data callback so the next
        // BLE packet after that point will dismiss the dialog
        handler.postDelayed({
            minTimeElapsed = true
            // Register one-shot: dismiss on next data received
            ClusterRepository.onDataIngested = {
                ClusterRepository.onDataIngested = null
                handler.post { dismissSafely() }
            }
        }, MIN_SHOW_MS)
    }

    override fun onStop() {
        handler.removeCallbacks(hardTimeoutRunnable)
        super.onStop()
    }

    fun dismissSafely() {
        handler.removeCallbacks(hardTimeoutRunnable)
        handler.removeCallbacksAndMessages(null)
        ClusterRepository.onDataIngested = null
        if (isAdded && !isStateSaved) dismissAllowingStateLoss()
    }
}
