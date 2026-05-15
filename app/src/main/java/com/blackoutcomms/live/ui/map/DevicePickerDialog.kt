package com.blackoutcomms.live.ui.map

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.blackoutcomms.live.model.DeviceState
import com.blackoutcomms.live.util.themedAlertBuilder

/**
 * Multi-select dialog listing all known devices by nickname.
 * Checked = visible on map. All checked by default.
 * Applies the selection immediately when the user taps OK.
 */
class DevicePickerDialog : DialogFragment() {

    companion object {
        const val TAG = "DevicePickerDialog"
    }

    private val viewModel: MapViewModel by activityViewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val allStates   = viewModel.allDeviceStates.value ?: emptyMap()
        val hiddenIds   = viewModel.hiddenDeviceIds.value ?: emptySet()

        // Build sorted list: self first, then others alphabetically by display name
        val selfId = viewModel.selfDevice.value?.id
        val sorted: List<DeviceState> = allStates.values
            .sortedWith(compareBy(
                { it.device.id != selfId },          // self floats to top
                { it.device.displayName.lowercase() }
            ))

        val labels   = sorted.map { state ->
            val suffix = if (state.device.id == selfId) " (self)" else ""
            "${state.device.displayName}${suffix}"
        }.toTypedArray()

        val checked  = sorted.map { it.device.id !in hiddenIds }.toBooleanArray()

        return requireContext().themedAlertBuilder()
            .setTitle("Visible Devices")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("OK") { _, _ ->
                val newHidden = sorted
                    .filterIndexed { i, _ -> !checked[i] }
                    .map { it.device.id }
                    .toSet()
                viewModel.setHiddenDevices(newHidden)
            }
            .setNeutralButton("All") { _, _ ->
                // Re-show all devices
                viewModel.setHiddenDevices(emptySet())
            }
            .setNegativeButton("Cancel", null)
            .create()
    }
}
