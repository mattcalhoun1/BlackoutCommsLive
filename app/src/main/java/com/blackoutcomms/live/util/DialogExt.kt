package com.blackoutcomms.live.util

import android.app.AlertDialog
import android.content.Context
import android.view.ContextThemeWrapper
import com.blackoutcomms.live.R

/**
 * Returns an AlertDialog.Builder pre-wrapped in the app's AlertDialogTheme
 * so that text colors, button labels, and backgrounds are always correct
 * regardless of whether the caller is a Fragment or Activity.
 */
fun Context.themedAlertBuilder(): AlertDialog.Builder =
    AlertDialog.Builder(ContextThemeWrapper(this, R.style.AlertDialogTheme))
