package com.blackoutcomms.live.util

import android.content.Context

/**
 * Persists the user's chosen BLE device address, display name, and optional
 * application-level PIN so that subsequent app launches can reconnect and
 * authenticate automatically without prompting the user again.
 *
 * The PIN here is an application-level credential sent over the TX
 * characteristic after GATT connection — not an OS-level pairing PIN.
 */
object BlePreferences {

    private const val PREFS_NAME  = "ble_prefs"
    private const val KEY_ADDRESS = "ble_address"
    private const val KEY_NAME    = "ble_name"
    private const val KEY_PIN     = "ble_pin"     // null/absent = no PIN required

    data class SavedDevice(
        val address: String,
        val name: String,
        val pin: String?        // null = user chose "No PIN"
    )

    /** Returns the saved device, or null if none has been saved yet. */
    fun load(context: Context): SavedDevice? {
        val prefs   = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val address = prefs.getString(KEY_ADDRESS, null) ?: return null
        val name    = prefs.getString(KEY_NAME, address) ?: address
        val pin     = prefs.getString(KEY_PIN, null)
        return SavedDevice(address, name, pin)
    }

    fun save(context: Context, address: String, name: String, pin: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putString(KEY_ADDRESS, address)
            putString(KEY_NAME,    name)
            if (pin != null) putString(KEY_PIN, pin) else remove(KEY_PIN)
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
