package com.blackoutcomms.live.ui.map

import android.os.Bundle
import android.view.*
import androidx.core.view.isVisible
import com.blackoutcomms.live.R
import com.blackoutcomms.live.data.ClusterRepository
import com.blackoutcomms.live.databinding.BottomSheetDeviceDetailBinding
import com.blackoutcomms.live.util.IconResolver
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
            tvNickname.text    = state.device.nickname ?: state.device.name
            tvName.text        = "Name: ${state.device.name}"
            tvLat.text         = "Lat: ${state.lat ?: "—"}"
            tvLon.text         = "Lon: ${state.lon ?: "—"}"
            tvSpeed.text       = "Speed: ${state.speed?.let { "%.1f m/s".format(it) } ?: "—"}"
            tvHeading.text     = "Heading: ${state.head?.let { "%.0f°".format(it) } ?: "—"}"
            tvTimestamp.text   = "Last seen: ${state.locationTs ?: "—"}"

            // Battery
            val batteryRes = IconResolver.batteryIcon(state.battery)
            if (batteryRes != null) {
                imgBattery.setImageResource(batteryRes)
                imgBattery.isVisible = true
            } else {
                imgBattery.isVisible = false
            }

            // Relay state
            val relayRes = IconResolver.relayIcon(state.relayState)
            if (relayRes != null) {
                imgRelay.setImageResource(relayRes)
                imgRelay.isVisible = true
            } else {
                imgRelay.isVisible = false
            }

            // Temperature
            if (state.temperature != null) {
                tvTemperature.text = "Temp: %.1f°F".format(state.temperature)
                tvTemperature.isVisible = true
            } else {
                tvTemperature.isVisible = false
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
