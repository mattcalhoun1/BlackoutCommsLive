package com.blackoutcomms.live.ui.map

import android.os.Bundle
import android.view.*
import android.view.View
import androidx.core.view.isVisible
import com.blackoutcomms.live.R
import com.blackoutcomms.live.data.ClusterRepository
import com.blackoutcomms.live.databinding.BottomSheetDeviceDetailBinding
import com.blackoutcomms.live.util.IconResolver
import com.blackoutcomms.live.util.TimestampUtil
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class DeviceDetailBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_DEVICE_ID = "device_id"
        fun newInstance(deviceId: String) = DeviceDetailBottomSheet().apply {
            arguments = Bundle().also { it.putString(ARG_DEVICE_ID, deviceId) }
        }
    }

    private var _binding: BottomSheetDeviceDetailBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = BottomSheetDeviceDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val deviceId = arguments?.getString(ARG_DEVICE_ID) ?: return
        val state = ClusterRepository.getDeviceById(deviceId) ?: return

        binding.apply {
            tvNickname.text  = state.device.nickname ?: state.device.name
            tvName.text      = state.device.name

            // Monospace data values
            tvLat.text       = state.lat?.let { "%.6f".format(it) } ?: "—"
            tvLon.text       = state.lon?.let { "%.6f".format(it) } ?: "—"
            tvSpeed.text     = state.speed?.let { "%.1f m/s".format(it) } ?: "—"
            tvHeading.text   = state.head?.let { "%.0f°".format(it) } ?: "—"
            tvTimestamp.text = TimestampUtil.formatTs(state.locationTs)

            // Temperature — stored Celsius, shown as Fahrenheit
            val tempStr = IconResolver.formatTempF(state.temperature)
            tvTemperature.text      = tempStr ?: "—"
            //tvTemperature.isVisible = if (tempStr != null) View.VISIBLE else View.GONE
            tvTemperature.isVisible = if (tempStr != null) true else false

            // Battery
            val batteryRes = IconResolver.batteryIcon(state.battery)
            imgBattery.isVisible = batteryRes != null
            batteryRes?.let { imgBattery.setImageResource(it) }

            // Relay state
            val relayRes = IconResolver.relayIcon(state.relayState)
            imgRelay.isVisible = relayRes != null
            relayRes?.let { imgRelay.setImageResource(it) }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
