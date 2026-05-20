package com.blackoutcomms.live.ui.messages

import android.app.Dialog
import android.os.Bundle
import android.text.*
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.fragment.app.DialogFragment
import com.blackoutcomms.live.R
import com.blackoutcomms.live.model.DeviceState
import com.blackoutcomms.live.util.IconResolver
import com.blackoutcomms.live.util.themedAlertBuilder

/**
 * Compose dialog for sending a broadcast or direct message over BLE.
 *
 * [isBroadcast] = true  → "Secure Broadcast" title, nodes checkbox, BC expiry options
 * [isBroadcast] = false → "Message to:" title + device picker, force-mesh checkbox, DM expiry
 */
class SendMessageDialog : DialogFragment() {

    companion object {
        const val TAG = "SendMessageDialog"

        fun newBroadcast() = SendMessageDialog().apply {
            arguments = Bundle().apply { putBoolean("is_broadcast", true) }
        }

        fun newDirectMessage() = SendMessageDialog().apply {
            arguments = Bundle().apply { putBoolean("is_broadcast", false) }
        }
    }

    var deviceStates: Map<String, DeviceState> = emptyMap()
    var preselectedDeviceId: String? = null   // auto-select this device in DM spinner
    var onSend: ((json: String) -> Unit)? = null

    private val isBroadcast get() = arguments?.getBoolean("is_broadcast", true) ?: true

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_send_message, null, false)

        // ── Title row ────────────────────────────────────────────────────────
        val tvDialogTitle  = view.findViewById<TextView>(R.id.tv_send_title)
        val layoutDmTarget = view.findViewById<View>(R.id.layout_dm_target)
        val spinnerTarget  = view.findViewById<Spinner>(R.id.spinner_dm_target)

        if (isBroadcast) {
            tvDialogTitle.text = "Secure Broadcast"
            layoutDmTarget.visibility = View.GONE
        } else {
            tvDialogTitle.text = "Message to:"
            layoutDmTarget.visibility = View.VISIBLE
            setupDeviceSpinner(spinnerTarget, preselectedDeviceId)
        }

        // ── Message text ──────────────────────────────────────────────────────
        val editMsg = view.findViewById<EditText>(R.id.edit_send_msg)
        val tvCharCount = view.findViewById<TextView>(R.id.tv_char_count)

        // Restrict to letters, numbers, space, period, comma
        editMsg.filters = arrayOf(
            InputFilter { src, _, _, _, _, _ ->
                src.filter { it.isLetterOrDigit() || it == ' ' || it == '.' || it == ',' }
            },
            InputFilter.LengthFilter(255)
        )
        editMsg.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                tvCharCount.text = "${s?.length ?: 0}/255"
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        tvCharCount.text = "0/255"

        // ── Priority slider ───────────────────────────────────────────────────
        val priorities = SendMessageManager.Priority.values()
        val seekPriority  = view.findViewById<SeekBar>(R.id.seek_priority)
        val tvPriorityVal = view.findViewById<TextView>(R.id.tv_priority_value)
        seekPriority.max = priorities.size - 1
        // Default: Normal (index 1)
        val defaultPriorityIdx = priorities.indexOfFirst {
            it == SendMessageManager.Priority.NORMAL
        }.coerceAtLeast(0)
        seekPriority.progress = defaultPriorityIdx
        tvPriorityVal.text = priorities[defaultPriorityIdx].label
        seekPriority.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                tvPriorityVal.text = priorities[progress].label
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // ── Expiry spinner ────────────────────────────────────────────────────
        val expiryOptions = if (isBroadcast) SendMessageManager.BC_EXPIRY_OPTIONS
                            else             SendMessageManager.DM_EXPIRY_OPTIONS
        val spinnerExpiry = view.findViewById<Spinner>(R.id.spinner_expiry)
        spinnerExpiry.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            expiryOptions.map { it.label }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        // ── Extra checkbox ────────────────────────────────────────────────────
        val checkExtra = view.findViewById<CheckBox>(R.id.checkbox_extra)
        if (isBroadcast) {
            checkExtra.text      = "+ Links"
            checkExtra.isChecked = true
        } else {
            checkExtra.text      = "Force Mesh"
            checkExtra.isChecked = false
        }

        // ── Buttons ───────────────────────────────────────────────────────────
        view.findViewById<View>(R.id.btn_send_cancel).setOnClickListener {
            dismiss()
        }

        view.findViewById<View>(R.id.btn_send_send).setOnClickListener {
            val msgText  = editMsg.text.toString().trim()
            if (msgText.isBlank()) {
                editMsg.error = "Message cannot be empty"
                return@setOnClickListener
            }
            val priority = priorities[seekPriority.progress]
            val expiry   = expiryOptions[spinnerExpiry.selectedItemPosition].minutes
            val extra    = checkExtra.isChecked

            val json = if (isBroadcast) {
                SendMessageManager.buildBroadcast(msgText, extra, priority, expiry)
            } else {
                val toId = (spinnerTarget.selectedItem as? DeviceEntry)?.id ?: ""
                if (toId.isBlank()) {
                    Toast.makeText(requireContext(), "Please select a recipient", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                SendMessageManager.buildDirectMessage(toId, msgText, priority, extra, expiry)
            }

            onSend?.invoke(json)
            dismiss()
        }

        return requireContext().themedAlertBuilder()
            .setView(view)
            .create()
    }

    // ── Device spinner entries ────────────────────────────────────────────────

    private data class DeviceEntry(
        val id: String,
        val label: String,
        val iconRes: Int
    ) {
        override fun toString() = label
    }

    // Device types excluded from DM targeting — infrastructure nodes, not people
    private val HIDDEN_DEVICE_TYPES = setOf("relay", "node", "proximity")

    private fun setupDeviceSpinner(spinner: Spinner, preselectedId: String? = null) {
        val entries = deviceStates.values
            .filter { it.device.icon?.lowercase() !in HIDDEN_DEVICE_TYPES }
            .sortedBy { it.device.displayName }
            .map { state ->
                DeviceEntry(
                    id      = state.device.id,
                    label   = state.device.displayName,
                    iconRes = IconResolver.deviceIcon(state.device.icon)
                )
            }

        if (entries.isEmpty()) {
            spinner.adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_item,
                listOf("No devices known")
            )
            return
        }

        spinner.adapter = object : ArrayAdapter<DeviceEntry>(
            requireContext(),
            android.R.layout.simple_spinner_item,
            entries
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                buildRow(position, convertView, parent)

            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                buildRow(position, convertView, parent)

            private fun buildRow(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val row = convertView ?: LayoutInflater.from(context)
                    .inflate(R.layout.item_device_spinner, parent, false)
                val entry = getItem(position) ?: return row
                row.findViewById<ImageView>(R.id.img_spinner_device)
                    .setImageResource(entry.iconRes)
                row.findViewById<TextView>(R.id.tv_spinner_device)
                    .text = entry.label
                return row
            }
        }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        // Pre-select the device if one was specified (e.g. from a Reply action)
        if (preselectedId != null) {
            val idx = entries.indexOfFirst { it.id == preselectedId }
            if (idx >= 0) spinner.setSelection(idx, false)
        }
    }
}
