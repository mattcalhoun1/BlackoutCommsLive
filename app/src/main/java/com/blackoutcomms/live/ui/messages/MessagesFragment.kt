package com.blackoutcomms.live.ui.messages

import android.os.Bundle
import android.view.*
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.blackoutcomms.live.service.BleFeedManager
import com.blackoutcomms.live.R
import com.blackoutcomms.live.util.IconResolver
import com.blackoutcomms.live.ui.MainActivity
import com.blackoutcomms.live.service.ConnectionService
import com.blackoutcomms.live.databinding.FragmentMessagesBinding
import com.blackoutcomms.live.model.DeviceState

class MessagesFragment : Fragment() {

    private var _binding: FragmentMessagesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MessagesViewModel by activityViewModels()
    private lateinit var messageAdapter: MessageAdapter

    // Parallel lists for the spinner — null entry = "All Devices"
    private val spinnerIds    = mutableListOf<String?>()
    private val spinnerLabels = mutableListOf<String>()
    private val spinnerIcons  = mutableListOf<Int>()   // drawable res per entry
    private var spinnerUpdating = false   // guard against feedback loops
    private var bleConnected = false      // tracks BLE state for button enable/disable

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMessagesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecycler()
        setupControls()
        setupObservers()
    }

    // ── RecyclerView ──────────────────────────────────────────────────────────

    private fun setupRecycler() {
        messageAdapter = MessageAdapter(requireContext()) { senderId ->
            // Reply: open DM dialog pre-selected to the sender
            showSendDialogForReply(senderId)
        }
        binding.recyclerMessages.apply {
            adapter = messageAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    // ── Controls ──────────────────────────────────────────────────────────────

    private fun setupControls() {
        binding.checkboxNewestFirst.isChecked = viewModel.newestFirst.value ?: true

        binding.checkboxNewestFirst.setOnCheckedChangeListener { _, checked ->
            viewModel.setNewestFirst(checked)
        }

        binding.btnSendDm.setOnClickListener { showSendDialog(isBroadcast = false) }
        binding.btnSendBroadcast.setOnClickListener { showSendDialog(isBroadcast = true) }
    }

    private fun updateSendButtonsEnabled() {
        binding.btnSendDm.isEnabled        = bleConnected
        binding.btnSendBroadcast.isEnabled = bleConnected
    }

    private fun showSendDialog(isBroadcast: Boolean, preselectedDeviceId: String? = null) {
        val dialog = if (isBroadcast) SendMessageDialog.newBroadcast()
                     else             SendMessageDialog.newDirectMessage()

        dialog.deviceStates         = viewModel.deviceStates.value ?: emptyMap()
        dialog.preselectedDeviceId  = preselectedDeviceId
        dialog.onSend = { json ->
            val activity = requireActivity() as? MainActivity
            val sent = activity?.connectionService?.sendJson(json) ?: false
            if (sent) {
                val sendingDialog = SendingDialog()
                sendingDialog.show(parentFragmentManager, SendingDialog.TAG)
            } else {
                android.widget.Toast.makeText(
                    requireContext(),
                    "Not connected to a Blackout Comms device",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
        dialog.show(parentFragmentManager, SendMessageDialog.TAG)
    }

    private fun showSendDialogForReply(senderId: String) {
        showSendDialog(isBroadcast = false, preselectedDeviceId = senderId)
    }

    // ── Observers ─────────────────────────────────────────────────────────────

    private fun setupObservers() {
        viewModel.filteredMessages.observe(viewLifecycleOwner) { messages ->
            val previousSize = messageAdapter.itemCount
            messageAdapter.submitList(messages.toList()) {
                // After list commit, scroll to show new message if one arrived.
                // Only scroll if the list actually grew (new message) and the filter
                // is set to "All Devices" or the message matches the current filter
                // (i.e. it's visible in the current list).
                if (messages.size > previousSize && messages.isNotEmpty()) {
                    val targetPos = if (viewModel.newestFirst.value == true) 0
                                    else messages.size - 1
                    binding.recyclerMessages.scrollToPosition(targetPos)
                }
            }
            binding.tvEmptyMessages.visibility =
                if (messages.isEmpty()) View.VISIBLE else View.GONE
        }

        viewModel.deviceStates.observe(viewLifecycleOwner) { states ->
            messageAdapter.updateDeviceStates(states)
            rebuildSpinner(states)
        }

        com.blackoutcomms.live.data.ClusterRepository.selfDevice.observe(viewLifecycleOwner) { self ->
            if (self != null) messageAdapter.updateSelfId(self.id)
        }

        // Observe BLE state — enable/disable send buttons and Reply buttons
        val activity = requireActivity() as? MainActivity
        activity?.connectionService?.getCurrBleState()?.observe(viewLifecycleOwner) { state ->
            bleConnected = state == BleFeedManager.BleState.CONNECTED
            updateSendButtonsEnabled()
            messageAdapter.updateBleConnected(bleConnected)
        }
    }

    // ── Device spinner ────────────────────────────────────────────────────────

    private fun rebuildSpinner(states: Map<String, DeviceState>) {
        spinnerUpdating = true

        val previousId = viewModel.selectedDeviceId.value

        spinnerIds.clear()
        spinnerLabels.clear()
        spinnerIcons.clear()

        // "All Devices" first — use the provided all_devices icon
        spinnerIds.add(null)
        spinnerLabels.add("All Devices")
        spinnerIcons.add(R.drawable.all_devices)

        // One entry per known device, sorted by display name
        states.values
            .sortedBy { it.device.displayName }
            .forEach { state ->
                spinnerIds.add(state.device.id)
                spinnerLabels.add(state.device.displayName)
                spinnerIcons.add(IconResolver.deviceIcon(state.device.icon))
            }

        // Custom adapter that shows icon + label in both collapsed and dropdown views
        val adapter = object : android.widget.ArrayAdapter<String>(
            requireContext(),
            R.layout.item_device_spinner,
            spinnerLabels
        ) {
            override fun getView(pos: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View =
                makeRow(pos, convertView, parent)
            override fun getDropDownView(pos: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View =
                makeRow(pos, convertView, parent)

            private fun makeRow(pos: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val row = convertView ?: android.view.LayoutInflater.from(context)
                    .inflate(R.layout.item_device_spinner, parent, false)
                row.findViewById<android.widget.ImageView>(R.id.img_spinner_device)
                    .setImageResource(spinnerIcons.getOrElse(pos) { R.drawable.ic_info })
                row.findViewById<android.widget.TextView>(R.id.tv_spinner_device)
                    .text = spinnerLabels.getOrElse(pos) { "" }
                return row
            }
        }

        binding.spinnerDevice.adapter = adapter

        // Restore the previously selected device if it still exists
        val selectedIdx = spinnerIds.indexOf(previousId).takeIf { it >= 0 } ?: 0
        binding.spinnerDevice.setSelection(selectedIdx, false)

        spinnerUpdating = false

        binding.spinnerDevice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, pos: Int, id: Long) {
                if (!spinnerUpdating) viewModel.selectDevice(spinnerIds[pos])
            }
            override fun onNothingSelected(parent: AdapterView<*>) {
                viewModel.selectDevice(null)
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
