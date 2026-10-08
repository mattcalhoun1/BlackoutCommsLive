package com.blackoutcomms.live.util

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * Persisted display preferences shared by the menu and every screen that
 * formats distance or temperature.
 *
 * Metric off (default): miles and Fahrenheit.
 * Metric on: kilometres and Celsius.
 */
object DisplayPrefs {
    const val PREFS_NAME = "app_prefs"
    const val PREF_METRIC = "use_metric"

    private val _metric = MutableLiveData(false)
    val metric: LiveData<Boolean> = _metric

    fun init(context: Context) {
        val enabled = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_METRIC, false)
        _metric.value = enabled
    }

    fun isMetric(): Boolean = _metric.value == true

    fun setMetric(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_METRIC, enabled)
            .apply()
        _metric.value = enabled
    }
}

object UnitFormat {
    private const val METERS_PER_MILE = 1609.344
    private const val METERS_PER_FOOT = 0.3048

    /** Format a metre distance. Short ranges stay in m/ft so labels stay readable. */
    fun formatDistance(meters: Double, metric: Boolean): String {
        if (meters <= 0.0) return ""
        return if (metric) {
            if (meters < 1000.0) "${meters.toInt()} m"
            else "%.1f km".format(meters / 1000.0)
        } else {
            val miles = meters / METERS_PER_MILE
            if (miles < 0.1) "${(meters / METERS_PER_FOOT).toInt()} ft"
            else "%.1f mi".format(miles)
        }
    }

    /** Stored temperatures are Celsius. */
    fun formatTemperature(celsius: Double, metric: Boolean): String {
        return if (metric) "%.1f°C".format(celsius)
        else "%.1f°F".format(celsius * 9.0 / 5.0 + 32.0)
    }
}
