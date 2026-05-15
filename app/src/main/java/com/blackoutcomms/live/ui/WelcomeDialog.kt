package com.blackoutcomms.live.ui

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.blackoutcomms.live.R
import com.blackoutcomms.live.databinding.DialogWelcomeBinding
import com.blackoutcomms.live.util.themedAlertBuilder

/**
 * First-run welcome dialog.
 *
 * Shown once on first launch. The user can suppress future appearances via
 * the "Don't show again" checkbox. The dialog is never shown if the user
 * has previously checked that box.
 *
 * To populate:
 *   - Replace the placeholder text in dialog_welcome.xml tv_welcome_description
 *     with your actual description copy, OR set it programmatically here.
 *   - Drop your welcome PNG into res/drawable/ as "welcome_image.png".
 *     The ImageView already references @drawable/welcome_image.
 */
class WelcomeDialog : DialogFragment() {

    companion object {
        const val TAG = "WelcomeDialog"
        private const val PREFS_NAME      = "welcome_prefs"
        private const val PREF_DONT_SHOW  = "dont_show_welcome"
        private const val LEARN_MORE_URL  = "https://chatters.io/using-blackout-comms-live"

        /**
         * Returns true if the dialog should be shown (first run or user hasn't
         * checked "Don't show again").
         */
        fun shouldShow(activity: android.app.Activity): Boolean {
            return !activity
                .getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .getBoolean(PREF_DONT_SHOW, false)
        }
    }

    private var _binding: DialogWelcomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogWelcomeBinding.inflate(LayoutInflater.from(requireContext()))

        binding.btnLearnMore.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LEARN_MORE_URL)))
        }

        binding.btnClose.setOnClickListener {
            persistDontShow()
            dismiss()
        }

        // Also persist when the dialog is cancelled (back button / outside tap)
        isCancelable = true

        return requireContext().themedAlertBuilder()
            .setView(binding.root)
            .create()
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        persistDontShow()
        super.onDismiss(dialog)
    }

    private fun persistDontShow() {
        if (_binding?.checkboxDontShow?.isChecked == true) {
            requireContext()
                .getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_DONT_SHOW, true)
                .apply()
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
