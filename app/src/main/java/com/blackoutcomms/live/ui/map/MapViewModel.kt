package com.blackoutcomms.live.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import com.blackoutcomms.live.data.ClusterRepository
import kotlinx.coroutines.flow.combine

class MapViewModel : ViewModel() {

    val selfDevice    = ClusterRepository.selfDevice
    val deviceStates  = ClusterRepository.deviceStates
    val graphData     = ClusterRepository.graphData
    val messages      = ClusterRepository.messages
    val neighbors     = ClusterRepository.neighbors

    fun dismissMessages() {
        // messages list stays in repo (history), UI just collapses the panel
    }
}
