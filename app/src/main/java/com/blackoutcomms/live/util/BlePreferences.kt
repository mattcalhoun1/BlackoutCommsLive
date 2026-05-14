package com.blackoutcomms.live.util

import android.content.Context

/**
 * Persists the user's chosen BLE device address and optional PIN so that
 * subsequent app launches can reconnect automatically without re-scanning.
 */
object BlePreferences {

    private const val PREFS_NAME  = "ble_prefs"
    private const val KEY_ADDRESS = "ble_address"
    private const val KEY_NAME    = "ble_name"

    data class SavedDevice(
        val address: String,
        val name: String
    )

    /** Returns the saved device, or null if none has been saved yet. */
    fun load(context: Context): SavedDevice? {
        val prefs   = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val address = prefs.getString(KEY_ADDRESS, null) ?: return null
        val name    = prefs.getString(KEY_NAME, address) ?: address
        return SavedDevice(address, name)
    }

    fun save(context: Context, address: String, name: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putString(KEY_ADDRESS, address)
            putString(KEY_NAME,    name)
            apply()
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun hasSaved(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ADDRESS, null) != null
}
