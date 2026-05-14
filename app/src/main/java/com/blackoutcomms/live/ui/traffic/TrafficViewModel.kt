package com.blackoutcomms.live.ui.traffic

import androidx.lifecycle.ViewModel
import com.blackoutcomms.live.data.ClusterRepository

class TrafficViewModel : ViewModel() {
    val trafficEntries = ClusterRepository.trafficEntries
    val pingEntries    = ClusterRepository.pingEntries
    val deviceStates   = ClusterRepository.deviceStates
}
