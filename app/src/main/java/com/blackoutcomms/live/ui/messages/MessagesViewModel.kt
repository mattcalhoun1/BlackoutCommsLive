package com.blackoutcomms.live.ui.messages

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.blackoutcomms.live.data.ClusterRepository
import com.blackoutcomms.live.model.DeviceState
import com.blackoutcomms.live.model.Message

class MessagesViewModel : ViewModel() {

    // ── Filter state ──────────────────────────────────────────────────────────

    /** null = "All Devices"; non-null = filter by this device id */
    private val _selectedDeviceId = MutableLiveData<String?>(null)
    val selectedDeviceId: LiveData<String?> = _selectedDeviceId

    private val _newestFirst = MutableLiveData(true)
    val newestFirst: LiveData<Boolean> = _newestFirst

    // ── Source data ───────────────────────────────────────────────────────────

    val deviceStates: LiveData<Map<String, DeviceState>> = ClusterRepository.deviceStates

    // ── Filtered + sorted messages ────────────────────────────────────────────

    val filteredMessages: LiveData<List<Message>> = MediatorLiveData<List<Message>>().also { med ->
        fun recompute() {
            val msgs    = ClusterRepository.savedMessages.value ?: emptyList()
            val devId   = _selectedDeviceId.value
            val newest  = _newestFirst.value ?: true

            val filtered = if (devId == null) msgs
            else msgs.filter { it.sender == devId || it.recipient == devId }

            med.value = if (newest) {
                filtered.sortedByDescending { it.ts }
            } else {
                filtered.sortedBy { it.ts }
            }
        }

        med.addSource(ClusterRepository.savedMessages) { recompute() }
        med.addSource(_selectedDeviceId)               { recompute() }
        med.addSource(_newestFirst)                    { recompute() }
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    fun selectDevice(deviceId: String?) {
        _selectedDeviceId.value = deviceId
    }

    fun setNewestFirst(value: Boolean) {
        _newestFirst.value = value
    }
}
