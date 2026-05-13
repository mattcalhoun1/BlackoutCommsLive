package com.blackoutcomms.live.ui.map

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.blackoutcomms.live.data.ClusterRepository
import com.blackoutcomms.live.model.DeviceState
import com.blackoutcomms.live.util.TimestampUtil
import java.util.*

class MapViewModel : ViewModel() {

    // ── Raw repo data ─────────────────────────────────────────────────────────

    val selfDevice   = ClusterRepository.selfDevice
    val graphData    = ClusterRepository.graphData
    val messages     = ClusterRepository.messages
    val neighbors    = ClusterRepository.neighbors

    // ── Filter state ──────────────────────────────────────────────────────────

    enum class MaxAge(val label: String, val millis: Long?) {
        THIRTY_MINUTES("30 Minutes",  30L * 60 * 1000),
        ONE_HOUR      ("1 Hour",       1L * 60 * 60 * 1000),
        FOUR_HOURS    ("4 Hours",      4L * 60 * 60 * 1000),
        ONE_DAY       ("1 Day",       24L * 60 * 60 * 1000),
        SEVEN_DAYS    ("7 Days",       7L * 24 * 60 * 60 * 1000),
        ONE_MONTH     ("1 Month",     30L * 24 * 60 * 60 * 1000),
        ALL_TIME      ("All Time",    null)
    }

    private val _selectedMaxAge = MutableLiveData(MaxAge.ALL_TIME)
    val selectedMaxAge: LiveData<MaxAge> = _selectedMaxAge

    // Set of device IDs the user has explicitly hidden (empty = all visible)
    private val _hiddenDeviceIds = MutableLiveData<Set<String>>(emptySet())
    val hiddenDeviceIds: LiveData<Set<String>> = _hiddenDeviceIds

    private val _criticalOnly = MutableLiveData(false)
    val criticalOnly: LiveData<Boolean> = _criticalOnly

    // ── Filtered output ───────────────────────────────────────────────────────

    /**
     * Merges raw device states with all three filter criteria and emits only
     * the devices that should be visible on the map.
     */
    val filteredDeviceStates: LiveData<Map<String, DeviceState>> = MediatorLiveData<Map<String, DeviceState>>().also { med ->

        fun recompute() {
            val all      = ClusterRepository.deviceStates.value ?: emptyMap()
            val maxAge   = _selectedMaxAge.value ?: MaxAge.ALL_TIME
            val hidden   = _hiddenDeviceIds.value ?: emptySet()
            val critOnly = _criticalOnly.value ?: false
            val nowMs    = System.currentTimeMillis()

            med.value = all.filter { (id, state) ->
                // 1. Device-picker filter
                if (id in hidden) return@filter false

                // 2. Critical-only filter
                if (critOnly && !state.device.critical) return@filter false

                // 3. Max-age filter (skip self — always show connected device)
                val selfId = selfDevice.value?.id
                if (id != selfId && maxAge.millis != null) {
                    val ts = state.locationTs ?: return@filter false
                    val tsMs = TimestampUtil.parseTs(ts) ?: return@filter false
                    if (nowMs - tsMs > maxAge.millis) return@filter false
                }

                true
            }
        }

        med.addSource(ClusterRepository.deviceStates) { recompute() }
        med.addSource(_selectedMaxAge)   { recompute() }
        med.addSource(_hiddenDeviceIds)  { recompute() }
        med.addSource(_criticalOnly)     { recompute() }
    }

    // ── All devices list (for the picker dialog) ──────────────────────────────

    /** All known devices including self, used to populate the picker. */
    val allDeviceStates: LiveData<Map<String, DeviceState>> = ClusterRepository.deviceStates

    // ── Filter mutators ───────────────────────────────────────────────────────

    fun setMaxAge(age: MaxAge) {
        _selectedMaxAge.value = age
    }

    fun setHiddenDevices(hiddenIds: Set<String>) {
        _hiddenDeviceIds.value = hiddenIds
    }

    fun setCriticalOnly(enabled: Boolean) {
        _criticalOnly.value = enabled
    }
}
